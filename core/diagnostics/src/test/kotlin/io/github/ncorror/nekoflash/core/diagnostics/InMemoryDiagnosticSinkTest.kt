package io.github.ncorror.nekoflash.core.diagnostics

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import org.junit.Assert.assertEquals
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
}
