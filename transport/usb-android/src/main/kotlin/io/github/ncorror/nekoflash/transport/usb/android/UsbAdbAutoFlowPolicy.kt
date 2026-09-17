package io.github.ncorror.nekoflash.transport.usb.android

sealed interface UsbAdbAutoSelection {
    data object None : UsbAdbAutoSelection

    data class Candidate(
        val deviceName: String,
        val deviceId: Int,
        val permissionGranted: Boolean,
        val interfaceIndex: Int,
    ) : UsbAdbAutoSelection

    data class Ambiguous(
        val deviceNames: List<String>,
        val adbInterfaceCount: Int,
    ) : UsbAdbAutoSelection
}

/**
 * Pure policy for the ordinary automatic ADB entry path.
 *
 * Auto-flow is intentionally narrower than manual diagnostics: exactly one physical device with
 * exactly one canonical ADB interface shape may advance automatically. Generic vendor bulk
 * interfaces and multi-device/multi-interface ambiguity stay visible for manual inspection.
 */
object UsbAdbAutoFlowPolicy {
    fun select(devices: List<UsbDeviceEvidence>): UsbAdbAutoSelection {
        val adbDevices = devices.filter { it.adbInterfaceIndexes.isNotEmpty() }
        if (adbDevices.isEmpty()) return UsbAdbAutoSelection.None

        val totalInterfaces = adbDevices.sumOf { it.adbInterfaceIndexes.size }
        if (adbDevices.size != 1 || totalInterfaces != 1) {
            return UsbAdbAutoSelection.Ambiguous(
                deviceNames = adbDevices.map { it.deviceName },
                adbInterfaceCount = totalInterfaces,
            )
        }

        val device = adbDevices.single()
        return UsbAdbAutoSelection.Candidate(
            deviceName = device.deviceName,
            deviceId = device.deviceId,
            permissionGranted = device.permissionGranted,
            interfaceIndex = device.adbInterfaceIndexes.single(),
        )
    }
}
