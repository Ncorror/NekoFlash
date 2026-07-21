package ru.forum.adbfastboottool

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One-reader bounded packet pump for ADB USB transport.
 *
 * USB IN is read by exactly one thread after CNXN/AUTH. Existing protocol code
 * consumes complete packets from this queue, so header and payload can no longer
 * be split between competing callers. Writes remain serialized in AdbProtocol.
 */
class AdbPacketDispatcher(
    private val source: (Int) -> ReadResult,
    private val onFailure: (FailureCode, String) -> Unit,
    private val queueCapacity: Int = 256,
    private val readSliceMs: Int = 500
) {
    data class Packet(
        val command: Long,
        val arg0: Int,
        val arg1: Int,
        val checksum: Int,
        val magic: Int,
        val payload: ByteArray,
        val receivedAtNs: Long = System.nanoTime()
    )

    sealed class ReadResult {
        data class PacketReady(val packet: Packet) : ReadResult()
        object Timeout : ReadResult()
        data class Failed(val code: FailureCode, val message: String) : ReadResult()
        object Closed : ReadResult()
    }

    enum class FailureCode {
        NONE,
        USB_IN_TIMEOUT_BUDGET,
        USB_IN_FAILED,
        INVALID_HEADER,
        PARTIAL_HEADER_TIMEOUT,
        INVALID_PAYLOAD,
        CHECKSUM_MISMATCH,
        QUEUE_OVERFLOW,
        DISPATCHER_STOPPED,
        DEVICE_DISCONNECTED
    }

    data class Snapshot(
        val running: Boolean,
        val queuedPackets: Int,
        val packetsRead: Long,
        val readTimeouts: Long,
        val readFailures: Long,
        val lastFailureCode: FailureCode,
        val lastFailureMessage: String?,
        val lastPacketAtNs: Long?
    )

    private val queue = LinkedBlockingQueue<Packet>(queueCapacity)
    private val running = AtomicBoolean(false)
    private val packetsRead = AtomicLong(0L)
    private val readTimeouts = AtomicLong(0L)
    private val readFailures = AtomicLong(0L)
    @Volatile private var thread: Thread? = null
    @Volatile private var lastFailureCode = FailureCode.NONE
    @Volatile private var lastFailureMessage: String? = null
    @Volatile private var lastPacketAtNs: Long? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val reader = Thread({ loop() }, "NekoFlash-ADB-Reader").apply { isDaemon = true }
        thread = reader
        reader.start()
        return true
    }

    fun take(timeoutMs: Int): Packet? {
        if (timeoutMs <= 0) return queue.poll()
        return try {
            queue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    fun stop(joinTimeoutMs: Long = 1_500L) {
        running.set(false)
        thread?.interrupt()
        if (Thread.currentThread() !== thread) {
            runCatching { thread?.join(joinTimeoutMs) }
        }
        thread = null
        queue.clear()
    }

    fun snapshot(): Snapshot = Snapshot(
        running = running.get(),
        queuedPackets = queue.size,
        packetsRead = packetsRead.get(),
        readTimeouts = readTimeouts.get(),
        readFailures = readFailures.get(),
        lastFailureCode = lastFailureCode,
        lastFailureMessage = lastFailureMessage,
        lastPacketAtNs = lastPacketAtNs
    )

    private fun loop() {
        while (running.get()) {
            when (val result = source(readSliceMs)) {
                is ReadResult.PacketReady -> {
                    packetsRead.incrementAndGet()
                    lastPacketAtNs = result.packet.receivedAtNs
                    if (!queue.offer(result.packet)) {
                        fail(FailureCode.QUEUE_OVERFLOW, "ADB dispatcher queue capacity=$queueCapacity exceeded")
                        break
                    }
                }
                ReadResult.Timeout -> {
                    readTimeouts.incrementAndGet()
                    try {
                        Thread.sleep(20L)
                    } catch (_: InterruptedException) {
                        if (!running.get()) break
                    }
                }
                is ReadResult.Failed -> {
                    fail(result.code, result.message)
                    break
                }
                ReadResult.Closed -> break
            }
        }
        running.set(false)
    }

    private fun fail(code: FailureCode, message: String) {
        lastFailureCode = code
        lastFailureMessage = message.take(500)
        readFailures.incrementAndGet()
        running.set(false)
        onFailure(code, message)
    }
}
