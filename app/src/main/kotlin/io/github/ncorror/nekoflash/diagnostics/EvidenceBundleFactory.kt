package io.github.ncorror.nekoflash.diagnostics

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.SystemClock
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticBundleSection
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.formatDiagnosticEvidence
import io.github.ncorror.nekoflash.transport.usb.android.UsbDeviceEvidence
import java.time.Instant

/** Builds one explicit per-target Phase 2 evidence payload without reading arbitrary filesystem content. */
object EvidenceBundleFactory {
    data class Snapshot(
        val exportedAt: Instant,
        val sections: List<DiagnosticBundleSection>,
    )

    fun capture(
        context: Context,
        sessionId: String,
        targetLabel: String,
        events: List<DiagnosticEvent>,
        devices: List<UsbDeviceEvidence>,
        exportedAt: Instant = Instant.now(),
    ): Snapshot {
        val exportedAtEpochMillis = exportedAt.toEpochMilli()
        val usbEvents = events.filter { it.category == USB_CATEGORY }
        val adbEvents = events.filter { it.category == ADB_CATEGORY }
        val cleanTargetLabel = singleLine(targetLabel).trim()

        return Snapshot(
            exportedAt = exportedAt,
            sections = listOf(
                DiagnosticBundleSection(
                    "summary.txt",
                    summaryText(sessionId, cleanTargetLabel, events, usbEvents, adbEvents, devices, exportedAt),
                ),
                DiagnosticBundleSection(
                    "usb-events.txt",
                    formatDiagnosticEvidence(usbEvents, exportedAtEpochMillis),
                ),
                DiagnosticBundleSection(
                    "adb-events.txt",
                    formatDiagnosticEvidence(adbEvents, exportedAtEpochMillis),
                ),
                DiagnosticBundleSection(
                    "usb-descriptors.txt",
                    usbDescriptorsText(devices),
                ),
                DiagnosticBundleSection(
                    "device-info.txt",
                    deviceInfoText(context),
                ),
                DiagnosticBundleSection(
                    "app-build.txt",
                    appBuildText(context),
                ),
                DiagnosticBundleSection(
                    "session-info.txt",
                    sessionInfoText(sessionId, cleanTargetLabel, events, exportedAt),
                ),
            ),
        )
    }

