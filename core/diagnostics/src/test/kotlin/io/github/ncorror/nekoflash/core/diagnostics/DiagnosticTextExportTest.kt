package io.github.ncorror.nekoflash.core.diagnostics

import io.github.ncorror.nekoflash.core.model.TargetId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTextExportTest {
    @Test
    fun exportsFullStructuredEvidenceAndRedactsSensitiveFields() {
        val text = formatDiagnosticEvidence(
            events = listOf(
                DiagnosticEvent(
                    monotonicNanos = 123L,
                    category = "usb",
                    code = "claim_result",
                    targetId = TargetId("usb:/dev/bus/usb/001/002"),
                    fields = mapOf(
                        "claimed" to "true",
                        "interfaceIndex" to "0",
                        "rawBytes" to "DEADBEEF",
                        "authToken" to "must-not-leak",
                    ),
                ),
            ),
            generatedAtEpochMillis = 456L,
        )

        assertTrue(text.contains("NekoFlash diagnostics export v1"))
        assertTrue(text.contains("eventCount=1"))
        assertTrue(text.contains("code=claim_result"))
        assertTrue(text.contains("claimed=true"))
        assertTrue(text.contains("interfaceIndex=0"))
        assertTrue(text.contains("rawBytes=<redacted>"))
        assertTrue(text.contains("authToken=<redacted>"))
        assertFalse(text.contains("DEADBEEF"))
        assertFalse(text.contains("must-not-leak"))
    }
}
