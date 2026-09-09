package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перезагрузка через односторонний сервис `reboot:`.
 *
 * Проверяется не «команда ушла», а два вопроса, которые задаёт `03` §3:
 * начался ли переход и **тронуто ли устройство**. Второй решает, можно ли
 * повторять, и ошибиться в нём дороже.
 */
class AdbRebootTest {
    private val harnesses = AdbDispatchHarnesses()

    @After
    fun stopDispatchLoops() {
        harnesses.stopAll()
    }

    @Test
    fun emptyTargetAsksForAPlainReboot() {
        assertEquals("reboot:", AdbRebootService.of(""))
        assertEquals("reboot:", AdbRebootService.of("   "))
    }

    /** `system` — это обычная перезагрузка, и хвоста у неё нет. */
    @Test
    fun systemIsRecognisedRegardlessOfCase() {
        assertEquals("reboot:", AdbRebootService.of("system"))
        assertEquals("reboot:", AdbRebootService.of("System"))
        assertEquals("reboot:", AdbRebootService.of("SYSTEM"))
    }

    /**
     * Регистр произвольной цели не трогается.
     *
     * Расхождение с Legacy намеренное: какие цели чувствительны к регистру,
     * знает устройство, а не хост. Молча менять набранное значило бы выполнять
     * не ту команду, которую просили.
     */
    @Test
    fun anArbitraryTargetKeepsItsCase() {
        assertEquals("reboot:bootloader", AdbRebootService.of("bootloader"))
        assertEquals("reboot:EDL", AdbRebootService.of("EDL"))
        assertEquals("reboot:recovery", AdbRebootService.of("  recovery  "))
    }

    /** Закрытие потока устройством — признак того, что команда принята. */
    @Test
    fun theDeviceClosingTheStreamMeansAccepted() {
        val device = ScriptedDevice(okay(), close())

        val outcome = device.reboot().reboot("") as AdbRebootOutcome.Accepted

        assertEquals("reboot:", outcome.service)
    }

    /** Устройство ушло с шины, не ответив, — ровно то, ради чего команда посылалась. */
    @Test
    fun leavingTheBusMeansAccepted() {
        val device = ScriptedDevice(failedRead(UsbTransferFailure.NOT_HELD))

        val outcome = device.reboot().reboot("bootloader") as AdbRebootOutcome.Accepted

        assertEquals("reboot:bootloader", outcome.service)
    }

