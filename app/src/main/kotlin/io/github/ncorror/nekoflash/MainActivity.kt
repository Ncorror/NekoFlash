package io.github.ncorror.nekoflash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.ui.NekoFlashApp
import io.github.ncorror.nekoflash.ui.theme.NekoFlashTheme
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Владение USB живёт на уровне приложения: подключённое устройство не
        // должно теряться при повороте экрана или пересоздании активности.
        val application = application as NekoFlashApplication
        val coordinator = application.usbSessions

        setContent {
            val sessions by coordinator.sessions.collectAsState()
            var exportStatus by remember { mutableStateOf<String?>(null) }
            var claimedGenerations by remember { mutableStateOf(emptySet<Long>()) }

            val savedTemplate = stringResource(R.string.diagnostics_export_done)
            val failedTemplate = stringResource(R.string.diagnostics_export_failed)
            val claimFailedTemplate = stringResource(R.string.usb_claim_failed)

            // Системный диалог сохранения: файл создаёт пользователь там, где
            // ему нужно, а приложение не заводит собственного хранилища отчётов.
            val saveLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip"),
            ) { destination ->
                if (destination == null) return@rememberLauncherForActivityResult
                exportStatus = runCatching { application.writeDiagnostics(destination) }
                    .fold(
                        onSuccess = { result -> savedTemplate.format(result.sectionCount) },
                        onFailure = { failure ->
                            failedTemplate.format(failure.message ?: failure.javaClass.simpleName)
                        },
                    )
            }

            NekoFlashTheme {
                NekoFlashApp(
                    sessions = sessions,
                    exportStatus = exportStatus,
                    claimedGenerations = claimedGenerations,
                    onRescanUsb = { coordinator.scanAttachedDevices() },
                    onClaim = { session ->
                        when (val result = coordinator.claim(session.generation)) {
                            is UsbClaimResult.Claimed ->
                                claimedGenerations = claimedGenerations + session.generation.value

                            is UsbClaimResult.Failed ->
                                exportStatus = claimFailedTemplate.format(result.reason.name)
                        }
                    },
                    onRelease = { session ->
                        coordinator.release(session.generation)
                        claimedGenerations = claimedGenerations - session.generation.value
                    },
                    onExportDiagnostics = {
                        saveLauncher.launch(application.suggestedDiagnosticsFileName())
                    },
                )
            }
        }
    }
}
