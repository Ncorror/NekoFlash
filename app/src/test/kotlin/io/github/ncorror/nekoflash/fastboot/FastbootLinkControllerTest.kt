package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Владелец Fastboot-соединения.
 *
 * Здесь проверяется не протокол — он закрыт тестами своего модуля, — а то, что
 * результат опроса доходит до состояния и до журнала неискажённым. Особенно
 * `UNKNOWN`: он обязан оставаться отдельным исходом с названной причиной.
 */
class FastbootLinkControllerTest {
    @Test
    fun aClaimFailureNeverReachesTheProtocol() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { RefusingCoordinator().claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(1))

        val state = controller.state.value
        assertTrue("до обмена дойти не должно", state is FastbootLinkState.Failed)
        assertEquals("fastboot_claim_failed", sink.snapshot().single().message)
    }

    @Test
    fun theProbeResultReachesTheStateAndTheJournal() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { ClaimingCoordinator(replies = listOf("OKAYyes")).claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(7))

        val connected = controller.state.value as FastbootLinkState.Connected
        assertEquals(FastbootMode.FASTBOOTD, connected.identity.mode)

        val finished = sink.snapshot().last()
        assertEquals("fastboot_probe_finished", finished.message)
        assertEquals("FASTBOOTD", finished.fields["mode"])
    }

    /**
     * Неустановленная роль — тоже результат, и причина обязана быть в журнале.
     *
     * Иначе разбор прогона упрётся в то же, во что упирались §6.52 и §6.55:
     * видно, что не получилось, и не видно почему.
     */
    @Test
    fun anUnknownModeIsRecordedWithItsReason() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { ClaimingCoordinator(replies = listOf("FAILunknown variable")).claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(3))

        val connected = controller.state.value as FastbootLinkState.Connected
        assertEquals(FastbootMode.UNKNOWN, connected.identity.mode)

        val finished = sink.snapshot().last()
        assertEquals("UNKNOWN", finished.fields["mode"])
        assertTrue(
            "причина должна быть названа, а не подразумеваться",
            finished.fields["detail"]?.contains("unknown variable") == true,
        )
    }

    /** Состояние полосы попадает в журнал: по нему видно, цела ли рамка. */
    @Test
    fun theLaneStateIsRecordedAlongsideTheOutcome() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { ClaimingCoordinator(replies = listOf("OKAYno")).claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(2))

        assertEquals("IDLE", sink.snapshot().last().fields["lane"])
    }

    @Test
    fun disconnectingReleasesTheInterfaceAndClearsTheState() {
        val coordinator = ClaimingCoordinator(replies = listOf("OKAYno"))
        val controller = FastbootLinkController({ coordinator.claim() }, { it.run() }, InMemoryDiagnosticSink())
        controller.connect(SessionGeneration(1))

        controller.disconnect()

        assertEquals(FastbootLinkState.Idle, controller.state.value)
        assertTrue("интерфейс должен быть отпущен", coordinator.lastHandle?.closed == true)
    }
}