    /**
     * Слово устройства весомее молчаливого закрытия.
     *
     * `reboot:` в норме молчит; если оно ответило текстом, значит отказало.
     * Это class B из `03` §2 — авторитет устройства, а не наша догадка.
     */
    @Test
    fun textFromTheDeviceIsARefusalAndNotATransition() {
        val device = ScriptedDevice(okay(), write("reboot not permitted"), close())

        val outcome = device.reboot().reboot("recovery") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.DEVICE_REFUSED, outcome.reason)
        assertEquals(AdbRebootState.UNTOUCHED, outcome.state)
        assertTrue(outcome.detail.contains("reboot not permitted"))
    }

    /**
     * Испорченный кадр не переклеивается в ожидаемый разрыв.
     *
     * Прямое требование Legacy `AdbServiceCompletionPolicy`, и оно здесь
     * сохранено: после потери кадра неизвестно ничего, в том числе и то,
     * дошёл ли запрос.
     */
    @Test
    fun aLostFrameIsNeverRelabelledAsAnExpectedDisconnect() {
        val device = ScriptedDevice(okay(), shortPayload())

        val outcome = device.reboot().reboot("") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.FRAMING_LOST, outcome.reason)
        assertEquals(AdbRebootState.UNKNOWN, outcome.state)
    }

    /**
     * Молчание при живом транспорте — не успех.
     *
     * Здесь расхождение с Legacy: у него не было способа отличить «peer ушёл с
     * шины» от «peer молчит», и молчание засчитывалось как ожидаемое
     * завершение. Ящик потока приносит конец с причиной, поэтому отличить
     * можно — и сообщать «перезагружается», не увидев ни одного признака
     * перехода, значит выдавать надежду за наблюдение.
     */
    @Test
    fun silenceOnALiveTransportIsNotSuccess() {
        val device = ScriptedDevice(okay())

        val outcome = device.reboot().reboot("", timeoutMillis = 300) as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.NO_TRANSITION, outcome.reason)
        assertEquals(AdbRebootState.UNKNOWN, outcome.state)
    }

    /** Запрос, не ушедший в провод, оставляет устройство нетронутым. */
    @Test
    fun aRequestThatNeverLeftTheHostLeavesTheDeviceUntouched() {
        val device = ScriptedDevice(outbound = listOf(FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_HELD)))

        val outcome = device.reboot().reboot("") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.TRANSPORT_CLOSED, outcome.reason)
        assertEquals(AdbRebootState.UNTOUCHED, outcome.state)
    }

    /**
     * Оборванная отправка без единого байта — тоже нетронутое устройство.
     *
     * Ноль байт означает, что запроса устройство не видело; всё остальное
     * консервативно считается неизвестным.
     */
    @Test
    fun anInterruptedSendOfNothingLeavesTheDeviceUntouched() {
        val device = ScriptedDevice(
            outbound = listOf(FakeUsbTransportHandle.Transfer.Completed(0)),
        )

        val outcome = device.reboot().reboot("") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.SEND_FAILED, outcome.reason)
        assertEquals(AdbRebootState.UNTOUCHED, outcome.state)
    }

    /** Исход и состояние устройства попадают в журнал: по ним потом разбирают прогон. */
    @Test
    fun theOutcomeAndTheDeviceStateReachTheLog() {
        val sink = InMemoryDiagnosticSink()
        val device = ScriptedDevice(okay(), close())

        device.reboot(sink).reboot("bootloader")

        val messages = sink.snapshot().map { it.message }
        assertTrue(messages.contains("reboot_requested"))
        val accepted = sink.snapshot().single { it.message == "reboot_accepted" }
        assertEquals("reboot:bootloader", accepted.fields["service"])
    }

    @Test
    fun theRefusalStateReachesTheLog() {
        val sink = InMemoryDiagnosticSink()
        val device = ScriptedDevice(okay(), write("no"), close())

        device.reboot(sink).reboot("")

        val failure = sink.snapshot().single { it.message == "reboot_failed" }
        assertEquals(AdbRebootState.UNTOUCHED.name, failure.fields["state"])
        assertEquals(AdbRebootFailure.DEVICE_REFUSED.name, failure.fields["reason"])
    }

    private inner class ScriptedDevice(
        vararg responses: List<FakeUsbTransportHandle.Transfer>,
        outbound: List<FakeUsbTransportHandle.Transfer> = emptyList(),
    ) {
        val handle = FakeUsbTransportHandle(
            inbound = responses.flatMap { it }.toMutableList(),
            outbound = outbound.toMutableList(),
            answerOnlyAfterRequest = true,
        )

        private val harness = harnesses.start(handle)

        fun reboot(diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink()) = AdbReboot(
            writer = harness.writer,
            dispatcher = harness.dispatcher,
            diagnostics = diagnostics,
        )
    }

    private companion object {
        const val REMOTE_ID = 42
        const val LOCAL_ID = 1

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

        fun close() = packet(AdbCommand.CLSE)

        fun write(text: String) = packet(AdbCommand.WRTE, payload = text.toByteArray())

        fun failedRead(reason: UsbTransferFailure) =
            listOf<FakeUsbTransportHandle.Transfer>(FakeUsbTransportHandle.Transfer.Failed(reason))

        /** Объявленный payload приходит короче объявленного: кадр потерян. */
        fun shortPayload(): List<FakeUsbTransportHandle.Transfer> {
            val payload = ByteArray(64) { 1 }
            return listOf(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(AdbCommand.WRTE, REMOTE_ID, LOCAL_ID, payload),
                ),
                FakeUsbTransportHandle.Transfer.Completed(32, payload),
            )
        }
    }
}
