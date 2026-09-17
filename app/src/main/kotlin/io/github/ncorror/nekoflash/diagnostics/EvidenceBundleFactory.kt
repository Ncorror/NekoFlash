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

/** Builds the current Phase 2 multi-file evidence payload without reading arbitrary filesystem content. */
object EvidenceBundleFactory {
    data class Snapshot(
        val exportedAt: Instant,
        val sections: List<DiagnosticBundleSection>,
    )

    fun capture(
        context: Context,
        runId: String,
        events: List<DiagnosticEvent>,
        devices: List<UsbDeviceEvidence>,
        exportedAt: Instant = Instant.now(),
    ): Snapshot {
        val exportedAtEpochMillis = exportedAt.toEpochMilli()
        val usbEvents = events.filter { it.category == USB_CATEGORY }

        return Snapshot(
            exportedAt = exportedAt,
            sections = listOf(
                DiagnosticBundleSection(
                    "summary.txt",
                    summaryText(runId, events, usbEvents, devices, exportedAt),
                ),
                DiagnosticBundleSection(
                    "usb-events.txt",
                    formatDiagnosticEvidence(usbEvents, exportedAtEpochMillis),
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
                    sessionInfoText(runId, events, exportedAt),
                ),
            ),
        )
    }

    private fun summaryText(
        runId: String,
        events: List<DiagnosticEvent>,
        usbEvents: List<DiagnosticEvent>,
        devices: List<UsbDeviceEvidence>,
        exportedAt: Instant,
    ): String = buildString {
        appendLine("schema=io.github.ncorror.nekoflash.phase2-usb-summary.v1")
        appendLine("scope=phase2-usb-evidence")
        appendLine("runId=$runId")
        appendLine("exportedAt=$exportedAt")
        appendLine("protocolBytesSent=false")
        appendLine("eventCount=${events.size}")
        appendLine("usbEventCount=${usbEvents.size}")
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
        devices.forEachIndexed { index, device ->
            appendLine("device[$index].vidPid=${device.vidPid()}")
            appendLine("device[$index].permissionGranted=${device.permissionGranted}")
            appendLine("device[$index].bulkPairInterfaces=${device.bulkPairInterfaceIndexes.joinToString(",")}")
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
        appendLine("scope=usb-evidence")
    }

    private fun sessionInfoText(
        runId: String,
        events: List<DiagnosticEvent>,
        exportedAt: Instant,
    ): String = buildString {
        appendLine("schema=io.github.ncorror.nekoflash.evidence-session.v1")
        appendLine("runId=$runId")
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
}
