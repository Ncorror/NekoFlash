package io.github.ncorror.nekoflash.core.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticBundleTest {
    @Test
    fun manifestComesFirstAndDescribesEverySection() {
        val entries = readEntries(writeBundle(SECTIONS))

        assertEquals(
            listOf(DiagnosticBundle.MANIFEST_NAME, "summary.txt", "usb-events.txt"),
            entries.keys.toList(),
        )
        val manifest = entries.getValue(DiagnosticBundle.MANIFEST_NAME)
        assertTrue(manifest.contains("schema=${DiagnosticBundle.SCHEMA}"))
        assertTrue(manifest.contains("exportedAt=$EXPORTED_AT"))
        assertTrue(manifest.contains("sectionCount=2"))
        assertTrue(manifest.contains("section=summary.txt"))
        assertTrue(manifest.contains("section=usb-events.txt"))
    }

    @Test
    fun sectionContentSurvivesRoundTrip() {
        val entries = readEntries(writeBundle(SECTIONS))

        assertEquals("scope=phase2-usb-evidence\n", entries.getValue("summary.txt"))
        assertEquals("event\n", entries.getValue("usb-events.txt"))
    }

    @Test
    fun identicalInputsProduceIdenticalArchiveBytes() {
        assertArrayEquals(writeBundle(SECTIONS), writeBundle(SECTIONS))
    }

    @Test
    fun unsafeDuplicateOrReservedSectionNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { section("../escape.txt", "x") }
        assertThrows(IllegalArgumentException::class.java) { section("nested/file.txt", "x") }
        assertThrows(IllegalArgumentException::class.java) { section(" ", "x") }
        assertThrows(IllegalArgumentException::class.java) { section(DiagnosticBundle.MANIFEST_NAME, "x") }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticBundle.write(
                ByteArrayOutputStream(),
                listOf(section("same.txt", "a"), section("same.txt", "b")),
                EXPORTED_AT,
            )
        }
    }

    @Test
    fun callerKeepsOwnershipOfOutputStream() {
        val output = ClosingAwareStream()

        DiagnosticBundle.write(output, SECTIONS, EXPORTED_AT)

        assertFalse(output.closed)
        assertTrue(output.size() > 0)
    }

    @Test
    fun suggestedFilenameUsesUtcTimestamp() {
        assertEquals(
            "NekoFlash-evidence-19700101-000000Z.zip",
            DiagnosticBundle.suggestedFileName(Instant.EPOCH),
        )
    }

    private class ClosingAwareStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        val EXPORTED_AT: Instant = Instant.parse("2026-09-17T20:00:00Z")
        val SECTIONS = listOf(
            section("summary.txt", "scope=phase2-usb-evidence\n"),
            section("usb-events.txt", "event\n"),
        )

        fun section(name: String, content: String) = DiagnosticBundleSection(name, content)

        fun writeBundle(sections: List<DiagnosticBundleSection>): ByteArray =
            ByteArrayOutputStream().also { output: OutputStream ->
                DiagnosticBundle.write(output, sections, EXPORTED_AT)
            }.toByteArray()

        fun readEntries(bytes: ByteArray): LinkedHashMap<String, String> {
            val entries = linkedMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
            return entries
        }
    }
}
