package io.github.ncorror.nekoflash.usb.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSessionCoordinatorTest {
    @Test
    fun anAlreadyPermittedDeviceGoesStraightToReady() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = UsbSessionCoordinator(host)

        coordinator.start()

        assertEquals(listOf(UsbSessionState.READY), coordinator.sessions.value.map { it.state })
        assertTrue(host.permissionRequests.isEmpty())
    }

    @Test
    fun aDeviceWithoutPermissionWaitsForTheAnswer() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)

        coordinator.start()

        assertEquals(
            listOf(UsbSessionState.PERMISSION_PENDING),
            coordinator.sessions.value.map { it.state },
        )
        assertEquals(listOf(deviceA.deviceName), host.permissionRequests)
    }

    @Test
    fun grantedPermissionMakesTheSessionReadyAndKeepsItsGeneration() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val opened = coordinator.sessions.value.single()

        coordinator.onPermissionResult(deviceA, granted = true)

        val ready = coordinator.sessions.value.single()
        assertEquals(opened.generation, ready.generation)
        assertEquals(UsbSessionState.READY, ready.state)
    }

    @Test
    fun aSerialLearnedWithPermissionUpgradesTheTargetIdentity() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val beforePermission = coordinator.sessions.value.single()

        coordinator.onPermissionResult(deviceA.copy(serialNumber = "MI9SERIAL"), granted = true)

        val session = coordinator.sessions.value.single()
        assertEquals(TargetIdentitySource.USB_ATTACHMENT, beforePermission.identity.source)
        assertEquals(TargetIdentitySource.SERIAL, session.identity.source)
        assertEquals(session.identity.id, session.targetId)
        assertEquals(beforePermission.generation, session.generation)
    }

    @Test
    fun deniedPermissionClosesTheSessionWithTheRealReason() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val registry = UsbSessionRegistry()
        val coordinator = UsbSessionCoordinator(host, registry)
        coordinator.start()
        val opened = coordinator.sessions.value.single()

        coordinator.onPermissionResult(deviceA, granted = false)

        assertTrue(coordinator.sessions.value.isEmpty())
        assertEquals(
            UsbSessionClosureReason.PERMISSION_DENIED,
            registry.session(opened.generation)?.closureReason,
        )
    }

    @Test
    fun aTimedOutSessionRecordsTimeoutAsItsReason() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val registry = UsbSessionRegistry()
        val coordinator = UsbSessionCoordinator(host, registry)
        coordinator.start()
        val opened = coordinator.sessions.value.single()

        coordinator.onPermissionTimeout(opened.generation, permissionGrantedNow = false)

        assertEquals(
            UsbSessionClosureReason.PERMISSION_TIMED_OUT,
            registry.session(opened.generation)?.closureReason,
        )
    }

    @Test
    fun detachClosesOnlyTheDetachedDevice() {
        val host = FakeUsbHost(
            attached = listOf(deviceA, deviceB),
            permitted = setOf(deviceA.deviceName, deviceB.deviceName),
        )
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()

        coordinator.onDeviceDetached(deviceA)

        assertEquals(
            listOf(deviceB.deviceName),
            coordinator.sessions.value.map { it.candidate.device.deviceName },
        )
    }

    @Test
    fun aDeviceWithoutAUsableInterfaceOpensNoSession() {
        val host = FakeUsbHost(attached = listOf(deviceA.copy(interfaces = emptyList())))
        val coordinator = UsbSessionCoordinator(host)

        coordinator.start()

        assertTrue(coordinator.sessions.value.isEmpty())
        assertTrue(host.permissionRequests.isEmpty())
    }

    @Test
    fun aDeviceLostBeforeTheRequestDoesNotLeaveAHangingSession() {
        val host = FakeUsbHost(attached = listOf(deviceA), requestSucceeds = false)
        val coordinator = UsbSessionCoordinator(host)

        coordinator.start()

        assertTrue(coordinator.sessions.value.isEmpty())
    }

    @Test
    fun repeatedScanDoesNotDisturbAWorkingSession() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val first = coordinator.sessions.value.single()

        coordinator.scanAttachedDevices()

        assertEquals(first.generation, coordinator.sessions.value.single().generation)
    }

    @Test
    fun timeoutClosesTheSessionAndReportsWhyOnlyWhenPermissionIsStillAbsent() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val session = coordinator.sessions.value.single()

        val outcome = coordinator.onPermissionTimeout(session.generation, permissionGrantedNow = false)

        assertEquals(UsbPermissionPolicy.TimeoutOutcome.CLOSE_AND_REPORT, outcome)
        assertTrue(coordinator.sessions.value.isEmpty())
    }

    @Test
    fun aTimeoutArrivingAfterTheAnswerChangesNothing() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val session = coordinator.sessions.value.single()
        coordinator.onPermissionResult(deviceA, granted = true)

        val outcome = coordinator.onPermissionTimeout(session.generation, permissionGrantedNow = true)

        assertEquals(UsbPermissionPolicy.TimeoutOutcome.IGNORE, outcome)
        assertEquals(UsbSessionState.READY, coordinator.sessions.value.single().state)
    }

    @Test
    fun anAnswerWithoutADescriptorChangesNothing() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()

        coordinator.onPermissionResult(device = null, granted = true)

        assertEquals(
            listOf(UsbSessionState.PERMISSION_PENDING),
            coordinator.sessions.value.map { it.state },
        )
    }

    @Test
    fun stoppingKeepsOpenSessionsBecauseDevicesAreStillAttached() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()

        coordinator.stop()

        assertTrue(host.stopped)
        assertEquals(1, coordinator.sessions.value.size)
    }

    @Test
    fun aPermissionRequestAnnouncesItselfSoTheTimeoutCanBeScheduled() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val scheduled = mutableListOf<Long>()
        val coordinator = UsbSessionCoordinator(
            host = host,
            onPermissionRequested = { scheduled += it.value },
        )

        coordinator.start()

        assertEquals(listOf(coordinator.sessions.value.single().generation.value), scheduled)
    }

    @Test
    fun noTimeoutIsScheduledWhenPermissionWasAlreadyGranted() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val scheduled = mutableListOf<Long>()
        val coordinator = UsbSessionCoordinator(
            host = host,
            onPermissionRequested = { scheduled += it.value },
        )

        coordinator.start()

        assertTrue(scheduled.isEmpty())
    }

    private class FakeUsbHost(
        private val attached: List<UsbDeviceDescriptor>,
        private val permitted: Set<String> = emptySet(),
        private val requestSucceeds: Boolean = true,
    ) : UsbHost {
        val permissionRequests = mutableListOf<String>()
        var stopped = false
            private set

        override fun start(listener: UsbHost.Listener) {
            stopped = false
        }

        override fun stop() {
            stopped = true
        }

        override fun devices(): List<UsbDeviceDescriptor> = attached

        override fun hasPermission(device: UsbDeviceDescriptor): Boolean =
            device.deviceName in permitted

        override fun requestPermission(device: UsbDeviceDescriptor): Boolean {
            if (!requestSucceeds) return false
            permissionRequests += device.deviceName
            return true
        }
    }

    private companion object {
        val deviceA = UsbDeviceDescriptor(
            deviceId = 1001,
            deviceName = "/dev/bus/usb/001/002",
            vendorId = 0x2717,
            productId = 0xFF48,
            interfaces = listOf(adbInterface()),
        )

        val deviceB = UsbDeviceDescriptor(
            deviceId = 1002,
            deviceName = "/dev/bus/usb/001/003",
            vendorId = 0x18D1,
            productId = 0x4EE0,
            interfaces = listOf(adbInterface()),
        )

        fun adbInterface() = UsbInterfaceDescriptor(
            id = 0,
            interfaceClass = UsbInterfaceClassifier.USB_CLASS_VENDOR_SPECIFIC,
            interfaceSubclass = UsbInterfaceClassifier.ANDROID_USB_SUBCLASS,
            interfaceProtocol = UsbInterfaceClassifier.ADB_INTERFACE_PROTOCOL,
            endpoints = listOf(
                UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK, 512),
                UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK, 512),
            ),
        )
    }
}
