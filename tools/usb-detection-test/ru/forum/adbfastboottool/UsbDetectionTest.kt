package ru.forum.adbfastboottool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

private fun bulkPair(): List<UsbEndpoint> = listOf(
    UsbEndpoint(UsbConstants.USB_DIR_IN, 0x81),
    UsbEndpoint(UsbConstants.USB_DIR_OUT, 0x01)
)

private fun iface(
    id: Int = 0,
    cls: Int = 255,
    sub: Int = 66,
    proto: Int = 3,
    endpoints: List<UsbEndpoint> = bulkPair()
) = UsbInterface(id, cls, sub, proto, endpoints)


private fun genericVendorInterfaceForRebind(id: Int): UsbInterface = iface(
    id = id,
    sub = 0,
    proto = 0
)

private fun device(
    name: String,
    vid: Int = 0x18D1,
    pid: Int = 0x4EE0,
    vararg interfaces: UsbInterface
) = UsbDevice(name, vid, pid, "Test", interfaces.toList())

private fun requireCheck(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val adb = device("/usb/adb", interfaces = arrayOf(iface(proto = 1)))
    val adbCandidates = UsbDeviceInspector.findCandidates(adb)
    requireCheck(adbCandidates.size == 1, "canonical ADB candidate count")
    requireCheck(adbCandidates.single().mode == UsbDeviceInspector.Mode.ADB, "canonical ADB mode")
    requireCheck(adbCandidates.single().matchKind == UsbDeviceInspector.MatchKind.CANONICAL, "canonical ADB kind")

    val fastboot = device("/usb/fb", pid = 0x4EE1, interfaces = arrayOf(iface(proto = 3)))
    val fbCandidate = UsbDeviceInspector.selectPrimaryCandidate(fastboot)
    requireCheck(fbCandidate?.mode == UsbDeviceInspector.Mode.FASTBOOT, "canonical Fastboot mode")
    requireCheck(fbCandidate?.matchKind == UsbDeviceInspector.MatchKind.CANONICAL, "canonical Fastboot kind")

    val compat = device("/usb/compat", pid = 0xD00D, interfaces = arrayOf(iface(proto = 0)))
    val compatCandidate = UsbDeviceInspector.selectPrimaryCandidate(compat)
    requireCheck(compatCandidate?.mode == UsbDeviceInspector.Mode.FASTBOOT, "compat Fastboot mode")
    requireCheck(compatCandidate?.matchKind == UsbDeviceInspector.MatchKind.COMPAT_FASTBOOT, "compat Fastboot kind")

    val composite = device(
        "/usb/composite",
        interfaces = arrayOf(
            iface(id = 0, proto = 1),
            iface(id = 1, sub = 0, proto = 0)
        )
    )
    val compositeCandidates = UsbDeviceInspector.findCandidates(composite, includeGenericFastboot = true)
    requireCheck(compositeCandidates.all { it.mode == UsbDeviceInspector.Mode.ADB }, "ADB must outrank generic vendor interfaces")

    val generic = device("/usb/generic", vid = 0x1234, pid = 0x5678, interfaces = arrayOf(iface(sub = 0, proto = 0)))
    val genericCandidates = UsbDeviceInspector.findCandidates(generic, includeGenericFastboot = true)
    requireCheck(genericCandidates.single().matchKind == UsbDeviceInspector.MatchKind.GENERIC_FASTBOOT, "generic manual candidate")
    requireCheck(UsbDeviceInspector.selectPrimaryCandidate(generic, allowGenericFastboot = false) == null, "generic must not auto-connect")

    val onlyOut = device(
        "/usb/one-way",
        interfaces = arrayOf(
            iface(endpoints = listOf(UsbEndpoint(UsbConstants.USB_DIR_OUT, 0x01)))
        )
    )
    requireCheck(UsbDeviceInspector.findCandidates(onlyOut).isEmpty(), "bulk IN+OUT pair required")

    val changed = UsbDeviceInspector.selectModeSwitchCandidate(
        listOf(adb, fastboot),
        UsbDeviceInspector.selectPrimaryCandidate(adb)!!.logicalSignature
    )
    requireCheck(changed?.mode == UsbDeviceInspector.Mode.FASTBOOT, "mode-switch must select changed logical profile")

    val sameOnly = UsbDeviceInspector.selectModeSwitchCandidate(
        listOf(adb),
        UsbDeviceInspector.selectPrimaryCandidate(adb)!!.logicalSignature,
        adb.vendorId
    )
    requireCheck(sameOnly == null, "same logical profile must not auto-retry")

    val unrelatedVendor = device(
        "/usb/unrelated",
        vid = 0x04E8,
        pid = 0x6860,
        interfaces = arrayOf(iface(proto = 1))
    )
    val wrongTarget = UsbDeviceInspector.selectModeSwitchCandidate(
        listOf(unrelatedVendor),
        UsbDeviceInspector.selectPrimaryCandidate(adb)!!.logicalSignature,
        adb.vendorId
    )
    requireCheck(wrongTarget == null, "mode-switch must not jump to another vendor device")

    // Permission broadcast may return a fresh UsbDevice object. Rebind must use
    // the new object and survive an interface-index change when the descriptor
    // signature itself is unchanged.
    val beforePermission = device(
        "/usb/permission",
        interfaces = arrayOf(genericVendorInterfaceForRebind(0), iface(id = 7, proto = 1))
    )
    val selectedBefore = UsbDeviceInspector.selectPrimaryCandidate(beforePermission)!!
    val afterPermission = device(
        "/usb/permission",
        interfaces = arrayOf(iface(id = 7, proto = 1), genericVendorInterfaceForRebind(0))
    )
    val rebound = requireNotNull(
        UsbDeviceInspector.rebindCandidate(afterPermission, selectedBefore)
    ) {
        "candidate must rebind to fresh UsbDevice object"
    }
    requireCheck(rebound.device === afterPermission, "rebound candidate must hold current UsbDevice object")
    requireCheck(rebound.interfaceIndex == 0, "rebind must recover after interface reordering")

    println("USB DETECTION TESTS: OK")
}
