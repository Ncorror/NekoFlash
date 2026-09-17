package io.github.ncorror.nekoflash.core.diagnostics

import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One named UTF-8 section inside a shareable diagnostics ZIP. */
data class DiagnosticBundleSection(
    val name: String,
    val content: String,
) {
    init {
        require(name.isNotBlank()) { "Section name must not be blank" }
        require(name.none { it == '/' || it == '\\' }) { "Section name must not contain path separators" }
        require(name != DiagnosticBundle.MANIFEST_NAME) { "Section name is reserved for the bundle manifest" }
    }
}

data class DiagnosticBundleResult(
    val sectionCount: Int,
    val uncompressedBytes: Long,
)

/**
 * Deterministic multi-file diagnostics archive writer.
 *
 * Only explicitly supplied sections are written. ZIP entry timestamps are zeroed so archive bytes do not
 * change because of filesystem metadata. The caller owns [OutputStream] and it is never closed here.
 */
object DiagnosticBundle {
    const val SCHEMA = "io.github.ncorror.nekoflash.diagnostics-export.v1"
    const val MANIFEST_NAME = "export-manifest.txt"

    private val fileNameFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    fun write(
        output: OutputStream,
        sections: List<DiagnosticBundleSection>,
        exportedAt: Instant,
    ): DiagnosticBundleResult {
        val duplicates = sections.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate section names: ${duplicates.sorted()}" }

        val manifest = manifestText(sections, exportedAt)
        val sectionBytes = sections.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
        val manifestBytes = manifest.toByteArray(Charsets.UTF_8).size.toLong()

        ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(output))).use { zip ->
            writeEntry(zip, MANIFEST_NAME, manifest)
            sections.forEach { section -> writeEntry(zip, section.name, section.content) }
        }
        output.flush()

        return DiagnosticBundleResult(
            sectionCount = sections.size,
            uncompressedBytes = sectionBytes + manifestBytes,
        )
    }

    fun suggestedFileName(exportedAt: Instant): String =
        "NekoFlash-evidence-${fileNameFormatter.format(exportedAt)}.zip"

    private fun manifestText(
        sections: List<DiagnosticBundleSection>,
        exportedAt: Instant,
    ): String = buildString {
        appendLine("schema=$SCHEMA")
        appendLine("exportedAt=$exportedAt")
        appendLine("sectionCount=${sections.size}")
        sections.forEach { section ->
            appendLine("section=${section.name} bytes=${section.content.toByteArray(Charsets.UTF_8).size}")
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        try {
            zip.write(content.toByteArray(Charsets.UTF_8))
        } finally {
            zip.closeEntry()
        }
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun write(source: ByteArray, offset: Int, length: Int) {
            out.write(source, offset, length)
        }

        override fun close() {
            flush()
        }
    }
}
