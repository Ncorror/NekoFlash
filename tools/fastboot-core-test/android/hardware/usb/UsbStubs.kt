@file:Suppress("UNUSED_PARAMETER")

package android.hardware.usb

import java.nio.ByteBuffer
import java.util.ArrayDeque

object UsbScript {
    val inResponses = ArrayDeque<ByteArray>()
    val dataOutResults = ArrayDeque<Int>()
    val commandOutResults = ArrayDeque<Int>()
    val syncDataOutResults = ArrayDeque<Int>()
    val commands = mutableListOf<String>()
    val outTransfers = mutableListOf<ByteArray>()
    val asyncRequestSizes = mutableListOf<Int>()
    val requestWaitTimeouts = mutableListOf<Long>()
    var dataBytesAccepted: Long = 0
    var syncDataBytesAccepted: Long = 0

    fun reset() {
        inResponses.clear()
        dataOutResults.clear()
        commandOutResults.clear()
        syncDataOutResults.clear()
        commands.clear()
        outTransfers.clear()
        asyncRequestSizes.clear()
        requestWaitTimeouts.clear()
        dataBytesAccepted = 0
        syncDataBytesAccepted = 0
    }

    fun enqueue(vararg responses: String) {
        responses.forEach { inResponses.add(it.toByteArray(Charsets.US_ASCII)) }
    }

    fun enqueueReadFailure() {
        // Empty sentinel: model one failed/empty bulk IN read without consuming a response packet.
        inResponses.add(ByteArray(0))
    }
}

class UsbManager(private val connection: UsbDeviceConnection = UsbDeviceConnection()) {
    fun openDevice(device: UsbDevice): UsbDeviceConnection? = connection
}

class UsbDevice(
    val deviceName: String = "/dev/bus/usb/001/001",
    val vendorId: Int = 0x18D1,
    val productId: Int = 0x4EE0,
    val productName: String? = "Fake Android",
    private val interfaces: List<UsbInterface> = listOf(UsbInterface())
) {
    val interfaceCount: Int get() = interfaces.size
    fun getInterface(i: Int): UsbInterface = interfaces[i]
}

class UsbInterface(
    val id: Int = 0,
    val interfaceClass: Int = 255,
    val interfaceSubclass: Int = 66,
    val interfaceProtocol: Int = 3,
    private val endpoints: List<UsbEndpoint> = listOf(
        UsbEndpoint(UsbConstants.USB_DIR_IN, 0x81),
        UsbEndpoint(UsbConstants.USB_DIR_OUT, 0x01)
    )
) {
    val endpointCount: Int get() = endpoints.size
    fun getEndpoint(i: Int): UsbEndpoint = endpoints[i]
}

class UsbEndpoint(
    val direction: Int = 0,
    val address: Int = 0,
    val type: Int = UsbConstants.USB_ENDPOINT_XFER_BULK,
    val maxPacketSize: Int = 512
)

class UsbRequest {
    private var connection: UsbDeviceConnection? = null
    private var endpoint: UsbEndpoint? = null
    private var pendingBuffer: ByteBuffer? = null
    private var closed = false

    fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        if (closed) return false
        this.connection = connection
        this.endpoint = endpoint
        return true
    }

    fun queue(buffer: ByteBuffer?): Boolean {
        if (closed || buffer == null || pendingBuffer != null) return false
        val conn = connection ?: return false
        val ep = endpoint ?: return false
        pendingBuffer = buffer
        UsbScript.asyncRequestSizes += buffer.remaining()
        return conn.enqueueRequest(this, ep)
    }

    fun cancel(): Boolean {
        connection?.cancelRequest(this)
        pendingBuffer = null
        return true
    }

    fun close() {
        cancel()
        closed = true
    }

    internal fun completeNext(): Int? {
        val buffer = pendingBuffer ?: return null
        val remaining = buffer.remaining()
        val result = if (UsbScript.dataOutResults.isEmpty()) remaining else UsbScript.dataOutResults.removeFirst()
        if (result <= 0) return result
        val confirmed = minOf(result, remaining)
        buffer.position(buffer.position() + confirmed)
        UsbScript.dataBytesAccepted += confirmed
        pendingBuffer = null
        return confirmed
    }

    internal fun clearPending() {
        pendingBuffer = null
    }
}

class UsbDeviceConnection {
    private var pendingRequest: UsbRequest? = null
    val fileDescriptor: Int = 42
    var closed: Boolean = false
        private set

    fun claimInterface(iface: UsbInterface, force: Boolean): Boolean = true
    fun releaseInterface(iface: UsbInterface): Boolean = true
    fun close() {
        closed = true
        pendingRequest?.clearPending()
        pendingRequest = null
    }

    fun bulkTransfer(ep: UsbEndpoint, data: ByteArray, length: Int, timeout: Int): Int =
        bulkTransfer(ep, data, 0, length, timeout)

    fun bulkTransfer(ep: UsbEndpoint, data: ByteArray, offset: Int, length: Int, timeout: Int): Int {
        if (ep.direction == UsbConstants.USB_DIR_IN) {
            val next = if (UsbScript.inResponses.isEmpty()) return -1 else UsbScript.inResponses.removeFirst()
            if (next.isEmpty()) return -1
            val n = minOf(length, next.size)
            next.copyInto(data, offset, 0, n)
            return n
        }
        val payload = data.copyOfRange(offset, offset + length)
        UsbScript.outTransfers += payload
        val ascii = String(payload, Charsets.US_ASCII)
        val isCommand = ascii.startsWith("getvar:") || ascii.startsWith("download:") ||
            ascii.startsWith("flash:") || ascii.startsWith("erase:") ||
            ascii.startsWith("reboot") || ascii.startsWith("oem ") ||
            ascii.startsWith("flashing ") || ascii.startsWith("snapshot-update:") ||
            ascii.startsWith("gsi:") ||
            ascii.startsWith("set_active:") || ascii.startsWith("fetch:") ||
            ascii.startsWith("stage") || ascii.startsWith("boot") || ascii.startsWith("continue")
        if (isCommand) {
            UsbScript.commands += ascii
            return if (UsbScript.commandOutResults.isEmpty()) length else UsbScript.commandOutResults.removeFirst()
        }
        val result = if (UsbScript.syncDataOutResults.isEmpty()) length else UsbScript.syncDataOutResults.removeFirst()
        if (result > 0) UsbScript.syncDataBytesAccepted += minOf(result, length)
        return result
    }

    internal fun enqueueRequest(request: UsbRequest, ep: UsbEndpoint): Boolean {
        if (closed || ep.direction != UsbConstants.USB_DIR_OUT || pendingRequest != null) return false
        pendingRequest = request
        return true
    }

    internal fun cancelRequest(request: UsbRequest) {
        if (pendingRequest === request) pendingRequest = null
    }

    fun requestWait(timeout: Long): UsbRequest? {
        UsbScript.requestWaitTimeouts += timeout
        val request = pendingRequest ?: return null
        val result = request.completeNext()
        return if (result != null && result > 0) {
            pendingRequest = null
            request
        } else {
            null
        }
    }

    fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeout: Int
    ): Int = 0
}

object UsbConstants {
    const val USB_CLASS_VENDOR_SPEC = 255
    const val USB_ENDPOINT_XFER_BULK = 2
    const val USB_DIR_IN = 128
    const val USB_DIR_OUT = 0
}
