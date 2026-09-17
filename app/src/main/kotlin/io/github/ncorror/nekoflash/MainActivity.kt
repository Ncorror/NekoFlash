package io.github.ncorror.nekoflash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
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
                LaunchedEffect(welcomeCompleted) {
                    if (welcomeCompleted) {
                        app.usbAdbAutoFlow.start()
                        app.usbAdbAutoFlow.handleActivityIntent(intent)
                    }
                }
                if (welcomeCompleted) {
                    Phase2UsbEvidenceScreen(
                        probe = app.usbEvidenceProbe,
                        adbProbe = app.adbUsbHandshakeProbe,
                        diagnostics = app.diagnostics,
                        initialSessionId = app.evidenceSessionId,
                        beginNewSession = app::beginEvidenceSession,
                    )
                } else {
                    WelcomeScreen(onContinue = { welcomeCompleted = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as NekoFlashApplication
        app.usbAdbAutoFlow.handleActivityIntent(intent)
    }
}
