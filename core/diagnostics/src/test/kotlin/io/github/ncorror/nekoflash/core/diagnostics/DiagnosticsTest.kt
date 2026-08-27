package io.github.ncorror.nekoflash.core.diagnostics

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun sinkPreservesStructuredEvents() {
        val sink = InMemoryDiagnosticSink()
        val event = DiagnosticEvent(
            timestamp = Instant.EPOCH,
            category = "bootstrap",
            message = "phase1",
            fields = mapOf("status" to "ready"),
        )

        sink.emit(event)

        assertEquals(listOf(event), sink.snapshot())
    }
}
