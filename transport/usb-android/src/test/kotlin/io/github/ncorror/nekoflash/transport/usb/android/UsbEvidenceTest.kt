package io.github.ncorror.nekoflash.transport.usb.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbEvidenceTest {
    @Test
    fun pocoAdbDescriptorShapeIsRecognizedAsBulkPairWithoutVendorBan() {
        val evidence = device(
            vendorId = 0x18D1,
            productId = 0x4EE7,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x01,
        )

        assertEquals("18D1:4EE7", evidence.vidPid())
        assertEquals(listOf(0), evidence.bulkPairInterfaceIndexes)
    }

    @Test
    fun bulkPairEligibilityDoesNotDependOnClassSubclassOrProtocol() {
        val evidence = device(
            vendorId = 0x2717,
            productId = 0xFFFF,
            interfaceClass = 0x12,
            interfaceSubclass = 0x34,
            interfaceProtocol = 0x56,
        )

        assertEquals(listOf(0), evidence.bulkPairInterfaceIndexes)
    }

    @Test
    fun interfaceWithoutBothBulkDirectionsIsNotClaimCandidate() {
        val oneWay = UsbInterfaceEvidence(
            index = 0,
            id = 7,
            alternateSetting = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x01,
            endpoints = listOf(
                endpoint(0x81, UsbEndpointDirection.IN),
            ),
        )

        assertTrue(oneWay.hasBulkIn)
        assertFalse(oneWay.hasBulkOut)
        assertFalse(oneWay.hasBulkPair)
    }

    private fun device(
        vendorId: Int,
        productId: Int,
        interfaceClass: Int,
        interfaceSubclass: Int,
        interfaceProtocol: Int,
    ): UsbDeviceEvidence = UsbDeviceEvidence(
        deviceName = "/dev/bus/usb/test",
        deviceId = 1,
        vendorId = vendorId,
        productId = productId,
        deviceClass = 0,
        deviceSubclass = 0,
        deviceProtocol = 0,
        permissionGranted = true,
        interfaces = listOf(
            UsbInterfaceEvidence(
                index = 0,
                id = 0,
                alternateSetting = 0,
                interfaceClass = interfaceClass,
                interfaceSubclass = interfaceSubclass,
                interfaceProtocol = interfaceProtocol,
                endpoints = listOf(
                    endpoint(0x01, UsbEndpointDirection.OUT),
                    endpoint(0x81, UsbEndpointDirection.IN),
                ),
            ),
        ),
    )

    private fun endpoint(address: Int, direction: UsbEndpointDirection): UsbEndpointEvidence = UsbEndpointEvidence(
        address = address,
        direction = direction,
        transferType = UsbTransferType.BULK,
        maxPacketSize = 512,
        interval = 0,
    )
}
