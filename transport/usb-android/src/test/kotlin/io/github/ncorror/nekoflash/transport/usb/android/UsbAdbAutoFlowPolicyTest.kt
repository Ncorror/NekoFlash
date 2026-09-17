package io.github.ncorror.nekoflash.transport.usb.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAdbAutoFlowPolicyTest {
    @Test
    fun exactlyOneCanonicalAdbDeviceIsSelectedAutomaticallyWithoutVendorWhitelist() {
        val result = UsbAdbAutoFlowPolicy.select(
            listOf(device("/dev/a", 7, 0x2717, permission = false, adbInterfaces = listOf(2))),
        )

        assertEquals(
            UsbAdbAutoSelection.Candidate(
                deviceName = "/dev/a",
                deviceId = 7,
                permissionGranted = false,
                interfaceIndex = 2,
            ),
            result,
        )
    }

    @Test
    fun genericBulkOnlyDeviceDoesNotAutoAdvance() {
        val result = UsbAdbAutoFlowPolicy.select(
            listOf(device("/dev/generic", 3, 0x18D1, permission = true, adbInterfaces = emptyList())),
        )

        assertEquals(UsbAdbAutoSelection.None, result)
    }

    @Test
    fun multipleCanonicalAdbDevicesStayManual() {
        val result = UsbAdbAutoFlowPolicy.select(
            listOf(
                device("/dev/a", 1, 0x18D1, permission = true, adbInterfaces = listOf(0)),
                device("/dev/b", 2, 0x2717, permission = true, adbInterfaces = listOf(0)),
            ),
        )

        assertTrue(result is UsbAdbAutoSelection.Ambiguous)
        result as UsbAdbAutoSelection.Ambiguous
        assertEquals(2, result.deviceNames.size)
        assertEquals(2, result.adbInterfaceCount)
    }

    @Test
    fun multipleAdbInterfacesOnOneDeviceStayManual() {
        val result = UsbAdbAutoFlowPolicy.select(
            listOf(device("/dev/a", 1, 0x18D1, permission = true, adbInterfaces = listOf(0, 1))),
        )

        assertTrue(result is UsbAdbAutoSelection.Ambiguous)
        result as UsbAdbAutoSelection.Ambiguous
        assertEquals(listOf("/dev/a"), result.deviceNames)
        assertEquals(2, result.adbInterfaceCount)
    }

    @Test
    fun permissionStateIsCarriedIntoAutomaticCandidateDecision() {
        val denied = UsbAdbAutoFlowPolicy.select(
            listOf(device("/dev/a", 1, 0x18D1, permission = false, adbInterfaces = listOf(0))),
        ) as UsbAdbAutoSelection.Candidate
        val granted = UsbAdbAutoFlowPolicy.select(
            listOf(device("/dev/a", 1, 0x18D1, permission = true, adbInterfaces = listOf(0))),
        ) as UsbAdbAutoSelection.Candidate

        assertEquals(false, denied.permissionGranted)
        assertEquals(true, granted.permissionGranted)
    }

    private fun device(
        name: String,
        id: Int,
        vendorId: Int,
        permission: Boolean,
        adbInterfaces: List<Int>,
    ): UsbDeviceEvidence {
        val interfaces = buildList {
            val maxIndex = adbInterfaces.maxOrNull() ?: 0
            for (index in 0..maxIndex) {
                val adb = index in adbInterfaces
                add(
                    UsbInterfaceEvidence(
                        index = index,
                        id = index,
                        alternateSetting = 0,
                        interfaceClass = if (adb) 0xFF else 0x12,
                        interfaceSubclass = if (adb) 0x42 else 0x34,
                        interfaceProtocol = if (adb) 0x01 else 0x56,
                        endpoints = listOf(
                            endpoint(0x01, UsbEndpointDirection.OUT),
                            endpoint(0x81, UsbEndpointDirection.IN),
                        ),
                    ),
                )
            }
        }
        return UsbDeviceEvidence(
            deviceName = name,
            deviceId = id,
            vendorId = vendorId,
            productId = 0x0001,
            deviceClass = 0,
            deviceSubclass = 0,
            deviceProtocol = 0,
            permissionGranted = permission,
            interfaces = interfaces,
        )
    }

    private fun endpoint(address: Int, direction: UsbEndpointDirection): UsbEndpointEvidence = UsbEndpointEvidence(
        address = address,
        direction = direction,
        transferType = UsbTransferType.BULK,
        maxPacketSize = 512,
        interval = 0,
    )
}
