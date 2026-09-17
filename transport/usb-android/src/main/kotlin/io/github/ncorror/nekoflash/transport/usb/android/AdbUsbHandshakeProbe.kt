package io.github.ncorror.nekoflash.transport.usb.android

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbFrame
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeEngine
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeTraceEvent
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeTransport
import io.github.ncorror.nekoflash.protocol.adb.AdbHostKey
import io.github.ncorror.nekoflash.protocol.adb.AdbIoFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbPacketCodec
import io.github.ncorror.nekoflash.protocol.adb.AdbReadResult
import io.github.ncorror.nekoflash.protocol.adb.AdbWriteResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface AdbUsbProbeResult {
    data object Busy : AdbUsbProbeResult
    data class DeviceMissing(val deviceName: String) : AdbUsbProbeResult
    data class PermissionRequired(val deviceName: String) : AdbUsbProbeResult
    data class NoAdbInterface(val deviceName: String) : AdbUsbProbeResult
    data class OpenFailed(val deviceName: String) : AdbUsbProbeResult
    data class ClaimFailed(val deviceName: String, val interfaceIndex: Int) : AdbUsbProbeResult
    data class Completed(val deviceName: String, val outcome: AdbHandshakeOutcome) : AdbUsbProbeResult
}

/**
 * Phase 2 Android adapter for exactly one ADB CNXN/AUTH handshake probe.
 *
 * It does not open ADB services, shell, sync/push or any Fastboot transaction. It intentionally
 * performs no hidden endpoint reset, reconnect or second CNXN when a transfer fails.
 */
