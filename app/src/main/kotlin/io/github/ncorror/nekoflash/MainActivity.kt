package io.github.ncorror.nekoflash

import android.content.pm.PackageManager
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
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.ui.FileActions
import io.github.ncorror.nekoflash.ui.TerminalActions
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
        val adbLink = application.adbLink

        setContent {
            val sessions by coordinator.sessions.collectAsState()
            val linkState by adbLink.state.collectAsState()
            val scan by coordinator.lastScan.collectAsState()
            val commandState by adbLink.command.collectAsState()
            val terminalState by adbLink.terminal.collectAsState()
            val fileState by adbLink.files.collectAsState()
            var exportStatus by remember { mutableStateOf<String?>(null) }

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
                    scan = scan,
                    usbHostSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
                    exportStatus = exportStatus,
                    adbLink = linkState,
                    adbCommand = commandState,
                    terminal = terminalState,
                    terminalActions = terminalActions(adbLink),
                    files = fileState,
                    fileActions = FileActions(adbLink::describeFile, adbLink::readFile),
                    onRescanUsb = { coordinator.scanAttachedDevices() },
                    onClaim = { session ->
                        val result = coordinator.claim(session.generation)
                        if (result is UsbClaimResult.Failed) {
                            exportStatus = claimFailedTemplate.format(result.reason.name)
                        }
                    },
                    onRelease = { session -> coordinator.release(session.generation) },
                    onAdbConnect = { session -> adbLink.connect(session.generation) },
                    onAdbDisconnect = { session -> adbLink.disconnect(session.generation) },
                    onRunCommand = adbLink::runCommand,
                    onExportDiagnostics = {
                        saveLauncher.launch(application.suggestedDiagnosticsFileName())
                    },
                )
            }
        }
    }
}

/** Действия терминала собраны отдельно: в теле экрана они только шумят. */
private fun terminalActions(link: AdbLinkController) = TerminalActions(
    onStart = link::startShell,
    onSend = link::sendShellInput,
    onInterrupt = link::interruptShell,
    onStop = link::stopShell,
)
