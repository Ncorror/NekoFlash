package ru.forum.adbfastboottool

import android.hardware.usb.*
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.zip.ZipFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

private const val EXPECTED_REBOOT_DISCONNECT_WINDOW_NS = 15_000_000_000L

/**
 * USB Host реализация ADB protocol для одной физической transport-сессии.
 *
 * Класс владеет ADB authentication, single-reader dispatcher, shell/sync и sideload.
 * Экземпляр одноразовый: после transport failure или [close] его нельзя переиспользовать.
 * Команды отправляются напрямую transport-слою; USB disconnect считается успешным только
 * для протокольно односторонних сервисов (например reboot), где это явно ожидается.
 */
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

    // ADB reboot is a one-way service: the device may leave the USB bus before
    // sending A_OKAY/A_CLSE. These fields let the single-reader dispatcher and
    // the command coroutine agree that this specific disconnect is expected.
    @Volatile private var expectedDisconnectService: String? = null
    @Volatile private var expectedDisconnectOpenWritten: Boolean = false
    @Volatile private var expectedDisconnectObserved: Boolean = false
    @Volatile private var expectedDisconnectExpiresAtNs: Long = 0L

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
    private val USB_BULK_CHUNK_BYTES = 16 * 1024
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

    private val EMPTY_PAYLOAD = ByteArray(0)
    private val outboundHeaderBuffer = ByteArray(24)
    private val inboundHeaderBuffer = ByteArray(24)

    private val MAX_RECOVERY_INSTALL_LOG_CHARS = 512 * 1024

    private val RECOVERY_INSTALL_LOG_PATHS = listOf(
        "/cache/recovery/last_install",
        "/tmp/recovery.log",
        "/cache/recovery/last_log",
        "/tmp/install.log"
    )

    private fun clearPendingInboundPayload() {
        pendingInboundChecksum = null
        pendingInboundLength = 0
        pendingInboundCommand = 0L
    }

    enum class PeerMode { DEVICE, RECOVERY, SIDELOAD, UNKNOWN }

    enum class SideloadFailureKind { FILE, TRANSPORT, PROTOCOL }

    sealed class SideloadResult {
        object TransferComplete : SideloadResult()
        data class TransferClosedBeforeDoneDone(
            val servedBytes: Long,
            val totalBytes: Long,
            val percent: Int,
            val message: String
        ) : SideloadResult()
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
        val lastReaderFailureMessage: String? = null
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
            lastReaderFailureMessage = dispatcher?.lastFailureMessage
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
        clearPendingInboundPayload()

        val iface = findAdbInterface() ?: run {
            onLog("ERROR: ADB interface not found")
            return false
        }
        adbInterface = iface

        val endpoints = findBulkEndpoints(iface)
        endpointIn = endpoints.first
        endpointOut = endpoints.second
        if (endpointIn == null || endpointOut == null) {
            onLog("ERROR: ADB bulk endpoints not found")
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
            onLog("ERROR: Could not open USB device for ADB")
            disconnect()
            return false
        }
        if (!connection!!.claimInterface(iface, true)) {
            onLog("ERROR: Could not claim ADB interface")
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
                onLog("ERROR: ADB connection failed (no response)")
                disconnect()
                return false
            }

            when (header.command) {
                A_CNXN -> {
                    handleConnectionBanner(header)
                    onLog("=== ADB CONNECTION ESTABLISHED ===")
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
                    onLog("ERROR: Unexpected ADB response (cmd=0x${header.command.toString(16)})")
                    disconnect()
                    false
                }
            }
        } catch (e: Exception) {
            onLog("ERROR: ADB connection failed: ${e.message ?: e.javaClass.simpleName}")
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
                onLog("⚠️ Selected ADB interface=$index no longer matches the expected descriptor — running a safe search")
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
            onLog("❌ Unsupported ADB AUTH type: ${firstHeader.arg0}")
            return false
        }

        val firstToken = readData(firstHeader.dataLength) ?: run {
            onLog("❌ Could not read ADB AUTH TOKEN")
            return false
        }

        onLog("🔐 ADB AUTH: device requires RSA authorization")

        var publicKeySent = false
        try {
            val signature = adbKeyStore.signToken(firstToken)
            onLog("🔑 Trying authorization with the saved ADB RSA key")
            sendMessageInternal(A_AUTH, AUTH_SIGNATURE, 0, signature)
        } catch (e: Exception) {
            onLog("⚠️ Could not sign ADB TOKEN: ${e.message ?: e.javaClass.simpleName}")
            publicKeySent = sendAdbPublicKeyForAuth()
            if (!publicKeySent) return false
        }

        repeat(12) { attempt ->
            if (cancelled) return false

            val timeout = if (publicKeySent) 60_000 else 10_000
            val header = readHeader(timeoutMs = timeout) ?: run {
                if (publicKeySent) {
                    onLog("❌ ADB RSA was not confirmed on the device within 60 seconds")
                } else {
                    onLog("❌ Device did not respond to the ADB RSA signature")
                }
                return false
            }

            when (header.command) {
                A_CNXN -> {
                    handleConnectionBanner(header)
                    onLog("✅ ADB authorized. Connection established.")
                    return true
                }

                A_AUTH -> {
                    when (header.arg0) {
                        AUTH_TOKEN -> {
                            if (header.dataLength > 0 && readData(header.dataLength) == null) {
                                onLog("❌ Could not read repeated ADB AUTH TOKEN")
                                return false
                            }

                            if (!publicKeySent) {
                                publicKeySent = sendAdbPublicKeyForAuth()
                                if (!publicKeySent) return false
                            } else if (attempt % 3 == 2) {
                                onLog("⏳ Still waiting for ADB RSA confirmation on the device...")
                            }
                        }

                        else -> {
                            if (header.dataLength > 0) readData(header.dataLength)
                            onLog("❌ Unsupported ADB AUTH type: ${header.arg0}")
                            return false
                        }
                    }
                }

                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("❌ Unexpected response during ADB AUTH: cmd=0x${header.command.toString(16)}")
                    return false
                }
            }
        }

        onLog("❌ ADB RSA authorization did not complete")
        onLog("💡 Check the device screen, USB debugging, and 'Always allow from this computer'")
        return false
    }


    private fun sendAdbPublicKeyForAuth(): Boolean {
        return try {
            val publicKeyPayload = adbKeyStore.publicKeyPayload()
            sendMessageInternal(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload)
            onLog("📤 ADB public key sent")
            onLog("⏳ Confirm the 'Allow USB debugging' prompt on the device screen")
            onLog("ℹ️ Public key saved: ${adbKeyStore.publicKeyPath()}")
            true
        } catch (e: Exception) {
            onLog("❌ Could not send ADB public key: ${e.message ?: e.javaClass.simpleName}")
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
            onLog("✅ ADB feature shell_v2 detected: exit code and stdout/stderr separation are available")
        } else {
            onLog("ℹ️ ADB feature shell_v2 is not advertised: shell will run in legacy mode without an exact exit code")
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



    fun sideloadZip(file: File): SideloadResult {
        if (!isConnected) {
            return SideloadResult.Failed(SideloadFailureKind.TRANSPORT, "No ADB connection")
        }
        if (peerMode != PeerMode.SIDELOAD) {
            onLog("ERROR: ADB Sideload is not started. Current ADB mode: ${peerMode.name}")
            onLog("💡 Open: Recovery → Apply update → Apply from ADB")
            return SideloadResult.NotInSideloadMode(peerMode)
        }

        if (!file.exists() || !file.isFile || !file.canRead()) {
            return SideloadResult.Failed(SideloadFailureKind.FILE, "File is unavailable: ${file.absolutePath}")
        }
        val fileSize = file.length()
        if (fileSize <= 0L) {
            return SideloadResult.Failed(SideloadFailureKind.FILE, "File is empty: ${file.name}")
        }

        cancelled = false
        var terminalState = SideloadTerminalState.RUNNING
        onLog("Starting ADB Sideload: ${file.name} ($fileSize bytes)")
        onProgress(0, "ADB Sideload · waiting for recovery requests")

        return try {
            sendMessageInternal(
                A_OPEN, 1, 0,
                "sideload-host:${fileSize}:$SIDELOAD_BLOCK_SIZE\u0000".toByteArray()
            )
            val openResp = readHeader()
            if (openResp == null || openResp.command != A_OKAY) {
                if (openResp != null && openResp.dataLength > 0) readData(openResp.dataLength)
                onLog("ERROR: Recovery did not confirm sideload-host OPEN.")
                return SideloadResult.Failed(
                    SideloadFailureKind.PROTOCOL,
                    "Recovery did not confirm sideload-host OPEN"
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

            fun servedPercent(): Int =
                SideloadCompletionPolicy.servedPercent(servedBytes, fileSize)

            fun closeBeforeDoneDone(kind: SideloadFailureKind, message: String): SideloadResult {
                val percent = servedPercent()
                return if (
                    SideloadCompletionPolicy.classifyCloseBeforeDoneDone(servedBytes, fileSize) ==
                    SideloadCompletionPolicy.CloseClassification.VERIFY_PENDING
                ) {
                    val detail = "$message after ≈$percent% transfer. Recovery may have already moved to the post-install/reboot flow; check the final result on the Recovery screen."
                    onProgress(100, "ADB Sideload · waiting for Recovery verification")
                    onLog("⚠️ ADB Sideload: $detail")
                    SideloadResult.TransferClosedBeforeDoneDone(
                        servedBytes = servedBytes,
                        totalBytes = fileSize,
                        percent = percent,
                        message = detail
                    )
                } else {
                    fail(kind, message)
                }
            }

            fun readStreamAck(): SideloadResult? {
                val ack = readHeader()
                    ?: return closeBeforeDoneDone(
                        SideloadFailureKind.TRANSPORT,
                        "ADB transport closed while confirming the block"
                    )
                if (ack.dataLength > 0 && readData(ack.dataLength) == null) {
                    return fail(SideloadFailureKind.TRANSPORT, "Could not read ADB response data")
                }
                return when (ack.command) {
                    A_OKAY -> null
                    A_CLSE -> closeBeforeDoneDone(
                        SideloadFailureKind.PROTOCOL,
                        "Recovery closed the sideload stream before DONEDONE"
                    )
                    else -> fail(
                        SideloadFailureKind.PROTOCOL,
                        "Unexpected ADB response after block: cmd=0x${ack.command.toString(16)}"
                    )
                }
            }

            RandomAccessFile(file, "r").use { raf ->
                sideloadLoop@ while (!cancelled && result == null) {
                    val reqHeader = readHeader()
                    if (reqHeader == null) {
                        result = closeBeforeDoneDone(
                            SideloadFailureKind.TRANSPORT,
                            "ADB transport closed before DONEDONE confirmation"
                        )
                        break@sideloadLoop
                    }

                    when (reqHeader.command) {
                        A_CLSE -> {
                            if (reqHeader.dataLength > 0) readData(reqHeader.dataLength)
                            runCatching { sendMessageInternal(A_CLSE, 1, remoteId, EMPTY_PAYLOAD) }
                            result = closeBeforeDoneDone(
                                SideloadFailureKind.PROTOCOL,
                                "Recovery closed the sideload stream before DONEDONE"
                            )
                        }

                        A_WRTE -> {
                            val reqData = readData(reqHeader.dataLength)
                            if (reqData == null) {
                                result = fail(SideloadFailureKind.TRANSPORT, "Could not read sideload block request")
                                continue@sideloadLoop
                            }

                            // ADB stream protocol requires an OKAY acknowledgement for every WRTE.
                            sendMessageInternal(A_OKAY, 1, remoteId, EMPTY_PAYLOAD)

                            val ascii = runCatching {
                                String(reqData, Charsets.US_ASCII).trimEnd('\u0000', ' ', '\r', '\n')
                            }.getOrNull().orEmpty()

                            if (ascii == "DONEDONE") {
                                terminalState = SideloadTerminalState.TRANSFER_COMPLETE
                                onProgress(100, "ADB Sideload · transfer completed")
                                onLog("✅ Recovery sent DONEDONE — sideload stream completed.")
                                onLog("ℹ️ DONEDONE does not confirm a successful ZIP install. Waiting for the return to Recovery and checking its final log.")
                                result = SideloadResult.TransferComplete
                                break@sideloadLoop
                            }

                            val blockToken = ascii.take(8).trim('\u0000', ' ')
                            val blockNum = blockToken.toIntOrNull()
                            if (blockNum == null) {
                                val hex = reqData.joinToString(" ") { "%02x".format(it) }
                                result = fail(
                                    SideloadFailureKind.PROTOCOL,
                                    "Invalid block request (${reqData.size} bytes): $hex"
                                )
                                continue@sideloadLoop
                            }

                            if (blockNum == -1) {
                                onLog("ℹ️ Recovery reported that block requests are complete; waiting for DONEDONE...")
                                sendMessageInternal(A_WRTE, 1, remoteId, EMPTY_PAYLOAD)
                                result = readStreamAck()
                                continue@sideloadLoop
                            }

                            if (blockNum < 0) {
                                result = fail(SideloadFailureKind.PROTOCOL, "Negative sideload block number: $blockNum")
                                continue@sideloadLoop
                            }

                            val offset = blockNum.toLong() * SIDELOAD_BLOCK_SIZE.toLong()
                            if (offset < 0L || offset >= fileSize) {
                                result = fail(
                                    SideloadFailureKind.PROTOCOL,
                                    "Recovery requested a block outside the ZIP: block=$blockNum offset=$offset size=$fileSize"
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
                                onLog("Sideload: ≈$displayProgress% (served $servedBytes bytes, block $blockNum)")
                                val overallProgress = displayProgress
                                onProgress(overallProgress.coerceIn(0, 99), "ADB Sideload · ≈$displayProgress%")
                                lastLoggedBucket = bucket
                            }
                        }

                        else -> {
                            if (reqHeader.dataLength > 0) readData(reqHeader.dataLength)
                            result = fail(
                                SideloadFailureKind.PROTOCOL,
                                "Unexpected ADB command in sideload stream: 0x${reqHeader.command.toString(16)}"
                            )
                        }
                    }
                }
            }

            when {
                terminalState == SideloadTerminalState.TRANSFER_COMPLETE -> SideloadResult.TransferComplete
                cancelled -> {
                    terminalState = SideloadTerminalState.CANCELLED
                    onLog("⚠️ ADB Sideload cancelled")
                    SideloadResult.Cancelled
                }
                else -> result ?: SideloadResult.Failed(
                    SideloadFailureKind.TRANSPORT,
                    "ADB Sideload finished without DONEDONE"
                )
            }
        } catch (e: Exception) {
            if (terminalState == SideloadTerminalState.TRANSFER_COMPLETE) {
                SideloadResult.TransferComplete
            } else if (cancelled) {
                terminalState = SideloadTerminalState.CANCELLED
                SideloadResult.Cancelled
            } else {
                terminalState = SideloadTerminalState.FAILED
                val message = e.message ?: e.javaClass.simpleName
                onLog("ERROR Sideload: $message")
                SideloadResult.Failed(SideloadFailureKind.TRANSPORT, message)
            }
        }
    }


    // ─── ADB SYNC / FILE TRANSFER ────────────────────────────────────────────

    fun pushPath(localPath: File, remotePath: String, mode: Int = 0x1A4): Boolean {
        if (!isConnected) return false
        cancelled = false

        return when {
            localPath.exists() && localPath.isFile && localPath.canRead() -> pushFile(localPath, remotePath, mode)
            localPath.exists() && localPath.isDirectory && localPath.canRead() -> pushDirectory(localPath, remotePath, mode)
            else -> {
                onLog("❌ adb push: local path is unavailable: ${localPath.absolutePath}")
                false
            }
        }
    }

    fun pushFile(localFile: File, remotePath: String, mode: Int = 0x1A4): Boolean {
        if (!isConnected) return false
        cancelled = false

        if (!localFile.exists() || !localFile.isFile || !localFile.canRead()) {
            onLog("❌ adb push: local file is unavailable: ${localFile.absolutePath}")
            return false
        }
        val cleanRemote = remotePath.trim()
        if (!isValidRemotePath(cleanRemote)) {
            onLog("❌ adb push: invalid remote path")
            return false
        }

        onLog("-> adb push ${localFile.name} $cleanRemote")
        onLog("Size: ${localFile.length()} bytes")

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
                        onLog("adb push: $progress% ($sentBytes/${localFile.length()} bytes)")
                        lastProgress = progress
                    }
                }
            }
            if (cancelled) {
                onLog("⚠️ adb push cancelled")
                return false
            }

            val mtime = (localFile.lastModified() / 1000L).toInt()
            if (!writeSyncIdAndInt(stream, "DONE", mtime)) return false
            val ok = readSyncStatus(stream, "adb push")
            if (ok) onLog("✅ adb push completed: $cleanRemote")
            return ok
        } catch (e: Exception) {
            onLog("❌ adb push error: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            closeAdbStream(stream)
        }
    }

    private fun pushDirectory(localDir: File, remotePath: String, mode: Int = 0x1A4): Boolean {
        if (!isConnected) return false
        cancelled = false

        if (!localDir.exists() || !localDir.isDirectory || !localDir.canRead()) {
            onLog("❌ adb push: local folder is unavailable: ${localDir.absolutePath}")
            return false
        }
        val cleanRemote = remotePath.trim()
        if (!isValidRemotePath(cleanRemote)) {
            onLog("❌ adb push: invalid remote path")
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
        onLog("ℹ️ Directory: ${directories.size} folders, ${files.size} files")

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
            onLog("⚠️ adb push directory cancelled")
            false
        } else {
            onLog("✅ adb push directory completed: $pushed files → $targetRoot")
            true
        }
    }

    fun pullFile(remotePath: String, localFile: File): Boolean {
        if (!isConnected) return false
        cancelled = false

        val cleanRemote = remotePath.trim()
        if (!isValidRemotePath(cleanRemote)) {
            onLog("❌ adb pull: invalid remote path")
            return false
        }

        val stat = statRemotePath(cleanRemote)
        if (stat == null || !stat.exists) {
            onLog("❌ adb pull: remote path not found or unavailable: $cleanRemote")
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
            onLog("❌ adb pull: could not create folder: ${parent.absolutePath}")
            return false
        }

        onLog("-> adb pull $cleanRemote ${localFile.absolutePath}")
        if (expectedSize != null && expectedSize >= 0L) {
            onLog("Remote file size: $expectedSize bytes")
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
                                onLog("❌ adb pull: invalid size DATA=${header.value}")
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
                                    onLog("adb pull: $progress% ($receivedBytes/$expectedSize bytes)")
                                }
                            } else if (receivedBytes == data.size.toLong() || receivedBytes % (1024L * 1024L) < data.size) {
                                onLog("adb pull: received $receivedBytes bytes")
                            }
                        }
                        "DONE" -> {
                            raf.fd.sync()
                            if (localFile.exists() && !localFile.delete()) {
                                onLog("❌ adb pull: could not replace file: ${localFile.absolutePath}")
                                return false
                            }
                            if (!tempFile.renameTo(localFile)) {
                                onLog("❌ adb pull: could not save file: ${localFile.absolutePath}")
                                return false
                            }
                            onLog("✅ adb pull completed: ${localFile.absolutePath} ($receivedBytes bytes)")
                            return true
                        }
                        "FAIL" -> {
                            val message = readSyncString(stream, header.value)
                            onLog("❌ adb pull FAIL: $message")
                            return false
                        }
                        else -> {
                            onLog("❌ adb pull: unexpected sync id=${header.id}")
                            return false
                        }
                    }
                }
            }

            onLog("⚠️ adb pull cancelled")
            return false
        } catch (e: Exception) {
            onLog("❌ adb pull error: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            runCatching {
                if (tempFile.exists() && !tempFile.delete()) {
                    onLog("ℹ️ adb pull: temporary file was not deleted")
                }
            }.onFailure { error ->
                onLog("ℹ️ adb pull: temporary file cleanup skipped (${error.javaClass.simpleName})")
            }
            closeAdbStream(stream)
        }
    }

    private fun pullDirectory(remoteDir: String, localDir: File): Boolean {
        if (!isConnected) return false
        cancelled = false

        if (localDir.exists() && localDir.isFile) {
            onLog("❌ adb pull: remote path is a directory, but local path is a file: ${localDir.absolutePath}")
            return false
        }
        if (!localDir.exists() && !localDir.mkdirs()) {
            onLog("❌ adb pull: could not create local folder: ${localDir.absolutePath}")
            return false
        }

        onLog("-> adb pull -r $remoteDir ${localDir.absolutePath}")
        val listCommand = "cd ${shellQuote(remoteDir)} && echo AFT_DIRS_BEGIN && find . -type d -print && echo AFT_FILES_BEGIN && find . -type f -print"
        val listResult = runShellCommandForResult(listCommand, logOutput = false)
        if (!listResult.success) {
            onLog("❌ adb pull: could not list files in the remote directory")
            return false
        }

        val sections = parseFindSections(listResult.stdout)
        val dirs = sections.first
        val files = sections.second
        onLog("ℹ️ Remote directory: ${dirs.size} folders, ${files.size} files")

        dirs.forEach { relative ->
            if (cancelled) return false
            val localSubDir = if (relative == ".") localDir else File(localDir, normalizeRelativeRemotePath(relative))
            if (!localSubDir.exists() && !localSubDir.mkdirs()) {
                onLog("❌ adb pull: could not create local folder: ${localSubDir.absolutePath}")
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
            onLog("⚠️ adb pull directory cancelled")
            false
        } else {
            onLog("✅ adb pull directory completed: $pulled files → ${localDir.absolutePath}")
            true
        }
    }

    fun installPackage(packageFile: File, options: List<String>): Boolean {
        if (!packageFile.exists() || !packageFile.isFile || !packageFile.canRead()) {
            onLog("❌ adb install: file is unavailable: ${packageFile.absolutePath}")
            return false
        }

        return when (packageFile.extension.lowercase()) {
            "apk" -> installApk(packageFile, options)
            "apks", "xapk" -> installPackageArchive(packageFile, options)
            else -> {
                onLog("⚠️ adb install: unknown extension .${packageFile.extension}. Trying as APK.")
                installApk(packageFile, options)
            }
        }
    }

    fun installApk(apkFile: File, options: List<String>): Boolean {
        if (!apkFile.exists() || !apkFile.isFile || !apkFile.canRead()) {
            onLog("❌ adb install: APK is unavailable: ${apkFile.absolutePath}")
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
        onLog("ℹ️ APK temporarily uploaded to $remotePath")
        onLog("ℹ️ Starting package manager on the target device")
        return runShellCommand(command)
    }

    private fun installPackageArchive(archiveFile: File, options: List<String>): Boolean {
        if (!isConnected) return false
        cancelled = false

        val cacheRoot = File(keyDirectory.parentFile ?: keyDirectory, "adb-package-cache")
        if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
            onLog("❌ adb install: could not create temporary folder: ${cacheRoot.absolutePath}")
            return false
        }
        val workDir = File(cacheRoot, "pkg-${System.currentTimeMillis()}")
        if (!workDir.mkdirs()) {
            onLog("❌ adb install: could not create temporary folder: ${workDir.absolutePath}")
            return false
        }

        onLog("-> adb install ${options.joinToString(" ")} ${archiveFile.name}".trim())
        onLog("ℹ️ Container ${archiveFile.extension.uppercase()}: extracting APK files")

        try {
            val contents = extractPackageArchiveContents(archiveFile, workDir)
            val extracted = contents.apks
            if (extracted.isEmpty()) {
                onLog("❌ No APK files found in the container: ${archiveFile.name}")
                return false
            }
            if (contents.obbs.isNotEmpty()) {
                onLog("ℹ️ Found OBB files in the container: ${contents.obbs.size}")
            }

            val selected = selectArchiveApksForInstall(extracted)
            onLog("ℹ️ Selected APKs for installation: ${selected.size}")
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
            onLog("❌ adb install: container processing error ${archiveFile.name}: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            deleteRecursivelyQuietly(workDir)
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
                if (!isValidArchiveEntryName(normalizedName)) {
                    onLog("⚠️ Skipped unsafe path in archive: $normalizedName")
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
        onLog("ℹ️ Found APK files in the container: ${apks.size}")
        manifestPackageName?.let { onLog("ℹ️ package_name from manifest.json: $it") }
        return PackageArchiveContents(apks, obbs, manifestPackageName)
    }

    @Suppress("SdCardPath")
    private fun pushArchiveObbs(obbs: List<ExtractedArchiveObb>, manifestPackageName: String?): Boolean {
        if (obbs.isEmpty()) return true
        var ok = true
        obbs.forEachIndexed { index, obb ->
            if (cancelled) return false
            val packageName = obb.packageNameFromPath ?: manifestPackageName
            if (packageName.isNullOrBlank()) {
                onLog("⚠️ OBB ${obb.entryName}: package was not detected, file was not sent. Extract the XAPK and run adb push manually.")
                ok = false
                return@forEachIndexed
            }
            val remoteDir = "/sdcard/Android/obb/$packageName"
            val remoteName = obb.entryName.substringAfterLast('/').ifBlank { obb.file.name.removePrefix("obb-") }
            val remotePath = "$remoteDir/$remoteName"
            onLog("ℹ️ OBB ${index + 1}/${obbs.size}: ${obb.entryName} → $remotePath")
            val mkdirResult = runShellCommandForResult("mkdir -p ${shellQuote(remoteDir)}", logOutput = false)
            if (!mkdirResult.success) {
                onLog("❌ Could not create folder OBB: $remoteDir")
                ok = false
                return@forEachIndexed
            }
            if (!pushFile(obb.file, remotePath, 0x1A4)) {
                ok = false
                return@forEachIndexed
            }
        }
        if (ok) onLog("✅ OBB files sent")
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
            onLog("ℹ️ Found universal.apk — using single APK installation instead of a split set")
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
            onLog("ℹ️ Found split set with base APK")
            return sortApksForInstall(splitSet)
        }

        val xapkSet = apks.filter { isBaseApkLike(it) || isConfigSplitLike(it) }
        if (xapkSet.any { isBaseApkLike(it) }) {
            onLog("ℹ️ Found XAPK/APK set with base/config split")
            return sortApksForInstall(xapkSet)
        }

        val standalone = apks.filter {
            val path = it.entryName.replace('\\', '/').lowercase()
            path.startsWith("standalones/") || path.contains("/standalones/")
        }
        if (standalone.size == 1) {
            onLog("ℹ️ Found one standalone APK")
            return standalone
        }
        if (standalone.size > 1) {
            onLog("⚠️ The container has multiple standalone APKs. The first one was selected automatically; for exact selection, extract the archive and install the required APK manually.")
            return listOf(standalone.first())
        }

        if (apks.size > 1) {
            onLog("⚠️ Could not confidently detect the base/split structure. Trying to install all APKs from the container.")
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

    private fun isValidArchiveEntryName(name: String): Boolean {
        if (name.startsWith("/") || name.startsWith("../") || name.contains("/../")) return false
        if (name.contains('\u0000')) return false
        return true
    }

    private fun deleteRecursivelyQuietly(file: File) {
        runCatching {
            if (file.exists() && !file.deleteRecursively()) {
                onLog("ℹ️ Could not fully delete the temporary directory")
            }
        }.onFailure { error ->
            onLog("ℹ️ Temporary directory cleanup skipped (${error.javaClass.simpleName})")
        }
    }

    fun installMultipleApks(apkFiles: List<File>, options: List<String>): Boolean {
        if (!isConnected) return false
        cancelled = false

        val files = apkFiles.distinctBy { it.absolutePath }
        if (files.size < 2) {
            onLog("❌ adb install-multiple: requires at least 2 APK files")
            return false
        }
        files.forEach { file ->
            if (!file.exists() || !file.isFile || !file.canRead()) {
                onLog("❌ adb install-multiple: APK is unavailable: ${file.absolutePath}")
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
        onLog("ℹ️ Split APK: ${files.size} files. Using package-manager session API.")

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
                onLog("❌ install-multiple: pm install-create failed")
                return false
            }

            sessionId = parseInstallSessionId(createResult.combinedOutput())
            if (sessionId.isNullOrBlank()) {
                onLog("❌ install-multiple: could not determine session id from pm install-create output")
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
                    onLog("❌ install-multiple: install-write error for ${file.name}")
                    abandonInstallSession(sessionId)
                    return false
                }
            }

            val commitResult = runShellCommandForResult("pm install-commit $sessionId")
            return if (commitResult.success) {
                onLog("✅ adb install-multiple completed")
                true
            } else {
                onLog("❌ install-multiple: pm install-commit failed")
                abandonInstallSession(sessionId)
                false
            }
        } catch (e: Exception) {
            onLog("❌ adb install-multiple error: ${e.message ?: e.javaClass.simpleName}")
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
        onLog("ℹ️ Cancelling install session $sessionId")
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
            return startInteractiveShell()
        }

        return runShellCommandForResult(cleanCommand, logOutput = true, forceLegacy = forceLegacy).success
    }

    fun sendInteractiveShellInput(line: String): Boolean {
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        val accepted = queueInteractiveShellBytes(payload)
        if (accepted) onLog("adb-shell$ ${line.trimEnd()}")
        return accepted
    }

    fun sendInteractiveShellInterrupt(): Boolean {
        val accepted = queueInteractiveShellBytes(byteArrayOf(0x03))
        if (accepted) onLog("adb-shell: SIGINT / Ctrl+C")
        return accepted
    }

    fun sendInteractiveShellEof(): Boolean {
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
        onLog("⏹ Interactive adb shell close requested")
        return true
    }

    private fun startInteractiveShell(): Boolean {
        if (!isConnected) return false
        cancelled = false

        synchronized(interactiveShellLock) {
            if (interactiveShellSession != null) {
                onLog("ℹ️ Interactive adb shell is already open. Enter commands without the adb shell prefix.")
                return true
            }
            interactiveShellSession = InteractiveShellSession()
        }

        val useShellV2 = supportsShellV2
        val service = if (useShellV2) "shell,v2,pty:" else "shell:"
        onLog("=== ADB INTERACTIVE SHELL START ===")
        onLog("-> adb open: $service")
        onLog("ℹ️ Enter commands in the input line below. To exit: exit, adb shell-stop, or the Stop button.")
        onLog("ℹ️ Process interrupt: :ctrl-c, :interrupt, or adb shell-ctrl-c. EOF: :ctrl-d.")

        val stream = openAdbStream(service, logOpen = false) ?: run {
            clearInteractiveShellSession()
            return false
        }

        return try {
            if (useShellV2) runInteractiveShellV2(stream) else runInteractiveLegacyShell(stream)
        } catch (e: Exception) {
            onLog("❌ interactive adb shell error: ${e.message ?: e.javaClass.simpleName}")
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
                    sendMessageInternal(A_OKAY, stream.localId, header.arg0, EMPTY_PAYLOAD)
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
                    onLog("⚠️ interactive shell/v2: unexpected ADB packet cmd=0x${header.command.toString(16)}")
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
            if (writeShellPacket(stream, SHELL_ID_CLOSE_STDIN, EMPTY_PAYLOAD)) {
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
                onLog("❌ interactive shell_v2: invalid length packet=$length")
                stream.closed = true
                return
            }
            if (pendingByteCount(stream) < SHELL_PACKET_HEADER + length) return
            readPendingExact(stream, SHELL_PACKET_HEADER)
            val payload = readPendingExact(stream, length) ?: EMPTY_PAYLOAD

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
                else -> onLog("⚠️ interactive shell_v2: unknown packet id=$id, length=$length")
            }
        }
    }

    private fun runInteractiveLegacyShell(stream: AdbStream): Boolean {
        onLog("ℹ️ legacy shell: stdout/stderr and exit code are not separated")
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
                    sendMessageInternal(A_OKAY, stream.localId, stream.remoteId, EMPTY_PAYLOAD)
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
                    onLog("⚠️ interactive legacy shell: unexpected ADB packet cmd=0x${header.command.toString(16)}")
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
                message = "ADB Recovery is not ready to verify the install result yet"
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
                onLog("ℹ️ Recovery log received: $path (${boundedText.length} characters)")
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
        return if (!forceLegacy && supportsShellV2) {
            runShellV2ForResult(cleanCommand, logOutput)
        } else {
            if (logOutput) onLog("ℹ️ adb shell legacy: exit code is unavailable on this device/mode")
            runLegacyShellForResult(cleanCommand, logOutput)
        }
    }

    private fun runShellV2ForResult(command: String, logOutput: Boolean): ShellResult {
        val service = "shell,v2,raw:$command"
        if (logOutput) onLog("-> adb shell/v2: $command")
        val stream = openAdbStream(service, logOpen = false) ?: run {
            if (logOutput) onLog("⚠️ shell_v2 could not open; trying legacy shell")
            return runShellCommandForResult(command, logOutput = logOutput, forceLegacy = true)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null
        try {
            // Непосредственно как в shell protocol: закрываем stdin для one-shot команд.
            if (!writeShellPacket(stream, SHELL_ID_CLOSE_STDIN, EMPTY_PAYLOAD)) {
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
                        val data = readAdbStreamExact(stream, header.length) ?: EMPTY_PAYLOAD
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
                        if (logOutput) onLog("⚠️ shell_v2: unknown packet id=${header.id}, length=${header.length}")
                    }
                }
            }
        } catch (e: Exception) {
            if (logOutput) onLog("❌ adb shell/v2 error: ${e.message ?: e.javaClass.simpleName}")
            return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
        } finally {
            closeAdbStream(stream)
        }

        if (exitCode == null && logOutput) onLog("⚠️ shell_v2 finished without an exit packet")
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
                        sendMessageInternal(A_OKAY, stream.localId, stream.remoteId, EMPTY_PAYLOAD)
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
                        if (logOutput) onLog("⚠️ legacy shell: unexpected packet cmd=0x${header.command.toString(16)}")
                    }
                }
            }
        } catch (e: Exception) {
            if (logOutput) onLog("❌ adb shell legacy error: ${e.message ?: e.javaClass.simpleName}")
            return ShellResult(stdout.toString(), "", null, false)
        } finally {
            closeAdbStream(stream)
        }
        return ShellResult(stdout.toString(), "", null, !cancelled)
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
        var closed: Boolean = false,
        var closeSent: Boolean = false
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
                onLog("❌ ADB stream did not respond: $service")
                return null
            }
            when (header.command) {
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    if (!packetTargetsLocalStream(header, localId)) {
                        onLog("ℹ️ ADB open: ignored stale OKAY for local=${header.arg1}, expected local=$localId")
                        continue
                    }
                    return AdbStream(localId, header.arg0)
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    if (!packetTargetsLocalStream(header, localId)) {
                        acknowledgeRemoteClose(header.arg1, header.arg0)
                        onLog("ℹ️ ADB open: ignored stale CLSE for local=${header.arg1}, expected local=$localId")
                        continue
                    }
                    acknowledgeRemoteClose(localId, header.arg0)
                    onLog("❌ ADB stream closed by the device: $service")
                    return null
                }
                A_WRTE -> {
                    val data = readData(header.dataLength)
                    if (!packetTargetsLocalStream(header, localId)) {
                        acknowledgeRemoteClose(header.arg1, header.arg0)
                        onLog("ℹ️ ADB open: ignored stale WRTE for local=${header.arg1}, expected local=$localId")
                        continue
                    }
                    sendMessageInternal(A_OKAY, localId, header.arg0, EMPTY_PAYLOAD)
                    if (data != null && data.isNotEmpty()) logServiceOutput(data)
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ Unexpected ADB packet while opening: cmd=0x${header.command.toString(16)}")
                }
            }
        }
        return null
    }

    private fun packetTargetsLocalStream(header: AdbHeader, localId: Int): Boolean = header.arg1 == localId

    private fun acknowledgeRemoteClose(localId: Int, remoteId: Int) {
        if (localId <= 0 || remoteId <= 0) return
        runCatching { sendMessageInternal(A_CLSE, localId, remoteId, EMPTY_PAYLOAD) }
    }

    private fun closeAdbStream(stream: AdbStream) {
        if (stream.closeSent) {
            stream.closed = true
            return
        }
        runCatching {
            sendMessageInternal(A_CLSE, stream.localId, stream.remoteId, EMPTY_PAYLOAD)
            stream.closeSent = true
        }.onFailure { error ->
            // Transport может уже исчезнуть; локально stream всё равно закрывается ниже.
            onLog("ℹ️ ADB stream close packet was not sent (${error.javaClass.simpleName})")
        }
        stream.closed = true
    }

    private fun writeAdbStream(stream: AdbStream, payload: ByteArray): Boolean {
        if (stream.closed) return false
        sendMessageInternal(A_WRTE, stream.localId, stream.remoteId, payload)
        while (!cancelled) {
            val header = readHeader(timeoutMs = 10_000) ?: run {
                onLog("❌ ADB stream: no ACK for WRTE")
                return false
            }
            when (header.command) {
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    if (!packetTargetsLocalStream(header, stream.localId)) continue
                    stream.remoteId = header.arg0
                    return true
                }
                A_WRTE -> {
                    val data = readData(header.dataLength)
                    if (!packetTargetsLocalStream(header, stream.localId)) {
                        acknowledgeRemoteClose(header.arg1, header.arg0)
                        continue
                    }
                    sendMessageInternal(A_OKAY, stream.localId, header.arg0, EMPTY_PAYLOAD)
                    if (data != null && data.isNotEmpty()) stream.pending.add(data)
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    if (!packetTargetsLocalStream(header, stream.localId)) {
                        acknowledgeRemoteClose(header.arg1, header.arg0)
                        continue
                    }
                    stream.remoteId = header.arg0
                    closeAdbStream(stream)
                    onLog("❌ ADB stream closed during write")
                    return false
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ ADB stream: unexpected packet cmd=0x${header.command.toString(16)}")
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
                onLog("❌ ADB stream: data read timeout")
                return null
            }
            when (header.command) {
                A_WRTE -> {
                    val data = readData(header.dataLength) ?: return null
                    if (!packetTargetsLocalStream(header, stream.localId)) {
                        acknowledgeRemoteClose(header.arg1, header.arg0)
                        continue
                    }
                    sendMessageInternal(A_OKAY, stream.localId, header.arg0, EMPTY_PAYLOAD)
                    if (data.isNotEmpty()) stream.pending.add(data)
                }
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    if (!packetTargetsLocalStream(header, stream.localId)) continue
                    stream.remoteId = header.arg0
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    if (!packetTargetsLocalStream(header, stream.localId)) {
                        acknowledgeRemoteClose(header.arg1, header.arg0)
                        continue
                    }
                    stream.remoteId = header.arg0
                    closeAdbStream(stream)
                    return null
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ ADB stream: unexpected packet cmd=0x${header.command.toString(16)}")
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
            onLog("❌ shell_v2: invalid length packet=$length")
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
                onLog("❌ $opName: unexpected sync id=${header.id}")
                false
            }
        }
    }

    private fun statRemotePath(remotePath: String, logMissing: Boolean = true): SyncStat? {
        val cleanRemote = remotePath.trim()
        if (!isValidRemotePath(cleanRemote)) return null
        val stream = openAdbStream("sync:", logOpen = false) ?: return null
        try {
            if (!writeSyncRequest(stream, "STAT", cleanRemote.toByteArray(Charsets.UTF_8))) return null
            val stat = readSyncStatResponse(stream, "adb stat") ?: return null
            if (!stat.exists && logMissing) {
                onLog("❌ adb stat: remote path not found: $cleanRemote")
            } else if (stat.exists && logMissing) {
                val kind = when {
                    stat.isDirectory -> "directory"
                    stat.isRegularFile -> "file"
                    else -> "object"
                }
                onLog("ℹ️ adb stat: $kind, size=${stat.size}, mode=0${stat.mode.toString(8)}")
            }
            return stat
        } catch (e: Exception) {
            if (logMissing) onLog("❌ adb stat error: ${e.message ?: e.javaClass.simpleName}")
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
                onLog("❌ $opName: unexpected sync id=$id")
                null
            }
        }
    }

    private fun ensureRemoteDirectory(remoteDir: String): Boolean {
        if (!isValidRemotePath(remoteDir)) return false
        val result = runShellCommandForResult("mkdir -p ${shellQuote(remoteDir)}", logOutput = false)
        if (!result.success) {
            onLog("❌ Could not create remote folder: $remoteDir")
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

    private fun isValidRemotePath(path: String): Boolean {
        return path.isNotBlank() && !path.contains('\u0000')
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun clearExpectedServiceDisconnect() {
        expectedDisconnectService = null
        expectedDisconnectOpenWritten = false
        expectedDisconnectObserved = false
        expectedDisconnectExpiresAtNs = 0L
    }

    private fun expectedServiceDisconnectWindowActive(): Boolean =
        expectedDisconnectOpenWritten &&
            expectedDisconnectExpiresAtNs > 0L &&
            System.nanoTime() <= expectedDisconnectExpiresAtNs

    private fun currentServiceTerminalSignal(): AdbServiceCompletionPolicy.TerminalSignal {
        val dispatcherFailure = (packetDispatcher?.snapshot() ?: lastDispatcherSnapshot)
            ?.lastFailureCode
        return AdbServiceCompletionPolicy.terminalSignalForFailure(
            dispatcherFailure
                ?.takeUnless { it == AdbPacketDispatcher.FailureCode.NONE }
                ?: directReadFailureCode
        ) ?: if (!isConnected || expectedDisconnectObserved) {
            AdbServiceCompletionPolicy.TerminalSignal.TRANSPORT_CLOSED
        } else {
            AdbServiceCompletionPolicy.TerminalSignal.HEADER_TIMEOUT
        }
    }

    private fun currentServiceFailureDetail(): String {
        val snapshot = packetDispatcher?.snapshot() ?: lastDispatcherSnapshot
        val code = snapshot?.lastFailureCode ?: directReadFailureCode
        val message = snapshot?.lastFailureMessage ?: directReadFailureMessage
        return buildString {
            append(code?.name ?: "UNKNOWN")
            message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
    }

    fun runService(service: String): Boolean {
        if (!isConnected) return false
        cancelled = false

        val normalizedService = service.trim()
        // A new explicit service supersedes any short reboot-disconnect grace
        // left by the previous one-way command.
        clearExpectedServiceDisconnect()
        if (normalizedService.isBlank()) {
            onLog("❌ ERROR: empty ADB service")
            return false
        }

        val oneWayReboot = AdbServiceCompletionPolicy.expectsOneWayDisconnect(normalizedService)
        if (oneWayReboot) {
            expectedDisconnectService = normalizedService
            expectedDisconnectOpenWritten = false
            expectedDisconnectObserved = false
            expectedDisconnectExpiresAtNs = 0L
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
            if (oneWayReboot) {
                expectedDisconnectOpenWritten = true
                expectedDisconnectExpiresAtNs = System.nanoTime() + EXPECTED_REBOOT_DISCONNECT_WINDOW_NS
                onLog("ℹ️ ADB reboot service was fully sent; further USB disconnect is an expected transition.")
            }

            while (!cancelled) {
                val header = readHeader(timeoutMs = if (oneWayReboot) 3_000 else 10_000)
                if (header == null) {
                    val signal = currentServiceTerminalSignal()
                    if (AdbServiceCompletionPolicy.isExpectedCompletion(
                            normalizedService,
                            expectedDisconnectOpenWritten,
                            signal
                        )
                    ) {
                        if (signal == AdbServiceCompletionPolicy.TerminalSignal.TRANSPORT_CLOSED) {
                            expectedDisconnectObserved = true
                        }
                        onLog("✅ ADB reboot command accepted: device started the transition and may temporarily disappear from USB.")
                        return true
                    }
                    if (signal == AdbServiceCompletionPolicy.TerminalSignal.EXPLICIT_PROTOCOL_FAILURE) {
                        onLog(
                            "❌ ADB protocol failure before service completion: " +
                                "$normalizedService [${currentServiceFailureDetail()}]"
                        )
                        return false
                    }
                    if (!isConnected) {
                        onLog("❌ ADB transport disconnected before service completion: $normalizedService")
                        return false
                    }
                    if (!opened) {
                        onLog("❌ ERROR: ADB service did not respond")
                        return false
                    }
                    idleTimeouts++
                    if (idleTimeouts >= 3) {
                        onLog("ℹ️ ADB service stopped sending data; after $idleTimeouts idle cycles the operation is considered complete.")
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
                        sendMessageInternal(A_OKAY, localId, remoteId, EMPTY_PAYLOAD)
                        logServiceOutput(data)
                    }
                    A_CLSE -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        if (remoteId != 0) sendMessageInternal(A_CLSE, localId, remoteId, EMPTY_PAYLOAD)
                        if (oneWayReboot) expectedDisconnectObserved = true
                        onLog("=== ADB SERVICE CLOSED ===")
                        return true
                    }
                    else -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        onLog("⚠️ Unexpected ADB packet: cmd=0x${header.command.toString(16)}")
                    }
                }
            }
        } catch (e: Exception) {
            if (AdbServiceCompletionPolicy.isExpectedCompletion(
                    normalizedService,
                    expectedDisconnectOpenWritten,
                    AdbServiceCompletionPolicy.TerminalSignal.TRANSPORT_FAILURE
                )
            ) {
                expectedDisconnectObserved = true
                onLog("✅ ADB reboot command accepted; transport closed during expected reboot hand-off.")
                return true
            }
            onLog("ERROR ADB service: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            if (expectedDisconnectService == normalizedService &&
                (!oneWayReboot || !expectedDisconnectOpenWritten || expectedDisconnectObserved)
            ) {
                clearExpectedServiceDisconnect()
            }
        }

        onLog("⚠️ ADB service cancelled by user")
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

        val header = outboundHeaderBuffer
        putIntLe(header, 0, command.toInt())
        putIntLe(header, 4, arg0)
        putIntLe(header, 8, arg1)
        putIntLe(header, 12, data.size)
        putIntLe(header, 16, checksum)
        putIntLe(header, 20, command.inv().toInt())

        if (!bulkWriteFully(header)) throw Exception("ADB header transfer error")

        if (data.isNotEmpty()) {
            var offset = 0
            while (offset < data.size) {
                val len = minOf(USB_BULK_CHUNK_BYTES, data.size - offset)
                if (!bulkWriteFully(data, offset, len)) throw Exception("ADB data transfer error")
                offset += len
            }
        }
    }

    private fun putIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readIntLe(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)

    private fun bulkWriteFully(data: ByteArray, offset: Int = 0, length: Int = data.size, timeout: Int = 5000): Boolean {
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
                    val expectedService = expectedDisconnectService
                    val disconnectLike = code == AdbPacketDispatcher.FailureCode.USB_IN_FAILED ||
                        code == AdbPacketDispatcher.FailureCode.DEVICE_DISCONNECTED
                    val expected = expectedService != null && disconnectLike &&
                        expectedServiceDisconnectWindowActive() &&
                        AdbServiceCompletionPolicy.isExpectedCompletion(
                            service = expectedService,
                            openPacketWritten = expectedDisconnectOpenWritten,
                            signal = AdbServiceCompletionPolicy.TerminalSignal.TRANSPORT_FAILURE
                        )
                    dispatcherTransportFailed = true
                    if (expected) {
                        expectedDisconnectObserved = true
                        onLog(
                            "ℹ️ ADB reboot was sent to the device; expected transport disconnect " +
                                "[${code.name}] is not considered an operation error."
                        )
                        runCatching { connection?.close() }
                        connection = null
                    } else {
                        onLog("❌ ADB reader stopped [${code.name}]: $message")
                        cancelled = true
                        runCatching { connection?.close() }
                        connection = null
                        onTransportFailure?.invoke(code, message)
                    }
                }
            }
        )
        packetDispatcher = dispatcher
        dispatchedPayloadPacket = null
        if (dispatcher.start()) {
            onLog("✅ ADB single-reader dispatcher started (bounded queue=256)")
        }
    }

    private fun stopPacketDispatcher(reason: String) {
        val dispatcher = packetDispatcher ?: return
        dispatcherGeneration.incrementAndGet()
        dispatcher.stop()
        lastDispatcherSnapshot = dispatcher.snapshot()
        packetDispatcher = null
        dispatchedPayloadPacket = null
        onLog("ℹ️ ADB dispatcher stopped: $reason")
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
        } else EMPTY_PAYLOAD
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
        val packet = dispatchedPayloadPacket ?: return if (length == 0) EMPTY_PAYLOAD else null
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
        val buffer = inboundHeaderBuffer
        var totalRead = 0

        while (totalRead < buffer.size) {
            val read = conn.bulkTransfer(ep, buffer, totalRead, buffer.size - totalRead, timeoutMs)
            if (read <= 0) {
                if (totalRead > 0) {
                    directReadFailureCode = AdbPacketDispatcher.FailureCode.PARTIAL_HEADER_TIMEOUT
                    directReadFailureMessage =
                        "ADB header interrupted after $totalRead/${buffer.size} bytes (result=$read); stream synchronization is no longer trusted"
                    onLog("❌ $directReadFailureMessage")
                }
                return null
            }
            totalRead += read
        }

        val cmd   = readIntLe(buffer, 0).toLong() and 0xFFFFFFFFL
        val a0    = readIntLe(buffer, 4)
        val a1    = readIntLe(buffer, 8)
        val len   = readIntLe(buffer, 12)
        val chk   = readIntLe(buffer, 16)
        val magic = readIntLe(buffer, 20)

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
                    EMPTY_PAYLOAD,
                    LOCAL_ADB_VERSION,
                    peerProtocolVersion
                )
            ) {
                directReadFailureCode = AdbPacketDispatcher.FailureCode.CHECKSUM_MISMATCH
                directReadFailureMessage = "ADB empty-payload checksum mismatch for cmd=0x${cmd.toString(16)}: expected=$chk, actual=0"
                onLog("❌ $directReadFailureMessage")
                return null
            }
            clearPendingInboundPayload()
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
        clearPendingInboundPayload()

        if (length != expectedLength) {
            directReadFailureCode = AdbPacketDispatcher.FailureCode.INVALID_PAYLOAD
            directReadFailureMessage = "ADB payload length mismatch: header=$expectedLength, requested=$length"
            onLog("❌ $directReadFailureMessage")
            return null
        }
        if (length == 0) {
            val empty = EMPTY_PAYLOAD
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
            // Keep individual host-controller transactions bounded, but read
            // directly into the final payload buffer to avoid one allocation
            // and one memcpy for every USB chunk.
            val chunkLength = minOf(USB_BULK_CHUNK_BYTES, length - totalRead)
            val read = conn.bulkTransfer(ep, buffer, totalRead, chunkLength, 5000)
            if (read <= 0) {
                directReadFailureCode = if (connection == null) AdbPacketDispatcher.FailureCode.DEVICE_DISCONNECTED else AdbPacketDispatcher.FailureCode.USB_IN_FAILED
                directReadFailureMessage = "ADB payload USB IN failed after $totalRead/$length bytes (result=$read)"
                return null
            }
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
        clearExpectedServiceDisconnect()
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
        clearPendingInboundPayload()
        dispatcherTransportFailed = false
        directReadFailureCode = null
        directReadFailureMessage = null
    }
}
