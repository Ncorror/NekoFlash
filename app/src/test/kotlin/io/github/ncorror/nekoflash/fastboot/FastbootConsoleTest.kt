package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Произвольная команда, переменная по имени и `getvar:all` в production.
 *
 * Проверяется не протокол — он закрыт тестами своего модуля, — а то, что исход
 * доходит до оператора и до журнала неискажённым: отказ устройства остаётся
 * отказом устройства, а незнание остаётся незнанием.
 */
class FastbootConsoleTest {
    @Test
    fun anArbitraryCommandReachesTheDeviceAsTyped() {
        val coordinator = ClaimingCoordinator(replies = listOf("OKAYno", "OKAY"))
        val controller = connected(coordinator)

        controller.runCommand("  oem device-info  ")

        assertEquals(
            "команда уходит без окружающих пробелов и без изменений",
            listOf("getvar:is-userspace", "oem device-info"),
            coordinator.lastHandle?.sent,
        )
    }

    /**
     * `FAIL` показывается как ответ устройства, а не как сбой.
     *
     * Иначе оператор не отличит «устройство сказало нет» от «мы не смогли
     * спросить», а это разные вещи (`03` §2).
     */
    @Test
    fun aDeviceRefusalIsShownAsAnAnswer() {
        val controller = connected(ClaimingCoordinator(listOf("OKAYno", "FAILunknown command")))

        controller.runCommand("oem something")

        val state = controller.console.value as FastbootConsoleState.Answered
        assertEquals(FastbootReply.FAIL, state.reply)
        assertEquals("unknown command", state.payload)
        assertEquals("отказ рамку не портит", FastbootLaneState.IDLE, state.lane)
    }

    /** Строки `INFO` доходят до оператора, а не теряются по пути. */
    @Test
    fun theInfoLinesReachTheOperator() {
        val controller = connected(
            ClaimingCoordinator(listOf("OKAYno", "INFOerasing", "INFOdone", "OKAY")),
        )

        controller.runCommand("erase:cache")

        val state = controller.console.value as FastbootConsoleState.Answered
        assertEquals(listOf("erasing", "done"), state.info)
    }

    /** Молчание — это не отказ, и в журнале оно называется иначе. */
    @Test
    fun silenceIsReportedSeparatelyFromARefusal() {
        val sink = InMemoryDiagnosticSink()
        val controller = connected(ClaimingCoordinator(listOf("OKAYno")), sink)

        controller.runCommand("getvar:product")

        val state = controller.console.value as FastbootConsoleState.NotAnswered
        assertEquals(FastbootLaneState.STALLED, state.lane)
        assertEquals("none", sink.snapshot().last().fields["reply"])
    }

    /**
     * Команда, которую провод не несёт, называется и не уходит.
     *
     * Набрать её никто не мешает — но отправить мы обязаны ровно набранное
     * либо ничего (ADR-0006 §4).
     */
    @Test
    fun aCommandTheWireCannotCarryIsNamedAndNotSent() {
        val coordinator = ClaimingCoordinator(listOf("OKAYno", "OKAY"))
        val controller = connected(coordinator)

        controller.runCommand("oem разблокировать")

        val state = controller.console.value as FastbootConsoleState.NotAnswered
        assertTrue("причина должна быть названа", state.detail.contains("ASCII"))
        assertEquals("на устройство ничего лишнего не ушло", 1, coordinator.lastHandle?.sent?.size)
    }

    @Test
    fun aVariableIsReadByName() {
        val coordinator = ClaimingCoordinator(listOf("OKAYno", "OKAYvayu"))
        val controller = connected(coordinator)

        controller.readVariable("product")

        val state = controller.console.value as FastbootConsoleState.Answered
        assertEquals("vayu", state.payload)
        assertEquals(listOf("getvar:is-userspace", "getvar:product"), coordinator.lastHandle?.sent)
    }

    @Test
    fun theWholeVariableListIsParsedAndCounted() {
        val sink = InMemoryDiagnosticSink()
        val controller = connected(
            ClaimingCoordinator(listOf("OKAYno", "INFOproduct: vayu", "INFOsecure: yes", "OKAY")),
            sink,
        )

        controller.readAllVariables()

        val state = controller.console.value as FastbootConsoleState.Variables
        assertEquals("vayu", state.snapshot.value("product"))
        assertTrue(state.snapshot.complete)
        assertEquals("2", sink.snapshot().last().fields["variables"])
    }

    /** Без соединения команда не уходит и говорит почему. */
    @Test
    fun withoutAConnectionNothingIsSent() {
        val controller = FastbootLinkController({ RefusingCoordinator().claim() }, { it.run() }, InMemoryDiagnosticSink())

        controller.runCommand("getvar:product")

        val state = controller.console.value as FastbootConsoleState.NotAnswered
        assertEquals("соединения нет", state.detail)
    }

    /** Соединение отпущено — консоль возвращается в исходное, а не хранит старый ответ. */
    @Test
    fun disconnectingClearsTheConsole() {
        val controller = connected(ClaimingCoordinator(listOf("OKAYno", "OKAY")))
        controller.runCommand("getvar:product")

        controller.disconnect()

        assertEquals(FastbootConsoleState.Idle, controller.console.value)
    }

    private fun connected(
        coordinator: ClaimingCoordinator,
        diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink(),
    ): FastbootLinkController {
        val controller = FastbootLinkController({ coordinator.claim() }, { it.run() }, diagnostics)
        controller.connect(SessionGeneration(1))
        return controller
    }
}
