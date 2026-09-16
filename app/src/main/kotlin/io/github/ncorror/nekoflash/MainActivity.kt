package io.github.ncorror.nekoflash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.ncorror.nekoflash.ui.NekoFlashTheme
import io.github.ncorror.nekoflash.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NekoFlashTheme {
                WelcomeScreen()
            }
        }
    }
}
