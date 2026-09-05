package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbInteractiveShellTest {
    @Test
    fun openRequestsAPtyWhenTheDeviceSupportsShellV2() {
        val device = Device()
        device.shell(useShellV2 = true).open()

        val open = device.handle.sentFrames().single()
        assertEquals(AdbCommand.OPEN, open.command)
        assertEquals("${AdbInteractiveShell.SERVICE_PTY}\u0000", open.payload.decodeToString())
    }

    @Test
    fun openFallsBackToPlainShellWithoutShellV2() {
        val device = Device()
        device.shell(useShellV2 = false).open()

        assertEquals(
            "${AdbInteractiveShell.SERVICE_LEGACY}\u0000",
            device.handle.sentFrames().single().payload.decodeToString(),
        )
    }

    @Test
    fun outputArrivesAsSeparateStreams() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "out".toByteArray())),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_STDERR, "err".toByteArray())),
        )
        val shell = device.opened()

        val first = shell.pump()
        val second = shell.pump()

        assertEquals(listOf(AdbShellEvent.Output("out")), first)
        assertEquals(listOf(AdbShellEvent.ErrorOutput("err")), second)
    }

    /** Рамка, разорванная между пакетами, не должна теряться. */
    @Test
    fun frameSplitBetweenPacketsIsAssembledAcrossPumps() {
        val frame = AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "halves".toByteArray())
        val device = Device(
            okay(),
            write(frame.copyOfRange(0, 4)),
            write(frame.copyOfRange(4, frame.size)),
        )
        val shell = device.opened()

        assertTrue(shell.pump().isEmpty())

        assertEquals(listOf(AdbShellEvent.Output("halves")), shell.pump())
    }

    @Test
    fun exitFrameEndsTheSessionAndCarriesTheCode() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_EXIT, byteArrayOf(7))),
        )
        val shell = device.opened()

        val events = shell.pump()

        assertEquals(listOf(AdbShellEvent.Exited(7)), events)
        assertFalse(shell.active)
    }

    /** После завершения качать нечего, и лишние пакеты уходить не должны. */
    @Test
    fun pumpingAfterExitDoesNothing() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_EXIT, byteArrayOf(0))),
        )
        val shell = device.opened()
        shell.pump()
        val sentAfterExit = device.handle.sentFrames().size

        assertTrue(shell.pump().isEmpty())
        assertEquals(sentAfterExit, device.handle.sentFrames().size)
    }

    @Test
    fun inputIsWrappedIntoAStdinFrame() {
        val device = Device(okay())
        val shell = device.opened()

        assertTrue(shell.sendInput("ls\n"))

        val write = device.handle.sentFrames().last { it.command == AdbCommand.WRTE }
        assertArrayEquals(
            AdbShellProtocol.encode(AdbShellProtocol.ID_STDIN, "ls\n".toByteArray()),
            write.payload,
        )
    }

    /** Без shell,v2 рамок нет: ввод уходит как есть. */
    @Test
    fun inputGoesRawWithoutShellV2() {
        val device = Device(okay())
        val shell = device.opened(useShellV2 = false)

        shell.sendInput("ls\n")

        assertArrayEquals(
            "ls\n".toByteArray(),
            device.handle.sentFrames().last { it.command == AdbCommand.WRTE }.payload,
        )
    }

    @Test
    fun controlBytesGoThroughUnchanged() {
        val device = Device(okay())
        val shell = device.opened()

        shell.sendInput(byteArrayOf(3))

        val write = device.handle.sentFrames().last { it.command == AdbCommand.WRTE }
        assertArrayEquals(
            AdbShellProtocol.encode(AdbShellProtocol.ID_STDIN, byteArrayOf(3)),
            write.payload,
        )
    }

    /** До подтверждения открытия писать некуда. */
    @Test
    fun inputBeforeOkayIsRefused() {
        val device = Device()
        val shell = device.shell(useShellV2 = true)
        shell.open()

        assertFalse(shell.sendInput("ls\n"))
    }

    @Test
    fun closeInputSendsTheCloseStdinFrame() {
        val device = Device(okay())
        val shell = device.opened()

        assertTrue(shell.closeInput())

        assertArrayEquals(
            AdbShellProtocol.closeStdinFrame(),
            device.handle.sentFrames().last { it.command == AdbCommand.WRTE }.payload,
        )
    }

    /** В обычном shell: конца ввода нет, и делать вид, что он отправлен, нельзя. */
    @Test
    fun closeInputIsRefusedWithoutShellV2() {
        val device = Device(okay())

        assertFalse(device.opened(useShellV2 = false).closeInput())
    }

    @Test
    fun deviceClosingTheStreamEndsTheSessionWithoutACode() {
        val device = Device(okay(), close())
        val shell = device.opened()

        val events = shell.pump()

        assertEquals(listOf(AdbShellEvent.Exited(null)), events)
        assertFalse(shell.active)
    }

    /** Вывод, пришедший вместе с закрытием, не должен потеряться. */
    @Test
    fun outputBeforeCloseIsDeliveredWithIt() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "bye".toByteArray())),
            close(),
        )
        val shell = device.opened()
        val output = shell.pump()

        val closing = shell.pump()

        assertEquals(listOf(AdbShellEvent.Output("bye")), output)
        assertEquals(listOf(AdbShellEvent.Exited(null)), closing)
    }

    @Test
    fun lostTransportBreaksTheSession() {
        val device = Device(okay(), failed(UsbTransferFailure.NOT_HELD))
        val shell = device.opened()

        val events = shell.pump()

        assertTrue((events.single() as AdbShellEvent.Broken).reason.contains("transport"))
        assertFalse(shell.active)
    }

    @Test
    fun corruptFrameBreaksTheSessionInsteadOfGuessing() {
        val device = Device(
            okay(),
            write(byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)),
        )
        val shell = device.opened()

        val events = shell.pump()

        assertTrue(events.single() is AdbShellEvent.Broken)
        assertFalse(shell.active)
    }

    /** Тишина — обычное состояние оболочки, ждущей ввода. */
    @Test
    fun silenceIsNotAnError() {
        val device = Device(okay(), silence())
        val shell = device.opened()

        assertTrue(shell.pump().isEmpty())
        assertTrue(shell.active)
    }

    @Test
    fun closingSendsCloseToTheDevice() {
        val device = Device(okay())
        val shell = device.opened()

        shell.close()

        assertEquals(AdbCommand.CLSE, device.handle.sentFrames().last().command)
        assertFalse(shell.active)
    }

    @Test
    fun exitWithoutPayloadLeavesTheCodeUnknown() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_EXIT, ByteArray(0))),
        )

        val events = device.opened().pump()

        assertNull((events.single() as AdbShellEvent.Exited).code)
    }

    private class Device(vararg responses: List<FakeUsbTransportHandle.Transfer>) {
        val handle = FakeUsbTransportHandle(inbound = responses.flatMap { it }.toMutableList())

        fun shell(useShellV2: Boolean) = AdbInteractiveShell(
            reader = AdbPacketReader(handle, AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES),
            writer = AdbPacketWriter(handle),
            router = AdbStreamRouter(),
            useShellV2 = useShellV2,
        )

        /** Открытая и подтверждённая сессия: первый шаг съедает `OKAY`. */
        fun opened(useShellV2: Boolean = true): AdbInteractiveShell =
            shell(useShellV2).also { shell ->
                shell.open()
                shell.pump()
            }
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

        fun write(payload: ByteArray) = packet(AdbCommand.WRTE, payload = payload)

        fun close() = packet(AdbCommand.CLSE)

        fun silence() = listOf<FakeUsbTransportHandle.Transfer>(
            FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED),
        )

        fun failed(reason: UsbTransferFailure) =
            listOf<FakeUsbTransportHandle.Transfer>(FakeUsbTransportHandle.Transfer.Failed(reason))
    }
}
