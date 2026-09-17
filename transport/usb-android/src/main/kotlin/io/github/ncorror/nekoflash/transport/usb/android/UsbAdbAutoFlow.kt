package io.github.ncorror.nekoflash.transport.usb.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeOutcome
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Application-owned auto-first USB -> permission -> ADB handshake orchestration.
 *
 * This restores the useful Legacy/A2 entry behavior without reviving a god coordinator. It owns
 * only discovery/permission sequencing and invokes the already bounded ADB handshake probe.
 * Generic vendor bulk candidates, multiple ADB candidates and terminal transport failures are not
 * retried or guessed automatically; the existing manual evidence controls remain the fallback.
 */
class UsbAdbAutoFlow(
    context: Context,
    private val usbProbe: UsbEvidenceProbe,
    private val adbProbe: AdbUsbHandshakeProbe,
    private val diagnostics: DiagnosticSink,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val monotonicNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val handledDeviceKeys = mutableSetOf<String>()

    @Volatile
    private var started = false

    private var detachReceiverRegistered = false
    private var pendingPermissionDeviceName: String? = null
    private var pendingPermissionDeviceKey: String? = null
    private var pendingScan: Runnable? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = intent.usbDeviceExtra()
            if (device == null) {
                emit("auto_detach_missing_device")
                return
            }
            val key = deviceKey(device.deviceId, device.deviceName)
            synchronized(lock) {
                handledDeviceKeys.remove(key)
                if (pendingPermissionDeviceName == device.deviceName) {
                    pendingPermissionDeviceName = null
                    pendingPermissionDeviceKey = null
                }
            }
            emit(
                "auto_device_detached",
                mapOf(
                    "deviceName" to device.deviceName,
                    "deviceId" to device.deviceId.toString(),
                ),
            )
            scheduleAdvance(reason = "detach_remaining", delayMs = DETACH_RESCAN_DELAY_MS)
        }
    }

    val isStarted: Boolean
        get() = started

    /** Starts ordinary automation only after the app entry/welcome gate is authorized. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        registerDetachReceiver()
        emit("auto_flow_started", mapOf("startupDelayMs" to STARTUP_SCAN_DELAY_MS.toString()))
        scheduleAdvance(reason = "startup", delayMs = STARTUP_SCAN_DELAY_MS)
    }

    /**
     * Receives the Activity attach intent because Android's USB attach contract is Activity-facing.
     * A pre-entry attach may be ignored here; the delayed startup scan will still observe it later.
     */
    fun handleActivityIntent(intent: Intent?): Boolean {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        if (intent.getBooleanExtra(EXTRA_USB_INTENT_CONSUMED, false)) return false
        intent.putExtra(EXTRA_USB_INTENT_CONSUMED, true)

        val device = intent.usbDeviceExtra()
        emit(
            if (started) "auto_attach_received" else "auto_attach_deferred_until_entry",
            mapOf(
                "deviceName" to (device?.deviceName ?: "<missing>"),
                "deviceId" to (device?.deviceId?.toString() ?: "<missing>"),
            ),
        )
        if (started) scheduleAdvance(reason = "attach", delayMs = ATTACH_SETTLE_DELAY_MS)
        return true
    }

    /** Permission callback from the Application-scoped UsbEvidenceProbe receiver. */
    fun onPermissionResult(result: UsbPermissionResult) {
        if (!started) return
        val pendingKey = synchronized(lock) {
            if (result.deviceName == pendingPermissionDeviceName) {
                val key = pendingPermissionDeviceKey
                pendingPermissionDeviceName = null
                pendingPermissionDeviceKey = null
                key
            } else {
                null
            }
        }

        if (!result.granted) {
            if (pendingKey != null) synchronized(lock) { handledDeviceKeys.add(pendingKey) }
            emit(
                "auto_permission_denied",
                mapOf("deviceName" to (result.deviceName ?: "<missing>")),
            )
            return
        }

        emit(
            "auto_permission_granted",
            mapOf("deviceName" to (result.deviceName ?: "<missing>")),
        )
        scheduleAdvance(reason = "permission_granted", delayMs = PERMISSION_SETTLE_DELAY_MS)
    }

    /**
     * A new evidence session is an explicit owner action and therefore re-arms the current device.
     * It does not recreate USB receivers or silently retry a failed transfer in the same session.
     */
    fun rearmForEvidenceSession() {
        if (!started) return
        synchronized(lock) {
            handledDeviceKeys.clear()
            pendingPermissionDeviceName = null
            pendingPermissionDeviceKey = null
        }
        emit("auto_flow_rearmed_for_evidence_session")
        scheduleAdvance(reason = "evidence_session", delayMs = 0L)
    }

    private fun scheduleAdvance(reason: String, delayMs: Long) {
        if (!started) return
        handler.post {
            synchronized(lock) {
                pendingScan?.let(handler::removeCallbacks)
                val runnable = Runnable {
                    synchronized(lock) { pendingScan = null }
                    executor.execute { advance(reason) }
                }
                pendingScan = runnable
                handler.postDelayed(runnable, delayMs)
            }
        }
    }

    private fun advance(reason: String) {
        if (!started) return
        val devices = usbProbe.scan()
        when (val selection = UsbAdbAutoFlowPolicy.select(devices)) {
            UsbAdbAutoSelection.None -> emit(
                "auto_no_canonical_adb_candidate",
                mapOf(
                    "reason" to reason,
                    "deviceCount" to devices.size.toString(),
                ),
            )

            is UsbAdbAutoSelection.Ambiguous -> emit(
                "auto_candidate_ambiguous",
                mapOf(
                    "reason" to reason,
                    "deviceCount" to selection.deviceNames.size.toString(),
                    "adbInterfaceCount" to selection.adbInterfaceCount.toString(),
                ),
            )

            is UsbAdbAutoSelection.Candidate -> advanceCandidate(reason, selection)
        }
    }

    private fun advanceCandidate(reason: String, candidate: UsbAdbAutoSelection.Candidate) {
        val key = deviceKey(candidate.deviceId, candidate.deviceName)
        val suppression = synchronized(lock) {
            when {
                key in handledDeviceKeys -> "handled"
                pendingPermissionDeviceName != null -> "permission_pending"
                else -> null
            }
        }
        if (suppression != null) {
            emit(
                "auto_candidate_suppressed",
                mapOf(
                    "reason" to reason,
                    "deviceName" to candidate.deviceName,
                    "suppression" to suppression,
                ),
            )
            return
        }

        emit(
            "auto_candidate_selected",
            mapOf(
                "reason" to reason,
                "deviceName" to candidate.deviceName,
                "deviceId" to candidate.deviceId.toString(),
                "interfaceIndex" to candidate.interfaceIndex.toString(),
                "permissionGranted" to candidate.permissionGranted.toString(),
            ),
        )

        if (!candidate.permissionGranted) {
            when (val result = usbProbe.requestPermission(candidate.deviceName)) {
                is UsbPermissionRequestResult.Requested -> synchronized(lock) {
                    pendingPermissionDeviceName = candidate.deviceName
                    pendingPermissionDeviceKey = key
                }
                is UsbPermissionRequestResult.AlreadyGranted -> runHandshake(key, candidate.deviceName)
                is UsbPermissionRequestResult.DeviceMissing -> emit(
                    "auto_permission_device_missing",
                    mapOf("deviceName" to result.deviceName),
                )
                is UsbPermissionRequestResult.Failed -> {
                    synchronized(lock) { handledDeviceKeys.add(key) }
                    emit(
                        "auto_permission_request_failed",
                        mapOf(
                            "deviceName" to result.deviceName,
                            "reason" to result.reason,
                        ),
                    )
                }
            }
            return
        }

        runHandshake(key, candidate.deviceName)
    }

    private fun runHandshake(key: String, deviceName: String) {
        synchronized(lock) { handledDeviceKeys.add(key) }
        emit("auto_adb_handshake_started", mapOf("deviceName" to deviceName))
        val result = adbProbe.probe(deviceName)
        val fields = mutableMapOf(
            "deviceName" to deviceName,
            "result" to result.javaClass.simpleName,
        )
        if (result is AdbUsbProbeResult.Completed) {
            fields["outcome"] = when (result.outcome) {
                is AdbHandshakeOutcome.Connected -> "CONNECTED"
                is AdbHandshakeOutcome.TransportFailed -> "TRANSPORT_FAILED"
                is AdbHandshakeOutcome.ProtocolFailed -> "PROTOCOL_FAILED"
                is AdbHandshakeOutcome.AuthKeyFailed -> "AUTH_KEY_FAILED"
                AdbHandshakeOutcome.AuthResponseLimit -> "AUTH_RESPONSE_LIMIT"
            }
        }
        emit("auto_adb_handshake_finished", fields)

        if (result is AdbUsbProbeResult.Busy || result is AdbUsbProbeResult.DeviceMissing) {
            synchronized(lock) { handledDeviceKeys.remove(key) }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!started) return
            started = false
            pendingScan?.let(handler::removeCallbacks)
            pendingScan = null
            pendingPermissionDeviceName = null
            pendingPermissionDeviceKey = null
            handledDeviceKeys.clear()
        }
        if (detachReceiverRegistered) {
            detachReceiverRegistered = false
            runCatching { appContext.unregisterReceiver(detachReceiver) }
        }
        executor.shutdownNow()
        emit("auto_flow_stopped")
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDetachReceiver() {
        if (detachReceiverRegistered) return
        appContext.registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        detachReceiverRegistered = true
    }

    private fun deviceKey(deviceId: Int, deviceName: String): String = "$deviceId|$deviceName"

    private fun emit(code: String, fields: Map<String, String> = emptyMap()) {
        diagnostics.emit(
            DiagnosticEvent(
                monotonicNanos = monotonicNanos(),
                category = "usb_auto_flow",
                code = code,
                fields = fields,
            ),
        )
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private companion object {
        const val STARTUP_SCAN_DELAY_MS = 350L
        const val ATTACH_SETTLE_DELAY_MS = 100L
        const val PERMISSION_SETTLE_DELAY_MS = 50L
        const val DETACH_RESCAN_DELAY_MS = 100L
        const val EXTRA_USB_INTENT_CONSUMED = "io.github.ncorror.nekoflash.AUTO_USB_INTENT_CONSUMED"
    }
}
