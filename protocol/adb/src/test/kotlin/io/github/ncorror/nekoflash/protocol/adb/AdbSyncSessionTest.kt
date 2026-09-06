package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSyncSessionTest {
    @Test
    fun openAsksForTheSyncService() {
        val device = Device(okay())

        device.session().open()

        val open = device.handle.sentFrames().first()
        assertEquals(AdbCommand.OPEN, open.command)
        assertEquals("${AdbSyncSession.SERVICE}\u0000", open.payload.decodeToString())
    }

    @Test
    fun statReturnsModeSizeAndTime() {
        val device = Device(okay(), data(statBody(mode = REGULAR, size = 1234, time = 99)))
        val session = device.opened()

        val outcome = session.stat("/sdcard/a.txt") as AdbSyncOutcome.Done

        assertEquals(1234L, outcome.value.size)
        assertEquals(99, outcome.value.modifiedAtSeconds)
        assertTrue(outcome.value.regularFile)
    }

    @Test
    fun statRequestCarriesThePath() {
        val device = Device(okay(), data(statBody(mode = REGULAR, size = 1, time = 1)))

        device.opened().stat("/sdcard/файл.txt")

        val request = device.handle.sentFrames().last { it.command == AdbCommand.WRTE }.payload
        assertEquals(AdbSyncProtocol.ID_STAT, AdbSyncProtocol.decodeHeader(request).id)
        assertEquals(
            "/sdcard/файл.txt",
            request.copyOfRange(8, request.size).toString(Charsets.UTF_8),
        )
    }

    /** Отсутствующий путь — успех с нулевым режимом, а не отказ. */
    @Test
    fun missingPathIsAnAnswerRatherThanARefusal() {
        val device = Device(okay(), data(statBody(mode = 0, size = 0, time = 0)))

        val outcome = device.opened().stat("/nope") as AdbSyncOutcome.Done

        assertFalse(outcome.value.exists)
    }

    @Test
    fun directoryIsRecognised() {
        val device = Device(okay(), data(statBody(mode = DIRECTORY, size = 0, time = 0)))

        val outcome = device.opened().stat("/sdcard") as AdbSyncOutcome.Done

        assertTrue(outcome.value.directory)
        assertFalse(outcome.value.regularFile)
    }

    @Test
    fun refusalCarriesTheDeviceMessage() {
        val message = "permission denied".toByteArray()
        val device = Device(
            okay(),
            data(AdbSyncProtocol.message(AdbSyncProtocol.ID_FAIL, message)),
        )

        val outcome = device.opened().stat("/data/system") as AdbSyncOutcome.Failed

        assertEquals(AdbSyncFailure.DEVICE_REFUSED, outcome.reason)
        assertTrue(outcome.detail.contains("permission denied"))
    }

    @Test
    fun fileIsReceivedInChunks() {
        val first = "hello ".toByteArray()
        val second = "world".toByteArray()
        val device = Device(
            okay(),
            data(AdbSyncProtocol.message(AdbSyncProtocol.ID_DATA, first)),
            data(AdbSyncProtocol.message(AdbSyncProtocol.ID_DATA, second)),
            data(AdbSyncProtocol.header(AdbSyncProtocol.ID_DONE, 0)),
        )
        val collected = mutableListOf<ByteArray>()

        val outcome = device.opened().receive("/sdcard/a.txt") { chunk -> collected += chunk }

        assertEquals(11L, (outcome as AdbSyncOutcome.Done).value)
        assertEquals("hello world", collected.reduce { a, b -> a + b }.decodeToString())
        assertEquals("два блока должны прийти по одному", 2, collected.size)
    }

    /** Границы пакетов ADB не совпадают с границами порций sync. */
    @Test
    fun portionsSplitAcrossAdbPacketsAreAssembled() {
        val message = AdbSyncProtocol.message(AdbSyncProtocol.ID_DATA, "split me".toByteArray())
        val device = Device(
            okay(),
            data(message.copyOfRange(0, 5)),
            data(message.copyOfRange(5, 10)),
            data(message.copyOfRange(10, message.size) + AdbSyncProtocol.header(AdbSyncProtocol.ID_DONE, 0)),
        )
        val collected = mutableListOf<ByteArray>()

        val outcome = device.opened().receive("/sdcard/a.txt") { chunk -> collected += chunk }

        assertTrue(outcome is AdbSyncOutcome.Done)
        assertArrayEquals("split me".toByteArray(), collected.single())
    }

    @Test
    fun emptyFileIsReceivedAsZeroBytes() {
        val device = Device(okay(), data(AdbSyncProtocol.header(AdbSyncProtocol.ID_DONE, 0)))
        var calls = 0

        val outcome = device.opened().receive("/sdcard/empty") { calls++ }

        assertEquals(0L, (outcome as AdbSyncOutcome.Done).value)
        assertEquals(0, calls)
    }

    /** Длину называет устройство, поэтому она проверяется. */
    @Test
    fun chunkLargerThanTheProtocolLimitIsRefused() {
        val device = Device(
            okay(),
            data(AdbSyncProtocol.header(AdbSyncProtocol.ID_DATA, AdbSyncProtocol.DATA_CHUNK_BYTES + 1)),
        )

        val outcome = device.opened().receive("/sdcard/a.txt") { } as AdbSyncOutcome.Failed

        assertEquals(AdbSyncFailure.INVALID_LENGTH, outcome.reason)
    }

    @Test
    fun refusedTransferIsReportedWithItsMessage() {
        val device = Device(
            okay(),
            data(AdbSyncProtocol.message(AdbSyncProtocol.ID_FAIL, "No such file".toByteArray())),
        )

        val outcome = device.opened().receive("/nope") { } as AdbSyncOutcome.Failed

        assertEquals(AdbSyncFailure.DEVICE_REFUSED, outcome.reason)
        assertTrue(outcome.detail.contains("No such file"))
    }

    @Test
    fun unexpectedIdentifierEndsTheTransfer() {
        val device = Device(okay(), data(AdbSyncProtocol.header(AdbSyncProtocol.ID_LIST, 0)))

        val outcome = device.opened().receive("/sdcard/a.txt") { } as AdbSyncOutcome.Failed

        assertEquals(AdbSyncFailure.UNEXPECTED_RESPONSE, outcome.reason)
    }

    @Test
    fun requestsBeforeOpenAreRefused() {
        val device = Device()
        val session = device.session()

        val outcome = session.stat("/sdcard") as AdbSyncOutcome.Failed

        assertEquals(AdbSyncFailure.NOT_OPEN, outcome.reason)
    }

    @Test
    fun deviceClosingTheStreamEndsTheSession() {
        val device = Device(okay(), close())
        val session = device.opened()

        val outcome = session.stat("/sdcard") as AdbSyncOutcome.Failed

        assertEquals(AdbSyncFailure.TRANSPORT_CLOSED, outcome.reason)
        assertFalse(session.active)
    }

    @Test
    fun releasedInterfaceIsReportedAsClosedTransport() {
        val device = Device(okay(), failed(UsbTransferFailure.NOT_HELD))

        val outcome = device.opened().stat("/sdcard") as AdbSyncOutcome.Failed

        assertEquals(AdbSyncFailure.TRANSPORT_CLOSED, outcome.reason)
    }

    @Test
    fun silenceEndsWithATimeoutRatherThanWaitingForever() {
        val device = Device(okay())
        val session = device.opened()

        val outcome = session.stat("/sdcard", timeoutMillis = 1_000) as AdbSyncOutcome.Failed

        assertEquals(AdbSyncFailure.TIMED_OUT, outcome.reason)
    }

    @Test
    fun closingAsksTheDeviceToCloseTheStream() {
        val device = Device(okay())
        val session = device.opened()

        session.close()

        assertEquals(AdbCommand.CLSE, device.handle.sentFrames().last().command)
        assertFalse(session.active)
    }

    @Test
    fun diagnosticsRecordTheExchange() {
        val sink = InMemoryDiagnosticSink()
        val device = Device(okay(), data(statBody(mode = REGULAR, size = 5, time = 0)))
        val session = device.session(sink)
        session.open()

        session.stat("/sdcard/a.txt")

        val messages = sink.snapshot().map { it.message }
        assertTrue(messages.containsAll(listOf("sync_open", "sync_opened", "sync_stat")))
    }

    private class Device(vararg responses: List<FakeUsbTransportHandle.Transfer>) {
        val handle = FakeUsbTransportHandle(inbound = responses.flatMap { it }.toMutableList())

        fun session(diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink()) = AdbSyncSession(
            reader = AdbPacketReader(handle, AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES),
            writer = AdbPacketWriter(handle),
            router = AdbStreamRouter(),
            diagnostics = diagnostics,
            elapsedNanos = StepwiseClock(),
        )

        fun opened(): AdbSyncSession = session().also { it.open() }
    }

    /** Время идёт само: каждая попытка приёма отъедает у дедлайна. */
    private class StepwiseClock : () -> Long {
        private var nanos = 0L

        override fun invoke(): Long {
            val current = nanos
            nanos += 300_000_000L
            return current
        }
    }

    private companion object {
        const val REMOTE_ID = 42
        const val LOCAL_ID = 1
        const val REGULAR = AdbSyncProtocol.MODE_REGULAR_FILE or 0x1A4
        const val DIRECTORY = AdbSyncProtocol.MODE_DIRECTORY or 0x1ED

        fun statBody(mode: Int, size: Int, time: Int): ByteArray =
            AdbSyncProtocol.header(AdbSyncProtocol.ID_STAT, mode) +
                intLe(size) + intLe(time)

        fun intLe(value: Int) = byteArrayOf(
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte(),
            (value ushr 24).toByte(),
        )

        fun packet(
            command: Long,
            arg0: Int = REMOTE_ID,
            arg1: Int = LOCAL_ID,
            payload: ByteArray = ByteArray(0),
        ): List<FakeUsbTransportHandle.Transfer> {
            val frames = mutableListOf<FakeUsbTransportHandle.Transfer>(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(command, arg0, arg1, payload),
                ),
            )
            if (payload.isNotEmpty()) {
                frames += FakeUsbTransportHandle.Transfer.Completed(payload.size, payload)
            }
            return frames
        }

        fun okay() = packet(AdbCommand.OKAY)

        fun data(payload: ByteArray) = packet(AdbCommand.WRTE, payload = payload)

        fun close() = packet(AdbCommand.CLSE)

        fun failed(reason: UsbTransferFailure) =
            listOf<FakeUsbTransportHandle.Transfer>(FakeUsbTransportHandle.Transfer.Failed(reason))
    }
}
