package io.github.ncorror.nekoflash.core.diagnostics

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId

/** Structured local evidence. Protocol payloads and secrets must not be added here by default. */
data class DiagnosticEvent(
    val monotonicNanos: Long,
    val category: String,
    val code: String,
    val targetId: TargetId? = null,
    val generation: SessionGeneration? = null,
    val fields: Map<String, String> = emptyMap(),
)

fun interface DiagnosticSink {
    fun emit(event: DiagnosticEvent)
}

class InMemoryDiagnosticSink : DiagnosticSink {
    private val events = mutableListOf<DiagnosticEvent>()

    override fun emit(event: DiagnosticEvent) {
        synchronized(events) {
            events += event
        }
    }

    fun snapshot(): List<DiagnosticEvent> = synchronized(events) { events.toList() }
}
