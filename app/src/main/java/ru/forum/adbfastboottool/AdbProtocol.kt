package ru.forum.adbfastboottool

import android.hardware.usb.*
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.zip.ZipFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

class AdbProtocol(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val keyDirectory: File,
    private val onLog: (String) -> Unit,
    private val onProgress: (Int, String) -> Unit = { _, _ -> },
    private val preferredInterfaceIndex: Int? = null
) {
    private val LOCAL_ADB_VERSION = AdbPacketChecksum.VERSION_WITH_CHECKSUM

    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var adbInterface: UsbInterface? = null

    @Volatile private var cancelled = false
    private var nextLocalId = 2
    private val adbKeyStore by lazy { AdbKeyStore(keyDirectory, onLog) }
    private val deviceFeatures = linkedSetOf<String>()
    private var remoteBanner: String = ""
    private var peerProtocolVersion: Int = LOCAL_ADB_VERSION
    private var pendingInboundChecksum: Int? = null
    private var pendingInboundLength: Int = 0
    private var pendingInboundCommand: Long = 0L
    private val adbWriteLock = Any()
    @Volatile private var packetDispatcher: AdbPacketDispatcher? = null
    @Volatile private var dispatchedPayloadPacket: AdbPacketDispatcher.Packet? = null
    private val dispatcherGeneration = AtomicLong(0L)
    @Volatile private var dispatcherTransportFailed = false
    @Volatile private var lastDispatcherSnapshot: AdbPacketDispatcher.Snapshot? = null
    @Volatile private var directReadFailureCode: AdbPacketDispatcher.FailureCode? = null
    @Volatile private var directReadFailureMessage: String? = null
    var onTransportFailure: ((AdbPacketDispatcher.FailureCode, String) -> Unit)? = null
    @Volatile var readOnlyMutationLock: Boolean = false
    @Volatile var readOnlyMutationLockReason: String = "Diagnostic READ-ONLY mode"

    private val interactiveShellLock = Any()
    private var interactiveShellSession: InteractiveShellSession? = null

    val isConnected: Boolean
        get() = !dispatcherTransportFailed && connection != null && endpointIn != null && endpointOut != null && adbInterface != null

    private val A_CNXN = 0x4E584E43L
    private val A_OPEN = 0x4E45504FL
    private val A_OKAY = 0x59414B4FL
    private val A_CLSE = 0x45534C43L
    private val A_WRTE = 0x45545257L
    private val A_AUTH = 0x48545541L

    private val AUTH_TOKEN        = 1
    private val AUTH_SIGNATURE    = 2
    private val AUTH_RSAPUBLICKEY = 3

    private val SIDELOAD_BLOCK_SIZE = 65536
    private val MAX_PAYLOAD = 1048576
    private val SYNC_DATA_CHUNK = 64 * 1024
    private val SYNC_MAX_STRING = 1024 * 1024
    private val SYNC_MODE_IFMT = 61440       // 0170000
    private val SYNC_MODE_IFDIR = 16384      // 0040000
    private val SYNC_MODE_IFREG = 32768      // 0100000

    private val SHELL_ID_STDIN = 0
    private val SHELL_ID_STDOUT = 1
    private val SHELL_ID_STDERR = 2
    private val SHELL_ID_EXIT = 3
    private val SHELL_ID_CLOSE_STDIN = 4
    private val SHELL_PACKET_HEADER = 5

    private val MAX_RECOVERY_INSTALL_LOG_CHARS = 512 * 1024

    private val RECOVERY_INSTALL_LOG_PATHS = listOf(
        "/cache/recovery/last_install",
        "/tmp/recovery.log",
        "/cache/recovery/last_log",
        "/tmp/install.log"
    )

    enum class PeerMode { DEVICE, RECOVERY, SIDELOAD, UNKNOWN }

    enum class SideloadFailureKind { FILE, TRANSPORT, PROTOCOL }

    sealed class SideloadResult {
        data class TransferComplete(val integrity: PackageIntegrityVerifier.Result) : SideloadResult()
        object Cancelled : SideloadResult()
        data class NotInSideloadMode(val mode: PeerMode) : SideloadResult()
        data class Failed(val kind: SideloadFailureKind, val message: String) : SideloadResult()
    }

    private enum class SideloadTerminalState { RUNNING, TRANSFER_COMPLETE, FAILED, CANCELLED }

    data class DeviceDiagnostics(
        val remoteBanner: String,
        val peerMode: PeerMode,
        val features: List<String>,
        val supportsShellV2: Boolean,
        val interactiveShellActive: Boolean,
        val publicKeyPath: String,
        val dispatcherRunning: Boolean = false,
        val queuedPackets: Int = 0,
        val packetsRead: Long = 0L,
        val readerTimeouts: Long = 0L,
        val readerFailures: Long = 0L,
        val lastReaderFailureCode: String = AdbPacketDispatcher.FailureCode.NONE.name,
        val lastReaderFailureMessage: String? = null,
        val readOnlyMutationLock: Boolean = false
    )

    val supportsShellV2: Boolean
        get() = deviceFeatures.contains("shell_v2")

    val peerMode: PeerMode
        get() = when {
            remoteBanner.startsWith("sideload::", ignoreCase = true) -> PeerMode.SIDELOAD
            remoteBanner.startsWith("recovery::", ignoreCase = true) -> PeerMode.RECOVERY
            remoteBanner.startsWith("device::", ignoreCase = true) -> PeerMode.DEVICE
            else -> PeerMode.UNKNOWN
        }

    val hasInteractiveShell: Boolean
        get() = synchronized(interactiveShellLock) { interactiveShellSession != null }

    fun currentDiagnostics(): DeviceDiagnostics {
        val dispatcher = packetDispatcher?.snapshot() ?: lastDispatcherSnapshot
        return DeviceDiagnostics(
            remoteBanner = remoteBanner,
            peerMode = peerMode,
            features = deviceFeatures.toList(),
            supportsShellV2 = supportsShellV2,
            interactiveShellActive = hasInteractiveShell,
            publicKeyPath = adbKeyStore.publicKeyPath(),
            dispatcherRunning = dispatcher?.running == true,
            queuedPackets = dispatcher?.queuedPackets ?: 0,
            packetsRead = dispatcher?.packetsRead ?: 0L,
            readerTimeouts = dispatcher?.readTimeouts ?: 0L,
            readerFailures = dispatcher?.readFailures ?: 0L,
            lastReaderFailureCode = dispatcher?.lastFailureCode?.name ?: AdbPacketDispatcher.FailureCode.NONE.name,
            lastReaderFailureMessage = dispatcher?.lastFailureMessage,
            readOnlyMutationLock = readOnlyMutationLock
        )
    }

    // ─── ПОДКЛЮЧЕНИЕ ─────────────────────────────────────────────────────────

    fun connect(): Boolean {
        cancelled = false
        dispatcherTransportFailed = false
        lastDispatcherSnapshot = null
        directReadFailureCode = null
        directReadFailureMessage = null
        peerProtocolVersion = LOCAL_ADB_VERSION
        pendingInboundChecksum = null
        pendingInboundLength = 0
        pendingInboundCommand = 0L

        val iface = findAdbInterface() ?: run {
            onLog("ОШИБКА: ADB интерфейс не найден")
            return false
        }
        adbInterface = iface

        val endpoints = findBulkEndpoints(iface)
        endpointIn = endpoints.first
        endpointOut = endpoints.second
        if (endpointIn == null || endpointOut == null) {
            onLog("ОШИБКА: ADB bulk endpoints не найдены")
            disconnect()
            return false
        }

        onLog(
            "ADB USB transport: interface=${iface.id}, " +
                "IN=0x${endpointIn!!.address.toString(16)}, " +
                "OUT=0x${endpointOut!!.address.toString(16)}"
        )

        connection = usbManager.openDevice(device)
        if (connection == null) {
            onLog("ОШИБКА: Не удалось открыть USB устройство для ADB")
            disconnect()
            return false
        }
        if (!connection!!.claimInterface(iface, true)) {
            onLog("ОШИБКА: Не удалось захватить ADB интерфейс")
            disconnect()
            return false
        }

        // Один transport, одно CNXN-рукопожатие. Автоматическое close/reopen и
        // повторный CNXN здесь запрещены: на ряде Android USB host это вызывало
        // detach/attach цикл и разрушало нормальную AUTH-последовательность.
        return try {
            sendMessageInternal(
                A_CNXN,
                LOCAL_ADB_VERSION,
                MAX_PAYLOAD,
                "host::NekoFlash\u0000".toByteArray(Charsets.UTF_8)
            )

            val header = readHeader() ?: run {
                onLog("ОШИБКА: Сбой подключения ADB (нет ответа)")
                disconnect()
                return false
            }

            when (header.command) {
                A_CNXN -> {
                    handleConnectionBanner(header)
                    onLog("=== СОЕДИНЕНИЕ ADB УСТАНОВЛЕНО ===")
                    startPacketDispatcher()
                    true
                }

                A_AUTH -> {
                    val ok = handleAuthPacket(header)
                    if (!ok) disconnect() else startPacketDispatcher()
                    ok
                }

                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("ОШИБКА: Неожиданный ADB ответ (cmd=0x${header.command.toString(16)})")
                    disconnect()
                    false
                }
            }
        } catch (e: Exception) {
            onLog("ОШИБКА: Сбой подключения ADB: ${e.message ?: e.javaClass.simpleName}")
            disconnect()
            false
        }
    }

    private fun findAdbInterface(): UsbInterface? {
        preferredInterfaceIndex?.let { index ->
            if (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                if (isAdbInterface(iface) && findBulkEndpoints(iface).let { it.first != null && it.second != null }) {
                    return iface
                }
                onLog("⚠️ Выбранный ADB interface=$index больше не соответствует ожидаемому дескриптору — выполняем безопасный поиск")
            }
        }

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (isAdbInterface(iface) && findBulkEndpoints(iface).let { it.first != null && it.second != null }) {
                return iface
            }
        }
        return null
    }

    private fun isAdbInterface(iface: UsbInterface): Boolean =
        iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            iface.interfaceSubclass == 0x42 &&
            iface.interfaceProtocol == 0x01

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

    private fun handleAuthPacket(firstHeader: AdbHeader): Boolean {
        if (firstHeader.arg0 != AUTH_TOKEN) {
            if (firstHeader.dataLength > 0) readData(firstHeader.dataLength)
            onLog("❌ Неподдерживаемый ADB AUTH тип: ${firstHeader.arg0}")
            return false
        }

        val firstToken = readData(firstHeader.dataLength) ?: run {
            onLog("❌ Не удалось прочитать ADB AUTH TOKEN")
            return false
        }

        onLog("🔐 ADB AUTH: устройство требует RSA-авторизацию")

        var publicKeySent = false
        try {
            val signature = adbKeyStore.signToken(firstToken)
            onLog("🔑 Пробуем авторизацию сохранённым ADB RSA-ключом")
            sendMessageInternal(A_AUTH, AUTH_SIGNATURE, 0, signature)
        } catch (e: Exception) {
            onLog("⚠️ Не удалось подписать ADB TOKEN: ${e.message ?: e.javaClass.simpleName}")
            publicKeySent = sendAdbPublicKeyForAuth()
            if (!publicKeySent) return false
        }

        repeat(12) { attempt ->
            if (cancelled) return false

            val timeout = if (publicKeySent) 60_000 else 10_000
            val header = readHeader(timeoutMs = timeout) ?: run {
                if (publicKeySent) {
                    onLog("❌ ADB RSA не подтверждён на устройстве за 60 сек")
                } else {
                    onLog("❌ Устройство не ответило на ADB RSA-подпись")
                }
                return false
            }

            when (header.command) {
                A_CNXN -> {
                    handleConnectionBanner(header)
                    onLog("✅ ADB авторизован. Соединение установлено.")
                    return true
                }

                A_AUTH -> {
                    when (header.arg0) {
                        AUTH_TOKEN -> {
                            if (header.dataLength > 0 && readData(header.dataLength) == null) {
                                onLog("❌ Не удалось прочитать повторный ADB AUTH TOKEN")
                                return false
                            }

                            if (!publicKeySent) {
                                publicKeySent = sendAdbPublicKeyForAuth()
                                if (!publicKeySent) return false
                            } else if (attempt % 3 == 2) {
                                onLog("⏳ Всё ещё ждём подтверждение ADB RSA на устройстве...")
                            }
                        }

                        else -> {
                            if (header.dataLength > 0) readData(header.dataLength)
                            onLog("❌ Неподдерживаемый ADB AUTH тип: ${header.arg0}")
                            return false
                        }
                    }
                }

                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("❌ Неожиданный ответ во время ADB AUTH: cmd=0x${header.command.toString(16)}")
                    return false
                }
            }
        }

        onLog("❌ ADB RSA-авторизация не завершена")
        onLog("💡 Проверьте экран устройства, USB-отладку и пункт 'Always allow from this computer'")
        return false
    }


    private fun sendAdbPublicKeyForAuth(): Boolean {
        return try {
            val publicKeyPayload = adbKeyStore.publicKeyPayload()
            sendMessageInternal(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload)
            onLog("📤 Отправлен ADB public key")
            onLog("⏳ Подтвердите запрос «Allow USB debugging» на экране устройства")
            onLog("ℹ️ Public key сохранён: ${adbKeyStore.publicKeyPath()}")
            true
        } catch (e: Exception) {
            onLog("❌ Не удалось отправить ADB public key: ${e.message ?: e.javaClass.simpleName}")
            false
        }

    }

    private fun handleConnectionBanner(header: AdbHeader) {
        peerProtocolVersion = header.arg0
        val bannerBytes = readData(header.dataLength)
        remoteBanner = bannerBytes?.toString(Charsets.UTF_8)?.trimEnd('\u0000').orEmpty()
        parseRemoteFeatures(remoteBanner)
        if (remoteBanner.isNotBlank()) onLog("ADB banner: $remoteBanner")
        if (supportsShellV2) {
            onLog("✅ ADB feature shell_v2 обнаружена: доступен exit-code и разделение stdout/stderr")
        } else {
            onLog("ℹ️ ADB feature shell_v2 не заявлена: shell будет работать в legacy-режиме без точного exit-code")
        }
    }

    private fun parseRemoteFeatures(banner: String) {
        deviceFeatures.clear()
        val featureText = banner
            .split(';')
            .firstOrNull { it.startsWith("features=") }
            ?.substringAfter("features=")
            .orEmpty()
        if (featureText.isNotBlank()) {
            featureText.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { deviceFeatures.add(it) }
        }
    }

    // ─── SIDELOAD ─────────────────────────────────────────────────────────────

    private fun rejectMutation(operation: String): Boolean {
        if (!readOnlyMutationLock) return false
        onLog("⛔ ADB READ-ONLY LOCK: операция '$operation' заблокирована. reason=$readOnlyMutationLockReason")
        return true
    }

    private fun allowShellInCurrentMode(command: String, operation: String): Boolean {
        if (!readOnlyMutationLock) return true
        if (AdbReadOnlyPolicy.isShellReadOnly(command)) return true
        rejectMutation(operation)
        return false
    }

    fun sideloadZip(file: File): SideloadResult {
        if (rejectMutation("adb sideload")) return SideloadResult.Failed(SideloadFailureKind.PROTOCOL, "READ-ONLY lock")
        if (!isConnected) {
            return SideloadResult.Failed(SideloadFailureKind.TRANSPORT, "Нет ADB-соединения")
        }
        if (peerMode != PeerMode.SIDELOAD) {
            onLog("ОШИБКА: ADB Sideload не запущен. Текущий ADB-режим: ${peerMode.name}")
            onLog("💡 Откройте: Recovery → Apply update → Apply from ADB")
            return SideloadResult.NotInSideloadMode(peerMode)
        }

        cancelled = false
        var terminalState = SideloadTerminalState.RUNNING

        onLog("Анализ файла: ${file.name}")
        val packageIntegrity = PackageIntegrityVerifier.verifyZip(
            file = file,
            onProgress = { progress ->
                val overall = ((progress.percent * 30L) / 100L).toInt().coerceIn(0, 30)
                val stage = when (progress.stage) {
                    PackageIntegrityVerifier.Stage.HASHING -> "хэширование"
                    PackageIntegrityVerifier.Stage.ZIP_SCAN -> "полный ZIP scan"
                }
                onProgress(overall, "Проверка пакета · $stage · ${progress.percent}%")
            },
            onLog = onLog,
            shouldCancel = { cancelled }
        )
        when (packageIntegrity.verdict) {
            PackageIntegrityVerifier.Verdict.VALID -> Unit
            PackageIntegrityVerifier.Verdict.CANCELLED -> return SideloadResult.Cancelled
            else -> {
                val detail = buildString {
                    append(packageIntegrity.message)
                    packageIntegrity.evidence?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                }
                onLog("❌ ADB Sideload заблокирован: ${packageIntegrity.verdict.name} · $detail")
                return SideloadResult.Failed(SideloadFailureKind.FILE, detail)
            }
        }

        val fileSize = packageIntegrity.fileSize
        if (file.length() != packageIntegrity.fileSize || file.lastModified() != packageIntegrity.lastModifiedMs) {
            onLog("❌ ADB Sideload заблокирован: файл изменился после проверки целостности")
            return SideloadResult.Failed(
                SideloadFailureKind.FILE,
                "Файл изменился после проверки целостности; выполните проверку заново"
            )
        }
        onLog("Старт ADB Sideload...")
        onProgress(30, "ADB Sideload · ожидание запросов recovery")

        return try {
            sendMessageInternal(
                A_OPEN, 1, 0,
                "sideload-host:${fileSize}:$SIDELOAD_BLOCK_SIZE\u0000".toByteArray()
            )
            val openResp = readHeader()
            if (openResp == null || openResp.command != A_OKAY) {
                if (openResp != null && openResp.dataLength > 0) readData(openResp.dataLength)
                onLog("ОШИБКА: Recovery не подтвердило sideload-host OPEN.")
                return SideloadResult.Failed(
                    SideloadFailureKind.PROTOCOL,
                    "Recovery не подтвердило sideload-host OPEN"
                )
            }

            val remoteId = openResp.arg0
            var servedBytes = 0L
            var lastLoggedBucket = -1
            var result: SideloadResult? = null

            fun fail(kind: SideloadFailureKind, message: String): SideloadResult.Failed {
                terminalState = SideloadTerminalState.FAILED
                onLog("❌ ADB Sideload: $message")
                return SideloadResult.Failed(kind, message)
            }

            fun readStreamAck(): SideloadResult.Failed? {
                val ack = readHeader()
                    ?: return fail(SideloadFailureKind.TRANSPORT, "ADB transport закрылся во время подтверждения блока")
                if (ack.dataLength > 0 && readData(ack.dataLength) == null) {
                    return fail(SideloadFailureKind.TRANSPORT, "Не удалось прочитать данные ответа ADB")
                }
                return when (ack.command) {
                    A_OKAY -> null
                    A_CLSE -> fail(SideloadFailureKind.PROTOCOL, "Recovery закрыла sideload stream до DONEDONE")
                    else -> fail(
                        SideloadFailureKind.PROTOCOL,
                        "Неожиданный ADB-ответ после блока: cmd=0x${ack.command.toString(16)}"
                    )
                }
            }

            RandomAccessFile(file, "r").use { raf ->
                sideloadLoop@ while (!cancelled && result == null) {
                    val reqHeader = readHeader()
                    if (reqHeader == null) {
                        result = fail(
                            SideloadFailureKind.TRANSPORT,
                            "ADB transport закрылся до подтверждения DONEDONE"
                        )
                        break@sideloadLoop
                    }

                    when (reqHeader.command) {
                        A_CLSE -> {
                            if (reqHeader.dataLength > 0) readData(reqHeader.dataLength)
                            runCatching { sendMessageInternal(A_CLSE, 1, remoteId, ByteArray(0)) }
                            result = fail(
                                SideloadFailureKind.PROTOCOL,
                                "Recovery закрыла sideload stream до DONEDONE"
                            )
                        }

                        A_WRTE -> {
                            val reqData = readData(reqHeader.dataLength)
                            if (reqData == null) {
                                result = fail(SideloadFailureKind.TRANSPORT, "Не удалось прочитать запрос sideload-блока")
                                continue@sideloadLoop
                            }

                            // ADB stream protocol requires an OKAY acknowledgement for every WRTE.
                            sendMessageInternal(A_OKAY, 1, remoteId, ByteArray(0))

                            val ascii = runCatching {
                                String(reqData, Charsets.US_ASCII).trimEnd('\u0000', ' ', '\r', '\n')
                            }.getOrNull().orEmpty()

                            if (ascii == "DONEDONE") {
                                terminalState = SideloadTerminalState.TRANSFER_COMPLETE
                                onProgress(100, "ADB Sideload · передача завершена")
                                onLog("✅ Recovery прислало DONEDONE — sideload-поток завершён.")
                                onLog("ℹ️ DONEDONE не подтверждает успешную установку ZIP. Ожидаем возврат в Recovery и проверяем её итоговый лог.")
                                result = SideloadResult.TransferComplete(packageIntegrity)
                                break@sideloadLoop
                            }

                            val blockToken = ascii.take(8).trim('\u0000', ' ')
                            val blockNum = blockToken.toIntOrNull()
                            if (blockNum == null) {
                                val hex = reqData.joinToString(" ") { "%02x".format(it) }
                                result = fail(
                                    SideloadFailureKind.PROTOCOL,
                                    "Некорректный запрос блока (${reqData.size} байт): $hex"
                                )
                                continue@sideloadLoop
                            }

                            if (blockNum == -1) {
                                onLog("ℹ️ Recovery сообщило о завершении запросов блоков; ожидаем DONEDONE...")
                                sendMessageInternal(A_WRTE, 1, remoteId, ByteArray(0))
                                result = readStreamAck()
                                continue@sideloadLoop
                            }

                            if (blockNum < 0) {
                                result = fail(SideloadFailureKind.PROTOCOL, "Отрицательный номер sideload-блока: $blockNum")
                                continue@sideloadLoop
                            }

                            val offset = blockNum.toLong() * SIDELOAD_BLOCK_SIZE.toLong()
                            if (offset < 0L || offset >= fileSize) {
                                result = fail(
                                    SideloadFailureKind.PROTOCOL,
                                    "Recovery запросило блок за пределами ZIP: block=$blockNum offset=$offset size=$fileSize"
                                )
                                continue@sideloadLoop
                            }

                            val payloadSize = minOf(SIDELOAD_BLOCK_SIZE.toLong(), fileSize - offset).toInt()
                            val payload = ByteArray(payloadSize)
                            raf.seek(offset)
                            raf.readFully(payload)

                            sendMessageInternal(A_WRTE, 1, remoteId, payload)
                            result = readStreamAck()
                            if (result != null) continue@sideloadLoop

                            servedBytes += payload.size.toLong()
                            val approximateProgress = ((servedBytes * 100L) / fileSize)
                                .toInt()
                                .coerceIn(0, 99)
                            val bucket = approximateProgress / 5
                            if (bucket > lastLoggedBucket) {
                                val displayProgress = (bucket * 5).coerceAtMost(99)
                                onLog("Sideload: ≈$displayProgress% (отдано $servedBytes байт, блок $blockNum)")
                                val overallProgress = 30 + ((displayProgress * 69) / 100)
                                onProgress(overallProgress.coerceIn(30, 98), "ADB Sideload · ≈$displayProgress%")
                                lastLoggedBucket = bucket
                            }
                        }

                        else -> {
                            if (reqHeader.dataLength > 0) readData(reqHeader.dataLength)
                            result = fail(
                                SideloadFailureKind.PROTOCOL,
                                "Неожиданная ADB-команда в sideload stream: 0x${reqHeader.command.toString(16)}"
                            )
                        }
                    }
                }
            }

            when {
                terminalState == SideloadTerminalState.TRANSFER_COMPLETE -> SideloadResult.TransferComplete(packageIntegrity)
                cancelled -> {
                    terminalState = SideloadTerminalState.CANCELLED
                    onLog("⚠️ ADB Sideload отменён")
                    SideloadResult.Cancelled
                }
                else -> result ?: SideloadResult.Failed(
                    SideloadFailureKind.TRANSPORT,
                    "ADB Sideload завершился без DONEDONE"
                )
            }
        } catch (e: Exception) {
            if (terminalState == SideloadTerminalState.TRANSFER_COMPLETE) {
                SideloadResult.TransferComplete(packageIntegrity)
            } else if (cancelled) {
                terminalState = SideloadTerminalState.CANCELLED
                SideloadResult.Cancelled
            } else {
                terminalState = SideloadTerminalState.FAILED
                val message = e.message ?: e.javaClass.simpleName
                onLog("ОШИБКА Sideload: $message")
                SideloadResult.Failed(SideloadFailureKind.TRANSPORT, message)
            }
        }
    }


    // ─── ADB SYNC / FILE TRANSFER ────────────────────────────────────────────

    fun pushPath(localPath: File, remotePath: String, mode: Int = 0x1A4): Boolean {
        if (rejectMutation("adb push")) return false
        if (!isConnected) return false
        cancelled = false

        return when {
            localPath.exists() && localPath.isFile && localPath.canRead() -> pushFile(localPath, remotePath, mode)
            localPath.exists() && localPath.isDirectory && localPath.canRead() -> pushDirectory(localPath, remotePath, mode)
            else -> {
                onLog("❌ adb push: локальный путь недоступен: ${localPath.absolutePath}")
                false
            }
        }
    }

    fun pushFile(localFile: File, remotePath: String, mode: Int = 0x1A4): Boolean {
        if (rejectMutation("adb push")) return false
        if (!isConnected) return false
        cancelled = false

        if (!localFile.exists() || !localFile.isFile || !localFile.canRead()) {
            onLog("❌ adb push: локальный файл недоступен: ${localFile.absolutePath}")
            return false
        }
        val cleanRemote = remotePath.trim()
        if (!isSafeRemotePath(cleanRemote)) {
            onLog("❌ adb push: некорректный remote path")
            return false
        }

        onLog("-> adb push ${localFile.name} $cleanRemote")
        onLog("Размер: ${localFile.length()} байт")

        val stream = openAdbStream("sync:") ?: return false
        try {
            val spec = "$cleanRemote,$mode".toByteArray(Charsets.UTF_8)
            if (!writeSyncRequest(stream, "SEND", spec)) return false

            val total = localFile.length().coerceAtLeast(1L)
            var sentBytes = 0L
            var lastProgress = -1
            RandomAccessFile(localFile, "r").use { raf ->
                val buffer = ByteArray(SYNC_DATA_CHUNK)
                while (!cancelled) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    if (!writeSyncData(stream, "DATA", buffer, read)) return false
                    sentBytes += read.toLong()
                    val progress = ((sentBytes * 100L) / total).toInt()
                    if (progress >= 100 || progress / 10 != lastProgress / 10) {
                        onLog("adb push: $progress% ($sentBytes/${localFile.length()} байт)")
                        lastProgress = progress
                    }
                }
            }
            if (cancelled) {
                onLog("⚠️ adb push отменён")
                return false
            }

            val mtime = (localFile.lastModified() / 1000L).toInt()
            if (!writeSyncIdAndInt(stream, "DONE", mtime)) return false
            val ok = readSyncStatus(stream, "adb push")
            if (ok) onLog("✅ adb push завершён: $cleanRemote")
            return ok
        } catch (e: Exception) {
            onLog("❌ adb push ошибка: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            closeAdbStream(stream)
        }
    }

    private fun pushDirectory(localDir: File, remotePath: String, mode: Int = 0x1A4): Boolean {
        if (!isConnected) return false
        cancelled = false

        if (!localDir.exists() || !localDir.isDirectory || !localDir.canRead()) {
            onLog("❌ adb push: локальная папка недоступна: ${localDir.absolutePath}")
            return false
        }
        val cleanRemote = remotePath.trim()
        if (!isSafeRemotePath(cleanRemote)) {
            onLog("❌ adb push: некорректный remote path")
            return false
        }

        val remoteStat = if (cleanRemote.endsWith("/")) null else statRemotePath(cleanRemote, logMissing = false)
        val targetRoot = if (cleanRemote.endsWith("/") || remoteStat?.isDirectory == true) {
            joinRemotePath(cleanRemote.trimEnd('/'), localDir.name)
        } else {
            cleanRemote.trimEnd('/')
        }

        val allEntries = localDir.walkTopDown().toList()
        val directories = allEntries.filter { it.isDirectory }
        val files = allEntries.filter { it.isFile }
        onLog("-> adb push -r ${localDir.absolutePath} $targetRoot")
        onLog("ℹ️ Каталог: ${directories.size} папок, ${files.size} файлов")

        directories.forEach { dir ->
            if (cancelled) return false
            val relative = dir.relativeTo(localDir).path.replace(File.separatorChar, '/')
            val remoteDir = if (relative.isBlank() || relative == ".") targetRoot else joinRemotePath(targetRoot, relative)
            if (!ensureRemoteDirectory(remoteDir)) return false
        }

        var pushed = 0
        files.forEach { file ->
            if (cancelled) return false
            val relative = file.relativeTo(localDir).path.replace(File.separatorChar, '/')
            val remoteFile = joinRemotePath(targetRoot, relative)
            onLog("ℹ️ adb push file ${pushed + 1}/${files.size}: $relative")
            if (!pushFile(file, remoteFile, mode)) return false
            pushed++
        }

        return if (cancelled) {
            onLog("⚠️ adb push каталога отменён")
            false
        } else {
            onLog("✅ adb push каталога завершён: $pushed файлов → $targetRoot")
            true
        }
    }

    fun pullFile(remotePath: String, localFile: File): Boolean {
        if (!isConnected) return false
        cancelled = false

        val cleanRemote = remotePath.trim()
        if (!isSafeRemotePath(cleanRemote)) {
            onLog("❌ adb pull: некорректный remote path")
            return false
        }

        val stat = statRemotePath(cleanRemote)
        if (stat == null || !stat.exists) {
            onLog("❌ adb pull: remote path не найден или недоступен: $cleanRemote")
            return false
        }

        return if (stat.isDirectory) {
            pullDirectory(cleanRemote, localFile)
        } else {
            pullFileSingle(cleanRemote, localFile, stat.size.takeIf { it >= 0L })
        }
    }

    private fun pullFileSingle(remotePath: String, localFile: File, expectedSize: Long?): Boolean {
        val cleanRemote = remotePath.trim()
        val parent = localFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            onLog("❌ adb pull: не удалось создать папку: ${parent.absolutePath}")
            return false
        }

        onLog("-> adb pull $cleanRemote ${localFile.absolutePath}")
        if (expectedSize != null && expectedSize >= 0L) {
            onLog("Размер remote-файла: $expectedSize байт")
        }

        val stream = openAdbStream("sync:") ?: return false
        val tempFile = File(localFile.absolutePath + ".part")
        var receivedBytes = 0L
        try {
            val pathBytes = cleanRemote.toByteArray(Charsets.UTF_8)
            if (!writeSyncRequest(stream, "RECV", pathBytes)) return false

            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(0)
                while (!cancelled) {
                    val header = readSyncHeader(stream) ?: return false
                    when (header.id) {
                        "DATA" -> {
                            if (header.value < 0 || header.value > SYNC_DATA_CHUNK * 4) {
                                onLog("❌ adb pull: некорректный размер DATA=${header.value}")
                                return false
                            }
                            val data = readAdbStreamExact(stream, header.value) ?: return false
                            raf.write(data)
                            val previousBytes = receivedBytes
                            receivedBytes += data.size.toLong()
                            if (expectedSize != null && expectedSize > 0L) {
                                val previousProgress = ((previousBytes * 100L) / expectedSize).toInt().coerceAtMost(100)
                                val progress = ((receivedBytes * 100L) / expectedSize).toInt().coerceAtMost(100)
                                if (progress >= 100 || progress / 10 != previousProgress / 10) {
                                    onLog("adb pull: $progress% ($receivedBytes/$expectedSize байт)")
                                }
                            } else if (receivedBytes == data.size.toLong() || receivedBytes % (1024L * 1024L) < data.size) {
                                onLog("adb pull: принято $receivedBytes байт")
                            }
                        }
                        "DONE" -> {
                            raf.fd.sync()
                            if (localFile.exists() && !localFile.delete()) {
                                onLog("❌ adb pull: не удалось заменить файл: ${localFile.absolutePath}")
                                return false
                            }
                            if (!tempFile.renameTo(localFile)) {
                                onLog("❌ adb pull: не удалось сохранить файл: ${localFile.absolutePath}")
                                return false
                            }
                            onLog("✅ adb pull завершён: ${localFile.absolutePath} ($receivedBytes байт)")
                            return true
                        }
                        "FAIL" -> {
                            val message = readSyncString(stream, header.value)
                            onLog("❌ adb pull FAIL: $message")
                            return false
                        }
                        else -> {
                            onLog("❌ adb pull: неожиданный sync id=${header.id}")
                            return false
                        }
                    }
                }
            }

            onLog("⚠️ adb pull отменён")
            return false
        } catch (e: Exception) {
            onLog("❌ adb pull ошибка: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
            closeAdbStream(stream)
        }
    }

    private fun pullDirectory(remoteDir: String, localDir: File): Boolean {
        if (!isConnected) return false
        cancelled = false

        if (localDir.exists() && localDir.isFile) {
            onLog("❌ adb pull: remote path является каталогом, а локальный путь — файл: ${localDir.absolutePath}")
            return false
        }
        if (!localDir.exists() && !localDir.mkdirs()) {
            onLog("❌ adb pull: не удалось создать локальную папку: ${localDir.absolutePath}")
            return false
        }

        onLog("-> adb pull -r $remoteDir ${localDir.absolutePath}")
        val listCommand = "cd ${shellQuote(remoteDir)} && echo AFT_DIRS_BEGIN && find . -type d -print && echo AFT_FILES_BEGIN && find . -type f -print"
        val listResult = runShellCommandForResult(listCommand, logOutput = false)
        if (!listResult.success) {
            onLog("❌ adb pull: не удалось получить список файлов remote-каталога")
            return false
        }

        val sections = parseFindSections(listResult.stdout)
        val dirs = sections.first
        val files = sections.second
        onLog("ℹ️ Remote-каталог: ${dirs.size} папок, ${files.size} файлов")

        dirs.forEach { relative ->
            if (cancelled) return false
            val localSubDir = if (relative == ".") localDir else File(localDir, normalizeRelativeRemotePath(relative))
            if (!localSubDir.exists() && !localSubDir.mkdirs()) {
                onLog("❌ adb pull: не удалось создать локальную папку: ${localSubDir.absolutePath}")
                return false
            }
        }

        var pulled = 0
        files.forEach { relative ->
            if (cancelled) return false
            val cleanRelative = normalizeRelativeRemotePath(relative)
            if (cleanRelative.isBlank()) return@forEach
            val remoteFile = joinRemotePath(remoteDir.trimEnd('/'), cleanRelative)
            val localTarget = File(localDir, cleanRelative)
            val stat = statRemotePath(remoteFile, logMissing = false)
            onLog("ℹ️ adb pull file ${pulled + 1}/${files.size}: $cleanRelative")
            if (!pullFileSingle(remoteFile, localTarget, stat?.size?.takeIf { it >= 0L })) return false
            pulled++
        }

        return if (cancelled) {
            onLog("⚠️ adb pull каталога отменён")
            false
        } else {
            onLog("✅ adb pull каталога завершён: $pulled файлов → ${localDir.absolutePath}")
            true
        }
    }

    fun installPackage(packageFile: File, options: List<String>): Boolean {
        if (rejectMutation("adb install")) return false
        if (!packageFile.exists() || !packageFile.isFile || !packageFile.canRead()) {
            onLog("❌ adb install: файл недоступен: ${packageFile.absolutePath}")
            return false
        }

        return when (packageFile.extension.lowercase()) {
            "apk" -> installApk(packageFile, options)
            "apks", "xapk" -> installPackageArchive(packageFile, options)
            else -> {
                onLog("⚠️ adb install: неизвестное расширение .${packageFile.extension}. Пробуем как APK.")
                installApk(packageFile, options)
            }
        }
    }

    fun installApk(apkFile: File, options: List<String>): Boolean {
        if (rejectMutation("adb install")) return false
        if (!apkFile.exists() || !apkFile.isFile || !apkFile.canRead()) {
            onLog("❌ adb install: APK недоступен: ${apkFile.absolutePath}")
            return false
        }
        val safeName = apkFile.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val remotePath = "/data/local/tmp/aft-${System.currentTimeMillis()}-$safeName"
        val installOptions = options.filter { it.isNotBlank() }
        onLog("-> adb install ${installOptions.joinToString(" ")} ${apkFile.name}".trim())
        if (!pushFile(apkFile, remotePath, 0x1A4)) return false

        val optionText = installOptions.joinToString(" ") { shellQuote(it) }
        val command = buildString {
            append("pm install")
            if (optionText.isNotBlank()) append(' ').append(optionText)
            append(' ').append(shellQuote(remotePath))
            append("; rc=\$?; echo AFT_PM_INSTALL_RC:\$rc; rm -f ").append(shellQuote(remotePath)).append("; exit \$rc")
        }
        onLog("ℹ️ APK временно загружен в $remotePath")
        onLog("ℹ️ Запускаем package manager на target-устройстве")
        return runShellCommand(command)
    }

    private fun installPackageArchive(archiveFile: File, options: List<String>): Boolean {
        if (!isConnected) return false
        cancelled = false

        val cacheRoot = File(keyDirectory.parentFile ?: keyDirectory, "adb-package-cache")
        if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
            onLog("❌ adb install: не удалось создать временную папку: ${cacheRoot.absolutePath}")
            return false
        }
        val workDir = File(cacheRoot, "pkg-${System.currentTimeMillis()}")
        if (!workDir.mkdirs()) {
            onLog("❌ adb install: не удалось создать временную папку: ${workDir.absolutePath}")
            return false
        }

        onLog("-> adb install ${options.joinToString(" ")} ${archiveFile.name}".trim())
        onLog("ℹ️ Контейнер ${archiveFile.extension.uppercase()}: распаковываем APK-файлы")

        try {
            val contents = extractPackageArchiveContents(archiveFile, workDir)
            val extracted = contents.apks
            if (extracted.isEmpty()) {
                onLog("❌ В контейнере не найдено APK-файлов: ${archiveFile.name}")
                return false
            }
            if (contents.obbs.isNotEmpty()) {
                onLog("ℹ️ Найдено OBB в контейнере: ${contents.obbs.size}")
            }

            val selected = selectArchiveApksForInstall(extracted)
            onLog("ℹ️ Выбрано APK для установки: ${selected.size}")
            selected.forEachIndexed { index, item ->
                onLog("   ${index + 1}. ${item.file.name} ← ${item.entryName}")
            }

            val installOk = if (selected.size == 1) {
                installApk(selected.first().file, options)
            } else {
                installMultipleApks(selected.map { it.file }, options)
            }
            if (!installOk) return false

            return pushArchiveObbs(contents.obbs, contents.manifestPackageName)
        } catch (e: Exception) {
            onLog("❌ adb install: ошибка обработки контейнера ${archiveFile.name}: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            deleteRecursivelySafe(workDir)
        }
    }

    private fun extractPackageArchiveContents(archiveFile: File, workDir: File): PackageArchiveContents {
        val apks = mutableListOf<ExtractedArchiveApk>()
        val obbs = mutableListOf<ExtractedArchiveObb>()
        var manifestPackageName: String? = null

        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            var apkIndex = 0
            var obbIndex = 0
            while (entries.hasMoreElements()) {
                if (cancelled) break
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val normalizedName = entry.name.replace('\\', '/')
                val lowerName = normalizedName.lowercase()
                if (!isSafeArchiveEntryName(normalizedName)) {
                    onLog("⚠️ Пропущен небезопасный путь в архиве: $normalizedName")
                    continue
                }

                if (lowerName == "manifest.json" || lowerName.endsWith("/manifest.json")) {
                    zip.getInputStream(entry).use { input ->
                        val text = input.readBytesLimited(2 * 1024 * 1024).toString(Charsets.UTF_8)
                        manifestPackageName = extractPackageNameFromManifestText(text) ?: manifestPackageName
                    }
                    continue
                }

                when {
                    lowerName.endsWith(".apk") -> {
                        val baseName = normalizedName.substringAfterLast('/').ifBlank { "entry-$apkIndex.apk" }
                        val outputName = "${apkIndex.toString().padStart(3, '0')}-${baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                        val outFile = File(workDir, outputName)
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        apks.add(ExtractedArchiveApk(outFile, normalizedName))
                        apkIndex++
                    }
                    lowerName.endsWith(".obb") -> {
                        val baseName = normalizedName.substringAfterLast('/').ifBlank { "entry-$obbIndex.obb" }
                        val outputName = "obb-${obbIndex.toString().padStart(3, '0')}-${baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                        val outFile = File(workDir, outputName)
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        obbs.add(ExtractedArchiveObb(outFile, normalizedName, extractObbPackageNameFromPath(normalizedName)))
                        obbIndex++
                    }
                }
            }
        }
        onLog("ℹ️ Найдено APK в контейнере: ${apks.size}")
        manifestPackageName?.let { onLog("ℹ️ package_name из manifest.json: $it") }
        return PackageArchiveContents(apks, obbs, manifestPackageName)
    }

    private fun pushArchiveObbs(obbs: List<ExtractedArchiveObb>, manifestPackageName: String?): Boolean {
        if (obbs.isEmpty()) return true
        var ok = true
        obbs.forEachIndexed { index, obb ->
            if (cancelled) return false
            val packageName = obb.packageNameFromPath ?: manifestPackageName
            if (packageName.isNullOrBlank()) {
                onLog("⚠️ OBB ${obb.entryName}: пакет не определён, файл не отправлен. Распакуйте XAPK и выполните adb push вручную.")
                ok = false
                return@forEachIndexed
            }
            val remoteDir = "/sdcard/Android/obb/$packageName"
            val remoteName = obb.entryName.substringAfterLast('/').ifBlank { obb.file.name.removePrefix("obb-") }
            val remotePath = "$remoteDir/$remoteName"
            onLog("ℹ️ OBB ${index + 1}/${obbs.size}: ${obb.entryName} → $remotePath")
            val mkdirResult = runShellCommandForResult("mkdir -p ${shellQuote(remoteDir)}", logOutput = false)
            if (!mkdirResult.success) {
                onLog("❌ Не удалось создать папку OBB: $remoteDir")
                ok = false
                return@forEachIndexed
            }
            if (!pushFile(obb.file, remotePath, 0x1A4)) {
                ok = false
                return@forEachIndexed
            }
        }
        if (ok) onLog("✅ OBB-файлы отправлены")
        return ok
    }

    private fun extractObbPackageNameFromPath(entryName: String): String? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotBlank() }
        for (i in 0 until parts.size - 2) {
            if (parts[i].equals("Android", ignoreCase = true) && parts[i + 1].equals("obb", ignoreCase = true)) {
                val candidate = parts[i + 2]
                if (isValidPackageName(candidate)) return candidate
            }
        }
        return null
    }

    private fun extractPackageNameFromManifestText(text: String): String? {
        val patterns = listOf(
            Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"packageName\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"package\"\\s*:\\s*\"([^\"]+)\"")
        )
        patterns.forEach { regex ->
            val candidate = regex.find(text)?.groupValues?.getOrNull(1)
            if (!candidate.isNullOrBlank() && isValidPackageName(candidate)) return candidate
        }
        return null
    }

    private fun isValidPackageName(value: String): Boolean =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(value)

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun selectArchiveApksForInstall(apks: List<ExtractedArchiveApk>): List<ExtractedArchiveApk> {
        val universal = apks.firstOrNull { it.entryName.substringAfterLast('/').equals("universal.apk", ignoreCase = true) }
        if (universal != null) {
            onLog("ℹ️ Найден universal.apk — используем одиночную установку вместо split-набора")
            return listOf(universal)
        }

        val nonStandalone = apks.filterNot {
            val path = it.entryName.replace('\\', '/').lowercase()
            path.startsWith("standalones/") || path.contains("/standalones/")
        }
        val splitLike = nonStandalone.filter {
            val path = it.entryName.replace('\\', '/').lowercase()
            path.startsWith("splits/") || path.contains("/splits/") || isBaseApkLike(it) || isConfigSplitLike(it)
        }
        val splitSet = if (splitLike.any { isBaseApkLike(it) }) splitLike else emptyList()
        if (splitSet.isNotEmpty()) {
            onLog("ℹ️ Найден split-набор с base APK")
            return sortApksForInstall(splitSet)
        }

        val xapkSet = apks.filter { isBaseApkLike(it) || isConfigSplitLike(it) }
        if (xapkSet.any { isBaseApkLike(it) }) {
            onLog("ℹ️ Найден XAPK/APK-набор с base/config split")
            return sortApksForInstall(xapkSet)
        }

        val standalone = apks.filter {
            val path = it.entryName.replace('\\', '/').lowercase()
            path.startsWith("standalones/") || path.contains("/standalones/")
        }
        if (standalone.size == 1) {
            onLog("ℹ️ Найден один standalone APK")
            return standalone
        }
        if (standalone.size > 1) {
            onLog("⚠️ В контейнере несколько standalone APK. Автоматически выбран первый; для точного выбора распакуйте архив и установите нужный APK вручную.")
            return listOf(standalone.first())
        }

        if (apks.size > 1) {
            onLog("⚠️ Не удалось уверенно определить base/split структуру. Пробуем установить все APK из контейнера.")
        }
        return sortApksForInstall(apks)
    }

    private fun sortApksForInstall(apks: List<ExtractedArchiveApk>): List<ExtractedArchiveApk> =
        apks.sortedWith(
            compareBy<ExtractedArchiveApk> { archiveApkInstallRank(it) }
                .thenBy { it.entryName.lowercase() }
        )

    private fun archiveApkInstallRank(item: ExtractedArchiveApk): Int = when {
        isPrimaryBaseApkLike(item) -> 0
        isBaseApkLike(item) -> 1
        isConfigSplitLike(item) -> 2
        else -> 3
    }

    private fun isPrimaryBaseApkLike(item: ExtractedArchiveApk): Boolean {
        val name = item.entryName.substringAfterLast('/').lowercase()
        return name == "base.apk" || name == "base_master.apk" || name == "base-master.apk"
    }

    private fun isBaseApkLike(item: ExtractedArchiveApk): Boolean {
        val name = item.entryName.substringAfterLast('/').lowercase()
        return isPrimaryBaseApkLike(item) || name.startsWith("base-")
    }

    private fun isConfigSplitLike(item: ExtractedArchiveApk): Boolean {
        val name = item.entryName.substringAfterLast('/').lowercase()
        return name.startsWith("split_config.") || name.startsWith("config.") || name.contains("split_config")
    }

    private fun isSafeArchiveEntryName(name: String): Boolean {
        if (name.startsWith("/") || name.startsWith("../") || name.contains("/../")) return false
        if (name.contains('\u0000')) return false
        return true
    }

    private fun deleteRecursivelySafe(file: File) {
        try {
            if (file.exists()) file.deleteRecursively()
        } catch (_: Exception) {}
    }

    fun installMultipleApks(apkFiles: List<File>, options: List<String>): Boolean {
        if (rejectMutation("adb install-multiple")) return false
        if (!isConnected) return false
        cancelled = false

        val files = apkFiles.distinctBy { it.absolutePath }
        if (files.size < 2) {
            onLog("❌ adb install-multiple: нужно минимум 2 APK-файла")
            return false
        }
        files.forEach { file ->
            if (!file.exists() || !file.isFile || !file.canRead()) {
                onLog("❌ adb install-multiple: APK недоступен: ${file.absolutePath}")
                return false
            }
        }

        val installOptions = options.filter { it.isNotBlank() }
        val stamp = System.currentTimeMillis()
        val remoteFiles = files.mapIndexed { index, file ->
            val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            file to "/data/local/tmp/aft-session-$stamp-$index-$safeName"
        }

        onLog("-> adb install-multiple ${installOptions.joinToString(" ")} ${files.joinToString(" ") { it.name }}".trim())
        onLog("ℹ️ Split APK: ${files.size} файлов. Используется package-manager session API.")

        var sessionId: String? = null
        try {
            remoteFiles.forEach { (file, remotePath) ->
                if (!pushFile(file, remotePath, 0x1A4)) return false
            }

            val optionText = installOptions.joinToString(" ") { shellQuote(it) }
            val hasSessionSizeOption = installOptions.any { it == "-S" || it == "--size" }
            val totalSize = files.sumOf { it.length() }
            val createCommand = buildString {
                append("pm install-create")
                if (!hasSessionSizeOption) append(" -S ").append(totalSize)
                if (optionText.isNotBlank()) append(' ').append(optionText)
            }
            val createResult = runShellCommandForResult(createCommand)
            if (!createResult.success) {
                onLog("❌ install-multiple: pm install-create завершился ошибкой")
                return false
            }

            sessionId = parseInstallSessionId(createResult.combinedOutput())
            if (sessionId.isNullOrBlank()) {
                onLog("❌ install-multiple: не удалось определить session id из вывода pm install-create")
                return false
            }
            onLog("ℹ️ install session: $sessionId")

            remoteFiles.forEachIndexed { index, (file, remotePath) ->
                if (cancelled) return false
                val splitName = buildSplitName(index, file)
                val writeCommand = "pm install-write -S ${file.length()} $sessionId ${shellQuote(splitName)} ${shellQuote(remotePath)}"
                onLog("ℹ️ install-write ${index + 1}/${remoteFiles.size}: $splitName")
                val writeResult = runShellCommandForResult(writeCommand)
                if (!writeResult.success) {
                    onLog("❌ install-multiple: ошибка install-write для ${file.name}")
                    abandonInstallSession(sessionId)
                    return false
                }
            }

            val commitResult = runShellCommandForResult("pm install-commit $sessionId")
            return if (commitResult.success) {
                onLog("✅ adb install-multiple завершён")
                true
            } else {
                onLog("❌ install-multiple: pm install-commit завершился ошибкой")
                abandonInstallSession(sessionId)
                false
            }
        } catch (e: Exception) {
            onLog("❌ adb install-multiple ошибка: ${e.message ?: e.javaClass.simpleName}")
            sessionId?.let { abandonInstallSession(it) }
            return false
        } finally {
            val cleanup = remoteFiles.joinToString(" ") { (_, remotePath) -> shellQuote(remotePath) }
            if (cleanup.isNotBlank() && isConnected && !cancelled) {
                runShellCommandForResult("rm -f $cleanup", logOutput = false)
            }
        }
    }

    private fun abandonInstallSession(sessionId: String) {
        if (sessionId.isBlank()) return
        onLog("ℹ️ Отменяем install session $sessionId")
        runShellCommandForResult("pm install-abandon $sessionId", logOutput = false)
    }

    private fun parseInstallSessionId(output: String): String? {
        val bracketMatch = Regex("\\[(\\d+)]").find(output)
        if (bracketMatch != null) return bracketMatch.groupValues[1]
        return Regex("(?i)session\\s+(\\d+)").find(output)?.groupValues?.getOrNull(1)
    }

    private fun buildSplitName(index: Int, file: File): String {
        val clean = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val withoutExtractPrefix = clean.replace(Regex("^\\d{3}-"), "")
        val lower = withoutExtractPrefix.lowercase()
        return if (index == 0 && (lower.startsWith("base") || lower == "base_master.apk" || lower == "base-master.apk")) {
            "base.apk"
        } else {
            withoutExtractPrefix.ifBlank { clean }
        }
    }

    fun runShellCommand(command: String, forceLegacy: Boolean = false): Boolean {
        if (!isConnected) return false
        cancelled = false

        val cleanCommand = command.trim()
        if (cleanCommand.isBlank()) {
            if (rejectMutation("interactive adb shell")) return false
            return startInteractiveShell()
        }
        if (!allowShellInCurrentMode(cleanCommand, "adb shell: $cleanCommand")) return false

        return runShellCommandForResult(cleanCommand, logOutput = true, forceLegacy = forceLegacy).success
    }

    fun sendInteractiveShellInput(line: String): Boolean {
        if (rejectMutation("interactive adb shell input")) return false
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        val accepted = queueInteractiveShellBytes(payload)
        if (accepted) onLog("adb-shell$ ${line.trimEnd()}")
        return accepted
    }

    fun sendInteractiveShellInterrupt(): Boolean {
        // Ctrl+C is allowed under READ-ONLY so an already-open shell can be stopped safely.
        val accepted = queueInteractiveShellBytes(byteArrayOf(0x03))
        if (accepted) onLog("adb-shell: SIGINT / Ctrl+C")
        return accepted
    }

    fun sendInteractiveShellEof(): Boolean {
        // EOF is allowed under READ-ONLY so an already-open shell can be closed safely.
        val accepted = queueInteractiveShellBytes(byteArrayOf(0x04))
        if (accepted) onLog("adb-shell: EOF / Ctrl+D")
        return accepted
    }

    private fun queueInteractiveShellBytes(payload: ByteArray): Boolean {
        return synchronized(interactiveShellLock) {
            val session = interactiveShellSession ?: return false
            session.stdinQueue.add(payload)
            true
        }
    }

    fun stopInteractiveShell(): Boolean {
        synchronized(interactiveShellLock) {
            val session = interactiveShellSession ?: return false
            session.stopRequested = true
            session.stdinQueue.add("exit\n".toByteArray(Charsets.UTF_8))
        }
        onLog("⏹ Запрошено закрытие интерактивного adb shell")
        return true
    }

    private fun startInteractiveShell(): Boolean {
        if (!isConnected) return false
        cancelled = false

        synchronized(interactiveShellLock) {
            if (interactiveShellSession != null) {
                onLog("ℹ️ Интерактивный adb shell уже открыт. Вводите команды без префикса adb shell.")
                return true
            }
            interactiveShellSession = InteractiveShellSession()
        }

        val useShellV2 = supportsShellV2
        val service = if (useShellV2) "shell,v2,pty:" else "shell:"
        onLog("=== ADB INTERACTIVE SHELL START ===")
        onLog("-> adb open: $service")
        onLog("ℹ️ Вводите команды в нижнюю строку. Для выхода: exit, adb shell-stop или кнопка Стоп.")
        onLog("ℹ️ Прерывание процесса: :ctrl-c, :interrupt или adb shell-ctrl-c. EOF: :ctrl-d.")

        val stream = openAdbStream(service, logOpen = false) ?: run {
            clearInteractiveShellSession()
            return false
        }

        return try {
            if (useShellV2) runInteractiveShellV2(stream) else runInteractiveLegacyShell(stream)
        } catch (e: Exception) {
            onLog("❌ interactive adb shell ошибка: ${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            closeAdbStream(stream)
            clearInteractiveShellSession()
            onLog("=== ADB INTERACTIVE SHELL CLOSED ===")
        }
    }

    private fun runInteractiveShellV2(stream: AdbStream): Boolean {
        var exitCode: Int? = null
        while (!cancelled && !stream.closed) {
            drainInteractiveShellInputV2(stream)
            consumeInteractiveShellV2Packets(stream) { code -> exitCode = code }
            if (exitCode != null) break

            val header = readHeader(timeoutMs = 250)
            if (header == null) continue

            when (header.command) {
                A_WRTE -> {
                    val data = readData(header.dataLength) ?: return false
                    sendMessageInternal(A_OKAY, stream.localId, header.arg0, ByteArray(0))
                    if (data.isNotEmpty()) stream.pending.add(data)
                    consumeInteractiveShellV2Packets(stream) { code -> exitCode = code }
                    if (exitCode != null) break
                }
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.remoteId = header.arg0
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.closed = true
                    break
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ interactive shell/v2: неожиданный ADB packet cmd=0x${header.command.toString(16)}")
                }
            }
        }

        exitCode?.let { onLog("=== ADB INTERACTIVE SHELL EXIT: $it ===") }
        return !cancelled
    }

    private fun drainInteractiveShellInputV2(stream: AdbStream) {
        while (!cancelled && !stream.closed) {
            val payload = pollInteractiveShellInput() ?: break
            if (!writeShellPacket(stream, SHELL_ID_STDIN, payload)) {
                stream.closed = true
                break
            }
            consumeInteractiveShellV2Packets(stream) { code ->
                onLog("=== ADB INTERACTIVE SHELL EXIT: $code ===")
                stream.closed = true
            }
        }
        if (shouldCloseInteractiveShell() && !isInteractiveShellCloseStdinSent()) {
            if (writeShellPacket(stream, SHELL_ID_CLOSE_STDIN, ByteArray(0))) {
                markInteractiveShellCloseStdinSent()
            }
        }
    }

    private fun consumeInteractiveShellV2Packets(stream: AdbStream, onExit: (Int) -> Unit) {
        while (pendingByteCount(stream) >= SHELL_PACKET_HEADER) {
            val headerRaw = peekPendingExact(stream, SHELL_PACKET_HEADER) ?: return
            val id = headerRaw[0].toInt() and 0xFF
            val length = ByteBuffer.wrap(headerRaw, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || length > MAX_PAYLOAD) {
                onLog("❌ interactive shell_v2: некорректная длина packet=$length")
                stream.closed = true
                return
            }
            if (pendingByteCount(stream) < SHELL_PACKET_HEADER + length) return
            readPendingExact(stream, SHELL_PACKET_HEADER)
            val payload = readPendingExact(stream, length) ?: ByteArray(0)

            when (id) {
                SHELL_ID_STDOUT -> logShellOutput(payload, isStderr = false)
                SHELL_ID_STDERR -> logShellOutput(payload, isStderr = true)
                SHELL_ID_EXIT -> {
                    val code = payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                    onExit(code)
                    stream.closed = true
                    return
                }
                SHELL_ID_STDIN, SHELL_ID_CLOSE_STDIN -> Unit
                else -> onLog("⚠️ interactive shell_v2: неизвестный packet id=$id, length=$length")
            }
        }
    }

    private fun runInteractiveLegacyShell(stream: AdbStream): Boolean {
        onLog("ℹ️ legacy shell: stdout/stderr и exit-code не разделяются")
        while (!cancelled && !stream.closed) {
            drainInteractiveLegacyOutput(stream)
            drainInteractiveLegacyInput(stream)
            drainInteractiveLegacyOutput(stream)

            val header = readHeader(timeoutMs = 250)
            if (header == null) continue

            when (header.command) {
                A_WRTE -> {
                    stream.remoteId = header.arg0
                    val data = readData(header.dataLength) ?: return false
                    sendMessageInternal(A_OKAY, stream.localId, stream.remoteId, ByteArray(0))
                    logServiceOutput(data)
                }
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.remoteId = header.arg0
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.closed = true
                    break
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ interactive legacy shell: неожиданный ADB packet cmd=0x${header.command.toString(16)}")
                }
            }
        }
        return !cancelled
    }

    private fun drainInteractiveLegacyInput(stream: AdbStream) {
        while (!cancelled && !stream.closed) {
            val payload = pollInteractiveShellInput() ?: break
            if (!writeAdbStream(stream, payload)) {
                stream.closed = true
                break
            }
        }
    }

    private fun drainInteractiveLegacyOutput(stream: AdbStream) {
        while (stream.pending.isNotEmpty()) {
            val remaining = pendingByteCount(stream)
            val data = readPendingExact(stream, remaining) ?: return
            logServiceOutput(data)
        }
    }

    private fun pollInteractiveShellInput(): ByteArray? = synchronized(interactiveShellLock) {
        val session = interactiveShellSession ?: return@synchronized null
        if (session.stdinQueue.isEmpty()) null else session.stdinQueue.removeFirst()
    }

    private fun shouldCloseInteractiveShell(): Boolean = synchronized(interactiveShellLock) {
        interactiveShellSession?.stopRequested == true
    }

    private fun isInteractiveShellCloseStdinSent(): Boolean = synchronized(interactiveShellLock) {
        interactiveShellSession?.closeStdinSent == true
    }

    private fun markInteractiveShellCloseStdinSent() {
        synchronized(interactiveShellLock) { interactiveShellSession?.closeStdinSent = true }
    }

    private fun clearInteractiveShellSession() {
        synchronized(interactiveShellLock) { interactiveShellSession = null }
    }

    data class CapturedShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val success: Boolean
    ) {
        fun combinedOutput(): String = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun captureShellCommand(command: String): CapturedShellResult {
        if (!isConnected) return CapturedShellResult("", "", null, false)
        cancelled = false
        val result = runShellCommandForResult(command, logOutput = false)
        return CapturedShellResult(result.stdout, result.stderr, result.exitCode, result.success)
    }

    fun inspectRecoveryInstallResult(): RecoveryInstallVerifier.Result {
        if (!isConnected || peerMode != PeerMode.RECOVERY) {
            return RecoveryInstallVerifier.Result(
                verdict = RecoveryInstallVerifier.Verdict.UNKNOWN,
                message = "ADB Recovery ещё не готово для проверки результата установки"
            )
        }

        val sources = mutableListOf<RecoveryInstallVerifier.LogSource>()
        RECOVERY_INSTALL_LOG_PATHS.forEach { path ->
            val quoted = shellQuote(path)
            val primary = captureShellCommand("if [ -r $quoted ]; then tail -n 1200 $quoted; fi")
            val text = primary.stdout.trim().ifBlank {
                captureShellCommand("if [ -r $quoted ]; then cat $quoted; fi").stdout.trim()
            }
            if (text.isNotBlank()) {
                val boundedText = text.takeLast(MAX_RECOVERY_INSTALL_LOG_CHARS)
                onLog("ℹ️ Получен Recovery-лог: $path (${boundedText.length} символов)")
                sources += RecoveryInstallVerifier.LogSource(path, boundedText)
            }
        }

        return RecoveryInstallVerifier.evaluate(sources)
    }

    private data class ShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val success: Boolean
    ) {
        fun combinedOutput(): String = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun runShellCommandForResult(
        command: String,
        logOutput: Boolean = true,
        forceLegacy: Boolean = false
    ): ShellResult {
        val cleanCommand = command.trim()
        if (!allowShellInCurrentMode(cleanCommand, "adb shell capture: $cleanCommand")) {
            return ShellResult("", "READ-ONLY lock", null, false)
        }
        return if (!forceLegacy && supportsShellV2) {
            runShellV2ForResult(cleanCommand, logOutput)
        } else {
            if (logOutput) onLog("ℹ️ adb shell legacy: exit-code недоступен на этом устройстве/режиме")
            runLegacyShellForResult(cleanCommand, logOutput)
        }
    }

    private fun runShellV2ForResult(command: String, logOutput: Boolean): ShellResult {
        val service = "shell,v2,raw:$command"
        if (logOutput) onLog("-> adb shell/v2: $command")
        val stream = openAdbStream(service, logOpen = false) ?: run {
            if (logOutput) onLog("⚠️ shell_v2 открыть не удалось, пробуем legacy shell")
            return runShellCommandForResult(command, logOutput = logOutput, forceLegacy = true)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null
        try {
            // Непосредственно как в shell protocol: закрываем stdin для one-shot команд.
            if (!writeShellPacket(stream, SHELL_ID_CLOSE_STDIN, ByteArray(0))) {
                return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
            }

            while (!cancelled && !stream.closed) {
                val header = readShellPacketHeader(stream) ?: break
                when (header.id) {
                    SHELL_ID_STDOUT -> {
                        val data = readAdbStreamExact(stream, header.length)
                            ?: return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
                        val text = data.toShellText()
                        stdout.append(text)
                        if (logOutput) logShellOutput(data, isStderr = false)
                    }
                    SHELL_ID_STDERR -> {
                        val data = readAdbStreamExact(stream, header.length)
                            ?: return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
                        val text = data.toShellText()
                        stderr.append(text)
                        if (logOutput) logShellOutput(data, isStderr = true)
                    }
                    SHELL_ID_EXIT -> {
                        val data = readAdbStreamExact(stream, header.length) ?: ByteArray(0)
                        exitCode = data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                        if (logOutput) onLog("=== ADB SHELL EXIT: $exitCode ===")
                    }
                    SHELL_ID_STDIN, SHELL_ID_CLOSE_STDIN -> {
                        if (header.length > 0 && readAdbStreamExact(stream, header.length) == null) {
                            return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
                        }
                    }
                    else -> {
                        if (header.length > 0 && readAdbStreamExact(stream, header.length) == null) {
                            return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
                        }
                        if (logOutput) onLog("⚠️ shell_v2: неизвестный packet id=${header.id}, length=${header.length}")
                    }
                }
            }
        } catch (e: Exception) {
            if (logOutput) onLog("❌ adb shell/v2 ошибка: ${e.message ?: e.javaClass.simpleName}")
            return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
        } finally {
            closeAdbStream(stream)
        }

        if (exitCode == null && logOutput) onLog("⚠️ shell_v2 завершился без exit packet")
        return ShellResult(stdout.toString(), stderr.toString(), exitCode, exitCode == 0)
    }

    private fun runLegacyShellForResult(command: String, logOutput: Boolean): ShellResult {
        if (logOutput) onLog("-> adb shell: $command")
        val stream = openAdbStream("shell:$command", logOpen = false)
            ?: return ShellResult("", "", null, false)
        val stdout = StringBuilder()
        try {
            while (!cancelled && !stream.closed) {
                val header = readHeader(timeoutMs = 30_000) ?: break
                when (header.command) {
                    A_WRTE -> {
                        stream.remoteId = header.arg0
                        val data = readData(header.dataLength) ?: return ShellResult(stdout.toString(), "", null, false)
                        sendMessageInternal(A_OKAY, stream.localId, stream.remoteId, ByteArray(0))
                        val text = data.toShellText()
                        stdout.append(text)
                        if (logOutput) logShellOutput(data, isStderr = false)
                    }
                    A_OKAY -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        stream.remoteId = header.arg0
                    }
                    A_CLSE -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        stream.closed = true
                        return ShellResult(stdout.toString(), "", null, true)
                    }
                    else -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        if (logOutput) onLog("⚠️ legacy shell: неожиданный packet cmd=0x${header.command.toString(16)}")
                    }
                }
            }
        } catch (e: Exception) {
            if (logOutput) onLog("❌ adb shell legacy ошибка: ${e.message ?: e.javaClass.simpleName}")
            return ShellResult(stdout.toString(), "", null, false)
        } finally {
            closeAdbStream(stream)
        }
        return ShellResult(stdout.toString(), "", null, !cancelled)
    }

    fun runSelfTest(): Boolean {
        if (!isConnected) {
            onLog("❌ ADB self-test: соединение не открыто")
            return false
        }
        cancelled = false
        onLog("=== ADB SELF-TEST ===")
        if (remoteBanner.isBlank()) {
            onLog("⚠️ ADB banner пустой или не был прочитан")
        } else {
            onLog("✅ ADB banner: $remoteBanner")
        }
        onLog("ADB features: ${if (deviceFeatures.isEmpty()) "—" else deviceFeatures.joinToString(",")}")
        onLog(if (supportsShellV2) "✅ shell_v2 поддерживается" else "⚠️ shell_v2 не заявлен: exit-code будет недоступен в legacy shell")
        onLog("ADB public key: ${adbKeyStore.publicKeyPath()}")

        var ok = true
        fun check(label: String, command: String, required: Boolean = true): ShellResult {
            onLog("--- $label ---")
            val result = runShellCommandForResult(command, logOutput = false)
            val out = result.combinedOutput().trim()
            if (out.isNotBlank()) {
                out.lines().take(12).forEach { onLog("│ $it") }
                if (out.lines().size > 12) onLog("│ ...")
            }
            val good = result.success || (!supportsShellV2 && result.combinedOutput().isNotBlank())
            if (good) {
                onLog("✅ $label: OK${result.exitCode?.let { " (exit=$it)" } ?: ""}")
            } else {
                if (required) ok = false
                onLog("⚠️ $label: FAIL${result.exitCode?.let { " (exit=$it)" } ?: ""}")
            }
            return result
        }

        check("shell basic", "echo AFT_SHELL_OK")
        check("device props", "getprop ro.product.device; getprop ro.build.version.release; getprop ro.build.version.sdk", required = false)
        check("identity", "id", required = false)
        check("usb state", "getprop sys.usb.state", required = false)
        val pm = check("package manager", "pm path android", required = false)
        if (!pm.success) onLog("ℹ️ В recovery package manager может быть недоступен — это нормально для sideload/recovery режима.")

        if (supportsShellV2) {
            val exitProbe = runShellCommandForResult("sh -c 'exit 7'", logOutput = false)
            if (exitProbe.exitCode == 7) {
                onLog("✅ shell_v2 exit-code probe: OK (exit=7)")
            } else {
                ok = false
                onLog("⚠️ shell_v2 exit-code probe: ожидался 7, получено ${exitProbe.exitCode ?: "нет exit packet"}")
            }
        }

        val sdcard = statRemotePath("/sdcard", logMissing = false)
        when {
            sdcard?.exists == true -> onLog("✅ sync STAT /sdcard: mode=${sdcard.mode} size=${sdcard.size}")
            else -> onLog("⚠️ sync STAT /sdcard недоступен; pull/push в пользовательскую память могут не работать в этом режиме")
        }
        val tmp = statRemotePath("/data/local/tmp", logMissing = false)
        when {
            tmp?.exists == true -> onLog("✅ sync STAT /data/local/tmp: mode=${tmp.mode} size=${tmp.size}")
            else -> onLog("⚠️ sync STAT /data/local/tmp недоступен; adb install через staging может не работать")
        }

        onLog("ℹ️ Self-test не выполнял install, push с записью, pull больших файлов, root/remount или reboot.")
        return ok
    }

    private class InteractiveShellSession {
        val stdinQueue: ArrayDeque<ByteArray> = ArrayDeque()
        var stopRequested: Boolean = false
        var closeStdinSent: Boolean = false
    }

    private data class AdbStream(
        val localId: Int,
        var remoteId: Int,
        val pending: ArrayDeque<ByteArray> = ArrayDeque(),
        var pendingOffset: Int = 0,
        var closed: Boolean = false
    )

    private data class ShellPacketHeader(val id: Int, val length: Int)

    private data class SyncHeader(val id: String, val value: Int)

    private data class SyncStat(
        val mode: Int,
        val size: Long,
        val mtime: Int,
        val isDirectory: Boolean,
        val isRegularFile: Boolean
    ) {
        val exists: Boolean get() = mode != 0
    }

    private data class ExtractedArchiveApk(
        val file: File,
        val entryName: String
    )

    private data class ExtractedArchiveObb(
        val file: File,
        val entryName: String,
        val packageNameFromPath: String?
    )

    private data class PackageArchiveContents(
        val apks: List<ExtractedArchiveApk>,
        val obbs: List<ExtractedArchiveObb>,
        val manifestPackageName: String?
    )

    private fun openAdbStream(service: String, logOpen: Boolean = true): AdbStream? {
        val localId = nextLocalId++
        if (logOpen) onLog("-> adb open: $service")
        sendMessageInternal(A_OPEN, localId, 0, "$service\u0000".toByteArray(Charsets.UTF_8))

        while (!cancelled) {
            val header = readHeader(timeoutMs = 10_000) ?: run {
                onLog("❌ ADB stream не ответил: $service")
                return null
            }
            when (header.command) {
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    return AdbStream(localId, header.arg0)
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("❌ ADB stream закрыт устройством: $service")
                    return null
                }
                A_WRTE -> {
                    val data = readData(header.dataLength)
                    sendMessageInternal(A_OKAY, localId, header.arg0, ByteArray(0))
                    if (data != null && data.isNotEmpty()) logServiceOutput(data)
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ Неожиданный ADB packet при open: cmd=0x${header.command.toString(16)}")
                }
            }
        }
        return null
    }

    private fun closeAdbStream(stream: AdbStream) {
        if (stream.closed) return
        try { sendMessageInternal(A_CLSE, stream.localId, stream.remoteId, ByteArray(0)) } catch (_: Exception) {}
        stream.closed = true
    }

    private fun writeAdbStream(stream: AdbStream, payload: ByteArray): Boolean {
        if (stream.closed) return false
        sendMessageInternal(A_WRTE, stream.localId, stream.remoteId, payload)
        while (!cancelled) {
            val header = readHeader(timeoutMs = 10_000) ?: run {
                onLog("❌ ADB stream: нет ACK на WRTE")
                return false
            }
            when (header.command) {
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.remoteId = header.arg0
                    return true
                }
                A_WRTE -> {
                    val data = readData(header.dataLength)
                    sendMessageInternal(A_OKAY, stream.localId, header.arg0, ByteArray(0))
                    if (data != null && data.isNotEmpty()) stream.pending.add(data)
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.closed = true
                    onLog("❌ ADB stream закрыт во время записи")
                    return false
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ ADB stream: неожиданный packet cmd=0x${header.command.toString(16)}")
                }
            }
        }
        return false
    }

    private fun readAdbStreamExact(stream: AdbStream, length: Int): ByteArray? {
        if (length < 0 || length > MAX_PAYLOAD * 16) return null
        val out = ByteArray(length)
        var written = 0

        while (written < length) {
            while (stream.pending.isNotEmpty() && written < length) {
                val first = stream.pending.first()
                val available = first.size - stream.pendingOffset
                val copy = minOf(available, length - written)
                System.arraycopy(first, stream.pendingOffset, out, written, copy)
                stream.pendingOffset += copy
                written += copy
                if (stream.pendingOffset >= first.size) {
                    stream.pending.removeFirst()
                    stream.pendingOffset = 0
                }
            }
            if (written >= length) break
            if (stream.closed || cancelled) return null

            val header = readHeader(timeoutMs = 30_000) ?: run {
                onLog("❌ ADB stream: таймаут чтения данных")
                return null
            }
            when (header.command) {
                A_WRTE -> {
                    val data = readData(header.dataLength) ?: return null
                    sendMessageInternal(A_OKAY, stream.localId, header.arg0, ByteArray(0))
                    if (data.isNotEmpty()) stream.pending.add(data)
                }
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.remoteId = header.arg0
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.closed = true
                    return null
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ ADB stream: неожиданный packet cmd=0x${header.command.toString(16)}")
                }
            }
        }
        return out
    }

    private fun pendingByteCount(stream: AdbStream): Int {
        var total = 0
        stream.pending.forEachIndexed { index, bytes ->
            total += if (index == 0) bytes.size - stream.pendingOffset else bytes.size
        }
        return total.coerceAtLeast(0)
    }

    private fun readPendingExact(stream: AdbStream, length: Int): ByteArray? {
        if (length < 0 || pendingByteCount(stream) < length) return null
        val out = ByteArray(length)
        var written = 0
        while (written < length) {
            val first = stream.pending.firstOrNull() ?: return null
            val available = first.size - stream.pendingOffset
            val copy = minOf(available, length - written)
            System.arraycopy(first, stream.pendingOffset, out, written, copy)
            stream.pendingOffset += copy
            written += copy
            if (stream.pendingOffset >= first.size) {
                stream.pending.removeFirst()
                stream.pendingOffset = 0
            }
        }
        return out
    }

    private fun peekPendingExact(stream: AdbStream, length: Int): ByteArray? {
        if (length < 0 || pendingByteCount(stream) < length) return null
        val out = ByteArray(length)
        var written = 0
        var first = true
        for (bytes in stream.pending) {
            val start = if (first) stream.pendingOffset else 0
            first = false
            if (start >= bytes.size) continue
            val available = bytes.size - start
            val copy = minOf(available, length - written)
            System.arraycopy(bytes, start, out, written, copy)
            written += copy
            if (written >= length) break
        }
        return if (written == length) out else null
    }

    private fun writeShellPacket(stream: AdbStream, id: Int, payload: ByteArray): Boolean {
        val packet = ByteBuffer.allocate(SHELL_PACKET_HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByte())
            putInt(payload.size)
            put(payload)
        }.array()
        return writeAdbStream(stream, packet)
    }

    private fun readShellPacketHeader(stream: AdbStream): ShellPacketHeader? {
        val raw = readAdbStreamExact(stream, SHELL_PACKET_HEADER) ?: return null
        val id = raw[0].toInt() and 0xFF
        val length = ByteBuffer.wrap(raw, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (length < 0 || length > MAX_PAYLOAD) {
            onLog("❌ shell_v2: некорректная длина packet=$length")
            return null
        }
        return ShellPacketHeader(id, length)
    }

    private fun ByteArray.toShellText(): String = String(this, Charsets.UTF_8).replace("\u0000", "")

    private fun logShellOutput(data: ByteArray, isStderr: Boolean) {
        if (data.isEmpty()) return
        val text = data.toShellText()
        if (text.isBlank()) return
        val prefix = if (isStderr) "│ stderr: " else "│ "
        text.split('\n').forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isNotEmpty()) onLog(prefix + line)
        }
    }

    private fun writeSyncRequest(stream: AdbStream, id: String, payload: ByteArray): Boolean {
        val packet = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByteArray(Charsets.US_ASCII))
            putInt(payload.size)
            put(payload)
        }.array()
        return writeAdbStream(stream, packet)
    }

    private fun writeSyncIdAndInt(stream: AdbStream, id: String, value: Int): Boolean {
        val packet = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByteArray(Charsets.US_ASCII))
            putInt(value)
        }.array()
        return writeAdbStream(stream, packet)
    }

    private fun writeSyncData(stream: AdbStream, id: String, data: ByteArray, length: Int): Boolean {
        val packet = ByteBuffer.allocate(8 + length).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByteArray(Charsets.US_ASCII))
            putInt(length)
            put(data, 0, length)
        }.array()
        return writeAdbStream(stream, packet)
    }

    private fun readSyncHeader(stream: AdbStream): SyncHeader? {
        val raw = readAdbStreamExact(stream, 8) ?: return null
        val id = String(raw, 0, 4, Charsets.US_ASCII)
        val value = ByteBuffer.wrap(raw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return SyncHeader(id, value)
    }

    private fun readSyncStatus(stream: AdbStream, opName: String): Boolean {
        val header = readSyncHeader(stream) ?: return false
        return when (header.id) {
            "OKAY" -> {
                if (header.value > 0) readSyncString(stream, header.value)
                true
            }
            "FAIL" -> {
                val message = readSyncString(stream, header.value)
                onLog("❌ $opName FAIL: $message")
                false
            }
            else -> {
                onLog("❌ $opName: неожиданный sync id=${header.id}")
                false
            }
        }
    }

    private fun statRemotePath(remotePath: String, logMissing: Boolean = true): SyncStat? {
        val cleanRemote = remotePath.trim()
        if (!isSafeRemotePath(cleanRemote)) return null
        val stream = openAdbStream("sync:", logOpen = false) ?: return null
        try {
            if (!writeSyncRequest(stream, "STAT", cleanRemote.toByteArray(Charsets.UTF_8))) return null
            val stat = readSyncStatResponse(stream, "adb stat") ?: return null
            if (!stat.exists && logMissing) {
                onLog("❌ adb stat: remote path не найден: $cleanRemote")
            } else if (stat.exists && logMissing) {
                val kind = when {
                    stat.isDirectory -> "каталог"
                    stat.isRegularFile -> "файл"
                    else -> "объект"
                }
                onLog("ℹ️ adb stat: $kind, size=${stat.size}, mode=0${stat.mode.toString(8)}")
            }
            return stat
        } catch (e: Exception) {
            if (logMissing) onLog("❌ adb stat ошибка: ${e.message ?: e.javaClass.simpleName}")
            return null
        } finally {
            closeAdbStream(stream)
        }
    }

    private fun readSyncStatResponse(stream: AdbStream, opName: String): SyncStat? {
        val idRaw = readAdbStreamExact(stream, 4) ?: return null
        val id = String(idRaw, Charsets.US_ASCII)
        return when (id) {
            "STAT" -> {
                val body = readAdbStreamExact(stream, 12) ?: return null
                val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val mode = bb.int
                val size = bb.int.toLong() and 0xFFFFFFFFL
                val mtime = bb.int
                val exists = mode != 0
                val type = mode and SYNC_MODE_IFMT
                SyncStat(
                    mode = mode,
                    size = size,
                    mtime = mtime,
                    isDirectory = exists && type == SYNC_MODE_IFDIR,
                    isRegularFile = exists && type == SYNC_MODE_IFREG
                )
            }
            "FAIL" -> {
                val lenRaw = readAdbStreamExact(stream, 4) ?: return null
                val length = ByteBuffer.wrap(lenRaw).order(ByteOrder.LITTLE_ENDIAN).int
                val message = readSyncString(stream, length)
                onLog("❌ $opName FAIL: $message")
                null
            }
            else -> {
                onLog("❌ $opName: неожиданный sync id=$id")
                null
            }
        }
    }

    private fun ensureRemoteDirectory(remoteDir: String): Boolean {
        if (!isSafeRemotePath(remoteDir)) return false
        val result = runShellCommandForResult("mkdir -p ${shellQuote(remoteDir)}", logOutput = false)
        if (!result.success) {
            onLog("❌ Не удалось создать remote-папку: $remoteDir")
            return false
        }
        return true
    }

    private fun parseFindSections(output: String): Pair<List<String>, List<String>> {
        val dirs = mutableListOf<String>()
        val files = mutableListOf<String>()
        var section = ""
        output.lines().forEach { raw ->
            val line = raw.trimEnd('\r')
            when (line) {
                "AFT_DIRS_BEGIN" -> section = "dirs"
                "AFT_FILES_BEGIN" -> section = "files"
                else -> {
                    if (line.isBlank()) return@forEach
                    when (section) {
                        "dirs" -> dirs.add(line)
                        "files" -> files.add(line)
                    }
                }
            }
        }
        return dirs.distinct() to files.distinct()
    }

    private fun normalizeRelativeRemotePath(path: String): String {
        return path.replace('\\', '/')
            .removePrefix("./")
            .trim('/')
    }

    private fun joinRemotePath(base: String, child: String): String {
        val left = base.trimEnd('/')
        val right = child.replace('\\', '/').trimStart('/')
        return when {
            left.isBlank() -> "/$right"
            right.isBlank() -> left
            else -> "$left/$right"
        }
    }

    private fun readSyncString(stream: AdbStream, length: Int): String {
        if (length <= 0) return ""
        if (length > SYNC_MAX_STRING) return "message too long ($length bytes)"
        return readAdbStreamExact(stream, length)?.toString(Charsets.UTF_8).orEmpty()
    }

    private fun isSafeRemotePath(path: String): Boolean {
        return path.isNotBlank() && !path.contains('\u0000')
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun runService(service: String): Boolean {
        if (!isConnected) return false
        cancelled = false

        val normalizedService = service.trim()
        if (readOnlyMutationLock) {
            if (!AdbReadOnlyPolicy.isServiceReadOnly(normalizedService)) {
                rejectMutation("adb service: $normalizedService")
                return false
            }
        }
        if (normalizedService.isBlank()) {
            onLog("❌ ОШИБКА: пустой ADB service")
            return false
        }

        val localId = nextLocalId++
        var remoteId = 0
        var opened = false
        var idleTimeouts = 0

        onLog("-> adb service: $normalizedService")
        try {
            sendMessageInternal(
                A_OPEN,
                localId,
                0,
                "$normalizedService\u0000".toByteArray(Charsets.UTF_8)
            )

            while (!cancelled) {
                val header = readHeader(timeoutMs = 10_000)
                if (header == null) {
                    // bulkTransfer() возвращает <=0 одинаково и при настоящем таймауте,
                    // и при мгновенном обрыве USB (например, устройство уже ушло с шины
                    // после reboot:sideload/reboot:fastboot) — раньше оба случая писали
                    // одно и то же "30 сек", хотя на деле проходило 1-3 секунды.
                    if (!isConnected) {
                        onLog("ℹ️ Соединение с устройством прервалось (вероятно, устройство перезагружается) — команда, скорее всего, выполнена.")
                        return true
                    }
                    if (!opened) {
                        onLog("❌ ОШИБКА: ADB service не ответил")
                        return false
                    }
                    idleTimeouts++
                    if (idleTimeouts >= 3) {
                        onLog("ℹ️ ADB service перестал присылать данные; после $idleTimeouts циклов ожидания операция завершена. Для reboot-команды устройство могло уже начать перезагрузку.")
                        return true
                    }
                    continue
                }
                idleTimeouts = 0

                when (header.command) {
                    A_OKAY -> {
                        remoteId = header.arg0
                        opened = true
                        if (header.dataLength > 0) readData(header.dataLength)
                    }
                    A_WRTE -> {
                        remoteId = header.arg0
                        val data = readData(header.dataLength)
                        sendMessageInternal(A_OKAY, localId, remoteId, ByteArray(0))
                        logServiceOutput(data)
                    }
                    A_CLSE -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        if (remoteId != 0) sendMessageInternal(A_CLSE, localId, remoteId, ByteArray(0))
                        onLog("=== ADB SERVICE CLOSED ===")
                        return true
                    }
                    else -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        onLog("⚠️ Неожиданный ADB packet: cmd=0x${header.command.toString(16)}")
                    }
                }
            }
        } catch (e: Exception) {
            onLog("ОШИБКА ADB service: ${e.message ?: e.javaClass.simpleName}")
            return false
        }

        onLog("⚠️ ADB service отменён пользователем")
        return false
    }

    private fun logServiceOutput(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        val text = String(data, Charsets.UTF_8).replace("\u0000", "")
        if (text.isBlank()) return
        text.split('\n').forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isNotEmpty()) onLog("│ $line")
        }
    }

    fun cancel() {
        cancelled = true
        synchronized(interactiveShellLock) {
            interactiveShellSession?.stopRequested = true
        }
    }

    // ─── ВНУТРЕННИЕ МЕТОДЫ ───────────────────────────────────────────────────

    private data class AdbHeader(
        val command: Long,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val checksum: Int,
        val magic: Int
    )

    private fun sendMessageInternal(command: Long, arg0: Int, arg1: Int, data: ByteArray) = synchronized(adbWriteLock) {
        val checksum = AdbPacketChecksum.compute(data)

        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command.toInt())
            putInt(arg0)
            putInt(arg1)
            putInt(data.size)
            putInt(checksum)
            putInt(command.inv().toInt())
        }.array()

        if (!safeBulkWrite(header)) throw Exception("Ошибка передачи заголовка ADB")

        if (data.isNotEmpty()) {
            var offset = 0
            val chunkSize = 16384
            while (offset < data.size) {
                val len = minOf(chunkSize, data.size - offset)
                if (!safeBulkWrite(data, offset, len)) throw Exception("Ошибка передачи данных ADB")
                offset += len
            }
        }
    }

    private fun safeBulkWrite(data: ByteArray, offset: Int = 0, length: Int = data.size, timeout: Int = 5000): Boolean {
        val conn = connection ?: return false
        val ep = endpointOut ?: return false
        var written = 0
        while (written < length) {
            val sent = conn.bulkTransfer(ep, data, offset + written, length - written, timeout)
            if (sent <= 0) {
                // См. аналогичный комментарий в FastbootProtocol.bulkWrite(): без
                // сброса halted-состояния эндпоинта все последующие передачи на нём
                // (даже маленькие) продолжат проваливаться после одного сбоя.
                clearEndpointHalt(ep)
                return false
            }
            written += sent
        }
        return true
    }

    /**
     * Сброс halted-состояния bulk-эндпоинта через стандартный USB-запрос
     * CLEAR_FEATURE(ENDPOINT_HALT). В Android USB Host API нет метода clearStall,
     * поэтому шлём вручную через controlTransfer на endpoint 0:
     *   bmRequestType=0x02 (standard, host→device, recipient=endpoint),
     *   bRequest=0x01 (CLEAR_FEATURE), wValue=0x00 (ENDPOINT_HALT),
     *   wIndex=адрес эндпоинта.
     */
    private fun clearEndpointHalt(endpoint: UsbEndpoint) {
        val conn = connection ?: return
        runCatching {
            conn.controlTransfer(0x02, 0x01, 0x00, endpoint.address, null, 0, 500)
        }
    }

    private fun startPacketDispatcher() {
        if (packetDispatcher?.snapshot()?.running == true) return
        val generation = dispatcherGeneration.incrementAndGet()
        dispatcherTransportFailed = false
        lastDispatcherSnapshot = null
        lateinit var dispatcher: AdbPacketDispatcher
        dispatcher = AdbPacketDispatcher(
            source = { timeoutMs -> readCompletePacketDirect(timeoutMs) },
            onFailure = { code, message ->
                if (dispatcherGeneration.get() == generation) {
                    lastDispatcherSnapshot = dispatcher.snapshot()
                    dispatcherTransportFailed = true
                    onLog("❌ ADB reader stopped [${code.name}]: $message")
                    cancelled = true
                    runCatching { connection?.close() }
                    connection = null
                    onTransportFailure?.invoke(code, message)
                }
            }
        )
        packetDispatcher = dispatcher
        dispatchedPayloadPacket = null
        if (dispatcher.start()) {
            onLog("✅ ADB single-reader dispatcher запущен (bounded queue=256)")
        }
    }

    private fun stopPacketDispatcher(reason: String) {
        val dispatcher = packetDispatcher ?: return
        dispatcherGeneration.incrementAndGet()
        dispatcher.stop()
        lastDispatcherSnapshot = dispatcher.snapshot()
        packetDispatcher = null
        dispatchedPayloadPacket = null
        onLog("ℹ️ ADB dispatcher остановлен: $reason")
    }

    private fun readCompletePacketDirect(timeoutMs: Int): AdbPacketDispatcher.ReadResult {
        if (connection == null || endpointIn == null) return AdbPacketDispatcher.ReadResult.Closed
        directReadFailureCode = null
        directReadFailureMessage = null
        val header = readHeaderDirect(timeoutMs) ?: return directReadFailureCode?.let { code ->
            AdbPacketDispatcher.ReadResult.Failed(code, directReadFailureMessage ?: "ADB header read failed")
        } ?: if (connection == null) {
            AdbPacketDispatcher.ReadResult.Closed
        } else {
            AdbPacketDispatcher.ReadResult.Timeout
        }
        val payload = if (header.dataLength > 0) {
            readDataDirect(header.dataLength) ?: return AdbPacketDispatcher.ReadResult.Failed(
                AdbPacketDispatcher.FailureCode.INVALID_PAYLOAD,
                "ADB payload read/validation failed for cmd=0x${header.command.toString(16)}, bytes=${header.dataLength}"
            )
        } else ByteArray(0)
        return AdbPacketDispatcher.ReadResult.PacketReady(
            AdbPacketDispatcher.Packet(
                command = header.command,
                arg0 = header.arg0,
                arg1 = header.arg1,
                checksum = header.checksum,
                magic = header.magic,
                payload = payload
            )
        )
    }

    private fun readHeader(timeoutMs: Int = 10000): AdbHeader? {
        val dispatcher = packetDispatcher
        if (dispatcher == null) return readHeaderDirect(timeoutMs)
        if (dispatchedPayloadPacket != null) {
            onLog("❌ ADB dispatcher protocol misuse: previous payload was not consumed")
            return null
        }
        val packet = dispatcher.take(timeoutMs) ?: return null
        if (packet.payload.isNotEmpty()) dispatchedPayloadPacket = packet
        return AdbHeader(
            command = packet.command,
            arg0 = packet.arg0,
            arg1 = packet.arg1,
            dataLength = packet.payload.size,
            checksum = packet.checksum,
            magic = packet.magic
        )
    }

    private fun readData(length: Int): ByteArray? {
        if (packetDispatcher == null) return readDataDirect(length)
        val packet = dispatchedPayloadPacket ?: return if (length == 0) ByteArray(0) else null
        dispatchedPayloadPacket = null
        if (length != packet.payload.size) {
            onLog("❌ ADB dispatched payload length mismatch: queued=${packet.payload.size}, requested=$length")
            return null
        }
        return packet.payload
    }

    // timeoutMs — параметр для AUTH-ожидания (до 30 сек)
    private fun readHeaderDirect(timeoutMs: Int = 10000): AdbHeader? {
        val conn = connection ?: return null
        val ep = endpointIn ?: return null
        val buffer = ByteArray(24)
        var totalRead = 0

        while (totalRead < 24) {
            val temp = ByteArray(24 - totalRead)
            val read = conn.bulkTransfer(ep, temp, temp.size, timeoutMs)
            if (read <= 0) {
                if (totalRead > 0) {
                    directReadFailureCode = AdbPacketDispatcher.FailureCode.PARTIAL_HEADER_TIMEOUT
                    directReadFailureMessage =
                        "ADB header interrupted after $totalRead/24 bytes (result=$read); stream synchronization is no longer trusted"
                    onLog("❌ $directReadFailureMessage")
                }
                return null
            }
            System.arraycopy(temp, 0, buffer, totalRead, read)
            totalRead += read
        }

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val cmd   = bb.int.toLong() and 0xFFFFFFFFL
        val a0    = bb.int
        val a1    = bb.int
        val len   = bb.int
        val chk   = bb.int
        val magic = bb.int

        if (magic != cmd.inv().toInt()) {
            directReadFailureCode = AdbPacketDispatcher.FailureCode.INVALID_HEADER
            directReadFailureMessage = "ADB header magic mismatch: cmd=0x${cmd.toString(16)}, magic=0x${magic.toUInt().toString(16)}"
            onLog("❌ $directReadFailureMessage")
            return null
        }
        if (len < 0 || len > MAX_PAYLOAD) {
            directReadFailureCode = AdbPacketDispatcher.FailureCode.INVALID_HEADER
            directReadFailureMessage = "ADB header payload length out of range: $len"
            onLog("❌ $directReadFailureMessage")
            return null
        }

        if (len == 0) {
            if (!AdbPacketChecksum.isValid(
                    chk,
                    ByteArray(0),
                    LOCAL_ADB_VERSION,
                    peerProtocolVersion
                )
            ) {
                directReadFailureCode = AdbPacketDispatcher.FailureCode.CHECKSUM_MISMATCH
                directReadFailureMessage = "ADB empty-payload checksum mismatch for cmd=0x${cmd.toString(16)}: expected=$chk, actual=0"
                onLog("❌ $directReadFailureMessage")
                return null
            }
            pendingInboundChecksum = null
            pendingInboundLength = 0
            pendingInboundCommand = 0L
        } else {
            pendingInboundChecksum = chk
            pendingInboundLength = len
            pendingInboundCommand = cmd
        }
        return AdbHeader(cmd, a0, a1, len, chk, magic)
    }

    private fun readDataDirect(length: Int): ByteArray? {
        val conn = connection ?: return null
        val ep = endpointIn ?: return null
        if (length < 0 || length > MAX_PAYLOAD) return null

        val expectedChecksum = pendingInboundChecksum
        val expectedLength = pendingInboundLength
        val command = pendingInboundCommand
        pendingInboundChecksum = null
        pendingInboundLength = 0
        pendingInboundCommand = 0L

        if (length != expectedLength) {
            directReadFailureCode = AdbPacketDispatcher.FailureCode.INVALID_PAYLOAD
            directReadFailureMessage = "ADB payload length mismatch: header=$expectedLength, requested=$length"
            onLog("❌ $directReadFailureMessage")
            return null
        }
        if (length == 0) {
            val empty = ByteArray(0)
            if (expectedChecksum != null && !AdbPacketChecksum.isValid(
                    expectedChecksum,
                    empty,
                    LOCAL_ADB_VERSION,
                    peerProtocolVersion
                )
            ) {
                directReadFailureCode = AdbPacketDispatcher.FailureCode.CHECKSUM_MISMATCH
                directReadFailureMessage = "ADB payload checksum mismatch for cmd=0x${command.toString(16)}: expected=$expectedChecksum, actual=0"
                onLog("❌ $directReadFailureMessage")
                return null
            }
            return empty
        }

        val buffer = ByteArray(length)
        var totalRead = 0

        while (totalRead < length) {
            // Тот же safe-лимит 16KB на один bulkTransfer, что и в safeBulkWrite() —
            // без него один вызов мог запросить до MAX_PAYLOAD (1MB) сразу,
            // что ненадёжно на части USB host-контроллеров/ядер.
            val remaining = length - totalRead
            val temp = ByteArray(remaining.coerceAtMost(16384))
            val read = conn.bulkTransfer(ep, temp, temp.size, 5000)
            if (read <= 0) {
                directReadFailureCode = if (connection == null) AdbPacketDispatcher.FailureCode.DEVICE_DISCONNECTED else AdbPacketDispatcher.FailureCode.USB_IN_FAILED
                directReadFailureMessage = "ADB payload USB IN failed after $totalRead/$length bytes (result=$read)"
                return null
            }
            System.arraycopy(temp, 0, buffer, totalRead, read)
            totalRead += read
        }

        if (expectedChecksum != null && !AdbPacketChecksum.isValid(
                expectedChecksum,
                buffer,
                LOCAL_ADB_VERSION,
                peerProtocolVersion
            )
        ) {
            val actual = AdbPacketChecksum.compute(buffer)
            directReadFailureCode = AdbPacketDispatcher.FailureCode.CHECKSUM_MISMATCH
            directReadFailureMessage = "ADB payload checksum mismatch for cmd=0x${command.toString(16)}: expected=$expectedChecksum, actual=$actual, bytes=$length"
            onLog("❌ $directReadFailureMessage")
            return null
        }
        return buffer
    }

    fun disconnect() {
        cancelled = true
        stopPacketDispatcher("disconnect")
        adbInterface?.let { iface -> runCatching { connection?.releaseInterface(iface) } }
        runCatching { connection?.close() }
        connection   = null
        endpointIn   = null
        endpointOut  = null
        adbInterface = null
        deviceFeatures.clear()
        remoteBanner = ""
        peerProtocolVersion = LOCAL_ADB_VERSION
        pendingInboundChecksum = null
        pendingInboundLength = 0
        pendingInboundCommand = 0L
        dispatcherTransportFailed = false
        directReadFailureCode = null
        directReadFailureMessage = null
    }
}
