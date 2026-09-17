package io.github.ncorror.nekoflash

import android.app.Application
import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.transport.usb.android.UsbEvidenceProbe

class NekoFlashApplication : Application() {
    val diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink()
    lateinit var usbEvidenceProbe: UsbEvidenceProbe
        private set

    override fun onCreate() {
        super.onCreate()
        usbEvidenceProbe = UsbEvidenceProbe(this, diagnostics).also { it.start() }
    }
}
