package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbByteChannel
import io.github.ncorror.nekoflash.protocol.adb.AdbForwardEnd
import io.github.ncorror.nekoflash.protocol.adb.AdbForwardOutcome
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbForwardControllerTest {
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "forward-controller-test").apply { isDaemon = true }
    }
    private val opened = mutableListOf<AutoCloseable>()

    @After
    fun closeEverything() {
        opened.forEach { closeable -> runCatching { closeable.close() } }
        opened.clear()
        pool.shutdownNow()
    }

    @Test
    fun aBoundForwardReportsThePortItActuallyGot() {
        val controller = controller()

        controller.add(HeldSource(), localPort = 0, address = ADDRESS)

        val entry = awaitSingleForward(controller)
        assertTrue("слушатель должен получить настоящий порт", entry.localPort > 0)
        assertEquals(ADDRESS, entry.address)
        assertEquals(0, entry.served)
    }

    /** Адрес передаётся как набран: что бывает на той стороне, знает устройство. */
    @Test
    fun theAddressIsNotParsedOrChecked() {
        val controller = controller()

        controller.add(HeldSource(), localPort = 0, address = "  localabstract:что угодно  ")

        assertEquals("localabstract:что угодно", awaitSingleForward(controller).address)
    }

    /** Пустой адрес — не ошибка, а нечего делать. */
    @Test
    fun anEmptyAddressDoesNothingAtAll() {
        val controller = controller()

        controller.add(HeldSource(), localPort = 0, address = "   ")

        // Утверждение отрицательное, и ждать его полным окном незачем: если бы
        // слушатель заводился, он завёлся бы сразу — привязка не медленная.
        assertFalse(await(QUIET_MS) { controller.state.value.forwards.isNotEmpty() })
        assertEquals(null, controller.state.value.failure)
    }

    /** Занятый порт — видимый отказ, а не тишина. */
    @Test
    fun aPortThatCannotBeBoundIsAVisibleFailure() {
        val taken = loopbackListener()
        val controller = controller()

        controller.add(HeldSource(), localPort = taken.localPort, address = ADDRESS)

        assertTrue(await { controller.state.value.failure != null })
        assertTrue(controller.state.value.forwards.isEmpty())
    }

    @Test
    fun aClientConnectionIsHandedToTheDevice() {
        val source = HeldSource()
        val controller = controller()
        controller.add(source, localPort = 0, address = ADDRESS)
        val port = awaitSingleForward(controller).localPort

        connect(port)

        assertTrue(await { source.opens.isNotEmpty() })
        assertEquals(ADDRESS, source.opens.first())
    }

    @Test
    fun bytesTravelBothWaysThroughTheSocket() {
        val source = HeldSource()
        val controller = controller()
        controller.add(source, localPort = 0, address = ADDRESS)
        val port = awaitSingleForward(controller).localPort

        val client = connect(port)
        client.getOutputStream().write("ping".toByteArray())
        client.getOutputStream().flush()
        assertTrue(await { source.channels.isNotEmpty() })
        val channel = source.channels.first()
        val seen = ByteArray(4)
        assertEquals(4, channel.read(seen))
        channel.write("pong".toByteArray(), 4)

        assertEquals("ping", seen.decodeToString())
        val back = ByteArray(4)
        assertEquals(4, client.getInputStream().read(back))
        assertEquals("pong", back.decodeToString())
    }

    /** Соединение сверх потолка закрывается, а не ждёт очереди молча. */
    @Test
    fun connectionsBeyondTheCapAreRefusedAndNamed() {
        val source = HeldSource()
        val sink = RecordingSink()
        val controller = controller(sink)
        controller.add(source, localPort = 0, address = ADDRESS)
        val port = awaitSingleForward(controller).localPort

        repeat(AdbForwardController.MAX_CONNECTIONS) { connect(port) }
        assertTrue(await { source.channels.size >= AdbForwardController.MAX_CONNECTIONS })
        connect(port)

        assertTrue(await { sink.messages().contains("forward_refused") })
        assertEquals(AdbForwardController.MAX_CONNECTIONS, source.channels.size)
    }

    /** Итог соединения виден оператору, а не теряется вместе с соединением. */
    @Test
    fun theEndOfAConnectionIsRemembered() {
        val source = HeldSource()
        val controller = controller()
        controller.add(source, localPort = 0, address = ADDRESS)
        val port = awaitSingleForward(controller).localPort
        connect(port)
        assertTrue(await { source.channels.isNotEmpty() })

        source.release(AdbForwardEnd.DEVICE_CLOSED, "device closed the stream")

        assertTrue(await { controller.state.value.forwards.single().served == 1 })
        val entry = controller.state.value.forwards.single()
        assertEquals(0, entry.live)
        assertTrue("итог должен называть причину: ${entry.lastEnd}", entry.lastEnd.orEmpty().contains("DEVICE_CLOSED"))
    }

    /** Отказ открыть поток — тоже итог: слушатель остаётся, соединение нет. */
    @Test
    fun aRefusedAddressEndsTheConnectionButNotTheForward() {
        val source = HeldSource(refuseWith = AdbForwardEnd.REJECTED)
        val controller = controller()
        controller.add(source, localPort = 0, address = ADDRESS)
        val port = awaitSingleForward(controller).localPort

        connect(port)

        assertTrue(await { controller.state.value.forwards.single().served == 1 })
        assertTrue(
            "слушатель должен пережить отказ адреса",
            controller.state.value.forwards.single().lastEnd.orEmpty().contains("REJECTED"),
        )
    }

    @Test
    fun removingAForwardClosesTheListener() {
        val controller = controller()
        controller.add(HeldSource(), localPort = 0, address = ADDRESS)
        val port = awaitSingleForward(controller).localPort

        controller.remove(port)

        assertTrue(controller.state.value.forwards.isEmpty())
        assertTrue("порт должен освободиться", await { canBind(port) })
    }

    @Test
    fun removingAForwardBreaksItsLiveConnections() {
        val source = HeldSource()
        val controller = controller()
        controller.add(source, localPort = 0, address = ADDRESS)
        val port = awaitSingleForward(controller).localPort
        connect(port)
        assertTrue(await { source.channels.isNotEmpty() })

        controller.remove(port)

        assertTrue(await { source.cancelled.isNotEmpty() })
        assertEquals("removed by operator", source.cancelled.first())
    }

    /** Транспорта нет — пробросов тоже: слушатель принимал бы клиентов в никуда. */
    @Test
    fun aDeadTransportTakesEveryForwardDown() {
        val controller = controller()
        controller.add(HeldSource(), localPort = 0, address = ADDRESS)
        controller.add(HeldSource(), localPort = 0, address = "tcp:1")
        assertTrue(await { controller.state.value.forwards.size == 2 })

        controller.stopAll("transport closed")

        assertTrue(controller.state.value.forwards.isEmpty())
    }

    @Test
    fun theJournalNamesEveryListener() {
        val sink = RecordingSink()
        val controller = controller(sink)

        controller.add(HeldSource(), localPort = 0, address = ADDRESS)

        assertTrue(await { sink.messages().contains("forward_listening") })
    }

    /** Закрытие сокета должно переживать повторный вызов: закрывают обе стороны. */
    @Test
    fun theSocketChannelSurvivesBeingClosedTwice() {
        val listener = loopbackListener()
        val client = connect(listener.localPort)
        val accepted = listener.accept()
        opened += AutoCloseable { accepted.close() }
        val channel = AdbSocketChannel(accepted)

        channel.close()
        channel.close()

        assertTrue(accepted.isClosed)
        assertTrue(client.isConnected)
    }

    private fun controller(sink: DiagnosticSink = DiagnosticSink { }) =
        AdbForwardController(executor = pool, diagnostics = sink).also { controller ->
            opened += AutoCloseable { controller.stopAll("test finished") }
        }

    private fun loopbackListener(): ServerSocket =
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).also { server ->
            opened += AutoCloseable { server.close() }
        }

    private fun connect(port: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), port).also { socket ->
            opened += AutoCloseable { socket.close() }
        }

    private fun canBind(port: Int): Boolean = try {
        ServerSocket(port, 8, InetAddress.getLoopbackAddress()).use { true }
    } catch (_: IOException) {
        false
    }

    private fun awaitSingleForward(controller: AdbForwardController): AdbForwardEntry {
        assertTrue(
            "слушатель не появился: ${controller.state.value}",
            await { controller.state.value.forwards.size == 1 },
        )
        val entry = controller.state.value.forwards.single()
        assertNotNull(entry)
        return entry
    }

    /** Состояние меняет чужой поток, поэтому проверка ждёт, а не смотрит снимок. */
    private fun await(timeoutMillis: Long = AWAIT_MS, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    /**
     * Соединение, которое никуда не спешит.
     *
     * Держит открытый поток, пока тест не скажет иначе: настоящий проброс живёт
     * ровно так, и проверять приём соединений на соединениях, кончающихся сами
     * собой, было бы проверкой не того.
     */
    private class HeldSource(private val refuseWith: AdbForwardEnd? = null) : AdbForwardSource {
        val opens: MutableList<String> = CopyOnWriteArrayList()
        val channels: MutableList<AdbByteChannel> = CopyOnWriteArrayList()
        val cancelled: MutableList<String> = CopyOnWriteArrayList()

        private val held = CountDownLatch(1)

        @Volatile
        private var end = AdbForwardEnd.CLIENT_CLOSED

        @Volatile
        private var detail = "test released the connection"

        fun release(reason: AdbForwardEnd, text: String) {
            end = reason
            detail = text
            held.countDown()
        }

        /**
         * Ждёт освобождения, переживая остановку пула.
         *
         * `shutdownNow` в конце теста будит держателей прерыванием, и без этого
         * каждый из них печатал бы стек в вывод сборки — шум, за которым
         * настоящая ошибка потерялась бы.
         */
        private fun hold() {
            try {
                held.await(AWAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        override fun connect(channel: AdbByteChannel): AdbForwardConnection {
            channels += channel
            return object : AdbForwardConnection {
                override fun open(address: String): AdbForwardOutcome? {
                    opens += address
                    return refuseWith?.let { reason ->
                        AdbForwardOutcome(reason, "address refused", 0, 0)
                    }
                }

                override fun fromDevice(): AdbForwardOutcome {
                    hold()
                    return AdbForwardOutcome(end, detail, 0, 0)
                }

                override fun fromClient(): Unit = hold()

                override fun cancel(detail: String) {
                    cancelled += detail
                    held.countDown()
                }
            }
        }
    }

    private class RecordingSink : DiagnosticSink {
        private val events = CopyOnWriteArrayList<DiagnosticEvent>()

        override fun emit(event: DiagnosticEvent) {
            events += event
        }

        fun messages(): List<String> = events.map { event -> event.message }
    }

    private companion object {
        const val ADDRESS = "tcp:5555"
        const val AWAIT_MS = 5_000L

        /** Окно для отрицательных утверждений: «за это время ничего не случилось». */
        const val QUIET_MS = 200L
        const val POLL_MS = 5L
    }
}