class AdbUsbHandshakeProbe(
    context: Context,
    private val diagnostics: DiagnosticSink,
    keyDirectory: File = File(context.applicationContext.filesDir, "adb-host-key"),
    private val monotonicNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val hostKey = AdbHostKey(keyDirectory)
    private val running = AtomicBoolean(false)

    val isRunning: Boolean
        get() = running.get()

    fun probe(deviceName: String): AdbUsbProbeResult {
        if (!running.compareAndSet(false, true)) return AdbUsbProbeResult.Busy
        val startedNanos = monotonicNanos()
        var connection: UsbDeviceConnection? = null
        var claimedInterface: UsbInterface? = null
        var base = mapOf("deviceName" to deviceName)

        try {
            val device = usbManager.deviceList[deviceName]
                ?: return AdbUsbProbeResult.DeviceMissing(deviceName).also {
                    emit(startedNanos, "probe_device_missing", base)
                }
            base = baseFields(device)
            emit(startedNanos, "probe_started", base)

            if (!usbManager.hasPermission(device)) {
                emit(startedNanos, "probe_permission_required", base)
                return AdbUsbProbeResult.PermissionRequired(deviceName)
            }

            val candidate = findAdbInterface(device)
                ?: return AdbUsbProbeResult.NoAdbInterface(deviceName).also {
                    emit(startedNanos, "probe_no_adb_interface", base)
                }
            val endpoints = findBulkEndpoints(candidate)
            val endpointIn = endpoints.first ?: error("ADB candidate lost bulk IN")
            val endpointOut = endpoints.second ?: error("ADB candidate lost bulk OUT")
            base = base + mapOf(
                "interfaceIndex" to indexOfInterface(device, candidate).toString(),
                "interfaceId" to candidate.id.toString(),
                "interfaceClass" to candidate.interfaceClass.toString(),
                "interfaceSubclass" to candidate.interfaceSubclass.toString(),
                "interfaceProtocol" to candidate.interfaceProtocol.toString(),
                "bulkInAddress" to "0x%02X".format(endpointIn.address),
                "bulkOutAddress" to "0x%02X".format(endpointOut.address),
                "bulkInMaxPacket" to endpointIn.maxPacketSize.toString(),
                "bulkOutMaxPacket" to endpointOut.maxPacketSize.toString(),
            )
            emit(startedNanos, "adb_interface_selected", base)

            val openedConnection = runCatching { usbManager.openDevice(device) }.getOrNull()
            connection = openedConnection
            if (openedConnection == null) {
                emit(startedNanos, "open_failed", base)
                return AdbUsbProbeResult.OpenFailed(deviceName)
            }
            emit(startedNanos, "open_succeeded", base)

            val claimed = runCatching { openedConnection.claimInterface(candidate, false) }.getOrDefault(false)
            emit(startedNanos, "claim_result", base + mapOf("claimed" to claimed.toString(), "force" to "false"))
            if (!claimed) {
                return AdbUsbProbeResult.ClaimFailed(deviceName, indexOfInterface(device, candidate))
            }
            claimedInterface = candidate

            val localMaxPayload = if (Build.VERSION.SDK_INT >= 28) MODERN_MAX_PAYLOAD_BYTES else PRE_P_MAX_PAYLOAD_BYTES
            val frameTransport = AndroidAdbFrameTransport(
                connection = openedConnection,
                endpointIn = endpointIn,
                endpointOut = endpointOut,
                maxPayloadBytes = localMaxPayload,
                onTransfer = { code, fields -> emit(startedNanos, code, base + fields) },
                monotonicNanos = monotonicNanos,
            )
            val engine = AdbHandshakeEngine(localMaxPayload = localMaxPayload)
            val outcome = engine.run(frameTransport, hostKey) { trace -> emitTrace(startedNanos, base, trace) }
            emitOutcome(startedNanos, base, outcome)
            return AdbUsbProbeResult.Completed(deviceName, outcome)
        } catch (error: Exception) {
            emit(
                startedNanos,
                "probe_exception",
                base + mapOf("error" to error.javaClass.simpleName),
            )
            return AdbUsbProbeResult.Completed(
                deviceName,
                AdbHandshakeOutcome.TransportFailed("probe_exception", AdbIoFailure.UNKNOWN),
            )
        } finally {
            claimedInterface?.let { usbInterface ->
                val released = runCatching { connection?.releaseInterface(usbInterface) ?: false }.getOrDefault(false)
                emit(startedNanos, "release_result", base + mapOf("released" to released.toString()))
            }
            connection?.close()
            if (connection != null) emit(startedNanos, "connection_closed", base)
            running.set(false)
        }
    }

    private fun emitTrace(startedNanos: Long, base: Map<String, String>, trace: AdbHandshakeTraceEvent) {
        emit(startedNanos, trace.code, base + trace.fields)
    }

    private fun emitOutcome(startedNanos: Long, base: Map<String, String>, outcome: AdbHandshakeOutcome) {
        val fields = when (outcome) {
            is AdbHandshakeOutcome.Connected -> mapOf(
                "outcome" to "CONNECTED",
                "peerVersion" to "0x%08X".format(outcome.peerVersion),
                "peerMaxPayload" to outcome.peerMaxPayload.toString(),
                "peerKind" to outcome.peerKind.name,
                "authPath" to outcome.authPath.name,
            )
            is AdbHandshakeOutcome.TransportFailed -> mapOf(
                "outcome" to "TRANSPORT_FAILED",
                "stage" to outcome.stage,
                "failure" to outcome.failure.name,
            )
            is AdbHandshakeOutcome.ProtocolFailed -> mapOf(
                "outcome" to "PROTOCOL_FAILED",
                "reason" to outcome.reason,
            )
            is AdbHandshakeOutcome.AuthKeyFailed -> mapOf(
                "outcome" to "AUTH_KEY_FAILED",
                "reason" to outcome.reason,
            )
            AdbHandshakeOutcome.AuthResponseLimit -> mapOf("outcome" to "AUTH_RESPONSE_LIMIT")
        }
        emit(startedNanos, "handshake_finished", base + fields)
    }

    private fun emit(startedNanos: Long, code: String, fields: Map<String, String>) {
        val now = monotonicNanos()
        diagnostics.emit(
            DiagnosticEvent(
                monotonicNanos = now,
                category = CATEGORY,
                code = code,
                fields = fields + ("elapsedMs" to ((now - startedNanos) / 1_000_000L).toString()),
            ),
        )
    }

    private fun findAdbInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val candidate = device.getInterface(index)
            if (
                candidate.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                candidate.interfaceSubclass == ADB_SUBCLASS &&
                candidate.interfaceProtocol == ADB_PROTOCOL &&
                hasBulkPair(candidate)
            ) {
                return candidate
            }
        }
        return null
    }

    private fun hasBulkPair(usbInterface: UsbInterface): Boolean =
        findBulkEndpoints(usbInterface).let { it.first != null && it.second != null }

    private fun findBulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN && input == null) input = endpoint
            if (endpoint.direction == UsbConstants.USB_DIR_OUT && output == null) output = endpoint
        }
        return input to output
    }

    private fun indexOfInterface(device: UsbDevice, target: UsbInterface): Int {
        for (index in 0 until device.interfaceCount) if (device.getInterface(index) === target) return index
        for (index in 0 until device.interfaceCount) if (device.getInterface(index).id == target.id) return index
        return -1
    }

    private fun baseFields(device: UsbDevice): Map<String, String> = mapOf(
        "deviceName" to device.deviceName,
        "deviceId" to device.deviceId.toString(),
        "vidPid" to "%04X:%04X".format(device.vendorId, device.productId),
    )

    private class AndroidAdbFrameTransport(
        private val connection: UsbDeviceConnection,
        private val endpointIn: UsbEndpoint,
        private val endpointOut: UsbEndpoint,
        private val maxPayloadBytes: Int,
        private val onTransfer: (String, Map<String, String>) -> Unit,
        private val monotonicNanos: () -> Long,
    ) : AdbHandshakeTransport {
        override fun send(frame: AdbFrame, timeoutMs: Int): AdbWriteResult {
            val header = AdbPacketCodec.encodeHeader(frame)
            val headerResult = writeFully("header", header, timeoutMs)
            if (headerResult.confirmed != header.size) {
                return AdbWriteResult.Failed(AdbIoFailure.USB_OUT_NOT_COMPLETED, headerResult.confirmed)
            }
            if (frame.payload.isEmpty()) return AdbWriteResult.Success
            val payloadResult = writeFully("payload", frame.payload, timeoutMs)
            return if (payloadResult.confirmed == frame.payload.size) {
                AdbWriteResult.Success
            } else {
                AdbWriteResult.Failed(
                    AdbIoFailure.USB_OUT_NOT_COMPLETED,
                    header.size + payloadResult.confirmed,
                )
            }
        }

        override fun receive(timeoutMs: Int): AdbReadResult {
            val headerBytes = ByteArray(AdbPacketCodec.HEADER_SIZE)
            var headerOffset = 0
            while (headerOffset < headerBytes.size) {
                val started = monotonicNanos()
                val result = connection.bulkTransfer(
                    endpointIn,
                    headerBytes,
                    headerOffset,
                    headerBytes.size - headerOffset,
                    timeoutMs,
                )
                transferEvent(
                    "usb_read",
                    "header",
                    headerBytes.size - headerOffset,
                    result,
                    started,
                    timeoutMs,
                )
                if (result <= 0) {
                    val failure = if (headerOffset == 0) AdbIoFailure.USB_IN_NOT_COMPLETED else AdbIoFailure.PARTIAL_HEADER
                    return AdbReadResult.Failed(failure, headerOffset)
                }
                headerOffset += result
            }

            val header = try {
                AdbPacketCodec.decodeHeader(headerBytes, maxPayloadBytes)
            } catch (_: IllegalArgumentException) {
                return AdbReadResult.Failed(AdbIoFailure.INVALID_HEADER, headerOffset)
            }

            if (header.dataLength == 0) {
                if (header.checksum != 0) return AdbReadResult.Failed(AdbIoFailure.CHECKSUM_MISMATCH, headerOffset)
                return AdbReadResult.Frame(AdbFrame(header.command, header.arg0, header.arg1, ByteArray(0)))
            }
            if (header.dataLength > maxPayloadBytes) {
                return AdbReadResult.Failed(AdbIoFailure.PAYLOAD_TOO_LARGE, headerOffset)
            }

            val payload = ByteArray(header.dataLength)
            var payloadOffset = 0
            while (payloadOffset < payload.size) {
                val requested = payload.size - payloadOffset
                val started = monotonicNanos()
                val read = connection.bulkTransfer(endpointIn, payload, payloadOffset, requested, timeoutMs)
                transferEvent("usb_read", "payload", requested, read, started, timeoutMs)
                if (read <= 0) {
                    val failure = if (payloadOffset == 0) AdbIoFailure.USB_IN_NOT_COMPLETED else AdbIoFailure.SHORT_PAYLOAD
                    return AdbReadResult.Failed(failure, headerOffset + payloadOffset)
                }
                payloadOffset += read
            }
            if (header.checksum != AdbPacketCodec.checksum(payload)) {
                return AdbReadResult.Failed(AdbIoFailure.CHECKSUM_MISMATCH, headerOffset + payloadOffset)
            }
            return AdbReadResult.Frame(AdbFrame(header.command, header.arg0, header.arg1, payload))
        }

        private fun writeFully(phase: String, bytes: ByteArray, timeoutMs: Int): WriteProgress {
            var offset = 0
            while (offset < bytes.size) {
                val requested = bytes.size - offset
                val started = monotonicNanos()
                val sent = connection.bulkTransfer(endpointOut, bytes, offset, requested, timeoutMs)
                transferEvent("usb_write", phase, requested, sent, started, timeoutMs)
                if (sent <= 0) return WriteProgress(offset)
                offset += sent
            }
            return WriteProgress(offset)
        }

        private fun transferEvent(
            code: String,
            phase: String,
            requested: Int,
            result: Int,
            startedNanos: Long,
            timeoutMs: Int,
        ) {
            val finished = monotonicNanos()
            onTransfer(
                code,
                mapOf(
                    "phase" to phase,
                    "requestedBytes" to requested.toString(),
                    "resultBytes" to result.toString(),
                    "callElapsedMs" to ((finished - startedNanos) / 1_000_000L).toString(),
                    "timeoutMs" to timeoutMs.toString(),
                ),
            )
        }

        private data class WriteProgress(val confirmed: Int)
    }

    private companion object {
        const val CATEGORY = "adb_handshake"
        const val ADB_SUBCLASS = 0x42
        const val ADB_PROTOCOL = 0x01
        const val PRE_P_MAX_PAYLOAD_BYTES = 16 * 1024
        const val MODERN_MAX_PAYLOAD_BYTES = 1024 * 1024
    }
}
