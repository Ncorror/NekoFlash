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
import io.github.ncorror.nekoflash.usb.api.TargetIdentitySource
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NekoFlashApp(
    sessions: List<UsbSession> = emptyList(),
    exportStatus: String? = null,
    onRescanUsb: () -> Unit = {},
    onExportDiagnostics: () -> Unit = {},
) {
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
                    Workspace(
                        sessions = sessions,
                        exportStatus = exportStatus,
                        onRescanUsb = onRescanUsb,
                        onExportDiagnostics = onExportDiagnostics,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Workspace(
                    sessions = sessions,
                    exportStatus = exportStatus,
                    onRescanUsb = onRescanUsb,
                    onExportDiagnostics = onExportDiagnostics,
                    modifier = Modifier.fillMaxSize(),
                )
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
    exportStatus: String?,
    onRescanUsb: () -> Unit,
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

        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.sessions_empty),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            sessions.forEach { session -> SessionCard(session) }
            Text(
                text = stringResource(R.string.mode_requires_handshake),
                style = MaterialTheme.typography.bodySmall,
            )
        }

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
}

@Composable
private fun SessionCard(session: UsbSession) {
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
        }
    }
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
