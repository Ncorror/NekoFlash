package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbFileState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.usb.api.UsbScanSummary
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState

@OptIn(ExperimentalMaterial3Api::class)
/** Название и подзаголовок приложения: своя вещь, и в теле экрана ей тесно. */
@Composable
private fun NekoFlashTopBar() {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.app_name))
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    )
}

@Composable
fun NekoFlashApp(
    sessions: List<UsbSession> = emptyList(),
    scan: UsbScanSummary = UsbScanSummary.NEVER_SCANNED,
    usbHostSupported: Boolean = true,
    exportStatus: String? = null,
    adbLink: AdbLinkState = AdbLinkState.Idle,
    adbCommand: AdbCommandState = AdbCommandState.None,
    terminal: AdbTerminalState = AdbTerminalState(),
    files: AdbFileState = AdbFileState.None,
    onRescanUsb: () -> Unit = {},
    onClaim: (UsbSession) -> Unit = {},
    onRelease: (UsbSession) -> Unit = {},
    onAdbConnect: (UsbSession) -> Unit = {},
    onAdbDisconnect: (UsbSession) -> Unit = {},
    onRunCommand: (String) -> Unit = {},
    reboot: RebootPanel = RebootPanel(),
    rawService: RawServicePanel = RawServicePanel(),
    forward: ForwardPanel = ForwardPanel(),
    reverse: ReversePanel = ReversePanel(),
    fastboot: FastbootPanel = FastbootPanel(),
    terminalActions: TerminalActions = TerminalActions(),
    fileActions: FileActions = FileActions(),
    onExportDiagnostics: () -> Unit = {},
) {
    // Рабочая область одинакова в обеих раскладках и отличается только тем,
    // как занимает место. Список аргументов длинный, и два его экземпляра уже
    // однажды разъехались, поэтому он существует в одном месте.
    val workspace: @Composable (Modifier) -> Unit = { modifier ->
        Workspace(
            sessions = sessions,
            scan = scan,
            usbHostSupported = usbHostSupported,
            exportStatus = exportStatus,
            adbLink = adbLink,
            adbCommand = adbCommand,
            terminal = terminal,
            terminalActions = terminalActions,
            files = files,
            fileActions = fileActions,
            onRescanUsb = onRescanUsb,
            onClaim = onClaim,
            onRelease = onRelease,
            onAdbConnect = onAdbConnect,
            onAdbDisconnect = onAdbDisconnect,
            onRunCommand = onRunCommand,
            reboot = reboot,
            rawService = rawService,
            forward = forward,
            reverse = reverse,
            fastboot = fastboot,
            onExportDiagnostics = onExportDiagnostics,
            modifier = modifier,
        )
    }

    Scaffold(topBar = { NekoFlashTopBar() }) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (maxWidth >= 840.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ProjectNavigation(
                        modifier = Modifier
                            .width(240.dp)
                            .fillMaxSize(),
                    )
                    workspace(Modifier.weight(1f))
                }
            } else {
                workspace(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ProjectNavigation(modifier: Modifier = Modifier) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.nav_workspace), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.nav_device))
            Text(stringResource(R.string.nav_terminal))
            Text(stringResource(R.string.nav_operations))
            Text(stringResource(R.string.nav_diagnostics))
        }
    }
}

@Composable
private fun Workspace(
    sessions: List<UsbSession>,
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    exportStatus: String?,
    adbLink: AdbLinkState,
    adbCommand: AdbCommandState,
    terminal: AdbTerminalState,
    terminalActions: TerminalActions,
    files: AdbFileState,
    fileActions: FileActions,
    onRescanUsb: () -> Unit,
    onClaim: (UsbSession) -> Unit,
    onRelease: (UsbSession) -> Unit,
    onAdbConnect: (UsbSession) -> Unit,
    onAdbDisconnect: (UsbSession) -> Unit,
    onRunCommand: (String) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
    fastboot: FastbootPanel,
    onExportDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.sessions_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        SessionList(
            sessions = sessions,
            scan = scan,
            usbHostSupported = usbHostSupported,
            adbLink = adbLink,
            adbCommand = adbCommand,
            terminal = terminal,
            terminalActions = terminalActions,
            files = files,
            fileActions = fileActions,
            onClaim = onClaim,
            onRelease = onRelease,
            onAdbConnect = onAdbConnect,
            onAdbDisconnect = onAdbDisconnect,
            onRunCommand = onRunCommand,
            reboot = reboot,
            rawService = rawService,
            forward = forward,
            reverse = reverse,
            fastboot = fastboot,
        )
        ActionsCard(
            exportStatus = exportStatus,
            onRescanUsb = onRescanUsb,
            onExportDiagnostics = onExportDiagnostics,
        )
        BuildBaselineCard()
    }
}

