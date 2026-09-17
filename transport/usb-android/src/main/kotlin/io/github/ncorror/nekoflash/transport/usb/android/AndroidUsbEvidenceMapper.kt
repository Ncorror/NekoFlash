package io.github.ncorror.nekoflash.transport.usb.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

internal object AndroidUsbEvidenceMapper {
    fun map(device: UsbDevice, permissionGranted: Boolean): UsbDeviceEvidence = UsbDeviceEvidence(
        deviceName = device.deviceName,
        deviceId = device.deviceId,
        vendorId = device.vendorId,
        productId = device.productId,
        deviceClass = device.deviceClass,
        deviceSubclass = device.deviceSubclass,
        deviceProtocol = device.deviceProtocol,
        permissionGranted = permissionGranted,
        interfaces = (0 until device.interfaceCount).map { index ->
            val usbInterface = device.getInterface(index)
            UsbInterfaceEvidence(
                index = index,
                id = usbInterface.id,
                alternateSetting = usbInterface.alternateSetting,
                interfaceClass = usbInterface.interfaceClass,
                interfaceSubclass = usbInterface.interfaceSubclass,
                interfaceProtocol = usbInterface.interfaceProtocol,
                endpoints = (0 until usbInterface.endpointCount).map { endpointIndex ->
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    UsbEndpointEvidence(
                        address = endpoint.address,
                        direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                            UsbEndpointDirection.IN
                        } else {
                            UsbEndpointDirection.OUT
                        },
                        transferType = when (endpoint.type) {
                            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> UsbTransferType.CONTROL
                            UsbConstants.USB_ENDPOINT_XFER_ISOC -> UsbTransferType.ISOCHRONOUS
                            UsbConstants.USB_ENDPOINT_XFER_BULK -> UsbTransferType.BULK
                            UsbConstants.USB_ENDPOINT_XFER_INT -> UsbTransferType.INTERRUPT
                            else -> UsbTransferType.UNKNOWN
                        },
                        maxPacketSize = endpoint.maxPacketSize,
                        interval = endpoint.interval,
                    )
                },
            )
        },
    )
}
