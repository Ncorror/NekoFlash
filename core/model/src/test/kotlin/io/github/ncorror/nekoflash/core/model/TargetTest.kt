package io.github.ncorror.nekoflash.core.model

import org.junit.Assert.assertNotEquals
import org.junit.Test

class TargetTest {
    @Test
    fun sessionGenerationIsNotTargetIdentity() {
        val target = TargetId("usb:18d1:4ee7:serial-A")
        val first = SessionGeneration(1)
        val second = SessionGeneration(2)

        assertNotEquals(first, second)
        assertNotEquals(target.value, first.value.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankTargetIdIsRejected() {
        TargetId(" ")
    }
}
