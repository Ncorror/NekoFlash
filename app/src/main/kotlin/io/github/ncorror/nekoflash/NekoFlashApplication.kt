package io.github.ncorror.nekoflash

import android.app.Application
import android.os.SystemClock
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.transport.usb.android.AdbUsbHandshakeProbe
import io.github.ncorror.nekoflash.transport.usb.android.UsbAdbAutoFlow
import io.github.ncorror.nekoflash.transport.usb.android.UsbEvidenceProbe
import java.util.UUID

class NekoFlashApplication : Application() {
    val diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink()

    var evidenceSessionId: String = newEvidenceSessionId()
        private set

    lateinit var usbEvidenceProbe: UsbEvidenceProbe
        private set

    lateinit var adbUsbHandshakeProbe: AdbUsbHandshakeProbe
        private set

    lateinit var usbAdbAutoFlow: UsbAdbAutoFlow
        private set

    override fun onCreate() {
        super.onCreate()
        usbEvidenceProbe = UsbEvidenceProbe(this, diagnostics)
        adbUsbHandshakeProbe = AdbUsbHandshakeProbe(this, diagnostics)
        usbAdbAutoFlow = UsbAdbAutoFlow(
            context = this,
            usbProbe = usbEvidenceProbe,
            adbProbe = adbUsbHandshakeProbe,
            diagnostics = diagnostics,
        )
        usbEvidenceProbe.start(usbAdbAutoFlow::onPermissionResult)
        emitSessionStarted(reason = "process_start")
    }

    /**
     * Starts a clean owner-driven evidence session while preserving the Application-scoped USB receiver.
     * Call this before switching the target device so archives cannot silently mix two phones.
     */
    fun beginEvidenceSession(): String {
        check(!adbUsbHandshakeProbe.isRunning) { "ADB handshake probe is running" }
        diagnostics.clear()
        evidenceSessionId = newEvidenceSessionId()
        emitSessionStarted(reason = "manual_reset")
        usbAdbAutoFlow.rearmForEvidenceSession()
        return evidenceSessionId
    }

    private fun emitSessionStarted(reason: String) {
        diagnostics.emit(
            DiagnosticEvent(
                monotonicNanos = SystemClock.elapsedRealtimeNanos(),
                category = "evidence_session",
                code = "session_started",
                fields = mapOf(
                    "sessionId" to evidenceSessionId,
                    "reason" to reason,
                ),
            ),
        )
    }

    private fun newEvidenceSessionId(): String = UUID.randomUUID().toString()
}
