package io.github.ncorror.nekoflash.transport.usb.android

enum class UsbEndpointDirection {
    IN,
    OUT,
}

enum class UsbTransferType {
    CONTROL,
    ISOCHRONOUS,
    BULK,
    INTERRUPT,
    UNKNOWN,
}

data class UsbEndpointEvidence(
    val address: Int,
    val direction: UsbEndpointDirection,
    val transferType: UsbTransferType,
    val maxPacketSize: Int,
    val interval: Int,
)

data class UsbInterfaceEvidence(
    val index: Int,
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointEvidence>,
) {
    val hasBulkIn: Boolean
        get() = endpoints.any { it.transferType == UsbTransferType.BULK && it.direction == UsbEndpointDirection.IN }

    val hasBulkOut: Boolean
        get() = endpoints.any { it.transferType == UsbTransferType.BULK && it.direction == UsbEndpointDirection.OUT }

    val hasBulkPair: Boolean
        get() = hasBulkIn && hasBulkOut
}

data class UsbDeviceEvidence(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val permissionGranted: Boolean,
    val interfaces: List<UsbInterfaceEvidence>,
) {
    val bulkPairInterfaceIndexes: List<Int>
        get() = interfaces.filter { it.hasBulkPair }.map { it.index }

    fun vidPid(): String = "%04X:%04X".format(vendorId, productId)
}

sealed interface UsbPermissionRequestResult {
    data class Requested(val deviceName: String) : UsbPermissionRequestResult
    data class AlreadyGranted(val deviceName: String) : UsbPermissionRequestResult
    data class DeviceMissing(val deviceName: String) : UsbPermissionRequestResult
    data class Failed(val deviceName: String, val reason: String) : UsbPermissionRequestResult
}

data class UsbPermissionResult(
    val deviceName: String?,
    val granted: Boolean,
)

sealed interface UsbOpenClaimResult {
    val deviceName: String

    data class PermissionRequired(override val deviceName: String) : UsbOpenClaimResult
    data class DeviceMissing(override val deviceName: String) : UsbOpenClaimResult
    data class OpenFailed(override val deviceName: String, val reason: String? = null) : UsbOpenClaimResult
    data class NoBulkPair(override val deviceName: String) : UsbOpenClaimResult
    data class ClaimFailed(override val deviceName: String, val attemptedInterfaceIndexes: List<Int>) : UsbOpenClaimResult
    data class ClaimSucceeded(override val deviceName: String, val interfaceIndex: Int, val interfaceId: Int) : UsbOpenClaimResult
}
