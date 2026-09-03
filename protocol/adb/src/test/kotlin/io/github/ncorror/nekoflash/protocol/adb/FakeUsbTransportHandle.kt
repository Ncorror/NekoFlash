package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferArguments
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceCandidate
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbTransferType

/**
 * Подставной интерфейс: заранее заданная очередь исходов передачи.
 *
 * Каждый элемент очереди — одна операция приёма или отправки. Это принципиально:
 * тест на inbound framing проверяет ровно то, сколько операций сделал читатель,
 * и очередь позволяет утверждать, что второй попытки дочитать payload не было.
 */
internal class FakeUsbTransportHandle(
    private val inbound: MutableList<Transfer> = mutableListOf(),
    private val outbound: MutableList<Transfer> = mutableListOf(),
) : UsbTransportHandle {
    /** Один запланированный исход операции. */
    sealed interface Transfer {
        /** Передача завершилась и перенесла [bytes] байт из [source]. */
        data class Completed(val bytes: Int, val source: ByteArray = ByteArray(0)) : Transfer

        /** Передача не состоялась. */
        data class Failed(val reason: UsbTransferFailure) : Transfer
    }

    /** Окна всех выполненных приёмов: длина каждой запрошенной операции. */
    val receiveWindows: MutableList<Int> = mutableListOf()

    /** Таймаут каждой операции приёма: рукопожатие меняет его по ходу. */
    val receiveTimeouts: MutableList<Int> = mutableListOf()

    /** Байты, ушедшие через [send], в порядке отправки. */
    val sentBytes: MutableList<ByteArray> = mutableListOf()

    private var released = false

    override val candidate: UsbInterfaceCandidate = CANDIDATE

    override val held: Boolean
        get() = !released

    override fun receive(
        destination: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): UsbTransferResult {
        UsbTransferArguments.validate(destination.size, offset, length, timeoutMillis)
        receiveWindows += length
        receiveTimeouts += timeoutMillis
        val transfer = inbound.removeFirstOrNull()
            ?: return UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
        return when (transfer) {
            is Transfer.Failed -> UsbTransferResult.Failed(transfer.reason)
            is Transfer.Completed -> {
                transfer.source.copyInto(
                    destination = destination,
                    destinationOffset = offset,
                    startIndex = 0,
                    endIndex = minOf(transfer.bytes, transfer.source.size),
                )
                UsbTransferResult.Completed(transfer.bytes)
            }
        }
    }

    override fun send(
        source: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): UsbTransferResult {
        UsbTransferArguments.validate(source.size, offset, length, timeoutMillis)
        val transfer = outbound.removeFirstOrNull() ?: Transfer.Completed(length)
        return when (transfer) {
            is Transfer.Failed -> UsbTransferResult.Failed(transfer.reason)
            is Transfer.Completed -> {
                val moved = minOf(transfer.bytes, length)
                sentBytes += source.copyOfRange(offset, offset + moved)
                UsbTransferResult.Completed(moved)
            }
        }
    }

    override fun close() {
        released = true
    }

    private companion object {
        val ENDPOINT_IN = UsbEndpointDescriptor(
            address = 0x81,
            direction = UsbEndpointDirection.IN,
            transferType = UsbTransferType.BULK,
        )

        val ENDPOINT_OUT = UsbEndpointDescriptor(
            address = 0x01,
            direction = UsbEndpointDirection.OUT,
            transferType = UsbTransferType.BULK,
        )

        val CANDIDATE = UsbInterfaceCandidate(
            device = UsbDeviceDescriptor(
                deviceId = 1,
                deviceName = "/dev/bus/usb/001/002",
                vendorId = 0x2717,
                productId = 0xFF48,
            ),
            kind = UsbInterfaceKind.ADB,
            confidence = UsbMatchConfidence.CANONICAL,
            interfaceIndex = 0,
            interfaceId = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x01,
            endpointIn = ENDPOINT_IN,
            endpointOut = ENDPOINT_OUT,
        )
    }
}

/** Кадр, собранный обратно из того, что ушло в [FakeUsbTransportHandle.send]. */
internal data class SentPacket(
    val command: Long,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray,
)

/**
 * Восстанавливает отправленные кадры из последовательности передач.
 *
 * Писатель отправляет заголовок и следом payload, возможно кусками, поэтому
 * собрать кадр обратно можно только по объявленной в заголовке длине — что
 * заодно проверяет, что заголовок и payload действительно согласованы.
 */
internal fun FakeUsbTransportHandle.sentFrames(): List<SentPacket> {
    val frames = mutableListOf<SentPacket>()
    var index = 0
    while (index < sentBytes.size) {
        val headerBytes = sentBytes[index]
        index += 1
        val decoded = AdbPacketHeader.decode(headerBytes, AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES)
        require(decoded is AdbHeaderDecoding.Decoded) { "writer produced an undecodable header: $decoded" }
        val header = decoded.header
        val payload = ByteArray(header.payloadLength)
        var collected = 0
        while (collected < header.payloadLength) {
            val part = sentBytes[index]
            index += 1
            part.copyInto(payload, collected)
            collected += part.size
        }
        frames += SentPacket(header.command, header.arg0, header.arg1, payload)
    }
    return frames
}

/** Собирает 24-байтный заголовок для теста. */
internal fun header(
    command: Long,
    arg0: Int = 0,
    arg1: Int = 0,
    payload: ByteArray = ByteArray(0),
    checksum: Int = AdbChecksum.compute(payload),
    magicOverride: Int? = null,
    declaredLength: Int? = null,
): ByteArray {
    val bytes = ByteArray(AdbPacketHeader.SIZE_BYTES)
    AdbPacketHeader.encode(bytes, command, arg0, arg1, payload, checksum)
    declaredLength?.let { writeIntLe(bytes, 12, it) }
    magicOverride?.let { writeIntLe(bytes, 20, it) }
    return bytes
}

private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
    target[offset] = value.toByte()
    target[offset + 1] = (value ushr 8).toByte()
    target[offset + 2] = (value ushr 16).toByte()
    target[offset + 3] = (value ushr 24).toByte()
}
