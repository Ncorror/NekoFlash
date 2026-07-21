package ru.forum.adbfastboottool

import android.hardware.usb.*
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FastbootProtocol(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val onLog: (String) -> Unit,
    private val onLogVerbose: (String) -> Unit = onLog,
    private val onProgress: (Int, String) -> Unit = { _, _ -> },
    private val preferredInterfaceIndex: Int? = null
) {
    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var fastbootInterface: UsbInterface? = null

    var debugLogging: Boolean = false
    @Volatile var readOnlyMutationLock: Boolean = false
    @Volatile var readOnlyMutationLockReason: String = "Diagnostic READ-ONLY mode"

    enum class SessionState { IDLE, COMMAND_SENT, DATA_OUT, AWAITING_DATA_FINAL, AWAITING_COMMAND_FINAL, BROKEN, CLOSED }

    enum class BrokenReasonCode {
        NONE,
        FIRST_RESPONSE_TIMEOUT,
        USB_OUT_TIMEOUT,
        USB_IN_TIMEOUT,
        SHORT_WRITE,
        SHORT_READ,
        PROTOCOL_DESYNC,
        DEVICE_DISCONNECTED,
        INTERFACE_LOST,
        UNEXPECTED_RESPONSE,
        USER_CANCELLED_DURING_DATA,
        INVALID_STATE,
        NATIVE_USBFS_FAILURE,
        UNKNOWN
    }

    enum class DataTransportMode {
        ASYNC_USB_REQUEST,
        SYNC_BULK,
        NATIVE_USBFS_SINGLE_64K,
        NATIVE_USBFS_SINGLE_256K,
        NATIVE_USBFS_PIPELINE_16K,
        NATIVE_USBFS_PIPELINE_64K,
        NATIVE_USBFS_PIPELINE_128K,
        NATIVE_USBFS_PIPELINE_256K
    }

    enum class DataSelfTestStage { VALIDATION, SEND_DOWNLOAD, WAIT_DATA, DATA_TRANSFER, WAIT_DOWNLOAD_FINAL }

    data class DataSelfTestResult(
        val success: Boolean,
        val stage: DataSelfTestStage,
        val transportMode: DataTransportMode,
        val bytes: Long,
        val message: String = "",
        val sessionCorrupted: Boolean = false,
        val bytesTransferred: Long = 0L
    )

    enum class FlashStage { VALIDATION, SEND_DOWNLOAD, WAIT_DATA, DATA_TRANSFER, WAIT_DOWNLOAD_FINAL, SEND_FLASH, WAIT_FLASH_FINAL }

    enum class FlashFailureKind { NONE, VALIDATION, PROTOCOL, TRANSPORT, CANCELLED, SESSION_BROKEN }

    data class FlashResult(
        val success: Boolean,
        val stage: FlashStage,
        val failureKind: FlashFailureKind = FlashFailureKind.NONE,
        val message: String = "",
        val sessionCorrupted: Boolean = false,
        val dataBytesTransferred: Long = 0L
    ) {
        companion object {
            fun ok(
                stage: FlashStage = FlashStage.WAIT_FLASH_FINAL,
                dataBytesTransferred: Long = 0L
            ) = FlashResult(true, stage, dataBytesTransferred = dataBytesTransferred)

            fun fail(
                stage: FlashStage,
                kind: FlashFailureKind,
                message: String,
                sessionCorrupted: Boolean = false,
                dataBytesTransferred: Long = 0L
            ) = FlashResult(false, stage, kind, message, sessionCorrupted, dataBytesTransferred)
        }
    }

    private data class TransferResult(
        val success: Boolean,
        val cancelled: Boolean = false,
        val message: String = "",
        val bytesTransferred: Long = 0L
    )

    private data class DataOutResult(
        val bytesSent: Int = 0,
        val cancelled: Boolean = false,
        val message: String = ""
    )

    @Volatile private var cancelled = false
    @Volatile private var sessionState: SessionState = SessionState.CLOSED
    @Volatile private var activeDataRequest: UsbRequest? = null
    @Volatile private var lastBrokenReasonCode: BrokenReasonCode = BrokenReasonCode.NONE
    @Volatile private var lastBrokenReason: String? = null

    var dataTransportMode: DataTransportMode = DataTransportMode.ASYNC_USB_REQUEST
    @Volatile var lastDataTransportUsed: DataTransportMode? = null
        private set

    private val transactionLock = ReentrantLock(true)
    private var cachedDiagnostics: DeviceDiagnostics? = null
    @Volatile private var lastKnownProduct: String? = null

    // Monotonic wire timing. Kept separate from wall-clock logging so clock changes cannot
    // corrupt command-to-command turnaround measurements.
    private var lastResponseCompletedNs: Long? = null
    private var lastCommandSentNs: Long? = null
    private var lastCommandName: String? = null
    private var lastCommandSequence: Long = 0L

    val compatibilityProduct: String?
        get() = cachedDiagnostics?.product?.trim()?.takeIf { it.isNotBlank() } ?: lastKnownProduct

    val isConnected: Boolean
        get() = connection != null && endpointIn != null && endpointOut != null && fastbootInterface != null

    val currentSessionState: SessionState
        get() = sessionState

    val isSessionBroken: Boolean
        get() = sessionState == SessionState.BROKEN

    data class DeviceDiagnostics(
        val product: String? = null,
        val currentSlot: String? = null,
        val slotCount: String? = null,
        val slotSuffix: String? = null,
        val unlocked: String? = null,
        val secure: String? = null,
        val serialno: String? = null,
        val versionBootloader: String? = null,
        val antiRollback: String? = null,
        val isUserspace: String? = null,
        val superPartitionName: String? = null,
        val snapshotUpdateStatus: String? = null,
        val maxDownloadSizeRaw: String? = null,
        val maxDownloadSizeBytes: Long? = null,
        val maxFetchSizeRaw: String? = null,
        val maxFetchSizeBytes: Long? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val sessionState: SessionState = SessionState.IDLE,
        val brokenReasonCode: BrokenReasonCode = BrokenReasonCode.NONE,
        val brokenReason: String? = null,
        val readOnlyMutationLock: Boolean = false
    )

    data class LogicalPartitionInfo(
        val partition: String,
        val isLogical: String? = null,
        val sizeRaw: String? = null,
        val sizeBytes: Long? = null,
        val type: String? = null
    )

    private data class FastbootPacket(val type: String, val payload: String, val raw: String)

    private data class MutablePartitionPointProbe(
        val name: String,
        var sizeBytes: Long? = null,
        var type: String? = null,
        var logical: Boolean? = null,
        var hasSlot: Boolean? = null,
        val attemptedFields: MutableSet<FastbootGetVarAllParser.MetadataField> = linkedSetOf(),
        val resolvedFields: MutableSet<FastbootGetVarAllParser.MetadataField> = linkedSetOf()
    ) {
        fun snapshot(): FastbootPartitionInventory.PointProbe = FastbootPartitionInventory.PointProbe(
            name = name,
            sizeBytes = sizeBytes,
            type = type,
            logical = logical,
            hasSlot = hasSlot,
            attemptedFields = attemptedFields.toSet(),
            resolvedFields = resolvedFields.toSet()
        )
    }

    // ─── ПОДКЛЮЧЕНИЕ ─────────────────────────────────────────────────────────

    fun connect(): Boolean {
        cancelled = false
        sessionState = SessionState.CLOSED
        lastResponseCompletedNs = null
        lastCommandSentNs = null
        lastCommandName = null
        lastCommandSequence = 0L
        lastDataTransportUsed = null
        lastBrokenReasonCode = BrokenReasonCode.NONE
        lastBrokenReason = null

        val iface = findFastbootInterface() ?: run {
            onLog("ОШИБКА: Fastboot интерфейс не найден")
            return false
        }
        fastbootInterface = iface

        val endpoints = findBulkEndpoints(iface)
        endpointIn = endpoints.first
        endpointOut = endpoints.second
        if (endpointIn == null || endpointOut == null) {
            onLog("ОШИБКА: Fastboot bulk IN/OUT endpoints не найдены")
            disconnect()
            return false
        }

        connection = usbManager.openDevice(device)
        if (connection == null) {
            onLog("ОШИБКА: Не удалось открыть USB устройство для Fastboot")
            disconnect()
            return false
        }
        if (!connection!!.claimInterface(iface, true)) {
            onLog("ОШИБКА: Не удалось захватить Fastboot интерфейс")
            disconnect()
            return false
        }

        sessionState = SessionState.IDLE
        onLog(
            "=== СОЕДИНЕНИЕ FASTBOOT УСТАНОВЛЕНО === " +
                "interface=${iface.id}, class=${iface.interfaceClass}, " +
                "subclass=${iface.interfaceSubclass}, protocol=${iface.interfaceProtocol}, " +
                "IN=0x${endpointIn!!.address.toString(16)}, OUT=0x${endpointOut!!.address.toString(16)}"
        )
        if (debugLogging) {
            onLog("[debug] ${UsbDeviceInspector.summarizeDevice(device).replace("\n", " | ")}")
        }
        return true
    }

    /**
     * Подтверждает готовность только что открытого USB-интерфейса. До успешного
     * getvar:product соединение не публикуется в UI как активный Fastboot.
     */
    fun qualifyConnection(
        settleMs: Long = FASTBOOT_HANDSHAKE_SETTLE_MS,
        timeoutMs: Int = FASTBOOT_HANDSHAKE_TIMEOUT_MS
    ): String? = transactionLock.withLock {
        if (!isConnected || sessionState != SessionState.IDLE) return@withLock null

        if (settleMs > 0L) {
            try {
                Thread.sleep(settleMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@withLock null
            }
        }

        onLog("=== FASTBOOT HANDSHAKE ===")
        val product = getVar("product", timeoutMs)?.trim()?.takeIf { it.isNotBlank() }
        if (product == null) {
            if (sessionState != SessionState.BROKEN && sessionState != SessionState.CLOSED) {
                markSessionBroken("Первичный Fastboot handshake getvar:product не подтверждён")
            }
            return@withLock null
        }

        lastKnownProduct = product
        onLog("✅ Fastboot handshake подтверждён: product=$product")
        product
    }

    private fun findFastbootInterface(): UsbInterface? {
        // Явно выбранный UsbDeviceInspector интерфейс имеет приоритет. Это позволяет
        // вручную работать с OEM Fastboot, который не использует 0xFF/0x42/0x03.
        preferredInterfaceIndex?.let { index ->
            if (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                if (isFastbootCompatibleInterface(iface, allowGeneric = true)) return iface
                onLog("⚠️ Выбранный Fastboot interface=$index больше не имеет bulk IN/OUT — выполняем безопасный поиск")
            }
        }

        // Сначала строго канонический Android Fastboot.
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (isCanonicalFastbootInterface(iface)) return iface
        }

        // Затем OEM-совместимый 0xFF/0x42 с нестандартным protocol, но не ADB.
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (isFastbootCompatibleInterface(iface, allowGeneric = false)) {
                onLog("ℹ️ Fastboot-интерфейс с нестандартным protocol=${iface.interfaceProtocol} принят по полному bulk IN/OUT pair")
                return iface
            }
        }
        return null
    }

    private fun isCanonicalFastbootInterface(iface: UsbInterface): Boolean =
        iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            iface.interfaceSubclass == 0x42 &&
            iface.interfaceProtocol == 0x03 &&
            findBulkEndpoints(iface).let { it.first != null && it.second != null }

    private fun isFastbootCompatibleInterface(iface: UsbInterface, allowGeneric: Boolean): Boolean {
        val hasPair = findBulkEndpoints(iface).let { it.first != null && it.second != null }
        if (!hasPair) return false
        if (iface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) return false
        if (allowGeneric) return iface.interfaceProtocol != 0x01
        return iface.interfaceSubclass == 0x42 && iface.interfaceProtocol != 0x01
    }

    private fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN && input == null) input = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT && output == null) output = ep
        }
        return input to output
    }

    // ─── КОМАНДЫ ─────────────────────────────────────────────────────────────

    fun beginOperation(): Boolean = transactionLock.withLock {
        if (!isConnected) return@withLock false
        if (sessionState == SessionState.BROKEN || sessionState == SessionState.CLOSED) {
            onLog("⛔ Fastboot-сессия непригодна для новой операции: $sessionState. Переподключите устройство.")
            return@withLock false
        }
        if (sessionState != SessionState.IDLE) {
            markSessionBroken("Новая операция начата при незавершённой Fastboot-транзакции: $sessionState")
            return@withLock false
        }
        cancelled = false
        true
    }

    fun sendCommand(command: String, timeout: Int = 5000): Boolean = transactionLock.withLock {
        if (!ensureSessionReady("command $command")) return@withLock false
        if (isUnsupportedHostSideFastbootHelper(command)) {
            onLog("⛔ fastboot wipe-super — host-side helper desktop fastboot, а не безопасная raw wire-команда.")
            onLog("Используйте только явный update-super с заранее проверенным образом; host-side wipe-super не поддерживается.")
            return@withLock false
        }
        val mutation = parseMutationRequest(command)
        if (mutation != null && !runMutationPreflight(mutation)) return@withLock false
        if (isFlashWriteCommand(command) && isBootloaderLockedForFlash()) return@withLock false

        val success = executeRawCommand(command, timeout)
        if (!success) return@withLock false

        if (mutation?.kind == FastbootMutationSafety.MutationKind.SET_ACTIVE) {
            val expected = mutation.slot?.trim()?.removePrefix("_")?.lowercase(Locale.US)
                ?: return@withLock false
            val actual = getVar("current-slot")?.trim()?.removePrefix("_")?.lowercase(Locale.US)
            if (actual != expected) {
                onLog("⛔ set_active не подтверждён: requested=$expected, current-slot=${actual ?: "unknown"}")
                return@withLock false
            }
            onLog("✅ set_active подтверждён повторным getvar:current-slot=$actual")
        }

        if (mutation?.kind == FastbootMutationSafety.MutationKind.SNAPSHOT_CANCEL ||
            mutation?.kind == FastbootMutationSafety.MutationKind.SNAPSHOT_MERGE
        ) {
            val actual = FastbootMutationSafety.parseSnapshotState(getVar("snapshot-update-status"))
            if (actual != FastbootMutationSafety.SnapshotState.NONE) {
                onLog("⛔ Snapshot control не подтверждён: после команды состояние=$actual, ожидалось NONE")
                return@withLock false
            }
            onLog("✅ Snapshot control подтверждён: snapshot-update-status=NONE")
        }
        true
    }

    private fun executeRawCommand(command: String, timeout: Int): Boolean {
        if (!writeCommand(command, timeout)) return false
        sessionState = SessionState.AWAITING_COMMAND_FINAL
        val finalPacket = readUntilFinalWithRetry(
            singleReadTimeoutMs = 2000,
            maxTotalTimeMs = 600_000
        ) ?: return false
        if (finalPacket.type == "OKAY") return true
        logFastbootFailure("Fastboot command failed: $command", finalPacket.payload.ifBlank { finalPacket.raw })
        return false
    }

    fun getVar(name: String, timeout: Int = 5000): String? = transactionLock.withLock {
        if (!ensureSessionReady("getvar:$name")) return@withLock null
        if (!writeCommand("getvar:$name", timeout)) return@withLock null
        val result = readGetVarResponse(name, timeout) ?: return@withLock null
        result.trim().ifEmpty { null }
    }

    /**
     * Reads a complete, non-mutating partition/variable snapshot from
     * `getvar:all`. The snapshot never invents `_a`/`_b` targets: a legacy
     * device such as POCO X3 Pro / vayu remains unslotted unless the bootloader
     * itself reports concrete slot evidence.
     */
    fun getVarAll(timeout: Int = 30_000): FastbootGetVarAllParser.Snapshot? = transactionLock.withLock {
        if (!ensureSessionReady("getvar:all")) return@withLock null
        if (!writeCommand("getvar:all", minOf(timeout, 10_000))) return@withLock null
        readGetVarAllResponse(timeout)
    }

    /**
     * Builds a read-only partition inventory. `getvar:all` is authoritative for
     * names; bounded point queries only fill omitted metadata or confirm a
     * concrete name referenced by family-only has-slot metadata. No result from
     * this method authorizes a flash operation.
     */
    fun collectPartitionInventory(
        diagnostics: DeviceDiagnostics,
        maxFallbackQueries: Int = PARTITION_INVENTORY_MAX_POINT_QUERIES
    ): FastbootPartitionInventory.Snapshot? = transactionLock.withLock {
        if (maxFallbackQueries < 0) return@withLock null
        val source = getVarAll() ?: return@withLock null
        val supplemental = linkedMapOf<String, String>()
        fun addVariable(name: String, value: String?) {
            value?.trim()?.takeIf { it.isNotBlank() }?.let { supplemental[name] = it }
        }
        addVariable("product", diagnostics.product)
        addVariable("current-slot", diagnostics.currentSlot)
        addVariable("slot-count", diagnostics.slotCount)
        addVariable("slot-suffix", diagnostics.slotSuffix)
        addVariable("is-userspace", diagnostics.isUserspace)
        addVariable("super-partition-name", diagnostics.superPartitionName)

        val initial = FastbootPartitionInventory.from(
            source = source,
            fallbackProduct = diagnostics.product,
            supplementalVariables = supplemental
        )
        val plan = FastbootPartitionProbePlanner.plan(
            source = source,
            inventory = initial,
            maxQueries = maxFallbackQueries
        )

        val probes = linkedMapOf<String, MutablePartitionPointProbe>()
        var abortedByBrokenSession = false
        plan.requests.forEach { request ->
            if (sessionState == SessionState.BROKEN || sessionState == SessionState.CLOSED) {
                abortedByBrokenSession = true
                return@forEach
            }
            val probe = probes.getOrPut(request.partition) { MutablePartitionPointProbe(request.partition) }
            probe.attemptedFields += request.field
            val variable = when (request.field) {
                FastbootGetVarAllParser.MetadataField.SIZE -> "partition-size:${request.partition}"
                FastbootGetVarAllParser.MetadataField.TYPE -> "partition-type:${request.partition}"
                FastbootGetVarAllParser.MetadataField.LOGICAL -> "is-logical:${request.partition}"
                FastbootGetVarAllParser.MetadataField.HAS_SLOT -> "has-slot:${request.partition}"
            }
            val raw = getVar(variable)
            if (raw == null) {
                if (sessionState == SessionState.BROKEN || sessionState == SessionState.CLOSED) {
                    abortedByBrokenSession = true
                }
                return@forEach
            }
            when (request.field) {
                FastbootGetVarAllParser.MetadataField.SIZE -> {
                    FastbootGetVarAllParser.parseSizeValue(raw)?.let {
                        probe.sizeBytes = it
                        probe.resolvedFields += request.field
                    }
                }
                FastbootGetVarAllParser.MetadataField.TYPE -> {
                    raw.trim().takeIf { it.isNotBlank() }?.let {
                        probe.type = it
                        probe.resolvedFields += request.field
                    }
                }
                FastbootGetVarAllParser.MetadataField.LOGICAL -> {
                    FastbootGetVarAllParser.parseBooleanValue(raw)?.let {
                        probe.logical = it
                        probe.resolvedFields += request.field
                    }
                }
                FastbootGetVarAllParser.MetadataField.HAS_SLOT -> {
                    FastbootGetVarAllParser.parseBooleanValue(raw)?.let {
                        probe.hasSlot = it
                        probe.resolvedFields += request.field
                    }
                }
            }
        }

        val collectionWarnings = mutableListOf<FastbootPartitionInventory.Warning>()
        if (plan.discoveryFallbackUsed) {
            collectionWarnings += FastbootPartitionInventory.Warning(
                code = "LIMITED_POINT_DISCOVERY",
                message = "getvar:all не подтвердил конкретные разделы; выполнен ограниченный read-only поиск известных имён.",
                severity = FastbootPartitionInventory.WarningSeverity.WARNING
            )
        }
        if (plan.omittedRequestCount > 0) {
            collectionWarnings += FastbootPartitionInventory.Warning(
                code = "POINT_QUERY_BUDGET_EXHAUSTED",
                message = "Лимит точечных getvar достигнут; пропущено запросов: ${plan.omittedRequestCount}.",
                severity = FastbootPartitionInventory.WarningSeverity.INFO
            )
        }
        if (abortedByBrokenSession) {
            collectionWarnings += FastbootPartitionInventory.Warning(
                code = "POINT_QUERY_ABORTED",
                message = "Дозаполнение метаданных остановлено: Fastboot-сессия потеряла синхронизацию.",
                severity = FastbootPartitionInventory.WarningSeverity.CRITICAL
            )
        }

        val result = FastbootPartitionInventory.from(
            source = source,
            fallbackProduct = diagnostics.product,
            supplementalVariables = supplemental,
            pointProbes = probes.values.map { it.snapshot() },
            collectionWarnings = collectionWarnings
        )
        onLog(
            "✅ Partition inventory: concrete=${result.entries.size}, topology=${result.topology}, " +
                "point-queries=${result.pointQueryCount}, warnings=${result.warnings.size}"
        )
        result
    }

    fun hasSlot(partition: String): Boolean? {
        val normalized = normalizePartitionName(partition)?.removeSuffix("_ab")?.removeSuffix("_a")?.removeSuffix("_b") ?: return null
        return when (getVar("has-slot:$normalized")?.trim()?.lowercase(Locale.US)) {
            "yes", "true", "1" -> true
            "no", "false", "0" -> false
            else -> null
        }
    }

    fun probeLogicalPartition(partition: String): Boolean? = transactionLock.withLock {
        val normalized = normalizePartitionName(partition) ?: return@withLock null
        FastbootMutationSafety.parseFastbootBoolean(getVar("is-logical:$normalized"))
    }

    fun inspectSlotHealth(slot: String): FastbootMutationSafety.SlotHealth = transactionLock.withLock {
        val normalized = slot.trim().removePrefix("_").lowercase(Locale.US)
        val successful = FastbootMutationSafety.parseFastbootBoolean(getVar("slot-successful:$normalized"))
        val unbootable = FastbootMutationSafety.parseFastbootBoolean(getVar("slot-unbootable:$normalized"))
        val retryCount = getVar("slot-retry-count:$normalized")?.trim()?.toIntOrNull()
        FastbootMutationSafety.SlotHealth(normalized, successful, unbootable, retryCount)
    }

    private data class MutationRequest(
        val kind: FastbootMutationSafety.MutationKind,
        val partition: String? = null,
        val slot: String? = null
    )

    private fun parseMutationRequest(command: String): MutationRequest? {
        val clean = command.trim()
        val lower = clean.lowercase(Locale.US)
        val control = lower.replace(':', ' ').replace(Regex("\\s+"), " ").trim()
        fun valueAfter(prefix: String): String? = clean.substringAfter(prefix, "").trim().takeIf { it.isNotBlank() }
        return when {
            lower.startsWith("erase:") -> MutationRequest(FastbootMutationSafety.MutationKind.ERASE, valueAfter("erase:"))
            lower.startsWith("erase ") -> MutationRequest(FastbootMutationSafety.MutationKind.ERASE, clean.substringAfter(' ').trim())
            lower.startsWith("format:") -> MutationRequest(FastbootMutationSafety.MutationKind.FORMAT, clean.substringAfterLast(':').trim())
            lower.startsWith("format ") -> MutationRequest(FastbootMutationSafety.MutationKind.FORMAT, clean.substringAfter(' ').trim())
            lower.startsWith("set_active:") -> MutationRequest(FastbootMutationSafety.MutationKind.SET_ACTIVE, slot = valueAfter("set_active:"))
            lower.startsWith("set_active ") || lower.startsWith("set-active ") -> MutationRequest(
                FastbootMutationSafety.MutationKind.SET_ACTIVE,
                slot = clean.substringAfter(' ').trim()
            )
            lower.startsWith("update-super:") -> MutationRequest(FastbootMutationSafety.MutationKind.UPDATE_SUPER, valueAfter("update-super:"))
            lower.startsWith("create-logical-partition:") || lower.startsWith("delete-logical-partition:") ||
                lower.startsWith("resize-logical-partition:") -> MutationRequest(
                    FastbootMutationSafety.MutationKind.LOGICAL_PARTITION,
                    clean.substringAfter(':').substringBefore(':').trim()
                )
            control == "snapshot-update cancel" -> MutationRequest(FastbootMutationSafety.MutationKind.SNAPSHOT_CANCEL)
            control == "snapshot-update merge" -> MutationRequest(FastbootMutationSafety.MutationKind.SNAPSHOT_MERGE)
            control == "flashing unlock" || control == "oem unlock" -> MutationRequest(FastbootMutationSafety.MutationKind.BOOTLOADER_UNLOCK)
            control == "flashing lock" || control == "oem lock" -> MutationRequest(FastbootMutationSafety.MutationKind.BOOTLOADER_LOCK)
            control == "flashing unlock_critical" || control == "flashing unlock critical" ||
                control == "flashing lock_critical" || control == "flashing lock critical" ->
                MutationRequest(FastbootMutationSafety.MutationKind.BOOTLOADER_CRITICAL_STATE)
            control == "gsi wipe" || control == "gsi disable" ->
                MutationRequest(FastbootMutationSafety.MutationKind.GSI_CONTROL)
            isPotentiallyDestructiveOemCommand(control) ->
                MutationRequest(FastbootMutationSafety.MutationKind.OEM_DESTRUCTIVE_CONTROL)
            else -> null
        }
    }

    private fun isUnsupportedHostSideFastbootHelper(command: String): Boolean {
        val clean = command.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
        return clean == "wipe-super" || clean.startsWith("wipe-super ") || clean.startsWith("wipe-super:")
    }

    private fun isPotentiallyDestructiveOemCommand(normalizedControl: String): Boolean {
        if (!normalizedControl.startsWith("oem ")) return false
        val riskyTokens = listOf(" erase", " wipe", " format", " unlock", " lock", " factory", " reset")
        return riskyTokens.any { normalizedControl.contains(it) }
    }

    private fun runMutationPreflight(request: MutationRequest): Boolean {
        onLog("=== FASTBOOT MUTATION SAFETY PREFLIGHT ===")
        if (readOnlyMutationLock) {
            onLog("⛔ READ-ONLY LOCK: изменяющая Fastboot-команда заблокирована на уровне протокола. reason=$readOnlyMutationLockReason")
            onLog("Mutation kind: ${request.kind}; partition=${request.partition ?: "none"}; slot=${request.slot ?: "none"}")
            return false
        }
        val diagnostics = refreshDiagnostics(force = false)
        if (sessionState == SessionState.BROKEN || sessionState == SessionState.CLOSED) {
            onLog("⛔ Mutation preflight interrupted: Fastboot session=$sessionState")
            return false
        }

        // Re-qualify identity immediately before a destructive mutation. A cached
        // product/serial is an anchor, not proof that the same USB target is still connected.
        val expectedProduct = diagnostics.product?.trim()?.takeIf { it.isNotBlank() } ?: lastKnownProduct
        val expectedSerial = diagnostics.serialno?.trim()?.takeIf { it.isNotBlank() }
        val freshProduct = getVar("product")?.trim()?.takeIf { it.isNotBlank() }
        if (expectedProduct != null && (freshProduct == null || !freshProduct.equals(expectedProduct, ignoreCase = true))) {
            onLog("⛔ Mutation identity mismatch: expected product=$expectedProduct, current=${freshProduct ?: "unknown"}")
            return false
        }
        val freshSerial = if (expectedSerial != null) getVar("serialno")?.trim()?.takeIf { it.isNotBlank() } else null
        if (expectedSerial != null && (freshSerial == null || !freshSerial.equals(expectedSerial, ignoreCase = true))) {
            onLog("⛔ Mutation identity mismatch: expected serial=$expectedSerial, current=${freshSerial ?: "unknown"}")
            return false
        }
        onLog("Mutation identity: product=${freshProduct ?: expectedProduct ?: "unknown"}, serial=${freshSerial ?: expectedSerial ?: "unknown"}")

        // Snapshot status is always queried fresh immediately before mutation. A cached
        // value is intentionally not used as proof that no merge started meanwhile.
        val freshSnapshotStatus = getVar("snapshot-update-status")
        val snapshotState = FastbootMutationSafety.parseSnapshotState(freshSnapshotStatus)
        if (request.kind == FastbootMutationSafety.MutationKind.GSI_CONTROL &&
            diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true
        ) {
            onLog("⛔ GSI control mutation requires confirmed fastbootd/userspace")
            return false
        }
        // AOSP exposes is-logical from userspace fastbootd. In bootloader fastboot
        // an unsupported probe would add noise, so static UI fallback remains in place.
        val runtimeLogical = request.partition?.let {
            if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) == true) probeLogicalPartition(it) else null
        }
        val targetIsLogical = request.partition?.let { partition ->
            runtimeLogical ?: FastbootMutationSafety.likelyLogicalPartition(partition)
        }
        if (targetIsLogical == true && diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⛔ Logical/dynamic partition mutation requires fastbootd/userspace: ${request.partition}")
            return false
        }
        val slotHealth = request.slot?.let { inspectSlotHealth(it) }
        if (sessionState == SessionState.BROKEN || sessionState == SessionState.CLOSED) {
            onLog("⛔ Mutation preflight interrupted while collecting target evidence")
            return false
        }

        val decision = FastbootMutationSafety.evaluate(
            FastbootMutationSafety.Context(
                kind = request.kind,
                snapshotState = snapshotState,
                partition = request.partition,
                targetIsLogical = targetIsLogical,
                slotHealth = slotHealth
            )
        )

        onLog("Mutation kind: ${request.kind}")
        onLog("Snapshot state: $snapshotState (raw=${freshSnapshotStatus ?: "unknown"})")
        request.partition?.let {
            val evidence = if (runtimeLogical != null) "runtime" else "static-fallback"
            onLog("Target partition: $it | logical=${targetIsLogical ?: "unknown"} | evidence=$evidence")
        }
        slotHealth?.let {
            onLog(
                "Slot health ${it.slot}: successful=${it.successful ?: "unknown"}, " +
                    "unbootable=${it.unbootable ?: "unknown"}, retry-count=${it.retryCount ?: "unknown"}"
            )
        }
        decision.warnings.forEach { onLog("⚠️ $it") }
        if (!decision.allowed) {
            onLog("⛔ ${decision.blockedReason ?: "Fastboot mutation blocked by safety policy"}")
            return false
        }
        onLog("✅ Mutation safety preflight passed")
        return true
    }

    /**
     * Collects conservative evidence for guided A/B flashing. slot-count alone is
     * never treated as proof that a concrete partition is slotted.
     */
    fun partitionSizeBytes(partition: String): Long? = transactionLock.withLock {
        val normalized = normalizePartitionName(partition) ?: return@withLock null
        parseFastbootSize(getVar("partition-size:$normalized"))
    }

    fun inspectSlotEvidence(partition: String): FastbootSlotResolver.Evidence = transactionLock.withLock {
        val base = normalizePartitionName(partition)
            ?.removeSuffix("_ab")?.removeSuffix("_a")?.removeSuffix("_b")
            ?: return@withLock FastbootSlotResolver.Evidence(sessionBroken = sessionState == SessionState.BROKEN)

        val diagnostics = refreshDiagnostics(force = false)
        val slotCount = diagnostics.slotCount?.trim()?.toIntOrNull()
        val hasSlotValue = hasSlot(base)

        fun probe(target: String): Boolean? {
            if (sessionState == SessionState.BROKEN || sessionState == SessionState.CLOSED) return null
            val size = getVar("partition-size:$target")
            if (parseFastbootSize(size) != null) return true
            val type = getVar("partition-type:$target")
            return if (!type.isNullOrBlank()) true else null
        }

        val baseExists: Boolean?
        val slotAExists: Boolean?
        val slotBExists: Boolean?
        when (hasSlotValue) {
            true -> {
                // has-slot:<base>=yes is enough to classify the partition as A/B.
                // Concrete partition probes are not mandatory here and can be flaky on
                // unstable bootloaders, so keep them unknown unless has-slot is absent.
                baseExists = null
                slotAExists = null
                slotBExists = null
            }
            false -> {
                baseExists = probe(base)
                slotAExists = null
                slotBExists = null
            }
            null -> {
                baseExists = probe(base)
                slotAExists = probe("${base}_a")
                slotBExists = probe("${base}_b")
            }
        }

        val evidence = FastbootSlotResolver.Evidence(
            slotCount = slotCount,
            currentSlot = diagnostics.currentSlot,
            hasSlot = hasSlotValue,
            unsuffixedExists = baseExists,
            slotAExists = slotAExists,
            slotBExists = slotBExists,
            sessionBroken = sessionState == SessionState.BROKEN
        )
        val topology = FastbootSlotResolver.detectTopology(evidence)
        onLog("Slot topology evidence for $base: ${FastbootSlotResolver.topologyLabel(topology)}")
        evidence
    }


    fun refreshDiagnostics(
        force: Boolean = false,
        maxAgeMs: Long = DIAGNOSTICS_CACHE_TTL_MS,
        knownProduct: String? = null
    ): DeviceDiagnostics = transactionLock.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedDiagnostics
        if (!force && cached != null && now - cached.timestamp <= maxAgeMs) {
            onLog("=== FASTBOOT ДАННЫЕ ИЗ КЭША ===")
            logDiagnostics(cached)
            return cached
        }

        onLog("=== ПРЕДПРОВЕРКА FASTBOOT ===")
        fun safeGetVar(name: String): String? {
            if (sessionState == SessionState.BROKEN || sessionState == SessionState.CLOSED) return null
            return getVar(name)
        }

        val product           = knownProduct?.trim()?.takeIf { it.isNotBlank() } ?: safeGetVar("product")
        val currentSlot       = safeGetVar("current-slot")
        val slotCount         = safeGetVar("slot-count")
        val slotSuffix        = safeGetVar("slot-suffix")
        val unlocked          = safeGetVar("unlocked")
        val secure            = safeGetVar("secure")
        val serialno          = safeGetVar("serialno")
        val versionBootloader = safeGetVar("version-bootloader")
        val antiPrimary       = if (sessionState == SessionState.IDLE) getVar("anti") else null
        val antiRollback      = antiPrimary
            ?: if (sessionState == SessionState.IDLE) safeGetVar("antirollback") else null
        val isUserspace       = safeGetVar("is-userspace")
        val superPartitionName = safeGetVar("super-partition-name")
        val snapshotUpdateStatus = safeGetVar("snapshot-update-status")
        val maxDownloadSizeRaw = safeGetVar("max-download-size")
        val maxDownloadSizeBytes = parseFastbootSize(maxDownloadSizeRaw)
        val maxFetchSizeRaw = safeGetVar("max-fetch-size")
        val maxFetchSizeBytes = parseFastbootSize(maxFetchSizeRaw)

        if (sessionState == SessionState.BROKEN) {
            onLog("⛔ Предпроверка остановлена: Fastboot-транспорт потерял синхронизацию. Новые getvar не отправляются.")
        }

        val diagnostics = DeviceDiagnostics(
            product           = product,
            currentSlot       = currentSlot,
            slotCount         = slotCount,
            slotSuffix        = slotSuffix,
            unlocked          = unlocked,
            secure            = secure,
            serialno          = serialno,
            versionBootloader = versionBootloader,
            antiRollback      = antiRollback,
            isUserspace       = isUserspace,
            superPartitionName = superPartitionName,
            snapshotUpdateStatus = snapshotUpdateStatus,
            maxDownloadSizeRaw   = maxDownloadSizeRaw,
            maxDownloadSizeBytes = maxDownloadSizeBytes,
            maxFetchSizeRaw      = maxFetchSizeRaw,
            maxFetchSizeBytes    = maxFetchSizeBytes,
            timestamp = now,
            sessionState = sessionState,
            brokenReasonCode = lastBrokenReasonCode,
            brokenReason = lastBrokenReason,
            readOnlyMutationLock = readOnlyMutationLock
        )
        cachedDiagnostics = diagnostics
        diagnostics.product?.trim()?.takeIf { it.isNotBlank() }?.let { lastKnownProduct = it }
        logDiagnostics(diagnostics)
        return diagnostics
    }

    fun currentDiagnostics(): DeviceDiagnostics? = cachedDiagnostics

    fun runSelfTest(): Boolean {
        if (!isConnected) {
            onLog("❌ Fastboot self-test: соединение не открыто")
            return false
        }
        onLog("=== FASTBOOT SELF-TEST ===")
        val diagnostics = refreshDiagnostics(force = true)
        var ok = true

        fun pass(label: String, value: String?) {
            onLog("✅ $label: ${value ?: "—"}")
        }
        fun warn(label: String, value: String?) {
            ok = false
            onLog("⚠️ $label: ${value ?: "неизвестно"}")
        }

        if (diagnostics.product.isNullOrBlank()) warn("product не получен", diagnostics.product) else pass("product", diagnostics.product)
        if (diagnostics.serialno.isNullOrBlank()) warn("serialno не получен", diagnostics.serialno) else pass("serialno", diagnostics.serialno)
        if (diagnostics.unlocked.isNullOrBlank()) warn("unlocked не получен", diagnostics.unlocked) else pass("unlocked", diagnostics.unlocked)
        if (diagnostics.maxDownloadSizeBytes == null) warn("max-download-size не получен", diagnostics.maxDownloadSizeRaw) else pass("max-download-size", "${diagnostics.maxDownloadSizeRaw} / ${diagnostics.maxDownloadSizeBytes} байт")
        diagnostics.antiRollback?.let { pass("anti / rollback index", it) }

        val mode = when {
            diagnostics.isUserspace?.equals("yes", ignoreCase = true) == true -> "fastbootd / userspace"
            diagnostics.isUserspace?.equals("no", ignoreCase = true) == true -> "bootloader fastboot"
            else -> "неизвестно"
        }
        pass("mode", mode)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) == true) {
            pass("super-partition-name", diagnostics.superPartitionName)
            if (diagnostics.maxFetchSizeBytes == null) warn("max-fetch-size не получен", diagnostics.maxFetchSizeRaw) else pass("max-fetch-size", "${diagnostics.maxFetchSizeRaw} / ${diagnostics.maxFetchSizeBytes} байт")
            val suffix = diagnostics.slotSuffix ?: diagnostics.currentSlot?.let { if (it.startsWith("_")) it else "_$it" }
            val sample = listOfNotNull(
                suffix?.let { "system$it" },
                suffix?.let { "vendor$it" },
                "system_a",
                "vendor_a"
            ).distinct()
            val first = sample.firstOrNull { part ->
                val logical = getVar("is-logical:$part")
                if (!logical.isNullOrBlank()) {
                    onLog("ℹ️ is-logical:$part = $logical")
                    true
                } else false
            }
            if (first == null) onLog("ℹ️ Не удалось подтвердить logical partition через is-logical:<partition>; это может быть ограничением OEM fastbootd.")
        } else {
            onLog("ℹ️ Logical partition / fetch тесты пропущены: устройство не сообщает fastbootd/userspace.")
        }

        if (diagnostics.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("✅ Guard check: fastboot flash будет заблокирован приложением при unlocked=no.")
        } else if (diagnostics.unlocked?.equals("yes", ignoreCase = true) == true) {
            onLog("ℹ️ Guard check: bootloader unlocked=yes, flash-команды разрешены после обычных проверок файла/размера.")
        }
        onLog("ℹ️ Self-test не выполнял download/flash/erase/format/update-super.")
        return ok
    }

    private fun logDiagnostics(d: DeviceDiagnostics) {
        onLog("Устройство/product: ${d.product ?: "неизвестно"}")
        d.serialno?.let          { onLog("Serial: $it") }
        d.versionBootloader?.let { onLog("Bootloader version: $it") }
        d.antiRollback?.let { onLog("Anti-rollback index: $it") }
        val fbMode = when {
            d.isUserspace?.equals("yes", ignoreCase = true) == true -> "fastbootd / userspace"
            d.isUserspace?.equals("no", ignoreCase = true) == true -> "bootloader fastboot"
            else -> "неизвестно"
        }
        onLog("Fastboot mode: $fbMode")
        d.superPartitionName?.let { onLog("Super partition: $it") }
        onLog("Snapshot update status: ${d.snapshotUpdateStatus ?: "unknown"}")
        onLog("Текущий слот: ${d.currentSlot ?: "—"}")
        d.slotCount?.let { onLog("Количество слотов: $it") } ?: onLog("Количество слотов: неизвестно/не поддерживается")
        d.slotSuffix?.let { onLog("Суффикс слота: $it") }
        onLog("Bootloader unlocked: ${d.unlocked ?: "неизвестно"}")
        onLog("Secure: ${d.secure ?: "неизвестно"}")
        onLog("Max download size: ${d.maxDownloadSizeRaw ?: "неизвестно"}${d.maxDownloadSizeBytes?.let { " ($it байт)" } ?: ""}")
        d.maxFetchSizeRaw?.let { onLog("Max fetch size: $it${d.maxFetchSizeBytes?.let { bytes -> " ($bytes байт)" } ?: ""}") }
        if (d.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("⚠️ ВНИМАНИЕ: загрузчик сообщает unlocked=no. Полный терминал доступен, но fastboot flash будет заблокирован приложением.")
        }
    }

    // ─── ПРОШИВКА РАЗДЕЛА ────────────────────────────────────────────────────

    fun flashPartition(partition: String, file: File): Boolean =
        flashPartitionDetailed(partition, file).success

    fun flashPartitionDetailed(partition: String, file: File): FlashResult = transactionLock.withLock {
        if (!ensureSessionReady("flash:$partition")) {
            return@withLock FlashResult.fail(
                FlashStage.VALIDATION,
                FlashFailureKind.SESSION_BROKEN,
                "Fastboot-сессия недоступна: $sessionState",
                sessionCorrupted = sessionState == SessionState.BROKEN
            )
        }

        val normalizedPartition = partition.trim().lowercase(Locale.US)
        if (normalizedPartition.isBlank() || !normalizedPartition.matches(Regex("[A-Za-z0-9._:-]+"))) {
            val message = "Некорректное имя раздела: $partition"
            onLog("❌ ОШИБКА: $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }
        val partitionBase = normalizedPartition.removeSuffix("_ab").removeSuffix("_a").removeSuffix("_b")
        if (partitionBase !in TYPICAL_FLASH_PARTITIONS) {
            onLog("⚠️ Раздел $normalizedPartition не входит в типовой список. Жёсткая блокировка снята, команда разрешена терминальным режимом.")
        }

        if (!file.exists() || !file.isFile || !file.canRead()) {
            val message = "Файл недоступен: ${file.name}"
            onLog("❌ ОШИБКА: $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }
        if (file.length() <= 0L) {
            val message = "Файл пустой: ${file.name}"
            onLog("❌ ОШИБКА: $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }
        if (file.length() > 0xFFFF_FFFFL) {
            val message = "Fastboot download поддерживает размер до 4 GiB в этой реализации"
            onLog("❌ ОШИБКА: $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }

        val fileSizeMb = file.length().toDouble() / 1024.0 / 1024.0
        onLog("Прошивка $normalizedPartition. Файл: ${file.name} (${"%.2f".format(fileSizeMb)} MB)")

        if (!runMutationPreflight(MutationRequest(FastbootMutationSafety.MutationKind.FLASH, normalizedPartition))) {
            return@withLock FlashResult.fail(
                FlashStage.VALIDATION,
                FlashFailureKind.VALIDATION,
                "Fastboot mutation safety preflight blocked flash:$normalizedPartition"
            )
        }
        val diagnostics = refreshDiagnostics(force = false)
        if (sessionState == SessionState.BROKEN) {
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.TRANSPORT, "Диагностика потеряла синхронизацию", true)
        }
        if (diagnostics.unlocked?.equals("no", ignoreCase = true) == true) {
            val message = "Bootloader locked: getvar:unlocked=no"
            onLog("⛔ $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }
        val limit = diagnostics.maxDownloadSizeBytes
        if (limit != null && limit > 0L && file.length() > limit) {
            val message = "Файл (${file.length()} байт) больше max-download-size ($limit байт)"
            onLog("❌ ОШИБКА: $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }
        if (!HashUtils.verifyFileWithSidecars(file, onLog)) {
            val message = "Ошибка контрольной суммы"
            onLog("Прошивка отменена из-за ошибки контрольной суммы.")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }

        val hexSize = String.format("%08x", file.length())
        if (!writeCommand("download:$hexSize", 5000)) {
            return@withLock flashTransportFailure(FlashStage.SEND_DOWNLOAD, "Сбой отправки команды download")
        }

        val downloadPacket = readUntilDataOrFinal(10000)
            ?: return@withLock flashTransportFailure(FlashStage.WAIT_DATA, "Нет корректного ответа на download")
        when (downloadPacket.type) {
            "DATA" -> onLog("Загрузчик готов принять образ: ${downloadPacket.payload}")
            "FAIL" -> {
                val message = downloadPacket.payload.ifBlank { downloadPacket.raw }
                logFastbootFailure("Загрузчик отказал в download", message)
                return@withLock FlashResult.fail(FlashStage.WAIT_DATA, FlashFailureKind.PROTOCOL, message)
            }
            else -> {
                val message = "Ожидался DATA, получено ${downloadPacket.raw}"
                markSessionBroken(message)
                return@withLock FlashResult.fail(FlashStage.WAIT_DATA, FlashFailureKind.TRANSPORT, message, true)
            }
        }

        val transfer = transferDownloadPayload(file, "flash:$normalizedPartition")
        if (!transfer.success) {
            val kind = if (transfer.cancelled) FlashFailureKind.CANCELLED else FlashFailureKind.TRANSPORT
            return@withLock FlashResult.fail(
                FlashStage.DATA_TRANSFER,
                kind,
                transfer.message,
                sessionCorrupted = true,
                dataBytesTransferred = transfer.bytesTransferred
            )
        }

        sessionState = SessionState.AWAITING_DATA_FINAL
        val downloadDone = readUntilFinalWithRetry(singleReadTimeoutMs = 2000, maxTotalTimeMs = 30_000)
            ?: return@withLock flashTransportFailure(FlashStage.WAIT_DOWNLOAD_FINAL, "Нет финального ответа после DATA")
                .copy(dataBytesTransferred = file.length())
        if (downloadDone.type != "OKAY") {
            val message = downloadDone.payload.ifBlank { downloadDone.raw }
            logFastbootFailure("Устройство забраковало образ после передачи", message)
            return@withLock FlashResult.fail(
                FlashStage.WAIT_DOWNLOAD_FINAL,
                FlashFailureKind.PROTOCOL,
                message,
                dataBytesTransferred = file.length()
            )
        }

        onLog("Запись образа в раздел $normalizedPartition (может занять несколько минут)...")
        if (!writeCommand("flash:$normalizedPartition", 5000)) {
            return@withLock flashTransportFailure(FlashStage.SEND_FLASH, "Сбой отправки команды flash")
                .copy(dataBytesTransferred = file.length())
        }
        sessionState = SessionState.AWAITING_COMMAND_FINAL

        val flashDone = readUntilFinalWithRetry(singleReadTimeoutMs = 2000, maxTotalTimeMs = 600_000)
            ?: return@withLock flashTransportFailure(FlashStage.WAIT_FLASH_FINAL, "Нет финального ответа flash")
                .copy(dataBytesTransferred = file.length())

        if (flashDone.type == "OKAY") {
            onLog("✅ Прошивка $normalizedPartition успешно завершена!")
            FlashResult.ok(dataBytesTransferred = file.length())
        } else {
            val message = flashDone.payload.ifBlank { flashDone.raw }
            logFastbootFailure("ОШИБКА записи раздела $normalizedPartition", message)
            FlashResult.fail(
                FlashStage.WAIT_FLASH_FINAL,
                FlashFailureKind.PROTOCOL,
                message,
                dataBytesTransferred = file.length()
            )
        }
    }

    private data class NativeUsbfsProfile(
        val label: String,
        val blockBytes: Int,
        val pipelineDepth: Int
    )

    private fun DataTransportMode.isNativeUsbfsDiagnostic(): Boolean = when (this) {
        DataTransportMode.NATIVE_USBFS_SINGLE_64K,
        DataTransportMode.NATIVE_USBFS_SINGLE_256K,
        DataTransportMode.NATIVE_USBFS_PIPELINE_16K,
        DataTransportMode.NATIVE_USBFS_PIPELINE_64K,
        DataTransportMode.NATIVE_USBFS_PIPELINE_128K,
        DataTransportMode.NATIVE_USBFS_PIPELINE_256K -> true
        else -> false
    }

    // V5.8.11: depth=1 single-URB leaves the OUT endpoint idle between URBs, which the
    // onyx bootloader wedges after a few hundred KB (confirmed on hardware; reference
    // fastboot never uses a single-URB path — it always double-buffers urb[2]). Single-URB
    // is therefore diagnostic-only and must never carry a real DATA payload.
    private fun DataTransportMode.isSingleUrbDiagnostic(): Boolean = when (this) {
        DataTransportMode.NATIVE_USBFS_SINGLE_64K,
        DataTransportMode.NATIVE_USBFS_SINGLE_256K -> true
        else -> false
    }

    private fun DataTransportMode.nativeUsbfsProfile(): NativeUsbfsProfile? = when (this) {
        DataTransportMode.NATIVE_USBFS_SINGLE_64K -> NativeUsbfsProfile("Native USBFS single URB 64 KiB", 64 * 1024, 1)
        DataTransportMode.NATIVE_USBFS_SINGLE_256K -> NativeUsbfsProfile("Native USBFS single URB 256 KiB", 256 * 1024, 1)
        DataTransportMode.NATIVE_USBFS_PIPELINE_16K -> NativeUsbfsProfile("Native USBFS pipeline depth=2 16 KiB", 16 * 1024, 2)
        DataTransportMode.NATIVE_USBFS_PIPELINE_64K -> NativeUsbfsProfile("Native USBFS pipeline depth=2 64 KiB", 64 * 1024, 2)
        DataTransportMode.NATIVE_USBFS_PIPELINE_128K -> NativeUsbfsProfile("Native USBFS pipeline depth=2 128 KiB", 128 * 1024, 2)
        DataTransportMode.NATIVE_USBFS_PIPELINE_256K -> NativeUsbfsProfile("Native USBFS pipeline depth=2 256 KiB", 256 * 1024, 2)
        else -> null
    }

    /**
     * Safe preset Fastboot DATA transport self-test.
     *
     * Stops after the final OKAY for the download DATA phase and never sends a command
     * that consumes the downloaded buffer. Preset sizes are intentionally bounded.
     */
    fun runDataSelfTestDetailed(payloadFile: File, mode: DataTransportMode): DataSelfTestResult =
        runDataDownloadOnlyTestDetailed(
            payloadFile = payloadFile,
            mode = mode,
            requirePresetSize = true,
            testLabel = "Safe DATA Self-Test"
        )

    /**
     * Exact-size qualification for a selected file.
     *
     * The file itself is sent only to Fastboot's volatile download buffer. The method
     * stops after the final DATA OKAY and never sends flash:, boot, stage, update-super,
     * or any other command that could mutate persistent storage.
     */
    fun runDataQualificationTestDetailed(payloadFile: File, mode: DataTransportMode): DataSelfTestResult =
        runDataDownloadOnlyTestDetailed(
            payloadFile = payloadFile,
            mode = mode,
            requirePresetSize = false,
            testLabel = "Size-Matched DATA Qualification"
        )

    private fun runDataDownloadOnlyTestDetailed(
        payloadFile: File,
        mode: DataTransportMode,
        requirePresetSize: Boolean,
        testLabel: String
    ): DataSelfTestResult = transactionLock.withLock {
        val size = payloadFile.length()
        if (!payloadFile.exists() || !payloadFile.isFile || !payloadFile.canRead()) {
            return@withLock DataSelfTestResult(
                success = false,
                stage = DataSelfTestStage.VALIDATION,
                transportMode = mode,
                bytes = size,
                message = "$testLabel payload недоступен: ${payloadFile.name}"
            )
        }
        if (size <= 0L || size > 0xFFFF_FFFFL) {
            return@withLock DataSelfTestResult(
                success = false,
                stage = DataSelfTestStage.VALIDATION,
                transportMode = mode,
                bytes = size,
                message = "Некорректный размер download-only теста: $size байт"
            )
        }
        if (requirePresetSize && size !in SAFE_DATA_SELF_TEST_SIZES_BYTES) {
            return@withLock DataSelfTestResult(
                success = false,
                stage = DataSelfTestStage.VALIDATION,
                transportMode = mode,
                bytes = size,
                message = "Неподдерживаемый размер Safe DATA Self-Test: $size байт"
            )
        }
        val maxDownload = currentDiagnostics()?.maxDownloadSizeBytes?.takeIf { it > 0L }
        if (maxDownload != null && size > maxDownload) {
            return@withLock DataSelfTestResult(
                success = false,
                stage = DataSelfTestStage.VALIDATION,
                transportMode = mode,
                bytes = size,
                message = "Размер теста ($size байт) больше max-download-size ($maxDownload байт)"
            )
        }
        if (mode.isNativeUsbfsDiagnostic()) {
            val nativePreflight = NativeUsbfsBackend.preflightError(connection, endpointOut)
            if (nativePreflight != null) {
                return@withLock DataSelfTestResult(
                    success = false,
                    stage = DataSelfTestStage.VALIDATION,
                    transportMode = mode,
                    bytes = size,
                    message = nativePreflight,
                    sessionCorrupted = false
                )
            }
            onLog("🧪 Native USBFS Matrix backend доступен. режим diagnostic-only: PASS не разрешает реальную прошивку.")
        }
        if (!ensureSessionReady(testLabel)) {
            return@withLock DataSelfTestResult(
                success = false,
                stage = DataSelfTestStage.VALIDATION,
                transportMode = mode,
                bytes = size,
                message = "Fastboot-сессия не готова к $testLabel",
                sessionCorrupted = isSessionBroken
            )
        }

        onLog("=== ${testLabel.uppercase(Locale.US)} ===")
        onLog("Файл: ${payloadFile.name}")
        onLog("Размер: ${formatBytes(size)}")
        onLog("Транспорт: ${dataTransportModeLabel(mode)}")
        onLog("🛡️ Раздел не выбран. Команды flash/boot/stage/update-super НЕ отправляются.")
        val partitionHint = PartitionNameResolver.resolve(payloadFile.name)
        when (partitionHint.kind) {
            PartitionNameResolver.Kind.EXACT_PARTITION ->
                onLog("ℹ️ Файл похож на раздел «${partitionHint.partition}». Это диагностика (download-only). Чтобы реально прошить, выберите раздел «${partitionHint.partition}» на вкладке Flash и подтвердите.")
            PartitionNameResolver.Kind.RECOVERY_IMAGE ->
                onLog("ℹ️ ${partitionHint.note} Кандидаты: ${partitionHint.candidates.joinToString(", ")}.")
            PartitionNameResolver.Kind.ARCHIVE ->
                onLog("ℹ️ ${partitionHint.note}")
            PartitionNameResolver.Kind.UNKNOWN -> Unit
        }
        onLog("🛡️ Тест выполняет только download → DATA payload → финальный ответ и останавливается.")

        val previousMode = dataTransportMode
        dataTransportMode = mode
        try {
            val hexSize = String.format("%08x", size)
            if (!writeCommand("download:$hexSize", 5000)) {
                return@withLock DataSelfTestResult(
                    success = false,
                    stage = DataSelfTestStage.SEND_DOWNLOAD,
                    transportMode = mode,
                    bytes = size,
                    message = "Сбой отправки download для $testLabel",
                    sessionCorrupted = isSessionBroken
                )
            }

            val dataPacket = readUntilDataOrFinal(10_000)
            if (dataPacket == null) {
                markSessionBroken("Нет корректного ответа на $testLabel download")
                return@withLock DataSelfTestResult(
                    success = false,
                    stage = DataSelfTestStage.WAIT_DATA,
                    transportMode = mode,
                    bytes = size,
                    message = "Нет корректного ответа на download-only тест",
                    sessionCorrupted = true
                )
            }
            when (dataPacket.type) {
                "FAIL" -> {
                    val message = dataPacket.payload.ifBlank { dataPacket.raw }
                    logFastbootFailure("Загрузчик отказал в $testLabel download", message)
                    return@withLock DataSelfTestResult(false, DataSelfTestStage.WAIT_DATA, mode, size, message)
                }
                "DATA" -> {
                    val acceptedBytes = parseFastbootDataSize(dataPacket.payload)
                    if (acceptedBytes == null || acceptedBytes != size) {
                        val acceptedLabel = acceptedBytes?.toString() ?: "invalid:${dataPacket.payload}"
                        val message = "$testLabel DATA size mismatch: requested=$size, accepted=$acceptedLabel"
                        markSessionBroken(message)
                        return@withLock DataSelfTestResult(
                            success = false,
                            stage = DataSelfTestStage.WAIT_DATA,
                            transportMode = mode,
                            bytes = size,
                            message = message,
                            sessionCorrupted = true
                        )
                    }
                    onLog("Загрузчик готов принять download-only payload: ${dataPacket.payload}")
                }
                else -> {
                    val message = "Ожидался DATA для $testLabel, получено ${dataPacket.raw}"
                    markSessionBroken(message)
                    return@withLock DataSelfTestResult(false, DataSelfTestStage.WAIT_DATA, mode, size, message, true)
                }
            }

            val transfer = transferDownloadPayload(payloadFile, testLabel, diagnostic = true)
            if (cancelled && transfer.success) {
                val message = "$testLabel отменён после DATA-передачи до подтверждённого финального OKAY"
                markSessionBroken(message)
                return@withLock DataSelfTestResult(
                    success = false,
                    stage = DataSelfTestStage.DATA_TRANSFER,
                    transportMode = mode,
                    bytes = size,
                    message = message,
                    sessionCorrupted = true,
                    bytesTransferred = transfer.bytesTransferred
                )
            }
            if (!transfer.success) {
                return@withLock DataSelfTestResult(
                    success = false,
                    stage = DataSelfTestStage.DATA_TRANSFER,
                    transportMode = mode,
                    bytes = size,
                    message = transfer.message,
                    sessionCorrupted = isSessionBroken,
                    bytesTransferred = transfer.bytesTransferred
                )
            }

            sessionState = SessionState.AWAITING_DATA_FINAL
            val finalPacket = readUntilFinalWithRetry(singleReadTimeoutMs = 2000, maxTotalTimeMs = 30_000)
            if (finalPacket == null) {
                if (cancelled && !isSessionBroken) {
                    markSessionBroken("$testLabel отменён во время ожидания финального OKAY после DATA")
                }
                return@withLock DataSelfTestResult(
                    success = false,
                    stage = DataSelfTestStage.WAIT_DOWNLOAD_FINAL,
                    transportMode = mode,
                    bytes = size,
                    message = "Нет финального ответа после download-only DATA",
                    sessionCorrupted = isSessionBroken,
                    bytesTransferred = size
                )
            }
            if (finalPacket.type != "OKAY") {
                val message = finalPacket.payload.ifBlank { finalPacket.raw }
                logFastbootFailure("$testLabel отклонён после DATA", message)
                return@withLock DataSelfTestResult(
                    false,
                    DataSelfTestStage.WAIT_DOWNLOAD_FINAL,
                    mode,
                    size,
                    message,
                    bytesTransferred = size
                )
            }

            onLog("✅ $testLabel PASS: ${formatBytes(size)} через ${dataTransportModeLabel(mode)}")
            onLog("✅ Проверено: финальный OKAY после DATA получен.")
            onLog("🛡️ Тест завершён. Ни одна команда записи раздела не отправлялась.")
            DataSelfTestResult(
                success = true,
                stage = DataSelfTestStage.WAIT_DOWNLOAD_FINAL,
                transportMode = mode,
                bytes = size,
                bytesTransferred = size
            )
        } finally {
            dataTransportMode = previousMode
        }
    }

    // ─── FASTBOOTD / DYNAMIC PARTITIONS ────────────────────────────────────

    fun inspectLogicalPartition(partition: String): LogicalPartitionInfo? = transactionLock.withLock {
        if (!isConnected) return null
        val normalized = normalizePartitionName(partition) ?: return null
        onLog("=== FASTBOOTD LOGICAL PARTITION INFO: $normalized ===")
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ Устройство не сообщает is-userspace=yes. Для dynamic partitions обычно нужен userspace fastbootd. Выполните: fastboot reboot fastboot")
        }
        val isLogical = getVar("is-logical:$normalized")
        val sizeRaw = getVar("partition-size:$normalized")
        val sizeBytes = parseFastbootSize(sizeRaw)
        val type = getVar("partition-type:$normalized")
        val info = LogicalPartitionInfo(
            partition = normalized,
            isLogical = isLogical,
            sizeRaw = sizeRaw,
            sizeBytes = sizeBytes,
            type = type
        )
        onLog("Partition: ${info.partition}")
        onLog("Logical: ${info.isLogical ?: "неизвестно"}")
        onLog("Size: ${info.sizeRaw ?: "неизвестно"}${info.sizeBytes?.let { " ($it байт)" } ?: ""}")
        onLog("Type: ${info.type ?: "неизвестно"}")
        diagnostics.superPartitionName?.let { onLog("Super partition: $it") }
        return info
    }

    fun runLogicalPartitionCommand(command: String): Boolean = transactionLock.withLock {
        if (!isConnected) return false
        val clean = command.trim()
        if (!isLogicalPartitionManagementCommand(clean)) {
            onLog("❌ ОШИБКА: команда не является командой управления logical partition: $clean")
            return false
        }
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⛔ Управление logical partitions разрешено только в fastbootd/userspace.")
            onLog("Выполните fastboot reboot fastboot и повторите команду после подтверждения is-userspace=yes.")
            return false
        }
        return sendCommand(clean)
    }

    fun fetchPartition(partition: String, outputFile: File): Boolean = transactionLock.withLock {
        if (!isConnected) return false
        val normalized = normalizePartitionName(partition) ?: return false
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ fetch обычно реализован в fastbootd. Если устройство вернёт FAIL, выполните: fastboot reboot fastboot")
        }
        if (diagnostics.unlocked?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ fetch в AOSP обычно требует unlocked/debuggable-состояние. Устройство может отказать.")
        }

        val parent = outputFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            onLog("❌ ОШИБКА: не удалось создать папку: ${parent.absolutePath}")
            return false
        }
        val partFile = File(outputFile.parentFile ?: File("."), outputFile.name + ".part")
        if (partFile.exists() && !partFile.delete()) {
            onLog("❌ ОШИБКА: не удалось удалить временный файл: ${partFile.absolutePath}")
            return false
        }

        val partitionSizeRaw = getVar("partition-size:$normalized")
        val partitionSize = parseFastbootSize(partitionSizeRaw)
        val maxFetch = diagnostics.maxFetchSizeBytes?.takeIf { it > 0L }
        onLog("Fastboot fetch: $normalized → ${outputFile.absolutePath}")
        partitionSize?.let { onLog("Partition size: $partitionSizeRaw ($it байт)") }
        maxFetch?.let { onLog("Max fetch chunk: $it байт") }

        return try {
            partFile.outputStream().use { out ->
                if (partitionSize != null && partitionSize > 0L && maxFetch != null) {
                    var offset = 0L
                    while (offset < partitionSize && !cancelled) {
                        val chunkSize = minOf(maxFetch, partitionSize - offset)
                        val command = "fetch:$normalized:$offset:$chunkSize"
                        val fetched = fetchChunk(command, out, offset, partitionSize) ?: return false
                        if (fetched <= 0L) {
                            onLog("❌ ОШИБКА fetch: устройство вернуло нулевой chunk")
                            return false
                        }
                        offset += fetched
                    }
                } else {
                    val command = "fetch:$normalized"
                    val fetched = fetchChunk(command, out, 0L, partitionSize ?: -1L)
                    if (fetched == null) return false
                }
            }
            if (cancelled) {
                onLog("⚠️ fetch отменён пользователем")
                false
            } else {
                if (outputFile.exists() && !outputFile.delete()) {
                    onLog("❌ ОШИБКА: не удалось заменить файл: ${outputFile.absolutePath}")
                    false
                } else if (!partFile.renameTo(outputFile)) {
                    onLog("❌ ОШИБКА: не удалось переименовать .part в итоговый файл")
                    false
                } else {
                    onLog("✅ Fastboot fetch завершён: ${outputFile.absolutePath} (${outputFile.length()} байт)")
                    true
                }
            }
        } catch (e: Exception) {
            onLog("❌ ОШИБКА fastboot fetch: ${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            if (partFile.exists() && partFile.length() == 0L) partFile.delete()
        }
    }

    // ─── УТИЛИТЫ ─────────────────────────────────────────────────────────────

    private fun isFlashWriteCommand(command: String): Boolean {
        val clean = command.trim().lowercase()
        return clean.startsWith("flash:") || clean.startsWith("flash ") || clean.startsWith("update-super:")
    }

    private fun isBootloaderLockedForFlash(): Boolean {
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("⛔ Bootloader locked: fastboot flash заблокирован приложением, потому что getvar:unlocked вернул no.")
            onLog("Разрешены диагностические и сервисные fastboot-команды, но запись разделов требует unlocked=yes.")
            return true
        }
        return false
    }

    fun downloadAndRun(file: File, commandAfterDownload: String): Boolean = transactionLock.withLock {
        if (!isConnected) return false

        val command = commandAfterDownload.trim()
        if (command.isBlank()) {
            onLog("❌ ОШИБКА: пустая команда после download")
            return false
        }
        if (command.any { it.code !in 32..126 }) {
            onLog("❌ ОШИБКА: Fastboot-команда должна быть ASCII")
            return false
        }

        val mutation = parseMutationRequest(command)
        if (mutation != null && !runMutationPreflight(mutation)) return false

        if (!file.exists() || !file.isFile || !file.canRead()) {
            onLog("❌ ОШИБКА: файл недоступен: ${file.name}")
            return false
        }
        if (file.length() <= 0L) {
            onLog("❌ ОШИБКА: файл пустой: ${file.name}")
            return false
        }
        if (file.length() > 0xFFFF_FFFFL) {
            onLog("❌ ОШИБКА: Fastboot download поддерживает размер до 4 GiB в этой реализации")
            return false
        }

        val diagnostics = refreshDiagnostics(force = false)
        if (isFlashWriteCommand(command) && diagnostics.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("⛔ Bootloader locked: запись разделов заблокирована приложением, потому что getvar:unlocked вернул no.")
            onLog("Файл не будет передан в download-буфер; flash/update-super требует unlocked=yes.")
            return false
        }
        if (command.lowercase().startsWith("update-super:") && diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⛔ update-super разрешён только в fastbootd/userspace. Выполните fastboot reboot fastboot и повторите.")
            return false
        }
        val limit = diagnostics.maxDownloadSizeBytes
        if (limit != null && limit > 0L && file.length() > limit) {
            onLog("❌ ОШИБКА: файл (${file.length()} байт) больше max-download-size ($limit байт)")
            return false
        }
        if (!HashUtils.verifyFileWithSidecars(file, onLog)) {
            onLog("Операция отменена из-за ошибки контрольной суммы.")
            return false
        }

        val fileSizeMb = file.length().toDouble() / 1024.0 / 1024.0
        onLog("Fastboot download: ${file.name} (${"%.2f".format(fileSizeMb)} MB), затем команда: $command")

        val hexSize = String.format("%08x", file.length())
        if (!writeCommand("download:$hexSize", 5000)) {
            onLog("ОШИБКА: Сбой отправки команды download")
            return false
        }

        val downloadPacket = readUntilDataOrFinal(10000) ?: return false
        when (downloadPacket.type) {
            "DATA" -> onLog("Загрузчик готов принять файл: ${downloadPacket.payload}")
            "FAIL" -> { logFastbootFailure("Загрузчик отказал в download", downloadPacket.payload); return false }
            else   -> { onLog("ОШИБКА: ожидался DATA, получено ${downloadPacket.raw}"); return false }
        }

        if (!transferDownloadPayload(file, "download + $command").success) return false
        sessionState = SessionState.AWAITING_DATA_FINAL

        if (cancelled) {
            onLog("⚠️ Операция отменена пользователем")
            return false
        }

        val downloadDone = readUntilFinal(30000) ?: return false
        if (downloadDone.type != "OKAY") {
            logFastbootFailure("Устройство забраковало файл после передачи", downloadDone.payload.ifBlank { downloadDone.raw })
            return false
        }

        if (!writeCommand(command, 5000)) {
            onLog("ОШИБКА: Сбой отправки команды после download")
            return false
        }

        val done = readUntilFinalWithRetry(
            singleReadTimeoutMs = 2000,
            maxTotalTimeMs = 600_000
        ) ?: return false

        return if (done.type == "OKAY") {
            onLog("✅ Fastboot-команда после download выполнена: $command")
            true
        } else {
            logFastbootFailure("Fastboot-команда после download не выполнена: $command", done.payload.ifBlank { done.raw })
            false
        }
    }

    /**
     * Разблокировка загрузчика Xiaomi: загружает encryptData (полученный от Mi
     * API) в download-буфер устройства, затем выполняет oem unlock.
     * Это финальный шаг официального протокола Mi Unlock.
     */
    fun stageAndOemUnlock(encryptDataFile: File): Boolean = transactionLock.withLock {
        if (!isConnected) {
            onLog("❌ Нет соединения с устройством")
            return false
        }
        if (!runMutationPreflight(MutationRequest(FastbootMutationSafety.MutationKind.BOOTLOADER_UNLOCK))) {
            onLog("⛔ Mi Unlock остановлен до DATA: bootloader-state mutation preflight не пройден")
            return false
        }
        if (!encryptDataFile.exists() || !encryptDataFile.isFile || encryptDataFile.length() <= 0L) {
            onLog("❌ ОШИБКА: файл разблокировки недоступен или пуст")
            return false
        }

        onLog("🔓 Staging данных разблокировки (${encryptDataFile.length()} байт)...")
        val hexSize = String.format("%08x", encryptDataFile.length())
        if (!writeCommand("download:$hexSize", 5000)) {
            onLog("❌ ОШИБКА: сбой команды download для разблокировки")
            return false
        }
        val downloadPacket = readUntilDataOrFinal(10000) ?: return false
        when (downloadPacket.type) {
            "DATA" -> onLog("Загрузчик готов принять данные разблокировки")
            "FAIL" -> { logFastbootFailure("Загрузчик отказал в download", downloadPacket.payload); return false }
            else   -> { onLog("❌ ОШИБКА: ожидался DATA, получено ${downloadPacket.raw}"); return false }
        }
        if (!transferDownloadPayload(encryptDataFile, "unlock data").success) return false
        sessionState = SessionState.AWAITING_DATA_FINAL

        val downloadDone = readUntilFinal(30000) ?: return false
        if (downloadDone.type != "OKAY") {
            onLog("❌ ОШИБКА: download разблокировки не подтверждён: ${downloadDone.raw}")
            return false
        }

        onLog("🔓 Выполняется fastboot oem unlock...")
        if (!writeCommand("oem unlock", 10000)) {
            onLog("❌ ОШИБКА: сбой отправки oem unlock")
            return false
        }
        val unlockDone = readUntilFinal(30000) ?: return false
        return if (unlockDone.type == "OKAY") {
            onLog("✅ Загрузчик разблокирован успешно!")
            true
        } else {
            logFastbootFailure("oem unlock отклонён", unlockDone.payload)
            false
        }
    }

    // Fix B: after a DATA wedge the onyx OUT endpoint stays stuck and a plain re-handshake
    // does not clear it (confirmed on hardware). Reference fastboot recovers with
    // USBDEVFS_RESET; we mirror that here. The session is already broken at every call site,
    // so this only nudges the device to re-enumerate cleanly for the next connect. It never
    // runs on the healthy transfer path.
    private fun attemptEndpointResetRecovery(reason: String) {
        val conn = connection ?: return
        onLog("🧯 USB endpoint wedge: пробую USBDEVFS_RESET для расклинивания ($reason)")
        val rc = NativeUsbfsBackend.resetUsbDevice(conn)
        when {
            rc == 0 -> onLog("✅ USBDEVFS_RESET выполнен: устройство переинициализируется. Дождитесь реэнумерации или переподключите OTG, затем повторите вход в Fastboot.")
            rc > 0 -> onLog("ℹ️ USBDEVFS_RESET не удался (errno=$rc). Выполните физический реконнект OTG и заново войдите в Fastboot.")
            else -> onLog("ℹ️ USBDEVFS_RESET недоступен (нет USB-fd). Выполните физический реконнект OTG.")
        }
    }

    private fun transferDownloadPayload(file: File, label: String, diagnostic: Boolean = false): TransferResult {
        // Native USBFS profiles are diagnostic-only. A real mutation uses only the
        // universal UsbRequest transport or the explicit sync bulk fallback. This also
        // preserves Fix A: single-URB can never carry a real payload.
        if (!diagnostic && dataTransportMode.isNativeUsbfsDiagnostic()) {
            val message = "DATA payload запрошен на diagnostic-only Native USBFS транспорте " +
                "(${dataTransportMode}, включая single-URB); реальная передача разрешена только через " +
                "ASYNC_USB_REQUEST или SYNC_BULK. Транзакция остановлена."
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = 0L)
        }
        lastDataTransportUsed = dataTransportMode
        val blockBytes = dataOutBlockBytes()
        val fileBuffer = ByteArray(blockBytes)
        val usbBuffer = ByteBuffer.allocateDirect(blockBytes)
        val totalBytes = file.length().coerceAtLeast(1L)
        var totalSent = 0L
        var lastLoggedProgress = -1
        var lastUiProgress = -1
        var lastRateLogMs = System.currentTimeMillis()
        var lastUiUpdateMs = lastRateLogMs
        var lastRateLogBytes = 0L
        val startedMs = lastRateLogMs

        if (sessionState != SessionState.DATA_OUT) {
            val message = "DATA-передача запущена в неверном состоянии: $sessionState"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        }

        val conn = connection
        val out = endpointOut
        if (conn == null || out == null) {
            val message = "USB DATA transport недоступен: соединение или OUT endpoint отсутствует"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        }

        if (dataTransportMode == DataTransportMode.SYNC_BULK) {
            onLog("ℹ️ Fastboot DATA transport: sync bulkTransfer selected before download payload transfer.")
            return transferDownloadPayloadSync(file, label, totalBytes)
        }
        if (dataTransportMode.isNativeUsbfsDiagnostic()) {
            val profile = dataTransportMode.nativeUsbfsProfile()
            onLog("ℹ️ Fastboot DATA transport: ${profile?.label ?: "Native USBFS"} selected for diagnostic download-only payload transfer.")
            return transferDownloadPayloadNativeUsbfs(file, label, totalBytes)
        }

        val request = UsbRequest()
        if (!request.initialize(conn, out)) {
            val message = "Не удалось инициализировать UsbRequest для Fastboot DATA OUT"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        }
        activeDataRequest = request

        onLog(
            "Передача $label: ${formatBytes(totalBytes)}. " +
                "transport=UsbRequest, block=${formatBytes(blockBytes.toLong())}, " +
                "watchdog=${formatDuration(DATA_REQUEST_WATCHDOG_MS)}. " +
                "Не сворачивайте приложение и не отключайте OTG/кабель."
        )
        onLogVerbose(
            "[fastboot-data-diag] mode=UsbRequest sdk=${Build.VERSION.SDK_INT} " +
                "endpoint=${endpointDescriptor(out)} total=$totalBytes block=$blockBytes watchdog_ms=$DATA_REQUEST_WATCHDOG_MS"
        )
        Thread.sleep(60)

        try {
            file.inputStream().use { input ->
                while (true) {
                    if (cancelled) {
                        request.cancel()
                        val message = "Операция отменена во время DATA-фазы на ${formatBytes(totalSent)}/${formatBytes(totalBytes)}"
                        markSessionBroken(message)
                        return TransferResult(false, cancelled = true, message = message, bytesTransferred = totalSent)
                    }

                    val read = input.read(fileBuffer)
                    if (read <= 0) break

                    usbBuffer.clear()
                    usbBuffer.put(fileBuffer, 0, read)
                    usbBuffer.flip()

                    while (usbBuffer.hasRemaining()) {
                        if (cancelled) {
                            request.cancel()
                            val message = "Операция отменена во время DATA-фазы на offset=$totalSent"
                            markSessionBroken(message)
                            return TransferResult(false, cancelled = true, message = message, bytesTransferred = totalSent)
                        }

                        val absoluteOffset = totalSent
                        val requested = usbBuffer.remaining()
                        val startedCallMs = System.currentTimeMillis()
                        val result = queueDataOutRequest(request, conn, usbBuffer, absoluteOffset)
                        val elapsedMs = System.currentTimeMillis() - startedCallMs

                        if (debugLogging) {
                            onLogVerbose(
                                "[usb-request-tx] offset=$absoluteOffset requested=$requested " +
                                    "confirmed=${result.bytesSent} elapsed=${elapsedMs}ms state=$sessionState"
                            )
                        }

                        if (result.cancelled) {
                            val message = result.message.ifBlank { "DATA-передача отменена на offset=$absoluteOffset" }
                            markSessionBroken(message)
                            return TransferResult(false, cancelled = true, message = message, bytesTransferred = totalSent)
                        }
                        if (result.bytesSent <= 0) {
                            // Нельзя менять transport внутри уже открытой Fastboot DATA-фазы.
                            // Неоднозначный async failure не доказывает, что контроллер не принял
                            // часть байтов. Никакого inline fallback и никакого автоматического
                            // объявления sync-транспорта совместимым: альтернативный путь должен
                            // отдельно пройти Safe DATA Self-Test в свежей Fastboot-сессии.
                            val message = result.message.ifBlank {
                                "USB DATA UsbRequest failed at offset=$absoluteOffset: requested=$requested, elapsed=${elapsedMs}ms"
                            } + ". Повтор/смена транспорта в этой DATA-сессии запрещены; выполните Safe DATA Self-Test после нового входа в Fastboot."
                            markSessionBroken(message)
                            return TransferResult(false, message = message, bytesTransferred = totalSent)
                        }

                        totalSent += result.bytesSent
                    }

                    val now = System.currentTimeMillis()
                    val progress = ((totalSent * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    val elapsedMs = (now - startedMs).coerceAtLeast(1L)
                    val avgBytesPerSec = (totalSent * 1000.0) / elapsedMs.toDouble()
                    val remainingBytes = (totalBytes - totalSent).coerceAtLeast(0L)
                    val etaMs = if (avgBytesPerSec > 1.0) ((remainingBytes / avgBytesPerSec) * 1000.0).toLong() else -1L

                    val shouldUpdateUi = progress == 100 ||
                        progress >= lastUiProgress + DiagnosticLogPolicy.uiProgressStepPercent() ||
                        now - lastUiUpdateMs >= DiagnosticLogPolicy.uiProgressIntervalMs()
                    if (shouldUpdateUi) {
                        onProgress(
                            progress,
                            "${formatBytes(totalSent)} / ${formatBytes(totalBytes)}  ·  " +
                                "avg ${formatBytesPerSecond(avgBytesPerSec)}  ·  ETA ${formatDuration(etaMs)}"
                        )
                        lastUiProgress = progress
                        lastUiUpdateMs = now
                    }

                    val shouldLogProgress = progress == 100 ||
                        progress >= lastLoggedProgress + DiagnosticLogPolicy.progressLogStepPercent(debugLogging)
                    val shouldLogRate = now - lastRateLogMs >= DiagnosticLogPolicy.progressLogIntervalMs(debugLogging)
                    if (shouldLogProgress || shouldLogRate) {
                        val instantWindowMs = (now - lastRateLogMs).coerceAtLeast(1L)
                        val instantBytesPerSec = ((totalSent - lastRateLogBytes) * 1000.0) / instantWindowMs.toDouble()
                        onLog(
                            "Передано: $progress% " +
                                "(${formatBytes(totalSent)}/${formatBytes(totalBytes)}), " +
                                "speed=${formatBytesPerSecond(instantBytesPerSec)}, " +
                                "avg=${formatBytesPerSecond(avgBytesPerSec)}, " +
                                "eta=${formatDuration(etaMs)}"
                        )
                        lastLoggedProgress = progress
                        lastRateLogMs = now
                        lastRateLogBytes = totalSent
                    }
                }
            }
        } catch (e: Exception) {
            val message = "Ошибка чтения/UsbRequest-передачи файла: ${e.message ?: e.javaClass.simpleName}"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        } finally {
            activeDataRequest = null
            runCatching { request.close() }
        }

        if (totalSent != totalBytes) {
            val message = "Передача завершилась раньше конца файла (${formatBytes(totalSent)}/${formatBytes(totalBytes)})"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        }
        return TransferResult(true, bytesTransferred = totalBytes)
    }

    /**
     * Experimental DATA transfer through Linux usbfs URB ioctls using the raw
     * UsbDeviceConnection file descriptor. This AOSP-style bounded URB transport is used only for download-only
     * diagnostics, not as persisted authorization for real mutation.
     */
    private fun transferDownloadPayloadNativeUsbfs(file: File, label: String, totalBytes: Long): TransferResult {
        val conn = connection
        val out = endpointOut
        val preflight = NativeUsbfsBackend.preflightError(conn, out)
        if (preflight != null) {
            val message = "Native USBFS transport unavailable before DATA: $preflight"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = 0L)
        }
        val activeConnection = conn ?: run {
            val message = "Native USBFS transport unavailable: UsbDeviceConnection is missing"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = 0L)
        }
        val activeOut = out ?: run {
            val message = "Native USBFS transport unavailable: OUT endpoint is missing"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = 0L)
        }
        val profile = dataTransportMode.nativeUsbfsProfile() ?: NativeUsbfsProfile(
            "Native USBFS fallback single 64 KiB",
            64 * 1024,
            1
        )
        val blockBytes = profile.blockBytes
        val pipelineDepth = profile.pipelineDepth
        onLog(
            "Передача $label: ${formatBytes(totalBytes)}. " +
                "transport=${profile.label}, depth=$pipelineDepth, block=${formatBytes(blockBytes.toLong())}, " +
                "stall-timeout=${formatDuration(NATIVE_USBFS_URB_TIMEOUT_MS.toLong())}, " +
                "hard-timeout=${formatDuration(NATIVE_USBFS_HARD_TIMEOUT_MS.toLong())}. " +
                "diagnostic-only: PASS не разрешает flash."
        )
        onLogVerbose(
            "[fastboot-data-diag] mode=NativeUsbfsMatrix endpoint=${endpointDescriptor(activeOut)} " +
                "profile=${profile.label} total=$totalBytes block=$blockBytes depth=$pipelineDepth " +
                "stall_timeout_ms=$NATIVE_USBFS_URB_TIMEOUT_MS hard_timeout_ms=$NATIVE_USBFS_HARD_TIMEOUT_MS"
        )
        onProgress(0, "0 B / ${formatBytes(totalBytes)}  ·  ${profile.label}  ·  speed=N/A")
        onLog("⏳ Native USBFS ожидает первый URB completion: передано 0 B/${formatBytes(totalBytes)}, speed=N/A")
        val startedMs = System.currentTimeMillis()
        val result = NativeUsbfsBackend.transferBulkOutUrb(
            connection = activeConnection,
            outEndpoint = activeOut,
            payloadFile = file,
            blockBytes = blockBytes,
            pipelineDepth = pipelineDepth,
            stallTimeoutMs = NATIVE_USBFS_URB_TIMEOUT_MS,
            hardTimeoutMs = NATIVE_USBFS_HARD_TIMEOUT_MS
        )
        val elapsedMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
        val speed = if (elapsedMs > 0) (result.bytesTransferred * 1000.0) / elapsedMs.toDouble() else 0.0
        onLogVerbose(
            "[fastboot-data-native-result] success=${result.success} " +
                "stage=${NativeUsbfsBackend.stageLabel(result.stage)} " +
                "confirmed_before_stop=${result.bytesTransferred} submitted=${result.submittedBytes} " +
                "stop_errno=${result.errnoCode} kernel_ioctl_errno=${result.kernelIoctlErrno} " +
                "last_completed_status=${result.urbStatus} last_completed_actual=${result.actualLength} " +
                "pending_at_stop=${result.pendingUrbCountAtStop} " +
                "last_completion_age_ms=${result.lastCompletionAgeMs} " +
                "drain=${NativeUsbfsBackend.drainLabel(result.drainState)} " +
                "drain_errno=${result.drainErrno} backend_poisoned=${result.backendPoisoned} " +
                "elapsed_ms=${result.elapsedMs}"
        )
        if (!result.success) {
            val speedLabel = if (result.bytesTransferred <= 0L) "speed=N/A (DATA не стартовала)" else "avg=${formatBytesPerSecond(speed)}"
            onProgress(0, "${formatBytes(result.bytesTransferred)} / ${formatBytes(totalBytes)}  ·  $speedLabel  ·  stage=${NativeUsbfsBackend.stageLabel(result.stage)}")
            val message = result.message + ", $speedLabel, profile=${profile.label}. Повтор/смена транспорта в этой DATA-сессии запрещены; выполните новый вход в Fastboot."
            markSessionBroken(message)
            attemptEndpointResetRecovery("Native USBFS wedge (${NativeUsbfsBackend.stageLabel(result.stage)})")
            return TransferResult(false, message = message, bytesTransferred = result.bytesTransferred)
        }
        if (result.bytesTransferred != totalBytes) {
            val message = "Native USBFS передал неожиданный размер: ${formatBytes(result.bytesTransferred)}/${formatBytes(totalBytes)}"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = result.bytesTransferred)
        }
        onLog(
            "✅ Native USBFS Pipeline DATA payload sent: ${formatBytes(result.bytesTransferred)}, " +
                "avg=${formatBytesPerSecond(speed)}, elapsed=${formatDuration(elapsedMs)}"
        )
        onProgress(100, "${formatBytes(result.bytesTransferred)} / ${formatBytes(totalBytes)}  ·  ${formatBytesPerSecond(speed)}")
        return TransferResult(true, bytesTransferred = totalBytes)
    }

    /**
     * Синхронная передача DATA-фазы через bulkTransfer для USB-путей, где
     * UsbRequest уже был признан несовместимым в предыдущей Fastboot-сессии.
     * Режим выбирается ДО начала DATA payload и никогда не включается inline.
     */
    private fun transferDownloadPayloadSync(file: File, label: String, totalBytes: Long): TransferResult {
        val conn = connection
        val out = endpointOut
        if (conn == null || out == null) {
            val message = "USB DATA transport недоступен (sync): соединение или OUT endpoint отсутствует"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = 0L)
        }

        val chunk = ByteArray(16384)   // 16KB — безопасный размер для bulkTransfer
        var totalSent = 0L
        var lastLoggedProgress = -1
        var lastUiProgress = -1
        val startedMs = System.currentTimeMillis()
        var lastRateLogMs = startedMs
        var lastUiUpdateMs = startedMs

        onLog(
            "Передача $label (sync): ${formatBytes(totalBytes)}. " +
                "transport=bulkTransfer, block=16 KB. Не сворачивайте приложение и не отключайте OTG/кабель."
        )
        onLogVerbose(
            "[fastboot-data-diag] mode=bulkTransfer sdk=${Build.VERSION.SDK_INT} " +
                "endpoint=${endpointDescriptor(out)} total=$totalBytes block=${chunk.size} timeout_ms=$SYNC_BULK_TIMEOUT_MS"
        )

        try {
            file.inputStream().use { input ->
                while (true) {
                    if (cancelled) {
                        val message = "Операция отменена во время sync DATA-фазы на ${formatBytes(totalSent)}/${formatBytes(totalBytes)}"
                        markSessionBroken(message)
                        return TransferResult(false, cancelled = true, message = message, bytesTransferred = totalSent)
                    }
                    val read = input.read(chunk)
                    if (read <= 0) break

                    var offset = 0
                    while (offset < read) {
                        val requested = read - offset
                        val callStartedNs = System.nanoTime()
                        val sent = conn.bulkTransfer(out, chunk, offset, requested, SYNC_BULK_TIMEOUT_MS)
                        val elapsedUs = (System.nanoTime() - callStartedNs) / 1_000L
                        onLogVerbose(
                            "[fastboot-data-diag] mode=bulkTransfer offset=${totalSent + offset} " +
                                "requested=$requested return=$sent elapsed_us=$elapsedUs timeout_ms=$SYNC_BULK_TIMEOUT_MS " +
                                "endpoint=${endpointDescriptor(out)}"
                        )
                        if (sent <= 0) {
                            // Mid-stream retry is intentionally forbidden: after an ambiguous OUT
                            // failure we cannot prove whether the controller accepted bytes.
                            val message =
                                "Sync bulkTransfer сбой: return=$sent, requested=$requested, " +
                                    "elapsed=${formatMicros(elapsedUs)}, timeout=${SYNC_BULK_TIMEOUT_MS}ms, " +
                                    "offset=${totalSent + offset} (${formatBytes(totalSent + offset)}/${formatBytes(totalBytes)}), " +
                                    "${endpointDescriptor(out)}. Повтор в той же DATA-сессии запрещён."
                            markSessionBroken(message)
                            return TransferResult(false, message = message, bytesTransferred = totalSent + offset)
                        }
                        if (sent > requested) {
                            val message =
                                "Sync bulkTransfer вернул невозможный размер: return=$sent > requested=$requested, " +
                                    "offset=${totalSent + offset}, ${endpointDescriptor(out)}"
                            markSessionBroken(message)
                            return TransferResult(false, message = message, bytesTransferred = totalSent + offset)
                        }
                        offset += sent
                    }
                    totalSent += read

                    val now = System.currentTimeMillis()
                    val progress = ((totalSent * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    val elapsedMs = (now - startedMs).coerceAtLeast(1L)
                    val avgBytesPerSec = (totalSent * 1000.0) / elapsedMs.toDouble()
                    val remainingBytes = (totalBytes - totalSent).coerceAtLeast(0L)
                    val etaMs = if (avgBytesPerSec > 1.0) ((remainingBytes / avgBytesPerSec) * 1000.0).toLong() else -1L

                    val shouldUpdateUi = progress == 100 ||
                        progress >= lastUiProgress + DiagnosticLogPolicy.uiProgressStepPercent() ||
                        now - lastUiUpdateMs >= DiagnosticLogPolicy.uiProgressIntervalMs()
                    if (shouldUpdateUi) {
                        onProgress(
                            progress,
                            "${formatBytes(totalSent)} / ${formatBytes(totalBytes)}  ·  " +
                                "avg ${formatBytesPerSecond(avgBytesPerSec)}  ·  ETA ${formatDuration(etaMs)}"
                        )
                        lastUiProgress = progress
                        lastUiUpdateMs = now
                    }

                    val shouldLog = progress == 100 ||
                        progress >= lastLoggedProgress + DiagnosticLogPolicy.progressLogStepPercent(debugLogging) ||
                        now - lastRateLogMs >= DiagnosticLogPolicy.progressLogIntervalMs(debugLogging)
                    if (shouldLog) {
                        onLog(
                            "Передано (sync): $progress% (${formatBytes(totalSent)}/${formatBytes(totalBytes)}), " +
                                "avg=${formatBytesPerSecond(avgBytesPerSec)}, eta=${formatDuration(etaMs)}"
                        )
                        lastLoggedProgress = progress
                        lastRateLogMs = now
                    }
                }
            }
        } catch (e: Exception) {
            val message = "Ошибка sync-передачи файла: ${e.message ?: e.javaClass.simpleName}"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        }

        if (totalSent != totalBytes) {
            val message = "Sync-передача завершилась раньше конца файла (${formatBytes(totalSent)}/${formatBytes(totalBytes)})"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        }
        onLog("✅ Sync-передача завершена: ${formatBytes(totalSent)}")
        return TransferResult(true, bytesTransferred = totalBytes)
    }

    private fun queueDataOutRequest(
        request: UsbRequest,
        conn: UsbDeviceConnection,
        buffer: ByteBuffer,
        absoluteOffset: Long
    ): DataOutResult {
        val startPosition = buffer.position()
        val requested = buffer.remaining()
        val queuedAt = System.nanoTime()
        val endpoint = endpointOut
        val endpointInfo = endpointDescriptor(endpoint)

        val queued = try {
            request.queue(buffer)
        } catch (e: Exception) {
            return DataOutResult(
                message = "UsbRequest.queue exception: ${throwableSummary(e)}, offset=$absoluteOffset, " +
                    "requested=$requested, $endpointInfo"
            )
        }
        if (!queued) {
            return DataOutResult(
                message = "UsbRequest.queue rejected DATA block: offset=$absoluteOffset, requested=$requested, $endpointInfo"
            )
        }

        val completed = try {
            conn.requestWait(DATA_REQUEST_WATCHDOG_MS)
        } catch (e: Exception) {
            request.cancel()
            val elapsedUs = (System.nanoTime() - queuedAt) / 1_000L
            return DataOutResult(
                message = "UsbRequest.requestWait exception: ${throwableSummary(e)}, offset=$absoluteOffset, " +
                    "requested=$requested, elapsed=${formatMicros(elapsedUs)}, watchdog=${DATA_REQUEST_WATCHDOG_MS}ms, $endpointInfo"
            )
        }
        val elapsedUs = (System.nanoTime() - queuedAt) / 1_000L

        if (cancelled) {
            request.cancel()
            return DataOutResult(
                cancelled = true,
                message = "DATA-передача отменена во время UsbRequest: offset=$absoluteOffset, requested=$requested, " +
                    "elapsed=${formatMicros(elapsedUs)}, $endpointInfo"
            )
        }
        if (completed == null) {
            request.cancel()
            return DataOutResult(
                message = "USB DATA UsbRequest watchdog/error: offset=$absoluteOffset, requested=$requested, " +
                    "elapsed=${formatMicros(elapsedUs)}, watchdog=${DATA_REQUEST_WATCHDOG_MS}ms, $endpointInfo"
            )
        }
        if (completed !== request) {
            request.cancel()
            return DataOutResult(
                message = "Получено завершение чужого UsbRequest: offset=$absoluteOffset, requested=$requested, " +
                    "elapsed=${formatMicros(elapsedUs)}, $endpointInfo"
            )
        }

        val sent = buffer.position() - startPosition
        onLogVerbose(
            "[fastboot-data-diag] mode=UsbRequest offset=$absoluteOffset requested=$requested " +
                "confirmed=$sent elapsed_us=$elapsedUs watchdog_ms=$DATA_REQUEST_WATCHDOG_MS endpoint=$endpointInfo"
        )
        if (sent <= 0 || sent > requested) {
            return DataOutResult(
                message = "UsbRequest завершён с некорректным количеством байт: offset=$absoluteOffset, " +
                    "requested=$requested, confirmed=$sent, elapsed=${formatMicros(elapsedUs)}, $endpointInfo"
            )
        }
        return DataOutResult(bytesSent = sent)
    }

    private fun dataOutBlockBytes(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) DATA_BLOCK_BYTES_MODERN else DATA_BLOCK_BYTES_LEGACY

    private fun logFastbootFailure(context: String, payload: String) {
        val cleanPayload = payload.trim().ifBlank { "unknown fastboot failure" }
        onLog("❌ $context: $cleanPayload")
        explainFastbootFailure(cleanPayload)?.let { onLog("ℹ️ Расшифровка: $it") }
    }

    private fun explainFastbootFailure(payload: String): String? {
        val p = payload.lowercase(Locale.US)
        return when {
            "locked" in p || "unlock" in p && "not" in p -> "загрузчик заблокирован или раздел запрещён к записи при текущем состоянии bootloader."
            "not allowed" in p || "permission" in p || "denied" in p -> "OEM bootloader отказал в операции; проверьте unlocked=yes, режим fastbootd и разрешённость раздела."
            "no such partition" in p || "unknown partition" in p || "partition" in p && "not found" in p -> "раздел отсутствует на этой модели/слоте или ROM не соответствует product устройства."
            "too large" in p || "data too" in p || "max-download" in p -> "файл больше лимита max-download-size; нужен другой режим fastboot/fastbootd или раздельный/sparse-образ."
            "sparse" in p -> "ошибка sparse/sparsechunk-образа; проверьте целостность ROM и наличие всех chunk-файлов."
            "signature" in p || "verify" in p || "verification" in p || "vbmeta" in p -> "отказ проверки подписи/верификации; проверьте vbmeta/verity/verification и совместимость ROM."
            "not support" in p || "unknown command" in p || "unrecognized" in p -> "команда не поддерживается этим bootloader/fastbootd; возможно нужен другой режим или OEM-специфичный скрипт."
            "space" in p || "storage" in p || "allocation" in p -> "не хватает места/размера в dynamic partitions; проверьте update-super, super_empty.img и fastbootd."
            "timeout" in p || "timed out" in p -> "таймаут USB/fastboot; проверьте кабель, OTG-питание и не блокируйте экран хоста."
            else -> null
        }
    }

    private fun dataTransportModeLabel(mode: DataTransportMode): String = when (mode) {
        DataTransportMode.ASYNC_USB_REQUEST -> "UsbRequest"
        DataTransportMode.SYNC_BULK -> "sync bulkTransfer"
        DataTransportMode.NATIVE_USBFS_SINGLE_64K -> "Native USBFS single 64 KiB (diagnostic-only)"
        DataTransportMode.NATIVE_USBFS_SINGLE_256K -> "Native USBFS single 256 KiB (diagnostic-only)"
        DataTransportMode.NATIVE_USBFS_PIPELINE_16K -> "Native USBFS pipeline 2×16 KiB (diagnostic-only)"
        DataTransportMode.NATIVE_USBFS_PIPELINE_64K -> "Native USBFS pipeline 2×64 KiB (diagnostic-only)"
        DataTransportMode.NATIVE_USBFS_PIPELINE_128K -> "Native USBFS pipeline 2×128 KiB (diagnostic-only)"
        DataTransportMode.NATIVE_USBFS_PIPELINE_256K -> "Native USBFS pipeline 2×256 KiB (diagnostic-only)"
    }

    private fun endpointDescriptor(endpoint: UsbEndpoint?): String {
        if (endpoint == null) return "endpoint=missing"
        val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
        return "endpoint=$direction:0x${endpoint.address.toString(16)},maxPacket=${endpoint.maxPacketSize},type=${endpoint.type}"
    }

    private fun throwableSummary(error: Throwable): String {
        val className = error.javaClass.name
        val message = error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "<empty>"
        val cause = error.cause?.let { causeError ->
            val causeMessage = causeError.message?.trim()?.takeIf { it.isNotEmpty() } ?: "<empty>"
            ", cause=${causeError.javaClass.name}:$causeMessage"
        } ?: ""
        return "class=$className,message=$message$cause"
    }

    private fun formatMicros(microseconds: Long): String = when {
        microseconds >= 1_000_000L -> String.format(Locale.US, "%.3fs", microseconds / 1_000_000.0)
        microseconds >= 1_000L -> String.format(Locale.US, "%.3fms", microseconds / 1_000.0)
        else -> "${microseconds}us"
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    private fun formatBytesPerSecond(bytesPerSec: Double): String =
        "${formatBytes(bytesPerSec.toLong().coerceAtLeast(0L))}/s"

    private fun formatDuration(ms: Long): String {
        if (ms < 0L) return "unknown"
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    fun cancel() {
        val wasCancelled = cancelled
        cancelled = true
        runCatching { activeDataRequest?.cancel() }
        val nativeCancelRequested = NativeUsbfsBackend.cancelActiveTransfer()
        if (!wasCancelled && nativeCancelRequested) {
            onLog("⏳ Отмена Native USBFS запрошена. Завершаем pending URB через DISCARDURB → REAP; USB-сессия остаётся активной до фактического завершения очистки.")
        }
    }

    /**
     * Requests cancellation and closes the Java USB transport only after the
     * blocking native USBFS call has returned. Returning false means close was
     * deliberately deferred; the owner must await native idle and call again.
     */
    fun disconnect(): Boolean {
        cancel()
        if (!closeUsbTransport()) {
            if (sessionState != SessionState.CLOSED) sessionState = SessionState.BROKEN
            cachedDiagnostics = null
            return false
        }
        sessionState = SessionState.CLOSED
        cachedDiagnostics = null
        return true
    }

    private fun closeUsbTransport(): Boolean {
        val nativeState = NativeUsbfsBackend.backendState()
        if (!UsbTransportShutdownPolicy.canCloseUsb(
                kotlinTransferActive = NativeUsbfsBackend.hasActiveTransfer,
                nativeTransferActive = nativeState.nativeTransferActive
            )
        ) {
            onLog(
                "⏳ Закрытие UsbDeviceConnection отложено: Native USBFS ещё выполняет " +
                    "DISCARDURB → REAP. Интерфейс и file descriptor остаются открыты до подтверждённого drain."
            )
            return false
        }
        runCatching { activeDataRequest?.cancel() }
        activeDataRequest = null
        fastbootInterface?.let { iface -> runCatching { connection?.releaseInterface(iface) } }
        runCatching { connection?.close() }
        connection        = null
        endpointIn        = null
        endpointOut       = null
        fastbootInterface = null
        return true
    }

    private fun ensureSessionReady(operation: String): Boolean {
        if (sessionState == SessionState.BROKEN || sessionState == SessionState.CLOSED) {
            onLog("⛔ Команда запрещена: Fastboot-сессия $sessionState. Требуется повторный вход устройства в Fastboot.")
            return false
        }
        if (!isConnected) {
            onLog("❌ Нет Fastboot-соединения для $operation")
            return false
        }
        if (sessionState != SessionState.IDLE) {
            markSessionBroken("Новая операция '$operation' запрошена в состоянии $sessionState")
            return false
        }
        if (cancelled) {
            onLog("⚠️ Операция отменена до отправки команды: $operation")
            return false
        }
        return true
    }

    private fun markSessionBroken(reason: String) {
        if (sessionState == SessionState.CLOSED) return
        if (sessionState != SessionState.BROKEN) {
            lastBrokenReasonCode = classifyBrokenReason(reason)
            lastBrokenReason = reason.take(700)
            sessionState = SessionState.BROKEN
            cachedDiagnostics = null
            onLog("⛔ FASTBOOT SESSION BROKEN [${lastBrokenReasonCode.name}]: $reason")
            val closed = closeUsbTransport()
            if (closed) {
                onLog("⛔ USB-соединение закрыто. Новые команды запрещены до полного повторного входа целевого устройства в Fastboot.")
            } else {
                onLog("⛔ Новые команды запрещены. Физическое закрытие USB будет выполнено только после подтверждённого Native USBFS drain.")
            }
        }
    }


    private fun classifyBrokenReason(reason: String): BrokenReasonCode {
        val lower = reason.lowercase(Locale.US)
        return when {
            "первич" in lower && ("handshake" in lower || "getvar:product" in lower) -> BrokenReasonCode.FIRST_RESPONSE_TIMEOUT
            "cancel" in lower || "отмен" in lower -> BrokenReasonCode.USER_CANCELLED_DURING_DATA
            "native usbfs" in lower || "urb" in lower -> BrokenReasonCode.NATIVE_USBFS_FAILURE
            "short write" in lower || "неоднозначно" in lower || ("отправ" in lower && "/" in lower) -> BrokenReasonCode.SHORT_WRITE
            "short read" in lower -> BrokenReasonCode.SHORT_READ
            "read failed" in lower || "таймаут ответа" in lower || "ожидания" in lower -> BrokenReasonCode.USB_IN_TIMEOUT
            "write" in lower || "out" in lower -> BrokenReasonCode.USB_OUT_TIMEOUT
            "disconnect" in lower || "отключ" in lower || "ушло с шины" in lower -> BrokenReasonCode.DEVICE_DISCONNECTED
            "interface" in lower || "интерфейс" in lower -> BrokenReasonCode.INTERFACE_LOST
            "неизвестный ответ" in lower || "unexpected" in lower -> BrokenReasonCode.UNEXPECTED_RESPONSE
            "состояни" in lower || "state" in lower || "незаверш" in lower -> BrokenReasonCode.INVALID_STATE
            "protocol" in lower || "синхронизац" in lower -> BrokenReasonCode.PROTOCOL_DESYNC
            else -> BrokenReasonCode.UNKNOWN
        }
    }

    private fun flashTransportFailure(stage: FlashStage, message: String): FlashResult {
        markSessionBroken(message)
        return FlashResult.fail(stage, FlashFailureKind.TRANSPORT, message, sessionCorrupted = true)
    }

    private fun writeCommand(command: String, timeout: Int): Boolean {
        if (!ensureSessionReady(command)) return false
        val cmdBytes = command.toByteArray(Charsets.US_ASCII)
        if (cmdBytes.isEmpty() || cmdBytes.size > 64) {
            onLog("❌ Некорректный размер Fastboot-команды: ${cmdBytes.size} байт")
            return false
        }

        val sequence = lastCommandSequence + 1L
        val callStartedNs = System.nanoTime()
        lastResponseCompletedNs?.let { previousResponseNs ->
            val gapUs = ((callStartedNs - previousResponseNs).coerceAtLeast(0L)) / 1_000L
            onLogVerbose(
                "[fastboot-timing] phase=turnaround seq=$sequence command=$command response_to_out_us=$gapUs"
            )
        }

        val sent = bulkWrite(cmdBytes, 0, cmdBytes.size, timeout)
        val completedNs = System.nanoTime()
        val elapsedUs = (completedNs - callStartedNs).coerceAtLeast(0L) / 1_000L
        onLogVerbose(
            "[fastboot-timing] phase=out seq=$sequence command=$command requested=${cmdBytes.size} " +
                "return=$sent elapsed_us=$elapsedUs timeout_ms=$timeout endpoint=${endpointDescriptor(endpointOut)}"
        )
        if (sent != cmdBytes.size) {
            val message = "Команда отправлена неоднозначно: $sent/${cmdBytes.size} байт, elapsed=${formatMicros(elapsedUs)}"
            onLog("ОШИБКА: $message")
            markSessionBroken(message)
            return false
        }

        lastCommandSequence = sequence
        lastCommandSentNs = completedNs
        lastCommandName = command
        sessionState = SessionState.COMMAND_SENT
        if (debugLogging) onLog("[debug] USB OUT command bytes=${cmdBytes.size}, elapsed=${formatMicros(elapsedUs)}")
        onLogVerbose("-> $command")
        return true
    }

    private fun bulkWrite(data: ByteArray, offset: Int, length: Int, timeout: Int): Int {
        val conn = connection ?: return -1
        val out = endpointOut ?: return -1
        // Короткие Fastboot-команды остаются на синхронном bulkTransfer.
        // Большая DATA-фаза передаётся отдельно через UsbRequest.
        return conn.bulkTransfer(out, data, offset, length, timeout)
    }

    private fun readPacket(timeoutMs: Int): FastbootPacket? {
        val conn  = connection ?: return null
        val input = endpointIn ?: return null
        // FIX: буфер 1024 байт — покрывает USB HS/SS пакеты и длинные INFO-строки
        val buffer = ByteArray(1024)
        val startedNs = System.nanoTime()
        val bytesRead = conn.bulkTransfer(input, buffer, buffer.size, timeoutMs)
        val completedNs = System.nanoTime()
        val elapsedUs = (completedNs - startedNs).coerceAtLeast(0L) / 1_000L
        val command = lastCommandName ?: "none"
        val sequence = lastCommandSequence

        if (bytesRead <= 0) {
            val sinceCommandUs = lastCommandSentNs?.let { ((completedNs - it).coerceAtLeast(0L)) / 1_000L } ?: -1L
            onLogVerbose(
                "[fastboot-timing] phase=in-empty seq=$sequence command=$command return=$bytesRead " +
                    "elapsed_us=$elapsedUs since_command_us=$sinceCommandUs timeout_ms=$timeoutMs endpoint=${endpointDescriptor(input)}"
            )
            // bulkTransfer возвращает <=0 и по обычному timeout. Повторять можно только
            // чтение уже отправленной команды; сама команда не пересылается.
            return null
        }

        val raw = String(buffer, 0, bytesRead, Charsets.US_ASCII).replace("\u0000", "").trim()
        val packet = if (raw.length < 4) {
            FastbootPacket("UNKNOWN", raw, raw)
        } else {
            FastbootPacket(raw.take(4), raw.drop(4).trim(), raw)
        }
        val roundTripUs = lastCommandSentNs?.let { ((completedNs - it).coerceAtLeast(0L)) / 1_000L } ?: -1L
        onLogVerbose(
            "[fastboot-timing] phase=in seq=$sequence command=$command type=${packet.type} bytes=$bytesRead " +
                "read_elapsed_us=$elapsedUs roundtrip_us=$roundTripUs endpoint=${endpointDescriptor(input)}"
        )
        lastResponseCompletedNs = completedNs

        if (raw.isNotEmpty()) {
            if (debugLogging) onLog("[debug] USB IN bytes=$bytesRead")
            onLogVerbose("<- $raw")
        }
        return packet
    }

    private fun readUntilFinal(timeout: Int): FastbootPacket? {
        while (!cancelled) {
            val packet = readPacket(timeout) ?: return null
            when (packet.type) {
                "OKAY", "FAIL" -> { sessionState = SessionState.IDLE; return packet }
                "INFO", "TEXT" -> continue
                "DATA"         -> { sessionState = SessionState.DATA_OUT; return packet }
                else           -> onLog("⚠️ Неизвестный ответ Fastboot: ${packet.raw}")
            }
        }
        onLog("⚠️ Операция отменена пользователем")
        return null
    }

    /**
     * FIX #3: Вместо одного readPacket(300_000) используем цикл с коротким
     * таймаутом (2 сек) и счётчиком суммарного времени.
     * Это позволяет:
     *  - корректно реагировать на cancelled в любой момент
     *  - не прерывать прошивку при временном молчании устройства (NAND erase)
     *  - логировать сколько секунд ждём
     */
    private fun readUntilFinalWithRetry(
        singleReadTimeoutMs: Int = 2000,
        maxTotalTimeMs: Long = 600_000L
    ): FastbootPacket? {
        val startTime = System.currentTimeMillis()
        var lastLogSec = 0L

        while (!cancelled) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= maxTotalTimeMs) {
                val message = "Превышен лимит ожидания (${maxTotalTimeMs / 1000} сек) в состоянии $sessionState"
                onLog("❌ $message")
                markSessionBroken(message)
                return null
            }

            val packet = readPacket(singleReadTimeoutMs)
            if (packet == null) {
                // Таймаут одного пакета — нормально при записи, логируем каждые 10 сек
                val elapsedSec = elapsed / 1000
                if (elapsedSec / 10 != lastLogSec / 10) {
                    onLog("⏳ Ожидание ответа устройства... ${elapsedSec} сек")
                    lastLogSec = elapsedSec
                }
                continue
            }

            when (packet.type) {
                "OKAY", "FAIL" -> { sessionState = SessionState.IDLE; return packet }
                "INFO", "TEXT" -> continue
                else           -> onLog("⚠️ Неизвестный ответ: ${packet.raw}")
            }
        }
        onLog("⚠️ Операция отменена пользователем")
        return null
    }

    private fun readUntilDataOrFinal(timeout: Int): FastbootPacket? {
        while (!cancelled) {
            val packet = readPacket(timeout) ?: return null
            when (packet.type) {
                "DATA" -> { sessionState = SessionState.DATA_OUT; return packet }
                "OKAY", "FAIL" -> { sessionState = SessionState.IDLE; return packet }
                "INFO", "TEXT" -> continue
                else                   -> onLog("⚠️ Неизвестный ответ Fastboot: ${packet.raw}")
            }
        }
        onLog("⚠️ Операция отменена пользователем")
        return null
    }

    private fun readGetVarResponse(name: String, timeout: Int): String? {
        val infoLines = mutableListOf<String>()
        val startedMs = System.currentTimeMillis()
        var emptyReads = 0

        while (!cancelled) {
            val elapsedMs = System.currentTimeMillis() - startedMs
            val remainingMs = timeout.toLong() - elapsedMs
            if (remainingMs <= 0L) {
                markSessionBroken(
                    "Таймаут ответа getvar:$name после подтверждённой отправки команды " +
                        "($emptyReads пустых/неудачных чтений)"
                )
                return null
            }

            val readTimeoutMs = minOf(GETVAR_READ_SLICE_MS, remainingMs.toInt().coerceAtLeast(1))
            val packet = readPacket(readTimeoutMs)
            if (packet == null) {
                emptyReads += 1
                if (debugLogging) {
                    onLog(
                        "[debug] getvar:$name: чтение ответа не завершено, " +
                            "failedRead=$emptyReads/$GETVAR_MAX_FAILED_READS remaining=${remainingMs}ms"
                    )
                }
                // V5.8.10 onyx handshake fix: пустые чтения возвращаются мгновенно,
                // поэтому счётчик из трёх набегал за ~200 мс и рвал сессию раньше, чем
                // onyx успевал отдать первый IN-ответ (наблюдаемая задержка ~200-400 мс).
                // Обрыв по количеству теперь разрешён только после того, как исчерпано
                // минимальное окно терпения; общий бюджет timeout (7 c на handshake)
                // остаётся жёстким верхним пределом для реально мёртвой сессии.
                if (emptyReads >= GETVAR_MAX_FAILED_READS && elapsedMs >= GETVAR_MIN_PATIENCE_MS) {
                    markSessionBroken(
                        "Fastboot read failed $emptyReads раза для getvar:$name после подтверждённой отправки команды"
                    )
                    return null
                }
                if (GETVAR_READ_RETRY_DELAY_MS > 0L) {
                    try {
                        Thread.sleep(GETVAR_READ_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
                continue
            }

            when (packet.type) {
                "INFO", "TEXT" -> if (packet.payload.isNotBlank()) infoLines += packet.payload.trim()
                "OKAY" -> {
                    sessionState = SessionState.IDLE
                    val direct = packet.payload.trim()
                    if (direct.isNotEmpty()) return normalizeGetVarValue(name, direct)
                    return infoLines.asReversed()
                        .asSequence()
                        .mapNotNull { normalizeGetVarValue(name, it) }
                        .firstOrNull()
                }
                "FAIL" -> {
                    sessionState = SessionState.IDLE
                    if (debugLogging) onLog("⚠️ getvar:$name не поддерживается: ${packet.payload}")
                    return null
                }
                else -> onLog("⚠️ Неизвестный ответ Fastboot: ${packet.raw}")
            }
        }
        return null
    }

    private fun readGetVarAllResponse(timeout: Int): FastbootGetVarAllParser.Snapshot? {
        val lines = mutableListOf<String>()
        val startedMs = System.currentTimeMillis()
        var emptyReads = 0

        while (!cancelled) {
            val elapsedMs = System.currentTimeMillis() - startedMs
            val remainingMs = timeout.toLong() - elapsedMs
            if (remainingMs <= 0L) {
                markSessionBroken(
                    "Таймаут ответа getvar:all после подтверждённой отправки команды " +
                        "($emptyReads пустых/неудачных чтений, lines=${lines.size})"
                )
                return null
            }

            val packet = readPacket(minOf(GETVAR_READ_SLICE_MS, remainingMs.toInt().coerceAtLeast(1)))
            if (packet == null) {
                emptyReads += 1
                if (emptyReads >= GETVAR_ALL_MAX_FAILED_READS && elapsedMs >= GETVAR_MIN_PATIENCE_MS) {
                    markSessionBroken(
                        "Fastboot read failed $emptyReads раза для getvar:all после подтверждённой отправки команды"
                    )
                    return null
                }
                if (GETVAR_READ_RETRY_DELAY_MS > 0L) {
                    try {
                        Thread.sleep(GETVAR_READ_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
                continue
            }

            emptyReads = 0
            when (packet.type) {
                "INFO", "TEXT" -> if (packet.payload.isNotBlank()) lines += packet.payload
                "OKAY" -> {
                    sessionState = SessionState.IDLE
                    if (packet.payload.isNotBlank()) lines += packet.payload
                    val snapshot = FastbootGetVarAllParser.parse(lines, complete = true, finalStatus = "OKAY")
                    onLog(
                        "✅ getvar:all inventory: variables=${snapshot.variables.size}, " +
                            "partitions=${snapshot.partitions.size}, ignored=${snapshot.ignoredLines.size}"
                    )
                    return snapshot
                }
                "FAIL" -> {
                    sessionState = SessionState.IDLE
                    val message = packet.payload.ifBlank { packet.raw }
                    if (lines.isEmpty()) {
                        onLog("⚠️ getvar:all не поддерживается: $message")
                        return null
                    }
                    val snapshot = FastbootGetVarAllParser.parse(
                        lines,
                        complete = false,
                        finalStatus = "FAIL",
                        finalMessage = message
                    )
                    onLog(
                        "⚠️ getvar:all вернул частичный inventory: variables=${snapshot.variables.size}, " +
                            "partitions=${snapshot.partitions.size}, final=$message"
                    )
                    return snapshot
                }
                else -> onLog("⚠️ Неизвестный ответ getvar:all: ${packet.raw}")
            }
        }
        return null
    }

    private fun normalizeGetVarValue(name: String, raw: String): String? {
        val cleaned  = raw.trim().removePrefix("INFO").trim()
        val variants = listOf(name, name.replace('-', '_'))
        for (variant in variants) {
            val prefix = "$variant:"
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                return cleaned.substringAfter(':').trim().ifBlank { null }
            }
        }
        return cleaned.substringAfter(':', cleaned).trim().ifBlank { null }
    }

    private fun normalizePartitionName(partition: String): String? {
        val normalized = partition.trim().lowercase()
        if (normalized.isBlank() || !normalized.matches(Regex("[A-Za-z0-9._:-]+"))) {
            onLog("❌ ОШИБКА: некорректное имя раздела: $partition")
            return null
        }
        return normalized
    }

    private fun isLogicalPartitionManagementCommand(command: String): Boolean {
        val clean = command.trim().lowercase()
        return clean.startsWith("create-logical-partition:") ||
            clean.startsWith("delete-logical-partition:") ||
            clean.startsWith("resize-logical-partition:") ||
            clean.startsWith("update-super:")
    }

    private fun fetchChunk(command: String, out: java.io.OutputStream, alreadyFetched: Long, totalSize: Long): Long? {
        if (!writeCommand(command, 5000)) {
            onLog("ОШИБКА: Сбой отправки команды $command")
            return null
        }
        val dataPacket = readUntilDataOrFinal(10000) ?: return null
        when (dataPacket.type) {
            "DATA" -> Unit
            "FAIL" -> { onLog("❌ ОШИБКА fetch: ${dataPacket.payload}"); return null }
            else -> { onLog("❌ ОШИБКА fetch: ожидался DATA, получено ${dataPacket.raw}"); return null }
        }
        val dataSize = parseFastbootDataSize(dataPacket.payload)
        if (dataSize == null || dataSize < 0L) {
            onLog("❌ ОШИБКА fetch: некорректный DATA размер: ${dataPacket.payload}")
            return null
        }
        if (!readRawDataTo(out, dataSize, alreadyFetched, totalSize)) return null
        val done = readUntilFinalWithRetry(singleReadTimeoutMs = 2000, maxTotalTimeMs = 120_000) ?: return null
        return if (done.type == "OKAY") {
            dataSize
        } else {
            onLog("❌ ОШИБКА fetch после data phase: ${done.payload}")
            null
        }
    }

    private fun readRawDataTo(out: java.io.OutputStream, expectedBytes: Long, alreadyFetched: Long, totalSize: Long): Boolean {
        val conn = connection ?: return false
        val input = endpointIn ?: return false
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        var lastLoggedProgress = -1
        while (received < expectedBytes && !cancelled) {
            // Fetch остаётся синхронным IN-путём и использует отдельный
            // консервативный 16 KiB compatibility-read. Это не связано с
            // асинхронным UsbRequest DATA OUT transport прошивки.
            val toRead = minOf(buffer.size.toLong(), expectedBytes - received, 16384L).toInt()
            val bytesRead = conn.bulkTransfer(input, buffer, toRead, 10000)
            if (bytesRead <= 0) {
                onLog("❌ ОШИБКА fetch: таймаут/сбой чтения raw data ($received/$expectedBytes байт)")
                return false
            }
            out.write(buffer, 0, bytesRead)
            received += bytesRead
            val absolute = alreadyFetched + received
            if (totalSize > 0L) {
                val progress = ((absolute * 100) / totalSize).toInt()
                if (progress % 10 == 0 && progress != lastLoggedProgress) {
                    onLog("Fetch: $progress% ($absolute/$totalSize байт)")
                    lastLoggedProgress = progress
                }
            } else if (received % (1024L * 1024L) < bytesRead) {
                onLog("Fetch принято: ${alreadyFetched + received} байт")
            }
        }
        if (cancelled) {
            onLog("⚠️ Fetch отменён пользователем")
            return false
        }
        return true
    }

    private fun parseFastbootDataSize(raw: String?): Long? {
        val token = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try { token.toLong(16) } catch (_: NumberFormatException) { parseFastbootSize(token) }
    }

    private fun parseFastbootSize(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val token = Regex("0x[0-9A-Fa-f]+|[0-9]+").find(raw)?.value ?: return null
        return try {
            if (token.startsWith("0x", ignoreCase = true))
                token.removePrefix("0x").removePrefix("0X").toLong(16)
            else token.toLong()
        } catch (_: NumberFormatException) { null }
    }

    companion object {
        private const val DIAGNOSTICS_CACHE_TTL_MS = 5L * 60L * 1000L
        private const val FASTBOOT_HANDSHAKE_SETTLE_MS = 350L
        private const val FASTBOOT_HANDSHAKE_TIMEOUT_MS = 7_000
        private const val GETVAR_READ_SLICE_MS = 900
        private const val GETVAR_MAX_FAILED_READS = 3
        private const val GETVAR_ALL_MAX_FAILED_READS = 8
        private const val GETVAR_READ_RETRY_DELAY_MS = 100L
        // Минимальное окно терпения до обрыва сессии по счётчику пустых чтений.
        // Покрывает наблюдаемую задержку первого IN-ответа onyx (~200-400 мс) с запасом,
        // оставаясь много меньше GETVAR/FASTBOOT_HANDSHAKE timeout бюджета.
        private const val GETVAR_MIN_PATIENCE_MS = 1_500L
        private const val PARTITION_INVENTORY_MAX_POINT_QUERIES = 24
        private const val DATA_BLOCK_BYTES_LEGACY = 16 * 1024
        private const val DATA_BLOCK_BYTES_MODERN = 256 * 1024
        private const val DATA_REQUEST_WATCHDOG_MS = 30_000L
        private const val SYNC_BULK_TIMEOUT_MS = 10_000
        private const val NATIVE_USBFS_URB_TIMEOUT_MS = 30_000
        private const val NATIVE_USBFS_HARD_TIMEOUT_MS = 90_000
        val SAFE_DATA_SELF_TEST_SIZES_BYTES: Set<Long> = linkedSetOf(
            64L * 1024L,
            256L * 1024L,
            1L * 1024L * 1024L,
            4L * 1024L * 1024L,
            8L * 1024L * 1024L,
            32L * 1024L * 1024L,
            64L * 1024L * 1024L,
            100L * 1024L * 1024L
        )
        // Не whitelist: используется только для мягкого предупреждения в терминальном режиме.
        val TYPICAL_FLASH_PARTITIONS = setOf(
            "boot", "init_boot", "vendor_boot", "recovery", "dtbo", "vbmeta", "vbmeta_system",
            "vbmeta_vendor", "vendor_kernel_boot", "logo", "modem", "modemfirmware", "radio",
            "system", "vendor", "product", "odm", "super", "userdata", "metadata",
            // Qualcomm/Xiaomi firmware-разделы, встречающиеся в flash_all-скриптах
            // (обычно с суффиксом _ab, см. isKnownAbPartitionBase()):
            "abl", "aop", "aop_config", "bluetooth", "countrycode", "cpucp", "cpucp_dtb",
            "devcfg", "dsp", "featenabler", "hyp", "idmanager", "imagefv", "keymaster",
            "multiimgqti", "pvmfw", "qupfw", "shrm", "soccp_dcd", "soccp_debug",
            "spuservice", "tz", "uefi", "uefisecapp", "vm-bootsys", "xbl", "xbl_config",
            "xbl_ramdump"
        )
    }
}