    private fun summaryText(
        sessionId: String,
        targetLabel: String,
        events: List<DiagnosticEvent>,
        usbEvents: List<DiagnosticEvent>,
        adbEvents: List<DiagnosticEvent>,
        devices: List<UsbDeviceEvidence>,
        exportedAt: Instant,
    ): String = buildString {
        appendLine("schema=io.github.ncorror.nekoflash.phase2-transport-summary.v3")
        appendLine("scope=phase2-usb-adb-handshake-evidence")
        appendLine("sessionId=$sessionId")
        appendLine("targetLabel=${targetLabel.ifBlank { "<unset>" }}")
        appendLine("exportedAt=$exportedAt")
        appendLine("protocolBytesSent=${adbEvents.any { it.code == "packet_tx_attempt" }}")
        appendLine("eventCount=${events.size}")
        appendLine("usbEventCount=${usbEvents.size}")
        appendLine("adbEventCount=${adbEvents.size}")
        appendLine("adbPacketTxAttemptCount=${adbEvents.count { it.code == "packet_tx_attempt" }}")
        appendLine("adbPacketRxCount=${adbEvents.count { it.code == "packet_rx" }}")
        appendLine("deviceCount=${devices.size}")
        appendLine("permissionGrantedDeviceCount=${devices.count { it.permissionGranted }}")
        appendLine("bulkPairDeviceCount=${devices.count { it.bulkPairInterfaceIndexes.isNotEmpty() }}")
        appendLine("openSucceededCount=${usbEvents.count { it.code == "open_succeeded" }}")
        appendLine(
            "claimSucceededCount=${usbEvents.count { event ->
                event.code == "claim_result" && event.fields["claimed"] == "true"
            }}",
        )
        usbEvents.lastOrNull()?.let { event -> appendLine("latestUsbEvent=${singleLine(event.code)}") }
        adbEvents.lastOrNull { it.code == "handshake_finished" }?.let { event ->
            appendLine("latestAdbOutcome=${singleLine(event.fields["outcome"] ?: "unknown")}")
        }
        devices.forEachIndexed { index, device ->
            appendLine("device[$index].vidPid=${device.vidPid()}")
            appendLine("device[$index].permissionGranted=${device.permissionGranted}")
            appendLine("device[$index].bulkPairInterfaces=${device.bulkPairInterfaceIndexes.joinToString(",")}")
            appendLine("device[$index].adbInterfaces=${device.adbInterfaceIndexes.joinToString(",")}")
        }
    }

    private fun usbDescriptorsText(devices: List<UsbDeviceEvidence>): String = buildString {
        appendLine("schema=io.github.ncorror.nekoflash.usb-descriptors.v1")
        appendLine("deviceCount=${devices.size}")
        devices.forEachIndexed { deviceIndex, device ->
            appendLine("device[$deviceIndex].name=${singleLine(device.deviceName)}")
            appendLine("device[$deviceIndex].id=${device.deviceId}")
            appendLine("device[$deviceIndex].vidPid=${device.vidPid()}")
            appendLine("device[$deviceIndex].class=${device.deviceClass}")
            appendLine("device[$deviceIndex].subclass=${device.deviceSubclass}")
            appendLine("device[$deviceIndex].protocol=${device.deviceProtocol}")
            appendLine("device[$deviceIndex].permissionGranted=${device.permissionGranted}")
            appendLine("device[$deviceIndex].interfaceCount=${device.interfaces.size}")
            appendLine("device[$deviceIndex].bulkPairInterfaces=${device.bulkPairInterfaceIndexes.joinToString(",")}")
            appendLine("device[$deviceIndex].adbInterfaces=${device.adbInterfaceIndexes.joinToString(",")}")
            device.interfaces.forEachIndexed { interfaceIndex, usbInterface ->
                val prefix = "device[$deviceIndex].interface[$interfaceIndex]"
                appendLine("$prefix.index=${usbInterface.index}")
                appendLine("$prefix.id=${usbInterface.id}")
                appendLine("$prefix.alternateSetting=${usbInterface.alternateSetting}")
                appendLine("$prefix.class=${usbInterface.interfaceClass}")
                appendLine("$prefix.subclass=${usbInterface.interfaceSubclass}")
                appendLine("$prefix.protocol=${usbInterface.interfaceProtocol}")
                appendLine("$prefix.hasBulkIn=${usbInterface.hasBulkIn}")
                appendLine("$prefix.hasBulkOut=${usbInterface.hasBulkOut}")
                usbInterface.endpoints.forEachIndexed { endpointIndex, endpoint ->
                    val endpointPrefix = "$prefix.endpoint[$endpointIndex]"
                    appendLine("$endpointPrefix.address=0x%02X".format(endpoint.address))
                    appendLine("$endpointPrefix.direction=${endpoint.direction}")
                    appendLine("$endpointPrefix.type=${endpoint.transferType}")
                    appendLine("$endpointPrefix.maxPacketSize=${endpoint.maxPacketSize}")
                    appendLine("$endpointPrefix.interval=${endpoint.interval}")
                }
            }
        }
    }

    private fun deviceInfoText(context: Context): String = buildString {
        appendLine("schema=io.github.ncorror.nekoflash.host-device.v1")
        appendLine("manufacturer=${singleLine(Build.MANUFACTURER)}")
        appendLine("brand=${singleLine(Build.BRAND)}")
        appendLine("model=${singleLine(Build.MODEL)}")
        appendLine("device=${singleLine(Build.DEVICE)}")
        appendLine("product=${singleLine(Build.PRODUCT)}")
        appendLine("hardware=${singleLine(Build.HARDWARE)}")
        appendLine("androidRelease=${singleLine(Build.VERSION.RELEASE)}")
        appendLine("sdkInt=${Build.VERSION.SDK_INT}")
        appendLine("supportedAbis=${Build.SUPPORTED_ABIS.joinToString(",") { singleLine(it) }}")
        appendLine("usbHostFeature=${context.packageManager.hasSystemFeature("android.hardware.usb.host")}")
    }

    private fun appBuildText(context: Context): String = buildString {
        appendLine("schema=io.github.ncorror.nekoflash.app-build.v1")
        appendLine("applicationId=${singleLine(context.packageName)}")
        val packageInfo = runCatching { packageInfo(context) }.getOrNull()
        if (packageInfo == null) {
            appendLine("versionName=unavailable")
            appendLine("versionCode=unavailable")
        } else {
            appendLine("versionName=${singleLine(packageInfo.versionName ?: "unavailable")}")
            appendLine("versionCode=${versionCode(packageInfo)}")
        }
        appendLine("phase=2")
        appendLine("scope=usb-adb-handshake-evidence")
    }

    private fun sessionInfoText(
        sessionId: String,
        targetLabel: String,
        events: List<DiagnosticEvent>,
        exportedAt: Instant,
    ): String = buildString {
        appendLine("schema=io.github.ncorror.nekoflash.evidence-session.v2")
        appendLine("sessionId=$sessionId")
        appendLine("targetLabel=${targetLabel.ifBlank { "<unset>" }}")
        appendLine("exportedAt=$exportedAt")
        appendLine("processElapsedRealtimeMillis=${SystemClock.elapsedRealtime()}")
        appendLine("eventCount=${events.size}")
        appendLine("firstMonotonicNanos=${events.firstOrNull()?.monotonicNanos ?: "none"}")
        appendLine("lastMonotonicNanos=${events.lastOrNull()?.monotonicNanos ?: "none"}")
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, 0)

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun singleLine(value: String): String = value.replace('\n', ' ').replace('\r', ' ')

    private const val USB_CATEGORY = "usb_evidence"
    private const val ADB_CATEGORY = "adb_handshake"
}
