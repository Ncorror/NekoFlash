package io.github.ncorror.nekoflash.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeTest {
    @Test
    fun unknownIsAFirstClassOutcome() {
        val outcome: Outcome<Unit> = Outcome.Unknown("mutation may have crossed the wire")

        assertTrue(outcome is Outcome.Unknown)
        assertEquals("mutation may have crossed the wire", (outcome as Outcome.Unknown).reason)
    }
}
