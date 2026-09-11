package io.github.ncorror.nekoflash

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import io.github.ncorror.nekoflash.ui.NekoFlashApp
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.ui.FastbootPanel
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.ui.FileActions
import io.github.ncorror.nekoflash.ui.ForwardPanel
import io.github.ncorror.nekoflash.ui.RawServicePanel
import io.github.ncorror.nekoflash.ui.RebootPanel
import io.github.ncorror.nekoflash.ui.ReversePanel
import io.github.ncorror.nekoflash.ui.TerminalActions
import io.github.ncorror.nekoflash.ui.theme.NekoFlashTheme
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Владение USB живёт на уровне приложения: подключённое устройство не
        // должно теряться при повороте экрана или пересоздании активности.
        val application = application as NekoFlashApplication
        val coordinator = application.usbSessions
        val adbLink = application.adbLink
        val fastbootLink = application.fastbootLink

        setContent {
            val sessions by coordinator.sessions.collectAsState()
            val linkState by adbLink.state.collectAsState()
            val scan by coordinator.lastScan.collectAsState()
            val commandState by adbLink.command.collectAsState()
            val terminalState by adbLink.terminal.collectAsState()
            val fileState by adbLink.files.collectAsState()
            val fastbootState by fastbootLink.state.collectAsState()
            var exportStatus by remember { mutableStateOf<String?>(null) }

            val savedTemplate = stringResource(R.string.diagnostics_export_done)
            val failedTemplate = stringResource(R.string.diagnostics_export_failed)
            val claimFailedTemplate = stringResource(R.string.usb_claim_failed)

            // Системный диалог сохранения: файл создаёт пользователь там, где
            // ему нужно, а приложение не заводит собственного хранилища отчётов.
            val saveLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip"),
            ) { destination ->
                exportDiagnostics(application, destination, savedTemplate, failedTemplate) { message ->
                    exportStatus = message
                }
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
                    fileActions = FileActions(
                        onDescribe = adbLink::describeFile,
                        onRead = adbLink::readFile,
                        onWrite = adbLink::writeFile,
                    ),
                    onRescanUsb = { coordinator.scanAttachedDevices() },
                    onClaim = claimAction(coordinator, claimFailedTemplate) { exportStatus = it },
                    onRelease = { session -> coordinator.release(session.generation) },
                    onAdbConnect = { session -> adbLink.connect(session.generation) },
                    onAdbDisconnect = { session -> adbLink.disconnect(session.generation) },
                    onRunCommand = adbLink::runCommand,
                    reboot = rebootPanel(adbLink),
                    rawService = rawServicePanel(adbLink),
                    forward = forwardPanel(adbLink),
                    reverse = reversePanel(adbLink),
                    fastboot = fastbootPanel(fastbootLink, fastbootState, sessions),
                    onExportDiagnostics = { saveLauncher.launch(application.suggestedDiagnosticsFileName()) },
                )
            }
        }
    }
}

/** Проводка панели перезагрузки: состояние экрана и действие контроллера. */
@Composable
private fun rebootPanel(adbLink: AdbLinkController): RebootPanel = RebootPanel(
    state = adbLink.reboot.collectAsState().value,
    onReboot = adbLink::requestReboot,
)

/** То же для произвольного сервиса. */
@Composable
private fun rawServicePanel(adbLink: AdbLinkController): RawServicePanel = RawServicePanel(
    state = adbLink.rawService.collectAsState().value,
    onCall = adbLink::callRawService,
)

/** То же для пробросов портов. */
@Composable
private fun forwardPanel(adbLink: AdbLinkController): ForwardPanel = ForwardPanel(
    state = adbLink.forward.collectAsState().value,
    onAdd = adbLink.forwards::add,
    onRemove = adbLink.forwards::remove,
)

/** То же для обратных пробросов. */
@Composable
private fun reversePanel(adbLink: AdbLinkController): ReversePanel = ReversePanel(
    state = adbLink.reverse.collectAsState().value,
    onAdd = adbLink.reverses::add,
    onRefresh = adbLink.reverses::refresh,
    onRemoveAll = adbLink.reverses::removeAll,
)

private fun MainActivity.exportDiagnostics(
    application: NekoFlashApplication,
    destination: Uri?,
    savedTemplate: String,
    failedTemplate: String,
    onStatus: (String) -> Unit,
) {
    if (destination == null) return
    lifecycleScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching { application.writeDiagnostics(destination) }
        }
        onStatus(
            outcome.fold(
                onSuccess = { result -> savedTemplate.format(result.sectionCount) },
                onFailure = { failure ->
                    failedTemplate.format(failure.message ?: failure.javaClass.simpleName)
                },
            ),
        )
    }
}

/** Действия терминала собраны отдельно: в теле экрана они только шумят. */
/**
 * Панель Fastboot: состояние опроса и два действия над ним.
 *
 * Вынесена из `onCreate` не ради красоты — иначе метод перерастает ориентир
 * длины, а раздувать точку входа именно тем, что легко вынести, значит начинать
 * тот путь, которым `MainActivity` Legacy дошла до 3880 строк.
 */
private fun fastbootPanel(
    link: FastbootLinkController,
    state: FastbootLinkState,
    sessions: List<UsbSession>,
): FastbootPanel = FastbootPanel(
    state = state,
    // Generation берётся из живой сессии: опрос принадлежит тому подключению,
    // в котором он начат.
    onProbe = {
        sessions.firstOrNull { it.candidate.kind == UsbInterfaceKind.FASTBOOT }
            ?.let { session -> link.connect(session.generation) }
    },
    onDisconnect = link::disconnect,
)

private fun terminalActions(link: AdbLinkController) = TerminalActions(
    onStart = link::startShell,
    onSend = link::sendShellInput,
    onInterrupt = link::interruptShell,
    onStop = link::stopShell,
)

/** Преобразует технический результат claim в короткое UI-сообщение. */
private fun claimAction(
    coordinator: UsbSessionCoordinator,
    failedTemplate: String,
    onFailure: (String) -> Unit,
): (UsbSession) -> Unit = { session ->
    val result = coordinator.claim(session.generation)
    if (result is UsbClaimResult.Failed) {
        onFailure(failedTemplate.format(result.reason.name))
    }
}
