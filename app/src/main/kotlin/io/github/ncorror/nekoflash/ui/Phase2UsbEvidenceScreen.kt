package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.transport.usb.android.UsbDeviceEvidence
import io.github.ncorror.nekoflash.transport.usb.android.UsbEvidenceProbe

@Composable
fun Phase2UsbEvidenceScreen(
    probe: UsbEvidenceProbe,
    diagnostics: InMemoryDiagnosticSink,
) {
    var devices by remember { mutableStateOf<List<UsbDeviceEvidence>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var evidenceLines by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refreshEvidence() {
        evidenceLines = diagnostics.snapshot().takeLast(30).map { event ->
            buildString {
                append(event.code)
                if (event.fields.isNotEmpty()) {
                    append(" · ")
                    append(event.fields.entries.joinToString(" · ") { (key, value) -> "$key=$value" })
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.phase2_usb_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.phase2_usb_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Button(
            onClick = {
                devices = probe.scan()
                status = "deviceCount=${devices.size}"
                refreshEvidence()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.usb_scan))
        }

        if (status.isNotBlank()) {
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        devices.forEach { device ->
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${device.vidPid()} · id=${device.deviceId}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "permission=${device.permissionGranted} · interfaces=${device.interfaces.size} · bulk-pair=${device.bulkPairInterfaceIndexes.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                device.interfaces.forEach { usbInterface ->
                    Text(
                        text = "if#${usbInterface.index} id=${usbInterface.id} ${usbInterface.interfaceClass}/${usbInterface.interfaceSubclass}/${usbInterface.interfaceProtocol} " +
                            usbInterface.endpoints.joinToString(" ") { endpoint ->
                                "0x%02X %s %s mps=%d".format(
                                    endpoint.address,
                                    endpoint.direction,
                                    endpoint.transferType,
                                    endpoint.maxPacketSize,
                                )
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = {
                        status = probe.requestPermission(device.deviceName).toString()
                        devices = probe.scan()
                        refreshEvidence()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.usb_permission))
                }
                Button(
                    onClick = {
                        status = probe.probeOpenAndClaim(device.deviceName).toString()
                        devices = probe.scan()
                        refreshEvidence()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.usb_probe_open_claim))
                }
            }
        }

        OutlinedButton(
            onClick = { refreshEvidence() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.usb_refresh_evidence))
        }
        Text(
            text = stringResource(R.string.usb_evidence_log),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        evidenceLines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
