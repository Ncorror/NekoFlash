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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val A_CNXN = 0x4E584E43L
private const val A_OPEN = 0x4E45504FL
private const val A_OKAY = 0x59414B4FL
private const val A_CLSE = 0x45534C43L
private const val A_WRTE = 0x45545257L
private const val MAX_PAYLOAD = 1_048_576

private fun requireCheck(value: Boolean, message: String) {
    if (!value) error(message)
}

private fun bulkPair(): List<UsbEndpoint> = listOf(
    UsbEndpoint(UsbConstants.USB_DIR_IN, 0x81),
    UsbEndpoint(UsbConstants.USB_DIR_OUT, 0x01)
)

private fun adbInterface(): UsbInterface = UsbInterface(
    id = 0,
    interfaceClass = UsbConstants.USB_CLASS_VENDOR_SPEC,
    interfaceSubclass = 0x42,
    interfaceProtocol = 0x01,
    endpoints = bulkPair()
)

private fun device(): UsbDevice = UsbDevice(
    deviceName = "/dev/bus/usb/001/009",
    vendorId = 0x18D1,
    productId = 0x4EE7,
    productName = "ADB sideload test",
    interfaces = listOf(adbInterface())
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

private fun enqueuePacket(command: Long, arg0: Int = 0, arg1: Int = 0, data: ByteArray = ByteArray(0)) {
    val (header, payload) = adbPacket(command, arg0, arg1, data)
    UsbScript.inResponses.add(header)
    if (payload.isNotEmpty()) UsbScript.inResponses.add(payload)
}

private fun enqueueConnectBanner(prefix: String) {
    val banner = "$prefix:ro.product.name=test;ro.product.model=Test;ro.product.device=test;features=shell_v2,cmd\u0000"
        .toByteArray(Charsets.UTF_8)
    enqueuePacket(A_CNXN, 0x01000000, MAX_PAYLOAD, banner)
}

private fun createZip(name: String, bytes: Int = 128 * 1024): File {
    val file = File(System.getProperty("java.io.tmpdir"), name)
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        zip.putNextEntry(ZipEntry("payload.bin"))
        val chunk = ByteArray(4096) { (it and 0xFF).toByte() }
        var remaining = bytes
        while (remaining > 0) {
            val n = minOf(chunk.size, remaining)
            zip.write(chunk, 0, n)
            remaining -= n
        }
        zip.closeEntry()
    }
    return file
}

private fun connect(prefix: String, logs: MutableList<String>, progress: MutableList<Int>): AdbProtocol {
    enqueueConnectBanner(prefix)
    val protocol = AdbProtocol(
        usbManager = UsbManager(UsbDeviceConnection()),
        device = device(),
        keyDirectory = File(System.getProperty("java.io.tmpdir"), "nekoflash-adb-sideload-test").apply { mkdirs() },
        onLog = logs::add,
        onProgress = { percent, _ -> progress += percent },
        preferredInterfaceIndex = 0
    )
    requireCheck(protocol.connect(), "ADB connect must succeed for $prefix")
    return protocol
}

