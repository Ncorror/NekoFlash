package io.github.ncorror.nekoflash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.ncorror.nekoflash.ui.NekoFlashTheme
import io.github.ncorror.nekoflash.ui.Phase1ReadyScreen
import io.github.ncorror.nekoflash.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NekoFlashTheme {
                var welcomeCompleted by rememberSaveable { mutableStateOf(false) }
                if (welcomeCompleted) {
                    Phase1ReadyScreen()
                } else {
                    WelcomeScreen(onContinue = { welcomeCompleted = true })
                }
            }
        }
    }
}
