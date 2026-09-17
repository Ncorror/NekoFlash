package io.github.ncorror.nekoflash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.ncorror.nekoflash.ui.NekoFlashTheme
import io.github.ncorror.nekoflash.ui.Phase2UsbEvidenceScreen
import io.github.ncorror.nekoflash.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NekoFlashApplication
        setContent {
            NekoFlashTheme {
                var welcomeCompleted by rememberSaveable { mutableStateOf(false) }
                if (welcomeCompleted) {
                    Phase2UsbEvidenceScreen(
                        probe = app.usbEvidenceProbe,
                        diagnostics = app.diagnostics,
                        runId = app.evidenceRunId,
                    )
                } else {
                    WelcomeScreen(onContinue = { welcomeCompleted = true })
                }
            }
        }
    }
}
