package ru.forum.adbfastboottool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbScript
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val A_CNXN = 0x4E584E43L
private const val MAX_PAYLOAD = 1_048_576

private fun bulkPair(): List<UsbEndpoint> = listOf(
    UsbEndpoint(UsbConstants.USB_DIR_IN, 0x81),
    UsbEndpoint(UsbConstants.USB_DIR_OUT, 0x01)
)

private fun adbInterface(id: Int = 1): UsbInterface = UsbInterface(
    id = id,
    interfaceClass = UsbConstants.USB_CLASS_VENDOR_SPEC,
    interfaceSubclass = 0x42,
    interfaceProtocol = 0x01,
    endpoints = bulkPair()
)

private fun genericVendorInterface(id: Int = 0): UsbInterface = UsbInterface(
    id = id,
    interfaceClass = UsbConstants.USB_CLASS_VENDOR_SPEC,
    interfaceSubclass = 0,
    interfaceProtocol = 0,
    endpoints = bulkPair()
)

private fun device(vararg interfaces: UsbInterface): UsbDevice = UsbDevice(
    deviceName = "/dev/bus/usb/001/007",
    vendorId = 0x18D1,
    productId = 0x4EE7,
    productName = "ADB test device",
    interfaces = interfaces.toList()
)

private fun adbPacket(command: Long, arg0: Int, arg1: Int, data: ByteArray): Pair<ByteArray, ByteArray> {
    val checksum = data.sumOf { it.toInt() and 0xFF }
    val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(command.toInt())
        putInt(arg0)
        putInt(arg1)
        putInt(data.size)
        putInt(checksum)
        putInt(command.inv().toInt())
    }.array()
    return header to data
}

private fun enqueueCnxnResponse() {
    val banner = "device::ro.product.name=test;ro.product.model=Test;features=shell_v2,cmd\u0000"
        .toByteArray(Charsets.UTF_8)
    val (header, data) = adbPacket(A_CNXN, 0x01000000, MAX_PAYLOAD, banner)
    UsbScript.inResponses.add(header)
    UsbScript.inResponses.add(data)
}

private fun outgoingCnxnCount(): Int = UsbScript.outTransfers.count { transfer ->
    transfer.size == 24 &&
        (ByteBuffer.wrap(transfer).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL) == A_CNXN
}

private fun requireCheck(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val keyDir = File(System.getProperty("java.io.tmpdir"), "nekoflash-adb-test").apply { mkdirs() }

    // 1) Явно выбранный канонический ADB-интерфейс подключается одним CNXN.
    UsbScript.reset()
    enqueueCnxnResponse()
    val logs1 = mutableListOf<String>()
    val protocol1 = AdbProtocol(
        usbManager = UsbManager(UsbDeviceConnection()),
        device = device(genericVendorInterface(0), adbInterface(1)),
        keyDirectory = keyDir,
        onLog = logs1::add,
        preferredInterfaceIndex = 1
    )
    requireCheck(protocol1.connect(), "canonical ADB connection must succeed")
    requireCheck(protocol1.isConnected, "ADB must report connected")
    requireCheck(outgoingCnxnCount() == 1, "ADB connect must send exactly one CNXN")
    requireCheck(logs1.any { "СОЕДИНЕНИЕ ADB УСТАНОВЛЕНО" in it }, "success log missing")
    protocol1.disconnect()

    // 2) Ошибочный preferred index не принимается как ADB; выполняется безопасный fallback.
    UsbScript.reset()
    enqueueCnxnResponse()
    val logs2 = mutableListOf<String>()
    val protocol2 = AdbProtocol(
        usbManager = UsbManager(UsbDeviceConnection()),
        device = device(genericVendorInterface(0), adbInterface(1)),
        keyDirectory = keyDir,
        onLog = logs2::add,
        preferredInterfaceIndex = 0
    )
    requireCheck(protocol2.connect(), "ADB fallback to canonical interface must succeed")
    requireCheck(logs2.any { "безопасный поиск" in it }, "preferred-interface fallback log missing")
    requireCheck(outgoingCnxnCount() == 1, "fallback connect must still send one CNXN")
    protocol2.disconnect()

    // 3) Нет ответа: одна попытка, без close/reopen и второго CNXN.
    UsbScript.reset()
    val logs3 = mutableListOf<String>()
    val protocol3 = AdbProtocol(
        usbManager = UsbManager(UsbDeviceConnection()),
        device = device(adbInterface(0)),
        keyDirectory = keyDir,
        onLog = logs3::add,
        preferredInterfaceIndex = 0
    )
    requireCheck(!protocol3.connect(), "ADB connect without response must fail")
    requireCheck(outgoingCnxnCount() == 1, "failed ADB connect must not retry CNXN automatically")
    requireCheck(logs3.any { "нет ответа" in it }, "no-response diagnostic missing")

    // 4) Глобальный READ-ONLY lock блокирует ADB mutation до отправки wire-команд.
    UsbScript.reset()
    enqueueCnxnResponse()
    val logs4 = mutableListOf<String>()
    val protocol4 = AdbProtocol(
        usbManager = UsbManager(UsbDeviceConnection()),
        device = device(adbInterface(0)),
        keyDirectory = keyDir,
        onLog = logs4::add,
        preferredInterfaceIndex = 0
    )
    requireCheck(protocol4.connect(), "ADB READ-ONLY test connection must succeed")
    protocol4.readOnlyMutationLock = true
    val local = File(keyDir, "readonly-test.bin").apply { writeText("x") }
    requireCheck(!protocol4.pushPath(local, "/data/local/tmp/readonly-test.bin"), "READ-ONLY must block push")
    requireCheck(!protocol4.runShellCommand("rm -f /data/local/tmp/readonly-test.bin"), "READ-ONLY must block mutating shell")
    requireCheck(!protocol4.runService("reboot:bootloader"), "READ-ONLY must block reboot service")
    requireCheck(logs4.count { "ADB READ-ONLY LOCK" in it } >= 3, "READ-ONLY block diagnostics missing: ${logs4.joinToString(" | ")}")
    requireCheck(UsbScript.outTransfers.none { String(it, Charsets.UTF_8).contains("readonly-test") }, "blocked operation leaked to USB")
    protocol4.disconnect()

    // 5) Частичный ADB header с последующим timeout считается потерей синхронизации,
    // а не обычным idle-timeout. Автоповтор CNXN запрещён.
    UsbScript.reset()
    val banner5 = "device::features=shell_v2\u0000".toByteArray(Charsets.UTF_8)
    val (header5, _) = adbPacket(A_CNXN, 0x01000000, MAX_PAYLOAD, banner5)
    UsbScript.inResponses.add(header5.copyOfRange(0, 9))
    UsbScript.enqueueReadFailure()
    val logs5 = mutableListOf<String>()
    val protocol5 = AdbProtocol(
        usbManager = UsbManager(UsbDeviceConnection()),
        device = device(adbInterface(0)),
        keyDirectory = keyDir,
        onLog = logs5::add,
        preferredInterfaceIndex = 0
    )
    requireCheck(!protocol5.connect(), "partial ADB header must fail closed")
    requireCheck(outgoingCnxnCount() == 1, "partial ADB header must not retry CNXN")
    requireCheck(
        logs5.any { "header interrupted after 9/24 bytes" in it },
        "partial-header reason code/message missing: ${logs5.joinToString(" | ")}"
    )

    println("ADB CORE TESTS: OK")
}