@Composable
private fun SessionList(
    sessions: List<UsbSession>,
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    adbLink: AdbLinkState,
    adbCommand: AdbCommandState,
    terminal: AdbTerminalState,
    terminalActions: TerminalActions,
    files: AdbFileState,
    fileActions: FileActions,
    onClaim: (UsbSession) -> Unit,
    onRelease: (UsbSession) -> Unit,
    onAdbConnect: (UsbSession) -> Unit,
    onAdbDisconnect: (UsbSession) -> Unit,
    onRunCommand: (String) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
    fastboot: FastbootPanel,
) {
    if (sessions.isEmpty()) {
        Text(
            text = stringResource(R.string.sessions_empty),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Пустой список сам по себе ничего не объясняет. Разбор 2026-09-04
        // показал цену этого молчания: по выгруженному evidence нельзя было
        // сказать, видит ли система устройство вообще.
        Text(
            text = emptyStateReason(scan, usbHostSupported),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    sessions.forEach { session ->
        SessionCard(
            session = session,
            adbLink = adbLink,
            adbCommand = adbCommand,
            terminal = terminal,
            terminalActions = terminalActions,
            files = files,
            fileActions = fileActions,
            onClaim = { onClaim(session) },
            onRelease = { onRelease(session) },
            onAdbConnect = { onAdbConnect(session) },
            onAdbDisconnect = { onAdbDisconnect(session) },
            onRunCommand = onRunCommand,
            reboot = reboot,
            rawService = rawService,
            forward = forward,
            reverse = reverse,
            fastboot = fastboot,
        )
    }
    Text(
        text = stringResource(R.string.mode_requires_handshake),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ActionsCard(
    exportStatus: String?,
    onRescanUsb: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onRescanUsb) {
                Text(stringResource(R.string.usb_rescan))
            }
            Text(
                text = stringResource(R.string.usb_rescan_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onExportDiagnostics) {
                Text(stringResource(R.string.diagnostics_export))
            }
            if (exportStatus != null) {
                Text(text = exportStatus, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BuildBaselineCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.build_baseline_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.build_baseline_values))
            Text(stringResource(R.string.professional_capability_policy))
        }
    }
}

/**
 * Пять строк, которыми сессия себя называет.
 *
 * Вынесены из карточки не ради длины, а потому что это одна вещь: кто перед
 * нами, чем опознан, в какой роли, в каком состоянии и в каком поколении.
 * Читать их порознь незачем.
 */
@Composable
private fun SessionIdentity(session: UsbSession) {
    LabelledValue(
        label = stringResource(R.string.target_label),
        value = session.targetId.value,
    )
    LabelledValue(
        label = stringResource(R.string.session_identity_label),
        value = localizedIdentitySource(session.identity.source),
    )
    LabelledValue(
        label = stringResource(R.string.session_interface_label),
        value = localizedInterfaceKind(session.candidate.kind) + " · " +
            localizedMatchConfidence(session.candidate.confidence),
    )
    LabelledValue(
        label = stringResource(R.string.session_state_label),
        value = localizedSessionState(session.state),
    )
    LabelledValue(
        label = stringResource(R.string.session_generation_label),
        value = session.generation.value.toString(),
    )
}

@Composable
private fun SessionCard(
    session: UsbSession,
    adbLink: AdbLinkState,
    adbCommand: AdbCommandState,
    terminal: AdbTerminalState,
    terminalActions: TerminalActions,
    files: AdbFileState,
    fileActions: FileActions,
    onClaim: () -> Unit,
    onRelease: () -> Unit,
    onAdbConnect: () -> Unit,
    onAdbDisconnect: () -> Unit,
    onRunCommand: (String) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
    fastboot: FastbootPanel,
) {
    // Удерживается ли интерфейс, видно по самому состоянию сессии. Отдельный
    // список захваченных был бы вторым источником истины о том же самом.
    val claimed = session.state == UsbSessionState.CLAIMED
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SessionIdentity(session = session)

            if (session.state == UsbSessionState.READY || session.state == UsbSessionState.CLAIMED) {
                // У интерфейса ADB владение одно: подключение захватывает
                // интерфейс, отключение отпускает. Отдельная кнопка захвата
                // была вторым органом управления тем же ресурсом, и прогон
                // 2026-09-03 показал, к чему это приводит: интерфейс отпущен,
                // а экран продолжает утверждать, что ADB подключён.
                // Захват без протокольного обмена ничего не даёт, поэтому
                // терять здесь нечего.
                if (session.candidate.kind == UsbInterfaceKind.ADB) {
                    AdbLinkSection(
                        session = session,
                        adbLink = adbLink,
                        adbCommand = adbCommand,
                        terminal = terminal,
                        terminalActions = terminalActions,
                        files = files,
                        fileActions = fileActions,
                        onAdbConnect = onAdbConnect,
                        onAdbDisconnect = onAdbDisconnect,
                        onRunCommand = onRunCommand,
                        reboot = reboot,
                        rawService = rawService,
                        forward = forward,
                        reverse = reverse,
                    )
                } else if (session.candidate.kind == UsbInterfaceKind.FASTBOOT) {
                    FastbootLinkSection(fastboot = fastboot)
                    Text(
                        text = stringResource(R.string.fastboot_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Button(onClick = if (claimed) onRelease else onClaim) {
                        Text(
                            stringResource(
                                if (claimed) R.string.usb_release else R.string.usb_claim,
                            ),
                        )
                    }
                    Text(
                        text = stringResource(R.string.usb_claim_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
