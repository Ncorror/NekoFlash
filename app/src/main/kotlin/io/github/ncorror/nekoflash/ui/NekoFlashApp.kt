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
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.usb.api.TargetIdentitySource
import io.github.ncorror.nekoflash.usb.api.UsbScanSummary
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NekoFlashApp(
    sessions: List<UsbSession> = emptyList(),
    scan: UsbScanSummary = UsbScanSummary.NEVER_SCANNED,
    usbHostSupported: Boolean = true,
    exportStatus: String? = null,
    adbLink: AdbLinkState = AdbLinkState.Idle,
    onRescanUsb: () -> Unit = {},
    onClaim: (UsbSession) -> Unit = {},
    onRelease: (UsbSession) -> Unit = {},
    onAdbConnect: (UsbSession) -> Unit = {},
    onAdbDisconnect: (UsbSession) -> Unit = {},
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
            onRescanUsb = onRescanUsb,
            onClaim = onClaim,
            onRelease = onRelease,
            onAdbConnect = onAdbConnect,
            onAdbDisconnect = onAdbDisconnect,
            onExportDiagnostics = onExportDiagnostics,
            modifier = modifier,
        )
    }

    Scaffold(
        topBar = {
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
        },
    ) { innerPadding ->
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
    onRescanUsb: () -> Unit,
    onClaim: (UsbSession) -> Unit,
    onRelease: (UsbSession) -> Unit,
    onAdbConnect: (UsbSession) -> Unit,
    onAdbDisconnect: (UsbSession) -> Unit,
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
            onClaim = onClaim,
            onRelease = onRelease,
            onAdbConnect = onAdbConnect,
            onAdbDisconnect = onAdbDisconnect,
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
    onClaim: (UsbSession) -> Unit,
    onRelease: (UsbSession) -> Unit,
    onAdbConnect: (UsbSession) -> Unit,
    onAdbDisconnect: (UsbSession) -> Unit,
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
            onClaim = { onClaim(session) },
            onRelease = { onRelease(session) },
            onAdbConnect = { onAdbConnect(session) },
            onAdbDisconnect = { onAdbDisconnect(session) },
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

@Composable
private fun SessionCard(
    session: UsbSession,
    adbLink: AdbLinkState,
    onClaim: () -> Unit,
    onRelease: () -> Unit,
    onAdbConnect: () -> Unit,
    onAdbDisconnect: () -> Unit,
) {
    // Удерживается ли интерфейс, видно по самому состоянию сессии. Отдельный
    // список захваченных был бы вторым источником истины о том же самом.
    val claimed = session.state == UsbSessionState.CLAIMED
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
                        onAdbConnect = onAdbConnect,
                        onAdbDisconnect = onAdbDisconnect,
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

/**
 * Состояние ADB-соединения для конкретной сессии.
 *
 * Показывается только у этой сессии: соединение принадлежит одному поколению,
 * и показывать его состояние рядом с чужим устройством означало бы сказать
 * неправду о том, к чему относится «подключено».
 */
@Composable
private fun AdbLinkSection(
    session: UsbSession,
    adbLink: AdbLinkState,
    onAdbConnect: () -> Unit,
    onAdbDisconnect: () -> Unit,
) {
    val linkForThisSession = adbLink.takeIf { it.generationOrNull() == session.generation }
    val connected = linkForThisSession is AdbLinkState.Connected
    val busy = linkForThisSession is AdbLinkState.Connecting ||
        linkForThisSession is AdbLinkState.WaitingForAuthorization

    LabelledValue(
        label = stringResource(R.string.adb_state_label),
        value = adbLinkText(linkForThisSession),
    )
    if (linkForThisSession is AdbLinkState.Connected) {
        LabelledValue(
            label = stringResource(R.string.adb_features_label),
            value = linkForThisSession.features.sorted().joinToString(", ").ifEmpty {
                stringResource(R.string.adb_features_none)
            },
        )
    }
    Button(
        onClick = if (connected) onAdbDisconnect else onAdbConnect,
        enabled = !busy,
    ) {
        Text(
            stringResource(
                if (connected) R.string.adb_disconnect else R.string.adb_connect,
            ),
        )
    }
    Text(
        text = stringResource(R.string.adb_connect_hint),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun AdbLinkState.generationOrNull(): SessionGeneration? = when (this) {
    AdbLinkState.Idle -> null
    is AdbLinkState.Connecting -> generation
    is AdbLinkState.WaitingForAuthorization -> generation
    is AdbLinkState.Connected -> generation
    is AdbLinkState.Failed -> generation
}

@Composable
private fun adbLinkText(state: AdbLinkState?): String = when (state) {
    null, AdbLinkState.Idle -> stringResource(R.string.adb_state_idle)
    is AdbLinkState.Connecting -> stringResource(R.string.adb_state_connecting)
    is AdbLinkState.WaitingForAuthorization -> stringResource(R.string.adb_state_waiting)
    is AdbLinkState.Connected ->
        stringResource(R.string.adb_state_connected, localizedPeerMode(state.peerMode))

    is AdbLinkState.Failed ->
        stringResource(R.string.adb_state_failed, state.reason.name, state.detail)
}

@Composable
private fun localizedPeerMode(mode: AdbPeerMode): String = stringResource(
    when (mode) {
        AdbPeerMode.DEVICE -> R.string.peer_mode_device
        AdbPeerMode.RECOVERY -> R.string.peer_mode_recovery
        AdbPeerMode.SIDELOAD -> R.string.peer_mode_sideload
        AdbPeerMode.UNKNOWN -> R.string.peer_mode_unknown
    },
)

/**
 * Почему список пуст.
 *
 * Отсутствие host-режима важнее всего остального: без него разбирать нечего, и
 * говорить про кабель было бы неправдой.
 */
@Composable
private fun emptyStateReason(scan: UsbScanSummary, usbHostSupported: Boolean): String = when {
    !usbHostSupported -> stringResource(R.string.usb_host_feature_missing)
    !scan.scanned -> stringResource(R.string.usb_scan_never)
    scan.visibleDevices == 0 -> stringResource(R.string.usb_scan_none)
    else -> stringResource(R.string.usb_scan_unusable, scan.visibleDevices)
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun localizedSessionState(state: UsbSessionState): String = stringResource(
    when (state) {
        UsbSessionState.DISCOVERED -> R.string.session_state_discovered
        UsbSessionState.PERMISSION_PENDING -> R.string.session_state_permission_pending
        UsbSessionState.READY -> R.string.session_state_ready
        UsbSessionState.CLAIMED -> R.string.session_state_claimed
        UsbSessionState.CLOSED -> R.string.session_state_closed
    },
)

@Composable
private fun localizedIdentitySource(source: TargetIdentitySource): String = stringResource(
    when (source) {
        TargetIdentitySource.SERIAL -> R.string.identity_source_serial
        TargetIdentitySource.USB_ATTACHMENT -> R.string.identity_source_attachment
    },
)

@Composable
private fun localizedInterfaceKind(kind: UsbInterfaceKind): String = stringResource(
    when (kind) {
        UsbInterfaceKind.ADB -> R.string.interface_kind_adb
        UsbInterfaceKind.FASTBOOT -> R.string.interface_kind_fastboot
    },
)

@Composable
private fun localizedMatchConfidence(confidence: UsbMatchConfidence): String = stringResource(
    when (confidence) {
        UsbMatchConfidence.CANONICAL -> R.string.match_confidence_canonical
        UsbMatchConfidence.ANDROID_COMPATIBLE -> R.string.match_confidence_android_compatible
        UsbMatchConfidence.GENERIC_VENDOR -> R.string.match_confidence_generic_vendor
    },
)