private fun outgoingCommandCount(command: Long): Int = UsbScript.outTransfers.count { transfer ->
    if (transfer.size != 24) return@count false
    val bb = ByteBuffer.wrap(transfer).order(ByteOrder.LITTLE_ENDIAN)
    val parsed = bb.int.toLong() and 0xFFFF_FFFFL
    val magic = ByteBuffer.wrap(transfer, 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
    parsed == command && magic == command.inv().toInt()
}

fun main() {
    val zip = createZip("nekoflash-sideload-test.zip")

    // 1) Обычный recovery ADB блокируется до A_OPEN и до старта тяжёлого протокола.
    UsbScript.reset()
    val logsRecovery = mutableListOf<String>()
    val progressRecovery = mutableListOf<Int>()
    val recovery = connect("recovery:", logsRecovery, progressRecovery)
    val openBefore = outgoingCommandCount(A_OPEN)
    val recoveryResult = recovery.sideloadZip(zip)
    requireCheck(recoveryResult is AdbProtocol.SideloadResult.NotInSideloadMode, "recovery mode must be rejected")
    requireCheck(outgoingCommandCount(A_OPEN) == openBefore, "wrong mode must not send sideload A_OPEN")
    recovery.disconnect()

    // 2) DONEDONE завершает host-side передачу: CLSE после него не требуется.
    UsbScript.reset()
    val logsDone = mutableListOf<String>()
    val progressDone = mutableListOf<Int>()
    val done = connect("sideload:", logsDone, progressDone)
    enqueuePacket(A_OKAY, arg0 = 42, arg1 = 1)
    enqueuePacket(A_WRTE, arg0 = 42, arg1 = 1, data = "DONEDONE".toByteArray(Charsets.US_ASCII))
    val doneResult = done.sideloadZip(zip)
    requireCheck(doneResult is AdbProtocol.SideloadResult.TransferComplete, "DONEDONE must complete transfer")
    requireCheck(progressDone.lastOrNull() == 100, "DONEDONE must force 100% progress")
    requireCheck((doneResult as AdbProtocol.SideloadResult.TransferComplete).integrity.isValid, "integrity result must be carried into transfer completion")
    requireCheck(doneResult.integrity.sha256?.length == 64, "package SHA-256 must be available")
    requireCheck(outgoingCommandCount(A_WRTE) == 0, "host must not send extra WRTE after DONEDONE")
    requireCheck(logsDone.any { "не подтверждает успешную установку" in it }, "pending verification log missing")
    done.disconnect()

    // 3) CLSE до DONEDONE не считается успехом.
    UsbScript.reset()
    val logsClse = mutableListOf<String>()
    val progressClse = mutableListOf<Int>()
    val clse = connect("sideload:", logsClse, progressClse)
    enqueuePacket(A_OKAY, arg0 = 42, arg1 = 1)
    enqueuePacket(A_CLSE, arg0 = 42, arg1 = 1)
    val clseResult = clse.sideloadZip(zip)
    requireCheck(
        clseResult is AdbProtocol.SideloadResult.Failed && clseResult.kind == AdbProtocol.SideloadFailureKind.PROTOCOL,
        "early CLSE must be a protocol failure"
    )
    clse.disconnect()

    // 4) Блок обслуживается, прогресс остаётся <100 до DONEDONE, затем становится 100.
    UsbScript.reset()
    val logsBlock = mutableListOf<String>()
    val progressBlock = mutableListOf<Int>()
    val block = connect("sideload:", logsBlock, progressBlock)
    enqueuePacket(A_OKAY, arg0 = 42, arg1 = 1)
    enqueuePacket(A_WRTE, arg0 = 42, arg1 = 1, data = "00000000".toByteArray(Charsets.US_ASCII))
    enqueuePacket(A_OKAY, arg0 = 42, arg1 = 1)
    enqueuePacket(A_WRTE, arg0 = 42, arg1 = 1, data = "DONEDONE".toByteArray(Charsets.US_ASCII))
    val blockResult = block.sideloadZip(zip)
    requireCheck(blockResult is AdbProtocol.SideloadResult.TransferComplete, "block flow must finish transfer on DONEDONE")
    requireCheck(progressBlock.dropLast(1).all { it in 0..99 }, "progress must stay below 100 before DONEDONE")
    requireCheck(progressBlock.lastOrNull() == 100, "terminal progress must be 100")
    requireCheck(outgoingCommandCount(A_WRTE) >= 1, "requested block payload must be sent")
    block.disconnect()

    // 5) Sentinel -1 не считается успехом сам по себе: после него обязателен DONEDONE.
    UsbScript.reset()
    val logsSentinel = mutableListOf<String>()
    val progressSentinel = mutableListOf<Int>()
    val sentinel = connect("sideload:", logsSentinel, progressSentinel)
    enqueuePacket(A_OKAY, arg0 = 42, arg1 = 1)
    enqueuePacket(A_WRTE, arg0 = 42, arg1 = 1, data = "-1      ".toByteArray(Charsets.US_ASCII))
    enqueuePacket(A_OKAY, arg0 = 42, arg1 = 1)
    enqueuePacket(A_WRTE, arg0 = 42, arg1 = 1, data = "DONEDONE".toByteArray(Charsets.US_ASCII))
    val sentinelResult = sentinel.sideloadZip(zip)
    requireCheck(sentinelResult is AdbProtocol.SideloadResult.TransferComplete, "-1 sentinel must wait for DONEDONE")
    requireCheck(progressSentinel.lastOrNull() == 100, "sentinel flow must finish at 100 only on DONEDONE")
    sentinel.disconnect()

    // 6) Запрос за пределами ZIP блокируется как protocol error до чтения/передачи данных.
    UsbScript.reset()
    val logsOob = mutableListOf<String>()
    val progressOob = mutableListOf<Int>()
    val oob = connect("sideload:", logsOob, progressOob)
    enqueuePacket(A_OKAY, arg0 = 42, arg1 = 1)
    enqueuePacket(A_WRTE, arg0 = 42, arg1 = 1, data = "99999999".toByteArray(Charsets.US_ASCII))
    val oobResult = oob.sideloadZip(zip)
    requireCheck(
        oobResult is AdbProtocol.SideloadResult.Failed && oobResult.kind == AdbProtocol.SideloadFailureKind.PROTOCOL,
        "out-of-range block must be rejected"
    )
    requireCheck(logsOob.any { "за пределами ZIP" in it }, "out-of-range diagnostic missing")
    oob.disconnect()

    // 7) Повреждённый ZIP блокируется integrity preflight до отправки sideload A_OPEN.
    val brokenZip = createZip("nekoflash-sideload-broken.zip")
    val brokenBytes = brokenZip.readBytes()
    brokenZip.writeBytes(brokenBytes.copyOf(brokenBytes.size - 18))
    UsbScript.reset()
    val logsBroken = mutableListOf<String>()
    val progressBroken = mutableListOf<Int>()
    val broken = connect("sideload:", logsBroken, progressBroken)
    val brokenOpenBefore = outgoingCommandCount(A_OPEN)
    val brokenResult = broken.sideloadZip(brokenZip)
    requireCheck(
        brokenResult is AdbProtocol.SideloadResult.Failed && brokenResult.kind == AdbProtocol.SideloadFailureKind.FILE,
        "corrupted ZIP must be blocked as FILE failure"
    )
    requireCheck(outgoingCommandCount(A_OPEN) == brokenOpenBefore, "corrupted ZIP must not send sideload A_OPEN")
    requireCheck(logsBroken.any { "ADB Sideload заблокирован" in it }, "integrity block diagnostic missing")
    broken.disconnect()

    zip.delete()
    brokenZip.delete()
    println("ADB SIDELOAD TESTS: OK")
}
