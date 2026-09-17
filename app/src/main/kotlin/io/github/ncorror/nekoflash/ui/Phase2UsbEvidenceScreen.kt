package io.github.ncorror.nekoflash.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticBundle
import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.core.diagnostics.formatDiagnosticEvidence
import io.github.ncorror.nekoflash.diagnostics.EvidenceBundleFactory
import io.github.ncorror.nekoflash.diagnostics.EvidenceBundleIo
import io.github.ncorror.nekoflash.transport.usb.android.UsbDeviceEvidence
import io.github.ncorror.nekoflash.transport.usb.android.UsbEvidenceProbe
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val evidenceFileTimestamp = DateTimeFormatter
    .ofPattern("yyyyMMdd-HHmmss'Z'")
    .withZone(ZoneOffset.UTC)

@Composable
fun Phase2UsbEvidenceScreen(
    probe: UsbEvidenceProbe,
    diagnostics: InMemoryDiagnosticSink,
    initialSessionId: String,
    beginNewSession: () -> String,
) {
    val context = LocalContext.current
    val evidenceSaveCancelled = stringResource(R.string.usb_evidence_save_cancelled)
    val evidenceSaved = stringResource(R.string.usb_evidence_saved)
    val evidenceSaveFailed = stringResource(R.string.usb_evidence_save_failed)
    val evidenceShareTitle = stringResource(R.string.usb_share_full_evidence)
    val zipSaved = stringResource(R.string.usb_evidence_zip_saved)
    val zipSaveFailed = stringResource(R.string.usb_evidence_zip_save_failed)
    val zipPreparing = stringResource(R.string.usb_evidence_zip_preparing)
    val zipShareFailed = stringResource(R.string.usb_evidence_zip_share_failed)
    val zipShareTitle = stringResource(R.string.usb_share_evidence_zip)
    val newSessionStarted = stringResource(R.string.usb_evidence_session_started)
    var sessionId by remember { mutableStateOf(initialSessionId) }
    var targetLabel by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf<List<UsbDeviceEvidence>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var evidenceLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingExportText by remember { mutableStateOf("") }
    var pendingZipSnapshot by remember { mutableStateOf<EvidenceBundleFactory.Snapshot?>(null) }

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

    /** Re-read UsbManager immediately before export so permission/device state cannot be stale UI state. */
    fun refreshDevicesForExport(): List<UsbDeviceEvidence> {
        val freshDevices = probe.scan()
        devices = freshDevices
        refreshEvidence()
        return freshDevices
    }

    fun fullEvidenceText(): String {
        refreshDevicesForExport()
        return formatDiagnosticEvidence(
            events = diagnostics.snapshot(),
            generatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    fun captureZip(): EvidenceBundleFactory.Snapshot {
        val freshDevices = refreshDevicesForExport()
        return EvidenceBundleFactory.capture(
            context = context,
            sessionId = sessionId,
            targetLabel = targetLabel,
            events = diagnostics.snapshot(),
            devices = freshDevices,
        )
    }

    val saveEvidence = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) {
            status = evidenceSaveCancelled
        } else {
            val saved = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter(Charsets.UTF_8)
                    ?.use { writer -> writer.write(pendingExportText) }
                    ?: error("Content resolver returned no output stream")
            }.isSuccess
            status = if (saved) evidenceSaved else evidenceSaveFailed
        }
    }

    val saveEvidenceZip = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val snapshot = pendingZipSnapshot
        if (uri == null || snapshot == null) {
            status = evidenceSaveCancelled
        } else {
            status = zipPreparing
            Thread {
                val saved = runCatching {
                    EvidenceBundleIo.writeDocument(context, uri, snapshot)
                }.isSuccess
                Handler(Looper.getMainLooper()).post {
                    status = if (saved) zipSaved else zipSaveFailed
                }
            }.start()
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
        Text(
            text = stringResource(R.string.usb_evidence_session_id, sessionId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = targetLabel,
            onValueChange = { targetLabel = it },
            label = { Text(stringResource(R.string.usb_target_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                sessionId = beginNewSession()
                targetLabel = ""
                devices = emptyList()
                pendingExportText = ""
                pendingZipSnapshot = null
                status = newSessionStarted
                refreshEvidence()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.usb_new_evidence_session))
        }
        Text(
            text = stringResource(R.string.usb_new_evidence_session_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = "permission=${device.permissionGranted} · interfaces=${device.interfaces.size} · bulk-pair interfaces=[${device.bulkPairInterfaceIndexes.joinToString()}]",
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
        Button(
            onClick = {
                val snapshot = captureZip()
                pendingZipSnapshot = snapshot
                saveEvidenceZip.launch(DiagnosticBundle.suggestedFileName(snapshot.exportedAt))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.usb_save_evidence_zip))
        }
        OutlinedButton(
            onClick = {
                val snapshot = captureZip()
                status = zipPreparing
                Thread {
                    val uri = runCatching { EvidenceBundleIo.writeShareCache(context, snapshot) }.getOrNull()
                    Handler(Looper.getMainLooper()).post {
                        if (uri == null) {
                            status = zipShareFailed
                        } else {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_SUBJECT, DiagnosticBundle.suggestedFileName(snapshot.exportedAt))
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, zipShareTitle))
                        }
                    }
                }.start()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.usb_share_evidence_zip))
        }
        OutlinedButton(
            onClick = {
                pendingExportText = fullEvidenceText()
                saveEvidence.launch(
                    "NekoFlash-usb-evidence-${evidenceFileTimestamp.format(Instant.now())}.txt",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.usb_save_full_evidence))
        }
        OutlinedButton(
            onClick = {
                val text = fullEvidenceText()
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "NekoFlash USB evidence")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(shareIntent, evidenceShareTitle))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.usb_share_full_evidence))
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
