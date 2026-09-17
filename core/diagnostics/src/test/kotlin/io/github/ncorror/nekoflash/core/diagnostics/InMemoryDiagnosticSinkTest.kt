package io.github.ncorror.nekoflash.core.diagnostics

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryDiagnosticSinkTest {
    @Test
    fun preservesTargetAndGenerationAsSeparateEvidenceFields() {
        val sink = InMemoryDiagnosticSink()
        sink.emit(
            DiagnosticEvent(
                monotonicNanos = 42L,
                category = "bootstrap",
                code = "target_seen",
                targetId = TargetId("usb:example"),
                generation = SessionGeneration(7),
            ),
        )

        val event = sink.snapshot().single()
        assertEquals("usb:example", event.targetId?.value)
        assertEquals(7L, event.generation?.value)
    }

    @Test
    fun clearStartsWithAnEmptySnapshotWithoutReplacingSink() {
        val sink = InMemoryDiagnosticSink()
        sink.emit(DiagnosticEvent(1L, "usb_evidence", "old_session"))

        sink.clear()

        assertTrue(sink.snapshot().isEmpty())
        sink.emit(DiagnosticEvent(2L, "usb_evidence", "new_session"))
        assertEquals("new_session", sink.snapshot().single().code)
    }
}
