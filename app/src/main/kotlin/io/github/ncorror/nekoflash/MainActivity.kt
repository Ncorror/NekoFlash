package io.github.ncorror.nekoflash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ncorror.nekoflash.ui.NekoFlashApp
import io.github.ncorror.nekoflash.ui.theme.NekoFlashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NekoFlashTheme {
                NekoFlashApp()
            }
        }
    }
}
