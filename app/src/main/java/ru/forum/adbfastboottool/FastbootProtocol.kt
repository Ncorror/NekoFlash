package ru.forum.adbfastboottool

import android.hardware.usb.*
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * USB Host реализация Fastboot command/DATA state machine для одной transport-сессии.
 *
 * После protocol desync, short write/read, timeout или disconnect сессия становится broken
 * и должна быть закрыта. Mutation-команды отправляются напрямую устройству; решение о
 * разрешённости flash/erase/oem/flashing принимает bootloader/fastbootd, а не UI-политика.
 */
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
        NATIVE_USBFS,
        ASYNC_USB_REQUEST,
        SYNC_BULK
    }

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

    var dataTransportMode: DataTransportMode =
        if (NativeUsbfsBackend.isAvailable) DataTransportMode.NATIVE_USBFS else DataTransportMode.ASYNC_USB_REQUEST
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
        val brokenReason: String? = null
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
            onLog("ERROR: Fastboot interface not found")
            return false
        }
        fastbootInterface = iface

        val endpoints = findBulkEndpoints(iface)
        endpointIn = endpoints.first
        endpointOut = endpoints.second
        if (endpointIn == null || endpointOut == null) {
            onLog("ERROR: Fastboot bulk IN/OUT endpoints not found")
            disconnect()
            return false
        }

        connection = usbManager.openDevice(device)
        if (connection == null) {
            onLog("ERROR: Could not open USB device for Fastboot")
            disconnect()
            return false
        }
        if (!connection!!.claimInterface(iface, true)) {
            onLog("ERROR: Could not claim Fastboot interface")
            disconnect()
            return false
        }

        sessionState = SessionState.IDLE
        onLog(
            "=== FASTBOOT CONNECTION ESTABLISHED === " +
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
     * Подтверждает, что открытый bulk-интерфейс отвечает как Fastboot peer.
     * `getvar:product` используется как probe, но его protocol FAIL не запрещает сессию.
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
        if (product != null) {
            lastKnownProduct = product
            onLog("✅ Fastboot handshake confirmed: product=$product")
            return@withLock product
        }
        if (sessionState == SessionState.IDLE) {
            // Полученный FAIL на необязательный getvar всё равно доказывает, что peer говорит Fastboot.
            // Не превращаем отсутствие product в host-side запрет на реальную сессию.
            onLog("ℹ️ Fastboot peer responded to handshake, but getvar:product was not provided; connection allowed")
            return@withLock ""
        }
        null
    }

    private fun findFastbootInterface(): UsbInterface? {
        // Явно выбранный UsbDeviceInspector интерфейс имеет приоритет. Это позволяет
        // вручную работать с OEM Fastboot, который не использует 0xFF/0x42/0x03.
        preferredInterfaceIndex?.let { index ->
            if (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                if (isFastbootCompatibleInterface(iface, allowGeneric = true)) return iface
                onLog("⚠️ Selected Fastboot interface=$index no longer has bulk IN/OUT — running a new search")
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
                onLog("ℹ️ Fastboot interface with non-standard protocol=${iface.interfaceProtocol} accepted by full bulk IN/OUT pair")
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
            onLog("⛔ Fastboot session is not usable for a new operation: $sessionState. Reconnect the device.")
            return@withLock false
        }
        if (sessionState != SessionState.IDLE) {
            markSessionBroken("New operation started while a Fastboot transaction is incomplete: $sessionState")
            return@withLock false
        }
        cancelled = false
        true
    }

    fun sendCommand(command: String, timeout: Int = 5000): Boolean = transactionLock.withLock {
        if (!ensureSessionReady("command $command")) return@withLock false
        val mutation = parseMutationRequest(command)
        val success = executeRawCommand(command, timeout)
        if (!success) return@withLock false

        if (mutation?.kind == PostVerifyKind.SET_ACTIVE) {
            val expected = mutation.slot?.trim()?.removePrefix("_")?.lowercase(Locale.US)
                ?: return@withLock false
            val actual = getVar("current-slot")?.trim()?.removePrefix("_")?.lowercase(Locale.US)
            if (actual != expected) {
                onLog("⛔ set_active not confirmed: requested=$expected, current-slot=${actual ?: "unknown"}")
                return@withLock false
            }
            onLog("✅ set_active confirmed by repeated getvar:current-slot=$actual")
        }

        if (mutation?.kind == PostVerifyKind.SNAPSHOT_CONTROL) {
            val actual = FastbootValueParser.parseSnapshotState(getVar("snapshot-update-status"))
            if (actual != FastbootValueParser.SnapshotState.NONE) {
                onLog("⛔ Snapshot control not confirmed: state after command=$actual, expected NONE")
                return@withLock false
            }
            onLog("✅ Snapshot control confirmed: snapshot-update-status=NONE")
        }
        true
    }

    fun runTerminalCommand(command: String, timeout: Int = 5000): Boolean = transactionLock.withLock {
        if (!ensureSessionReady("terminal command $command")) return@withLock false
        val clean = command.trim()
        if (clean.isBlank()) return@withLock false
        val mutation = parseMutationRequest(clean)
        if (!writeCommand(clean, timeout)) return@withLock false
        sessionState = SessionState.AWAITING_COMMAND_FINAL

        val startedMs = System.currentTimeMillis()
        var infoLines = 0
        var lastWaitLogSec = 0L

        while (!cancelled) {
            val elapsedMs = System.currentTimeMillis() - startedMs
            if (elapsedMs >= 120_000L) {
                val message = "Terminal Fastboot command timed out: $clean"
                onLog("❌ $message")
                markSessionBroken(message)
                return@withLock false
            }

            val packet = readPacket(2000)
            if (packet == null) {
                val elapsedSec = elapsedMs / 1000
                if (elapsedSec / 10 != lastWaitLogSec / 10) {
                    onLog("⏳ Fastboot is waiting for a response... ${elapsedSec} sec")
                    lastWaitLogSec = elapsedSec
                }
                continue
            }

            when (packet.type) {
                "INFO", "TEXT" -> {
                    infoLines += 1
                    logTerminalInfo(packet.payload)
                }
                "OKAY" -> {
                    sessionState = SessionState.IDLE
                    logTerminalOkay(clean, packet.payload, infoLines)
                    if (!verifyTerminalMutation(mutation)) return@withLock false
                    return@withLock true
                }
                "FAIL" -> {
                    sessionState = SessionState.IDLE
                    val message = packet.payload.ifBlank { packet.raw }.trim()
                    onLog("❌ Fastboot FAIL: $message")
                    return@withLock false
                }
                "DATA" -> {
                    val message = "Terminal Fastboot command entered DATA phase without a payload handler: $clean"
                    onLog("❌ $message")
                    markSessionBroken(message)
                    return@withLock false
                }
                else -> onLog("⚠️ Unknown Fastboot response: ${packet.raw}")
            }
        }

        onLog("⚠️ Operation cancelled by user")
        false
    }

    private fun verifyTerminalMutation(mutation: MutationRequest?): Boolean {
        if (mutation?.kind == PostVerifyKind.SET_ACTIVE) {
            val expected = mutation.slot?.trim()?.removePrefix("_")?.lowercase(Locale.US) ?: return false
            val actual = getVar("current-slot")?.trim()?.removePrefix("_")?.lowercase(Locale.US)
            if (actual != expected) {
                onLog("⛔ set_active not confirmed: requested=$expected, current-slot=${actual ?: "unknown"}")
                return false
            }
            onLog("✅ set_active confirmed: current-slot=$actual")
        }

        if (mutation?.kind == PostVerifyKind.SNAPSHOT_CONTROL) {
            val actual = FastbootValueParser.parseSnapshotState(getVar("snapshot-update-status"))
            if (actual != FastbootValueParser.SnapshotState.NONE) {
                onLog("⛔ Snapshot control not confirmed: state after command=$actual, expected NONE")
                return false
            }
            onLog("✅ Snapshot control confirmed: snapshot-update-status=NONE")
        }
        return true
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

    fun readXiaomiUnlockToken(): String? = transactionLock.withLock {
        val direct = getVar("token")?.let { normalizeUnlockTokenCandidate(it) }
        if (!direct.isNullOrEmpty()) {
            onLog("🔑 device token received through getvar:token (${direct.length} characters)")
            return@withLock direct
        }

        if (!ensureSessionReady("oem get_token")) return@withLock null
        if (!writeCommand("oem get_token", 5000)) return@withLock null
        sessionState = SessionState.AWAITING_COMMAND_FINAL

        val parts = mutableListOf<String>()
        val startedMs = System.currentTimeMillis()
        var emptyReads = 0

        while (!cancelled) {
            val elapsedMs = System.currentTimeMillis() - startedMs
            if (elapsedMs >= 30_000L) {
                markSessionBroken("oem get_token response timeout after confirmed command send")
                return@withLock null
            }

            val packet = readPacket(GETVAR_READ_SLICE_MS)
            if (packet == null) {
                emptyReads += 1
                if (emptyReads >= GETVAR_MAX_FAILED_READS && elapsedMs >= GETVAR_MIN_PATIENCE_MS) {
                    markSessionBroken("Fastboot read failed $emptyReads times for oem get_token")
                    return@withLock null
                }
                if (GETVAR_READ_RETRY_DELAY_MS > 0L) {
                    try {
                        Thread.sleep(GETVAR_READ_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@withLock null
                    }
                }
                continue
            }

            emptyReads = 0
            when (packet.type) {
                "INFO", "TEXT" -> extractUnlockTokenPart(packet.payload)?.let { parts += it }
                "OKAY" -> {
                    sessionState = SessionState.IDLE
                    extractUnlockTokenPart(packet.payload)?.let { parts += it }
                    val token = parts.joinToString(separator = "").filterNot { it.isWhitespace() }
                    if (token.isNotBlank()) {
                        onLog("🔑 device token received through oem get_token (${parts.size} fragments, ${token.length} characters)")
                        return@withLock token
                    }
                    onLog("⚠️ oem get_token finished without token fragments")
                    return@withLock null
                }
                "FAIL" -> {
                    sessionState = SessionState.IDLE
                    onLog("⚠️ oem get_token is not supported: ${packet.payload.ifBlank { packet.raw }}")
                    return@withLock null
                }
                else -> onLog("⚠️ Unknown oem get_token response: ${packet.raw}")
            }
        }

        onLog("⚠️ Operation cancelled by user")
        null
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
                message = "getvar:all did not confirm concrete partitions; a limited read-only scan of known names was performed.",
                severity = FastbootPartitionInventory.WarningSeverity.WARNING
            )
        }
        if (plan.omittedRequestCount > 0) {
            collectionWarnings += FastbootPartitionInventory.Warning(
                code = "POINT_QUERY_BUDGET_EXHAUSTED",
                message = "Point getvar limit reached; skipped requests: ${plan.omittedRequestCount}.",
                severity = FastbootPartitionInventory.WarningSeverity.INFO
            )
        }
        if (abortedByBrokenSession) {
            collectionWarnings += FastbootPartitionInventory.Warning(
                code = "POINT_QUERY_ABORTED",
                message = "Metadata backfill stopped: Fastboot session lost synchronization.",
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

    private enum class PostVerifyKind { SET_ACTIVE, SNAPSHOT_CONTROL }

    private data class MutationRequest(
        val kind: PostVerifyKind,
        val slot: String? = null
    )

    private fun parseMutationRequest(command: String): MutationRequest? {
        val clean = command.trim()
        val lower = clean.lowercase(Locale.US)
        val control = lower.replace(':', ' ').replace(Regex("\\s+"), " ").trim()
        return when {
            lower.startsWith("set_active:") -> MutationRequest(
                PostVerifyKind.SET_ACTIVE,
                slot = clean.substringAfter(':').trim().takeIf { it.isNotBlank() }
            )
            lower.startsWith("set_active ") || lower.startsWith("set-active ") -> MutationRequest(
                PostVerifyKind.SET_ACTIVE,
                slot = clean.substringAfter(' ').trim().takeIf { it.isNotBlank() }
            )
            control == "snapshot-update cancel" || control == "snapshot-update merge" ->
                MutationRequest(PostVerifyKind.SNAPSHOT_CONTROL)
            else -> null
        }
    }

    fun refreshDiagnostics(
        force: Boolean = false,
        maxAgeMs: Long = DIAGNOSTICS_CACHE_TTL_MS,
        knownProduct: String? = null
    ): DeviceDiagnostics = transactionLock.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedDiagnostics
        if (!force && cached != null && now - cached.timestamp <= maxAgeMs) {
            onLog("=== FASTBOOT DATA FROM CACHE ===")
            logDiagnostics(cached)
            return cached
        }

        onLog("=== FASTBOOT DIAGNOSTICS ===")
        fun queryVarIfSessionAlive(name: String): String? {
            if (sessionState == SessionState.BROKEN || sessionState == SessionState.CLOSED) return null
            return getVar(name)
        }

        val product           = knownProduct?.trim()?.takeIf { it.isNotBlank() } ?: queryVarIfSessionAlive("product")
        val currentSlot       = queryVarIfSessionAlive("current-slot")
        val slotCount         = queryVarIfSessionAlive("slot-count")
        val slotSuffix        = queryVarIfSessionAlive("slot-suffix")
        val unlocked          = queryVarIfSessionAlive("unlocked")
        val secure            = queryVarIfSessionAlive("secure")
        val serialno          = queryVarIfSessionAlive("serialno")
        val versionBootloader = queryVarIfSessionAlive("version-bootloader")
        val antiPrimary       = if (sessionState == SessionState.IDLE) getVar("anti") else null
        val antiRollback      = antiPrimary
            ?: if (sessionState == SessionState.IDLE) queryVarIfSessionAlive("antirollback") else null
        val isUserspace       = queryVarIfSessionAlive("is-userspace")
        val superPartitionName = queryVarIfSessionAlive("super-partition-name")
        val snapshotUpdateStatus = queryVarIfSessionAlive("snapshot-update-status")
        val maxDownloadSizeRaw = queryVarIfSessionAlive("max-download-size")
        val maxDownloadSizeBytes = parseFastbootSize(maxDownloadSizeRaw)
        val maxFetchSizeRaw = queryVarIfSessionAlive("max-fetch-size")
        val maxFetchSizeBytes = parseFastbootSize(maxFetchSizeRaw)

        if (sessionState == SessionState.BROKEN) {
            onLog("⚠️ Diagnostic polling stopped: Fastboot transport lost synchronization. New getvar commands are not sent.")
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
            brokenReason = lastBrokenReason
        )
        cachedDiagnostics = diagnostics
        diagnostics.product?.trim()?.takeIf { it.isNotBlank() }?.let { lastKnownProduct = it }
        logDiagnostics(diagnostics)
        return diagnostics
    }

    fun currentDiagnostics(): DeviceDiagnostics? = cachedDiagnostics


    private fun logDiagnostics(d: DeviceDiagnostics) {
        onLog("Device/product: ${d.product ?: "unknown"}")
        d.serialno?.let          { onLog("Serial: $it") }
        d.versionBootloader?.let { onLog("Bootloader version: $it") }
        d.antiRollback?.let { onLog("Anti-rollback index: $it") }
        val fbMode = when {
            d.isUserspace?.equals("yes", ignoreCase = true) == true -> "fastbootd / userspace"
            d.isUserspace?.equals("no", ignoreCase = true) == true -> "bootloader fastboot"
            else -> "unknown"
        }
        onLog("Fastboot mode: $fbMode")
        d.superPartitionName?.let { onLog("Super partition: $it") }
        onLog("Snapshot update status: ${d.snapshotUpdateStatus ?: "unknown"}")
        if (FastbootPartitionInventory.isLegacyAOnlyProduct(d.product)) {
            onLog("Current slot: not applicable (legacy A-only / no A/B)")
            onLog(
                "Slot topology: legacy A-only by product compatibility; " +
                    "the concrete partition is still confirmed by read-only inventory/point-query"
            )
        } else {
            onLog("Current slot: ${d.currentSlot ?: "—"}")
            d.slotCount?.let { onLog("Slot count: $it") }
                ?: onLog("Slot count: unknown/not supported")
            d.slotSuffix?.let { onLog("Suffix slot: $it") }
        }
        onLog("Bootloader unlocked: ${d.unlocked ?: "unknown"}")
        onLog("Secure: ${d.secure ?: "unknown"}")
        onLog("Max download size: ${d.maxDownloadSizeRaw ?: "unknown"}${d.maxDownloadSizeBytes?.let { " ($it bytes)" } ?: ""}")
        d.maxFetchSizeRaw?.let { onLog("Max fetch size: $it${d.maxFetchSizeBytes?.let { bytes -> " ($bytes bytes)" } ?: ""}") }
        if (d.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("⚠️ WARNING: bootloader reports unlocked=no. Full terminal is available, but fastboot flash will be blocked by the app.")
        }
    }

    fun resolveSlotPartitionTargets(partition: String, slotOverride: String? = null): List<String>? = transactionLock.withLock {
        val normalized = normalizePartitionName(partition) ?: return@withLock null
        val slot = slotOverride?.trim()?.removePrefix("_")?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
            ?: return@withLock resolveCurrentSlotPartitionTarget(normalized)?.let { listOf(it) }

        if (slot != "all" && slot != "other" && !(slot.length == 1 && slot[0] in 'a'..'z')) {
            onLog("❌ ERROR: invalid slot: $slotOverride")
            return@withLock null
        }

        val base = normalized.substringBefore(':')
        val hasSlot = getVar("has-slot:$base")?.equals("yes", ignoreCase = true) == true
        if (!hasSlot) {
            onLog("⚠️ Partition $base does not report has-slot=yes; --slot=$slot will be ignored.")
            return@withLock listOf(normalized)
        }

        val count = fastbootSlotCount()
        if (count <= 0) {
            onLog("❌ ERROR: device reports has-slot=yes for $base, but slot-count is unknown")
            return@withLock null
        }

        when (slot) {
            "all" -> (0 until count).map { applySlotSuffix(normalized, ('a'.code + it).toChar().toString()) }
            "other" -> {
                val current = currentFastbootSlot() ?: return@withLock null
                val other = ((current[0] - 'a' + 1) % count + 'a'.code).toChar().toString()
                listOf(applySlotSuffix(normalized, other))
            }
            else -> {
                val index = slot[0] - 'a'
                if (index !in 0 until count) {
                    onLog("❌ ERROR: slot $slot does not exist; slot-count=$count")
                    return@withLock null
                }
                listOf(applySlotSuffix(normalized, slot))
            }
        }
    }

    private fun resolveCurrentSlotPartitionTarget(partition: String): String? {
        val base = partition.substringBefore(':')
        val hasSlot = getVar("has-slot:$base")?.equals("yes", ignoreCase = true) == true
        if (!hasSlot) return partition
        val current = currentFastbootSlot() ?: return null
        return applySlotSuffix(partition, current)
    }

    private fun currentFastbootSlot(): String? {
        val current = getVar("current-slot")
            ?.trim()
            ?.removePrefix("_")
            ?.lowercase(Locale.US)
            ?.takeIf { it.length == 1 && it[0] in 'a'..'z' }
        if (current == null) onLog("❌ ERROR: could not determine current-slot")
        return current
    }

    private fun fastbootSlotCount(): Int {
        val raw = getVar("slot-count")?.trim() ?: cachedDiagnostics?.slotCount?.trim()
        return raw?.toIntOrNull()?.takeIf { it > 0 } ?: 0
    }

    private fun applySlotSuffix(partition: String, slot: String): String {
        val pieces = partition.split(':', limit = 2)
        val suffixed = pieces[0] + "_" + slot
        return if (pieces.size == 2) "$suffixed:${pieces[1]}" else suffixed
    }

    // ─── ПРОШИВКА РАЗДЕЛА ────────────────────────────────────────────────────

    fun flashPartition(partition: String, file: File): Boolean =
        flashPartitionDetailed(partition, file).success

    fun flashPartitionDetailed(partition: String, file: File): FlashResult = transactionLock.withLock {
        if (!ensureSessionReady("flash:$partition")) {
            return@withLock FlashResult.fail(
                FlashStage.VALIDATION,
                FlashFailureKind.SESSION_BROKEN,
                "Fastboot session unavailable: $sessionState",
                sessionCorrupted = sessionState == SessionState.BROKEN
            )
        }

        val normalizedPartition = partition.trim().lowercase(Locale.US)
        if (normalizedPartition.isBlank() || !normalizedPartition.matches(Regex("[A-Za-z0-9._:-]+"))) {
            val message = "Invalid partition name: $partition"
            onLog("❌ ERROR: $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }
        val partitionBase = normalizedPartition.removeSuffix("_ab").removeSuffix("_a").removeSuffix("_b")
        if (partitionBase !in TYPICAL_FLASH_PARTITIONS) {
            onLog("⚠️ Partition $normalizedPartition is not in the standard list. The hard block is removed; the command is allowed in terminal mode.")
        }

        if (!file.exists() || !file.isFile || !file.canRead()) {
            val message = "File is unavailable: ${file.name}"
            onLog("❌ ERROR: $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }
        if (file.length() <= 0L) {
            val message = "File is empty: ${file.name}"
            onLog("❌ ERROR: $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }
        if (file.length() > 0xFFFF_FFFFL) {
            val message = "Fastboot download supports sizes up to 4 GiB in this implementation"
            onLog("❌ ERROR: $message")
            return@withLock FlashResult.fail(FlashStage.VALIDATION, FlashFailureKind.VALIDATION, message)
        }

        val fileSizeMb = file.length().toDouble() / 1024.0 / 1024.0
        onLog("Flashing $normalizedPartition. File: ${file.name} (${"%.2f".format(fileSizeMb)} MB)")

        val hexSize = String.format("%08x", file.length())
        if (!writeCommand("download:$hexSize", 5000)) {
            return@withLock flashTransportFailure(FlashStage.SEND_DOWNLOAD, "Failed to send download command")
        }

        val downloadPacket = readUntilDataOrFinal(10000)
            ?: return@withLock flashTransportFailure(FlashStage.WAIT_DATA, "No valid response to download")
        when (downloadPacket.type) {
            "DATA" -> onLog("Bootloader is ready to receive the image: ${downloadPacket.payload}")
            "FAIL" -> {
                val message = downloadPacket.payload.ifBlank { downloadPacket.raw }
                logFastbootFailure("Bootloader rejected download", message)
                return@withLock FlashResult.fail(FlashStage.WAIT_DATA, FlashFailureKind.PROTOCOL, message)
            }
            else -> {
                val message = "Expected DATA, got ${downloadPacket.raw}"
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
            ?: return@withLock flashTransportFailure(FlashStage.WAIT_DOWNLOAD_FINAL, "No final response after DATA")
                .copy(dataBytesTransferred = file.length())
        if (downloadDone.type != "OKAY") {
            val message = downloadDone.payload.ifBlank { downloadDone.raw }
            logFastbootFailure("Device rejected the image after transfer", message)
            return@withLock FlashResult.fail(
                FlashStage.WAIT_DOWNLOAD_FINAL,
                FlashFailureKind.PROTOCOL,
                message,
                dataBytesTransferred = file.length()
            )
        }

        onLog("Writing image to partition $normalizedPartition (this may take several minutes)...")
        if (!writeCommand("flash:$normalizedPartition", 5000)) {
            return@withLock flashTransportFailure(FlashStage.SEND_FLASH, "Failed to send flash command")
                .copy(dataBytesTransferred = file.length())
        }
        sessionState = SessionState.AWAITING_COMMAND_FINAL

        val flashDone = readUntilFinalWithRetry(singleReadTimeoutMs = 2000, maxTotalTimeMs = 600_000)
            ?: return@withLock flashTransportFailure(FlashStage.WAIT_FLASH_FINAL, "No final flash response")
                .copy(dataBytesTransferred = file.length())

        if (flashDone.type == "OKAY") {
            onLog("✅ Flashing $normalizedPartition completed successfully!")
            FlashResult.ok(dataBytesTransferred = file.length())
        } else {
            val message = flashDone.payload.ifBlank { flashDone.raw }
            logFastbootFailure("Partition write ERROR $normalizedPartition", message)
            FlashResult.fail(
                FlashStage.WAIT_FLASH_FINAL,
                FlashFailureKind.PROTOCOL,
                message,
                dataBytesTransferred = file.length()
            )
        }
    }

    // ─── FASTBOOTD / DYNAMIC PARTITIONS ────────────────────────────────────

    fun inspectLogicalPartition(partition: String): LogicalPartitionInfo? = transactionLock.withLock {
        if (!isConnected) return null
        val normalized = normalizePartitionName(partition) ?: return null
        onLog("=== FASTBOOTD LOGICAL PARTITION INFO: $normalized ===")
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ Device does not report is-userspace=yes. Dynamic partitions usually require userspace fastbootd. Run: fastboot reboot fastboot")
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
        onLog("Logical: ${info.isLogical ?: "unknown"}")
        onLog("Size: ${info.sizeRaw ?: "unknown"}${info.sizeBytes?.let { " ($it bytes)" } ?: ""}")
        onLog("Type: ${info.type ?: "unknown"}")
        diagnostics.superPartitionName?.let { onLog("Super partition: $it") }
        return info
    }

    fun runLogicalPartitionCommand(command: String): Boolean = transactionLock.withLock {
        if (!isConnected) return false
        val clean = command.trim()
        if (!isLogicalPartitionManagementCommand(clean)) {
            onLog("❌ ERROR: command is not a logical partition management command: $clean")
            return false
        }
        return sendCommand(clean)
    }

    fun fetchPartition(partition: String, outputFile: File): Boolean = transactionLock.withLock {
        if (!isConnected) return false
        val normalized = normalizePartitionName(partition) ?: return false
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ fetch is usually implemented in fastbootd. If the device returns FAIL, run: fastboot reboot fastboot")
        }
        if (diagnostics.unlocked?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ fetch in AOSP usually requires an unlocked/debuggable state. The device may reject it.")
        }

        val parent = outputFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            onLog("❌ ERROR: could not create folder: ${parent.absolutePath}")
            return false
        }
        val partFile = File(outputFile.parentFile ?: File("."), outputFile.name + ".part")
        if (partFile.exists() && !partFile.delete()) {
            onLog("❌ ERROR: could not delete temporary file: ${partFile.absolutePath}")
            return false
        }

        val partitionSizeRaw = getVar("partition-size:$normalized")
        val partitionSize = parseFastbootSize(partitionSizeRaw)
        val maxFetch = diagnostics.maxFetchSizeBytes?.takeIf { it > 0L }
        onLog("Fastboot fetch: $normalized → ${outputFile.absolutePath}")
        partitionSize?.let { onLog("Partition size: $partitionSizeRaw ($it bytes)") }
        maxFetch?.let { onLog("Max fetch chunk: $it bytes") }

        return try {
            partFile.outputStream().use { out ->
                if (partitionSize != null && partitionSize > 0L && maxFetch != null) {
                    var offset = 0L
                    while (offset < partitionSize && !cancelled) {
                        val chunkSize = minOf(maxFetch, partitionSize - offset)
                        val command = "fetch:$normalized:$offset:$chunkSize"
                        val fetched = fetchChunk(command, out, offset, partitionSize) ?: return false
                        if (fetched <= 0L) {
                            onLog("❌ ERROR fetch: device returned a zero-length chunk")
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
                onLog("⚠️ fetch cancelled by user")
                false
            } else {
                if (outputFile.exists() && !outputFile.delete()) {
                    onLog("❌ ERROR: could not replace file: ${outputFile.absolutePath}")
                    false
                } else if (!partFile.renameTo(outputFile)) {
                    onLog("❌ ERROR: could not rename .part to the final file")
                    false
                } else {
                    onLog("✅ Fastboot fetch completed: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                    true
                }
            }
        } catch (e: Exception) {
            onLog("❌ ERROR fastboot fetch: ${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            if (partFile.exists() && partFile.length() == 0L) partFile.delete()
        }
    }

    // ─── УТИЛИТЫ ─────────────────────────────────────────────────────────────



    fun downloadAndRun(file: File, commandAfterDownload: String): Boolean = transactionLock.withLock {
        if (!isConnected) return false

        val command = commandAfterDownload.trim()
        if (command.isBlank()) {
            onLog("❌ ERROR: empty command after download")
            return false
        }
        if (command.any { it.code !in 32..126 }) {
            onLog("❌ ERROR: Fastboot command must be ASCII")
            return false
        }

        if (!file.exists() || !file.isFile || !file.canRead()) {
            onLog("❌ ERROR: file is unavailable: ${file.name}")
            return false
        }
        if (file.length() <= 0L) {
            onLog("❌ ERROR: file is empty: ${file.name}")
            return false
        }
        if (file.length() > 0xFFFF_FFFFL) {
            onLog("❌ ERROR: Fastboot download supports sizes up to 4 GiB in this implementation")
            return false
        }

        val fileSizeMb = file.length().toDouble() / 1024.0 / 1024.0
        onLog("Fastboot download: ${file.name} (${"%.2f".format(fileSizeMb)} MB), then command: $command")

        val hexSize = String.format("%08x", file.length())
        if (!writeCommand("download:$hexSize", 5000)) {
            onLog("ERROR: Failed to send download command")
            return false
        }

        val downloadPacket = readUntilDataOrFinal(10000) ?: return false
        when (downloadPacket.type) {
            "DATA" -> onLog("Bootloader is ready to receive the file: ${downloadPacket.payload}")
            "FAIL" -> { logFastbootFailure("Bootloader rejected download", downloadPacket.payload); return false }
            else   -> { onLog("ERROR: expected DATA, got ${downloadPacket.raw}"); return false }
        }

        if (!transferDownloadPayload(file, "download + $command").success) return false
        sessionState = SessionState.AWAITING_DATA_FINAL

        if (cancelled) {
            onLog("⚠️ Operation cancelled by user")
            return false
        }

        val downloadDone = readUntilFinal(30000) ?: return false
        if (downloadDone.type != "OKAY") {
            logFastbootFailure("Device rejected the file after transfer", downloadDone.payload.ifBlank { downloadDone.raw })
            return false
        }

        if (!writeCommand(command, 5000)) {
            onLog("ERROR: Failed to send command after download")
            return false
        }

        val done = readUntilFinalWithRetry(
            singleReadTimeoutMs = 2000,
            maxTotalTimeMs = 600_000
        ) ?: return false

        return if (done.type == "OKAY") {
            onLog("✅ Fastboot command after download completed: $command")
            true
        } else {
            logFastbootFailure("Fastboot command after download failed: $command", done.payload.ifBlank { done.raw })
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
            onLog("❌ No connection to device")
            return false
        }
        if (!encryptDataFile.exists() || !encryptDataFile.isFile || encryptDataFile.length() <= 0L) {
            onLog("❌ ERROR: unlock file is unavailable or empty")
            return false
        }

        onLog("🔓 Staging unlock data (${encryptDataFile.length()} bytes)...")
        val hexSize = String.format("%08x", encryptDataFile.length())
        if (!writeCommand("download:$hexSize", 5000)) {
            onLog("❌ ERROR: download command failed for unlock")
            return false
        }
        val downloadPacket = readUntilDataOrFinal(10000) ?: return false
        when (downloadPacket.type) {
            "DATA" -> onLog("Bootloader is ready to receive unlock data")
            "FAIL" -> { logFastbootFailure("Bootloader rejected download", downloadPacket.payload); return false }
            else   -> { onLog("❌ ERROR: expected DATA, got ${downloadPacket.raw}"); return false }
        }
        if (!transferDownloadPayload(encryptDataFile, "unlock data").success) return false
        sessionState = SessionState.AWAITING_DATA_FINAL

        val downloadDone = readUntilFinal(30000) ?: return false
        if (downloadDone.type != "OKAY") {
            onLog("❌ ERROR: unlock download was not confirmed: ${downloadDone.raw}")
            return false
        }

        onLog("🔓 Running fastboot oem unlock...")
        if (!writeCommand("oem unlock", 10000)) {
            onLog("❌ ERROR: failed to send oem unlock")
            return false
        }
        val unlockDone = readUntilFinal(30000) ?: return false
        return if (unlockDone.type == "OKAY") {
            onLog("✅ Bootloader unlocked successfully!")
            true
        } else {
            logFastbootFailure("oem unlock rejected", unlockDone.payload)
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
        onLog("🧯 USB endpoint wedge: trying USBDEVFS_RESET to unwedge ($reason)")
        val rc = NativeUsbfsBackend.resetUsbDevice(conn)
        when {
            rc == 0 -> onLog("✅ USBDEVFS_RESET completed: device is reinitializing. Wait for re-enumeration or reconnect OTG, then enter Fastboot again.")
            rc > 0 -> onLog("ℹ️ USBDEVFS_RESET failed (errno=$rc). Physically reconnect OTG and enter Fastboot again.")
            else -> onLog("ℹ️ USBDEVFS_RESET unavailable (no USB fd). Physically reconnect OTG.")
        }
    }

    private fun transferDownloadPayload(file: File, label: String): TransferResult {
        lastDataTransportUsed = dataTransportMode
        val totalBytes = file.length().coerceAtLeast(1L)

        if (sessionState != SessionState.DATA_OUT) {
            val message = "DATA transfer started in an invalid state: $sessionState"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = 0L)
        }

        val conn = connection
        val out = endpointOut
        if (conn == null || out == null) {
            val message = "USB DATA transport unavailable: connection or OUT endpoint is missing"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = 0L)
        }

        if (dataTransportMode == DataTransportMode.SYNC_BULK) {
            onLog("ℹ️ Fastboot DATA transport: sync bulkTransfer selected before download payload transfer.")
            return transferDownloadPayloadSync(file, label, totalBytes)
        }
        if (dataTransportMode == DataTransportMode.NATIVE_USBFS) {
            onLog("ℹ️ Fastboot DATA transport: Native USBFS selected for production payload transfer.")
            return transferDownloadPayloadNativeUsbfs(file, label, totalBytes)
        }

        val request = UsbRequest()
        if (!request.initialize(conn, out)) {
            val message = "Could not initialize UsbRequest for Fastboot DATA OUT"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = 0L)
        }
        activeDataRequest = request

        val blockBytes = dataOutBlockBytes()
        val usbBuffer = ByteBuffer.allocateDirect(blockBytes)
        var totalSent = 0L
        var lastLoggedProgress = -1
        var lastUiProgress = -1
        var lastRateLogMs = System.currentTimeMillis()
        var lastUiUpdateMs = lastRateLogMs
        var lastRateLogBytes = 0L
        val startedMs = lastRateLogMs

        onLog(
            "Transfer $label: ${formatBytes(totalBytes)}. " +
                "transport=UsbRequest, block=${formatBytes(blockBytes.toLong())}, " +
                "watchdog=${formatDuration(DATA_REQUEST_WATCHDOG_MS)}. " +
                "Do not background the app or disconnect OTG/cable."
        )
        onLogVerbose(
            "[fastboot-data] mode=UsbRequest sdk=${Build.VERSION.SDK_INT} " +
                "endpoint=${endpointDescriptor(out)} total=$totalBytes block=$blockBytes watchdog_ms=$DATA_REQUEST_WATCHDOG_MS"
        )
        try {
            file.inputStream().channel.use { input ->
                while (true) {
                    if (cancelled) {
                        request.cancel()
                        val message = "Operation cancelled during DATA phase at ${formatBytes(totalSent)}/${formatBytes(totalBytes)}"
                        markSessionBroken(message)
                        return TransferResult(false, cancelled = true, message = message, bytesTransferred = totalSent)
                    }

                    usbBuffer.clear()
                    val read = input.read(usbBuffer)
                    if (read <= 0) break
                    usbBuffer.flip()

                    while (usbBuffer.hasRemaining()) {
                        if (cancelled) {
                            request.cancel()
                            val message = "Operation cancelled during DATA phase at offset=$totalSent"
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
                            val message = result.message.ifBlank { "DATA transfer cancelled at offset=$absoluteOffset" }
                            markSessionBroken(message)
                            return TransferResult(false, cancelled = true, message = message, bytesTransferred = totalSent)
                        }
                        if (result.bytesSent <= 0) {
                            // Нельзя менять transport внутри уже открытой Fastboot DATA-фазы:
                            // неоднозначный async failure не доказывает, сколько байтов принял контроллер.
                            val message = result.message.ifBlank {
                                "USB DATA UsbRequest failed at offset=$absoluteOffset: requested=$requested, elapsed=${elapsedMs}ms"
                            } + ". Repeat the operation after starting a new Fastboot session."
                            markSessionBroken(message)
                            return TransferResult(false, message = message, bytesTransferred = totalSent)
                        }

                        totalSent += result.bytesSent
                    }

                    val now = System.currentTimeMillis()
                    val progress = dataProgressPercent(totalSent, totalBytes)
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
                            "Transferred: $progress% " +
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
            val message = "Read error during UsbRequest file transfer: ${e.message ?: e.javaClass.simpleName}"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        } finally {
            activeDataRequest = null
            runCatching { request.close() }
        }

        if (totalSent != totalBytes) {
            val message = "Transfer ended before the end of the file (${formatBytes(totalSent)}/${formatBytes(totalBytes)})"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        }
        return TransferResult(true, bytesTransferred = totalBytes)
    }

    /**
     * Production DATA transfer through Linux usbfs URB ioctls using the raw
     * UsbDeviceConnection file descriptor and a bounded two-URB pipeline.
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
        val blockBytes = NATIVE_USBFS_BLOCK_BYTES
        val pipelineDepth = NATIVE_USBFS_PIPELINE_DEPTH
        val profileLabel = NATIVE_USBFS_PROFILE_LABEL
        onLog(
            "Transfer $label: ${formatBytes(totalBytes)}. " +
                "transport=${profileLabel}, depth=$pipelineDepth, block=${formatBytes(blockBytes.toLong())}, " +
                "stall-timeout=${formatDuration(NATIVE_USBFS_URB_TIMEOUT_MS.toLong())}, " +
                "hard-timeout=${formatDuration(NATIVE_USBFS_HARD_TIMEOUT_MS.toLong())}."
        )
        onLogVerbose(
            "[fastboot-data] mode=NativeUsbfs endpoint=${endpointDescriptor(activeOut)} " +
                "profile=${profileLabel} total=$totalBytes block=$blockBytes depth=$pipelineDepth " +
                "stall_timeout_ms=$NATIVE_USBFS_URB_TIMEOUT_MS hard_timeout_ms=$NATIVE_USBFS_HARD_TIMEOUT_MS"
        )
        onProgress(0, NativeTransferProgress.formatDetail(
            NativeTransferProgress.calculate(0L, totalBytes, 0L),
            profileLabel
        ))
        onLog("⏳ Native USBFS is waiting for the first URB completion: transferred 0 B/${formatBytes(totalBytes)}, speed=N/A")
        val startedMs = System.currentTimeMillis()
        var lastNativeLoggedProgress = -1
        var lastNativeLogMs = startedMs
        val result = NativeUsbfsBackend.transferBulkOutUrb(
            connection = activeConnection,
            outEndpoint = activeOut,
            payloadFile = file,
            blockBytes = blockBytes,
            pipelineDepth = pipelineDepth,
            stallTimeoutMs = NATIVE_USBFS_URB_TIMEOUT_MS,
            hardTimeoutMs = NATIVE_USBFS_HARD_TIMEOUT_MS,
            onProgress = { snapshot ->
                val metrics = NativeTransferProgress.calculate(
                    confirmedBytes = snapshot.confirmedBytes,
                    totalBytes = snapshot.totalBytes.takeIf { it > 0L } ?: totalBytes,
                    elapsedMs = snapshot.elapsedMs
                )
                onProgress(metrics.percent, NativeTransferProgress.formatDetail(metrics, profileLabel))
                val now = System.currentTimeMillis()
                val shouldLog = metrics.percent == 100 ||
                    metrics.percent >= lastNativeLoggedProgress + DiagnosticLogPolicy.progressLogStepPercent(debugLogging) ||
                    now - lastNativeLogMs >= DiagnosticLogPolicy.progressLogIntervalMs(debugLogging)
                if (shouldLog) {
                    onLog(
                        "Native USBFS: ${metrics.percent}% " +
                            "(${formatBytes(metrics.confirmedBytes)}/${formatBytes(metrics.totalBytes)}), " +
                            "avg=${formatBytesPerSecond(metrics.averageBytesPerSecond)}, " +
                            "eta=${metrics.etaMs?.let(::formatDuration) ?: "unknown"}, " +
                            "submitted=${formatBytes(snapshot.submittedBytes)}, " +
                            "stage=${NativeUsbfsBackend.stageLabel(snapshot.stage)}"
                    )
                    lastNativeLoggedProgress = metrics.percent
                    lastNativeLogMs = now
                }
            }
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
            val failureMetrics = NativeTransferProgress.calculate(
                confirmedBytes = result.bytesTransferred,
                totalBytes = totalBytes,
                elapsedMs = result.elapsedMs.takeIf { it > 0L } ?: elapsedMs
            )
            val speedLabel = if (result.bytesTransferred <= 0L) {
                "speed=N/A (DATA did not start)"
            } else {
                "avg=${formatBytesPerSecond(speed)}"
            }
            onProgress(
                failureMetrics.percent,
                NativeTransferProgress.formatDetail(failureMetrics, profileLabel) +
                    "  ·  stage=${NativeUsbfsBackend.stageLabel(result.stage)}"
            )
            val message = result.message + ", $speedLabel, profile=${profileLabel}. Retry/transport switch in this DATA session is forbidden; enter Fastboot again."
            markSessionBroken(message)
            attemptEndpointResetRecovery("Native USBFS wedge (${NativeUsbfsBackend.stageLabel(result.stage)})")
            return TransferResult(false, message = message, bytesTransferred = result.bytesTransferred)
        }
        if (result.bytesTransferred != totalBytes) {
            val message = "Native USBFS transferred an unexpected size: ${formatBytes(result.bytesTransferred)}/${formatBytes(totalBytes)}"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = result.bytesTransferred)
        }
        onLog(
            "✅ Native USBFS Pipeline DATA payload sent: ${formatBytes(result.bytesTransferred)}, " +
                "avg=${formatBytesPerSecond(speed)}, elapsed=${formatDuration(elapsedMs)}"
        )
        val finalMetrics = NativeTransferProgress.calculate(result.bytesTransferred, totalBytes, elapsedMs)
        onProgress(100, NativeTransferProgress.formatDetail(finalMetrics, profileLabel))
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
            val message = "USB DATA transport unavailable (sync): connection or OUT endpoint is missing"
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
            "Transfer $label (sync): ${formatBytes(totalBytes)}. " +
                "transport=bulkTransfer, block=16 KB. Do not background the app or disconnect OTG/cable."
        )
        onLogVerbose(
            "[fastboot-data-diag] mode=bulkTransfer sdk=${Build.VERSION.SDK_INT} " +
                "endpoint=${endpointDescriptor(out)} total=$totalBytes block=${chunk.size} timeout_ms=$SYNC_BULK_TIMEOUT_MS"
        )

        try {
            file.inputStream().use { input ->
                while (true) {
                    if (cancelled) {
                        val message = "Operation cancelled during sync DATA phase at ${formatBytes(totalSent)}/${formatBytes(totalBytes)}"
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
                                "Sync bulkTransfer failure: return=$sent, requested=$requested, " +
                                    "elapsed=${formatMicros(elapsedUs)}, timeout=${SYNC_BULK_TIMEOUT_MS}ms, " +
                                    "offset=${totalSent + offset} (${formatBytes(totalSent + offset)}/${formatBytes(totalBytes)}), " +
                                    "${endpointDescriptor(out)}. Retry in the same DATA session is forbidden."
                            markSessionBroken(message)
                            return TransferResult(false, message = message, bytesTransferred = totalSent + offset)
                        }
                        if (sent > requested) {
                            val message =
                                "Sync bulkTransfer returned an impossible size: return=$sent > requested=$requested, " +
                                    "offset=${totalSent + offset}, ${endpointDescriptor(out)}"
                            markSessionBroken(message)
                            return TransferResult(false, message = message, bytesTransferred = totalSent + offset)
                        }
                        offset += sent
                    }
                    totalSent += read

                    val now = System.currentTimeMillis()
                    val progress = dataProgressPercent(totalSent, totalBytes)
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
                            "Transferred (sync): $progress% (${formatBytes(totalSent)}/${formatBytes(totalBytes)}), " +
                                "avg=${formatBytesPerSecond(avgBytesPerSec)}, eta=${formatDuration(etaMs)}"
                        )
                        lastLoggedProgress = progress
                        lastRateLogMs = now
                    }
                }
            }
        } catch (e: Exception) {
            val message = "Sync file-transfer error: ${e.message ?: e.javaClass.simpleName}"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        }

        if (totalSent != totalBytes) {
            val message = "Sync transfer ended before the end of the file (${formatBytes(totalSent)}/${formatBytes(totalBytes)})"
            markSessionBroken(message)
            return TransferResult(false, message = message, bytesTransferred = totalSent)
        }
        onLog("✅ Sync-transfer completed: ${formatBytes(totalSent)}")
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
                message = "DATA transfer cancelled during UsbRequest: offset=$absoluteOffset, requested=$requested, " +
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
                message = "Received completion for a foreign UsbRequest: offset=$absoluteOffset, requested=$requested, " +
                    "elapsed=${formatMicros(elapsedUs)}, $endpointInfo"
            )
        }

        val sent = buffer.position() - startPosition
        onLogVerbose(
            "[fastboot-data] mode=UsbRequest offset=$absoluteOffset requested=$requested " +
                "confirmed=$sent elapsed_us=$elapsedUs watchdog_ms=$DATA_REQUEST_WATCHDOG_MS endpoint=$endpointInfo"
        )
        if (sent <= 0 || sent > requested) {
            return DataOutResult(
                message = "UsbRequest completed with an invalid byte count: offset=$absoluteOffset, " +
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
        explainFastbootFailure(cleanPayload)?.let { onLog("ℹ️ Explanation: $it") }
    }

    private fun explainFastbootFailure(payload: String): String? {
        val p = payload.lowercase(Locale.US)
        return when {
            "locked" in p || "unlock" in p && "not" in p -> "bootloader is locked or the partition is not writable in the current bootloader state."
            "not allowed" in p || "permission" in p || "denied" in p -> "OEM bootloader rejected the operation; check unlocked=yes, fastbootd mode, and whether the partition may be written."
            "no such partition" in p || "unknown partition" in p || "partition" in p && "not found" in p -> "partition is missing on this model/slot, or the ROM does not match the device product."
            "too large" in p || "data too" in p || "max-download" in p -> "file exceeds the max-download-size limit; use another fastboot/fastbootd mode or a partition/sparse image."
            "sparse" in p -> "sparse/sparsechunk image error; check ROM integrity and that all chunk files are present."
            "signature" in p || "verify" in p || "verification" in p || "vbmeta" in p -> "signature/verification rejection; check vbmeta/verity/verification and ROM compatibility."
            "not support" in p || "unknown command" in p || "unrecognized" in p -> "command is not supported by this bootloader/fastbootd; another mode or an OEM-specific script may be required."
            "space" in p || "storage" in p || "allocation" in p -> "not enough space/size in dynamic partitions; check update-super, super_empty.img, and fastbootd."
            "timeout" in p || "timed out" in p -> "USB/fastboot timeout; check cable, OTG power, and do not lock the host screen."
            else -> null
        }
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

    private fun dataProgressPercent(bytesDone: Long, totalBytes: Long): Int =
        if (totalBytes <= 0L) 0 else ((bytesDone.coerceAtLeast(0L).toDouble() / totalBytes.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)

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
            onLog("⏳ Native USBFS cancellation requested. Finishing pending URB through DISCARDURB → REAP; USB session remains active until cleanup actually completes.")
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
                "⏳ UsbDeviceConnection close deferred: Native USBFS is still running " +
                    "DISCARDURB → REAP. Interface and file descriptor remain open until drain is confirmed."
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
            onLog("⛔ Command blocked: Fastboot session $sessionState. Device must re-enter Fastboot.")
            return false
        }
        if (!isConnected) {
            onLog("❌ No Fastboot connection for $operation")
            return false
        }
        if (sessionState != SessionState.IDLE) {
            markSessionBroken("New operation '$operation' requested in state $sessionState")
            return false
        }
        if (cancelled) {
            onLog("⚠️ Operation cancelled before sending the command: $operation")
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
                onLog("⛔ USB connection is closed. New commands are blocked until the target device fully re-enters Fastboot.")
            } else {
                onLog("⛔ New commands are blocked. Physical USB close will be performed only after confirmed Native USBFS drain.")
            }
        }
    }


    private fun classifyBrokenReason(reason: String): BrokenReasonCode {
        val lower = reason.lowercase(Locale.US)
        return when {
            "initial" in lower && ("handshake" in lower || "getvar:product" in lower) -> BrokenReasonCode.FIRST_RESPONSE_TIMEOUT
            "cancel" in lower || "cancel" in lower -> BrokenReasonCode.USER_CANCELLED_DURING_DATA
            "native usbfs" in lower || "urb" in lower -> BrokenReasonCode.NATIVE_USBFS_FAILURE
            "short write" in lower || "ambiguous" in lower || ("sent" in lower && "/" in lower) -> BrokenReasonCode.SHORT_WRITE
            "short read" in lower -> BrokenReasonCode.SHORT_READ
            "read failed" in lower || "response timeout" in lower || "wait" in lower -> BrokenReasonCode.USB_IN_TIMEOUT
            "write" in lower || "out" in lower -> BrokenReasonCode.USB_OUT_TIMEOUT
            "disconnect" in lower || "disconnect" in lower || "left the bus" in lower -> BrokenReasonCode.DEVICE_DISCONNECTED
            "interface" in lower || "interface" in lower -> BrokenReasonCode.INTERFACE_LOST
            "unknown response" in lower || "unexpected" in lower -> BrokenReasonCode.UNEXPECTED_RESPONSE
            "state" in lower || "state" in lower || "unfinished" in lower -> BrokenReasonCode.INVALID_STATE
            "protocol" in lower || "sync" in lower -> BrokenReasonCode.PROTOCOL_DESYNC
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
            onLog("❌ Invalid Fastboot command size: ${cmdBytes.size} bytes")
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
            val message = "Command send was ambiguous: $sent/${cmdBytes.size} bytes, elapsed=${formatMicros(elapsedUs)}"
            onLog("ERROR: $message")
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
                else           -> onLog("⚠️ Unknown Fastboot response: ${packet.raw}")
            }
        }
        onLog("⚠️ Operation cancelled by user")
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
                val message = "Wait limit exceeded (${maxTotalTimeMs / 1000} sec) in state $sessionState"
                onLog("❌ $message")
                markSessionBroken(message)
                return null
            }

            val packet = readPacket(singleReadTimeoutMs)
            if (packet == null) {
                // Таймаут одного пакета — нормально при записи, логируем каждые 10 сек
                val elapsedSec = elapsed / 1000
                if (elapsedSec / 10 != lastLogSec / 10) {
                    onLog("⏳ Waiting for device response... ${elapsedSec} sec")
                    lastLogSec = elapsedSec
                }
                continue
            }

            when (packet.type) {
                "OKAY", "FAIL" -> { sessionState = SessionState.IDLE; return packet }
                "INFO", "TEXT" -> continue
                else           -> onLog("⚠️ Unknown response: ${packet.raw}")
            }
        }
        onLog("⚠️ Operation cancelled by user")
        return null
    }

    private fun readUntilDataOrFinal(timeout: Int): FastbootPacket? {
        while (!cancelled) {
            val packet = readPacket(timeout) ?: return null
            when (packet.type) {
                "DATA" -> { sessionState = SessionState.DATA_OUT; return packet }
                "OKAY", "FAIL" -> { sessionState = SessionState.IDLE; return packet }
                "INFO", "TEXT" -> continue
                else                   -> onLog("⚠️ Unknown Fastboot response: ${packet.raw}")
            }
        }
        onLog("⚠️ Operation cancelled by user")
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
                    "getvar response timeout:$name after confirmed command send " +
                        "($emptyReads empty/failed reads)"
                )
                return null
            }

            val readTimeoutMs = minOf(GETVAR_READ_SLICE_MS, remainingMs.toInt().coerceAtLeast(1))
            val packet = readPacket(readTimeoutMs)
            if (packet == null) {
                emptyReads += 1
                if (debugLogging) {
                    onLog(
                        "[debug] getvar:$name: response read did not complete, " +
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
                        "Fastboot read failed $emptyReads times for getvar:$name after confirmed command send"
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
                    if (debugLogging) onLog("⚠️ getvar:$name is not supported: ${packet.payload}")
                    return null
                }
                else -> onLog("⚠️ Unknown Fastboot response: ${packet.raw}")
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
                    "getvar response timeout:all after confirmed command send " +
                        "($emptyReads empty/failed reads, lines=${lines.size})"
                )
                return null
            }

            val packet = readPacket(minOf(GETVAR_READ_SLICE_MS, remainingMs.toInt().coerceAtLeast(1)))
            if (packet == null) {
                emptyReads += 1
                if (emptyReads >= GETVAR_ALL_MAX_FAILED_READS && elapsedMs >= GETVAR_MIN_PATIENCE_MS) {
                    markSessionBroken(
                        "Fastboot read failed $emptyReads times for getvar:all after confirmed command send"
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
                        onLog("⚠️ getvar:all is not supported: $message")
                        return null
                    }
                    val snapshot = FastbootGetVarAllParser.parse(
                        lines,
                        complete = false,
                        finalStatus = "FAIL",
                        finalMessage = message
                    )
                    onLog(
                        "⚠️ getvar:all returned partial inventory: variables=${snapshot.variables.size}, " +
                            "partitions=${snapshot.partitions.size}, final=$message"
                    )
                    return snapshot
                }
                else -> onLog("⚠️ Unknown getvar:all response: ${packet.raw}")
            }
        }
        return null
    }

    private fun logTerminalInfo(payload: String) {
        val clean = payload.trim().removePrefix("INFO").trim()
        if (clean.isNotBlank()) onLog("↩ $clean")
    }

    private fun logTerminalOkay(command: String, payload: String, infoLines: Int) {
        val cleanCommand = command.trim()
        val cleanPayload = payload.trim()
        if (cleanCommand.startsWith("getvar:", ignoreCase = true)) {
            val name = cleanCommand.substringAfter(':').trim()
            if (name.equals("all", ignoreCase = true)) {
                val suffix = cleanPayload.ifBlank { "$infoLines lines" }
                onLog("✅ getvar:all completed: $suffix")
                return
            }
            val value = normalizeGetVarValue(name, cleanPayload).orEmpty()
            onLog("✅ $name: ${value.ifBlank { "<empty>" }}")
            return
        }
        if (cleanPayload.isNotBlank()) {
            onLog("✅ Fastboot OKAY: $cleanPayload")
        } else {
            onLog("✅ Fastboot OKAY")
        }
    }

    private fun normalizeUnlockTokenCandidate(raw: String): String? =
        extractUnlockTokenPart(raw)?.filterNot { it.isWhitespace() }?.takeIf { it.isNotBlank() }

    private fun extractUnlockTokenPart(raw: String): String? {
        val cleaned = raw.trim().removePrefix("INFO").trim()
        if (cleaned.isBlank()) return null
        val value = if (cleaned.startsWith("token:", ignoreCase = true)) {
            cleaned.substringAfter(':')
        } else {
            cleaned
        }.trim()
        if (value.isBlank()) return null
        return value.filterNot { it.isWhitespace() }.takeIf { token ->
            token.length >= 8 && token.any { it.isLetterOrDigit() }
        }
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
            onLog("❌ ERROR: invalid partition name: $partition")
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
            onLog("ERROR: Failed to send command $command")
            return null
        }
        val dataPacket = readUntilDataOrFinal(10000) ?: return null
        when (dataPacket.type) {
            "DATA" -> Unit
            "FAIL" -> { onLog("❌ ERROR fetch: ${dataPacket.payload}"); return null }
            else -> { onLog("❌ ERROR fetch: expected DATA, got ${dataPacket.raw}"); return null }
        }
        val dataSize = parseFastbootDataSize(dataPacket.payload)
        if (dataSize == null || dataSize < 0L) {
            onLog("❌ ERROR fetch: invalid DATA size: ${dataPacket.payload}")
            return null
        }
        if (!readRawDataTo(out, dataSize, alreadyFetched, totalSize)) return null
        val done = readUntilFinalWithRetry(singleReadTimeoutMs = 2000, maxTotalTimeMs = 120_000) ?: return null
        return if (done.type == "OKAY") {
            dataSize
        } else {
            onLog("❌ ERROR fetch after data phase: ${done.payload}")
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
                onLog("❌ ERROR fetch: raw data read timeout/failure ($received/$expectedBytes bytes)")
                return false
            }
            out.write(buffer, 0, bytesRead)
            received += bytesRead
            val absolute = alreadyFetched + received
            if (totalSize > 0L) {
                val progress = ((absolute * 100) / totalSize).toInt()
                if (progress % 10 == 0 && progress != lastLoggedProgress) {
                    onLog("Fetch: $progress% ($absolute/$totalSize bytes)")
                    lastLoggedProgress = progress
                }
            } else if (received % (1024L * 1024L) < bytesRead) {
                onLog("Fetch received: ${alreadyFetched + received} bytes")
            }
        }
        if (cancelled) {
            onLog("⚠️ Fetch cancelled by user")
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
        private const val NATIVE_USBFS_BLOCK_BYTES = 256 * 1024
        private const val NATIVE_USBFS_PIPELINE_DEPTH = 2
        private const val NATIVE_USBFS_PROFILE_LABEL = "Native USBFS pipeline 2×256 KiB"

        private const val NATIVE_USBFS_URB_TIMEOUT_MS = 30_000
        private const val NATIVE_USBFS_HARD_TIMEOUT_MS = 90_000
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
