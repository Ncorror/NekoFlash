package io.github.ncorror.nekoflash.core.diagnostics

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

data class DiagnosticEvent(
    val timestamp: Instant,
    val category: String,
    val message: String,
    val targetId: TargetId? = null,
    val sessionGeneration: SessionGeneration? = null,
    val fields: Map<String, String> = emptyMap(),
) {
    init {
        require(category.isNotBlank()) { "Diagnostic category must not be blank" }
        require(message.isNotBlank()) { "Diagnostic message must not be blank" }
    }
}

fun interface DiagnosticSink {
    fun emit(event: DiagnosticEvent)
}

class InMemoryDiagnosticSink : DiagnosticSink {
    private val events = CopyOnWriteArrayList<DiagnosticEvent>()

    override fun emit(event: DiagnosticEvent) {
        events += event
    }

    fun snapshot(): List<DiagnosticEvent> = events.toList()

    fun clear() {
        events.clear()
    }
}
