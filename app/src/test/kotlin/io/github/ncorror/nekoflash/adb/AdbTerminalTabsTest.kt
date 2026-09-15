package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceCandidate
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransferType
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import java.io.File
import java.util.concurrent.Executor
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Несколько одновременных оболочек.
 *
 * Проверяется владение вкладками, а не сам обмен: обмен принадлежит
 * [AdbTerminalController] и уже доказан. Здесь важно другое — что вкладка это
 * второй экземпляр владельца, что закрытие не оставляет сессию на устройстве и
 * что показываемая вкладка всегда существует.
 */
class AdbTerminalTabsTest {
    /** Ничего не выполняет: сюда попадают циклы разбора, и трогать их незачем. */
    private class HoldingExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }
    }

    @Test
    fun theFirstShellOpensAndIsShown() = withConnection { connection ->
        val tabs = AdbTerminalTabs(HoldingExecutor(), HoldingExecutor())

        val id = tabs.open(connection)

        assertEquals(1, tabs.tabs.value.size)
        assertEquals(id, tabs.selected.value)
        assertEquals(id, tabs.current()?.id)
    }

    /** Вкладка — это второй владелец сессии, а не второй режим первого. */
    @Test
    fun eachTabOwnsItsOwnSession() = withConnection { connection ->
        val tabs = AdbTerminalTabs(HoldingExecutor(), HoldingExecutor())

        tabs.open(connection)
        tabs.open(connection)

        val owners = tabs.tabs.value.map { it.sessions }
        assertEquals(2, owners.size)
        assertTrue("владельцы обязаны быть разными", owners[0] !== owners[1])
    }

    /** Новая вкладка показывается сразу: её и открывали, чтобы в ней работать. */
    @Test
    fun aNewTabIsShownAtOnce() = withConnection { connection ->
        val tabs = AdbTerminalTabs(HoldingExecutor(), HoldingExecutor())
        tabs.open(connection)

        val second = tabs.open(connection)

        assertEquals(second, tabs.selected.value)
    }

    /** Номера не переиспользуются: закрытая вкладка не оживает чужим выводом. */
    @Test
    fun numbersAreNotReused() = withConnection { connection ->
        val tabs = AdbTerminalTabs(HoldingExecutor(), HoldingExecutor())
        val first = tabs.open(connection)
        tabs.close(first)

        val second = tabs.open(connection)

        assertTrue("$second не должен повторять $first", second != first)
    }

    /** Закрытие показываемой вкладки переводит показ на оставшуюся. */
    @Test
    fun closingTheShownTabMovesTheViewToWhatIsLeft() = withConnection { connection ->
        val tabs = AdbTerminalTabs(HoldingExecutor(), HoldingExecutor())
        val first = tabs.open(connection)
        val second = tabs.open(connection)

        tabs.close(second)

        assertEquals(first, tabs.selected.value)
        assertEquals(1, tabs.tabs.value.size)
    }

    /** Закрытие не показываемой вкладки показа не трогает. */
    @Test
    fun closingAnotherTabLeavesTheViewAlone() = withConnection { connection ->
        val tabs = AdbTerminalTabs(HoldingExecutor(), HoldingExecutor())
        val first = tabs.open(connection)
        val second = tabs.open(connection)

        tabs.close(first)

        assertEquals(second, tabs.selected.value)
    }

    /** Последняя закрытая вкладка оставляет показ пустым, а не на несуществующей. */
    @Test
    fun closingTheLastTabLeavesNothingShown() = withConnection { connection ->
        val tabs = AdbTerminalTabs(HoldingExecutor(), HoldingExecutor())
        val only = tabs.open(connection)

        tabs.close(only)

        assertTrue(tabs.tabs.value.isEmpty())
        assertNull(tabs.selected.value)
        assertNull(tabs.current())
    }

    /**
     * Закрытие вкладки закрывает и её сессию.
     *
     * Убрать вкладку и оставить поток значило бы держать на устройстве
     * оболочку, о которой на экране больше ничего не сказано.
     */
    @Test
    fun closingATabAlsoClosesItsSession() = withConnection { connection ->
        val reader = HoldingExecutor()
        val tabs = AdbTerminalTabs(reader, HoldingExecutor())
        val id = tabs.open(connection)
        val owner = tabs.tabs.value.single().sessions

        tabs.close(id)

        assertTrue("сессия обязана быть закрыта", !owner.state.value.active || owner.state.value.closing)
    }

    /** Показать можно только существующую вкладку. */
    @Test
    fun selectingAnUnknownTabChangesNothing() = withConnection { connection ->
        val tabs = AdbTerminalTabs(HoldingExecutor(), HoldingExecutor())
        val only = tabs.open(connection)

        tabs.select(only + 100)

        assertEquals(only, tabs.selected.value)
    }

    /** Потеря соединения уносит все вкладки: оболочек без устройства не бывает. */
    @Test
    fun losingTheConnectionClosesEveryTab() = withConnection { connection ->
        val tabs = AdbTerminalTabs(HoldingExecutor(), HoldingExecutor())
        tabs.open(connection)
        tabs.open(connection)

        tabs.closeAll()

        assertTrue(tabs.tabs.value.isEmpty())
        assertNull(tabs.selected.value)
    }

    private fun withConnection(block: (AdbConnection) -> Unit) {
        val directory = createTempDirectory("nekoflash-tabs-").toFile()
        try {
            block(
                AdbConnection(
                    handle = SilentHandle(),
                    keyStore = AdbKeyStore(File(directory, "keys")),
                    apiLevel = 35,
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Провод, который ничего не отвечает: обмен здесь не проверяется. */
    private class SilentHandle : UsbTransportHandle {
        override val candidate: UsbInterfaceCandidate = CANDIDATE
        override val held: Boolean = true

        override fun receive(
            destination: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult = UsbTransferResult.Completed(0)

        override fun send(
            source: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult = UsbTransferResult.Completed(length)

        override fun close() = Unit
    }

    private companion object {
        val DEVICE = UsbDeviceDescriptor(
            deviceId = 1,
            deviceName = "/dev/bus/usb/test",
            vendorId = 0x18D1,
            productId = 0x4EE7,
        )
        val CANDIDATE = UsbInterfaceCandidate(
            device = DEVICE,
            kind = UsbInterfaceKind.ADB,
            confidence = UsbMatchConfidence.CANONICAL,
            interfaceIndex = 0,
            interfaceId = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x01,
            endpointIn = UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK),
            endpointOut = UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK),
        )
    }
}
