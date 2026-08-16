package ru.forum.adbfastboottool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootRegressionTest {

    @Test
    fun responsePrefixesPreserveInfoOkayFailAndDataSemantics() {
        val info = FastbootResponseParser.parse("INFOwriting partition")
        val okay = FastbootResponseParser.parse("OKAYdone")
        val fail = FastbootResponseParser.parse("FAILnot allowed")
        val data = FastbootResponseParser.parse("DATA00100000")

        assertEquals("INFO", info.type)
        assertEquals("writing partition", info.payload)
        assertEquals("OKAY", okay.type)
        assertEquals("done", okay.payload)
        assertEquals("FAIL", fail.type)
        assertEquals("not allowed", fail.payload)
        assertEquals("DATA", data.type)
        assertEquals("00100000", data.payload)
    }

    @Test
    fun shortFastbootPacketRemainsUnknown() {
        val packet = FastbootResponseParser.parse("OK")
        assertEquals("UNKNOWN", packet.type)
        assertEquals("OK", packet.payload)
    }

    @Test
    fun getVarAllAcceptsBootloaderAndInfoPrefixesWithoutInventingPartitions() {
        val snapshot = FastbootGetVarAllParser.parse(
            listOf(
                "INFO(bootloader) product: vayu",
                "(bootloader) partition-size:boot_a: 0x04000000",
                "TEXTpartition-type:boot_a: raw",
                "INFOhas-slot:boot: yes",
                "INFOall: done!"
            )
        )

        assertEquals("vayu", snapshot.value("product"))
        assertEquals(0x04000000L, snapshot.partition("boot_a")?.sizeBytes)
        assertEquals("raw", snapshot.partition("boot_a")?.type)
        assertEquals(true, snapshot.partition("boot")?.hasSlot)
        assertFalse(snapshot.partition("boot")!!.hasConcreteEvidence)
        assertTrue(snapshot.partition("boot_a")!!.hasConcreteEvidence)
    }

    @Test
    fun getVarAllRetainsDuplicateConflictEvidenceAndLastValueWins() {
        val snapshot = FastbootGetVarAllParser.parse(
            listOf(
                "INFOcurrent-slot: a",
                "INFOcurrent-slot: b"
            )
        )

        assertEquals("b", snapshot.value("current-slot"))
        assertEquals(1, snapshot.duplicateVariables.size)
        assertTrue(snapshot.duplicateVariables.single().conflicting)
    }

    @Test
    fun fastbootValueParserKeepsUnknownValuesUnknown() {
        assertEquals(true, FastbootValueParser.parseBoolean("yes"))
        assertEquals(false, FastbootValueParser.parseBoolean("0"))
        assertNull(FastbootValueParser.parseBoolean("maybe"))
        assertEquals(
            FastbootValueParser.SnapshotState.UNKNOWN,
            FastbootValueParser.parseSnapshotState("future-state")
        )
    }
}
