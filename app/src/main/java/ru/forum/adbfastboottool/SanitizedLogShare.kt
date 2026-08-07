package ru.forum.adbfastboottool

import java.io.File
import java.util.UUID

/** Creates a bounded-lifetime privacy-filtered copy for Android ACTION_SEND. */
object SanitizedLogShare {
    const val DEFAULT_MAX_SOURCE_BYTES: Long = 64L * 1024L * 1024L
    const val DEFAULT_RETENTION_MS: Long = 24L * 60L * 60L * 1000L

    fun create(
        source: File,
        outputDir: File,
        scope: ReportSanitizer.Scope,
        maxSourceBytes: Long = DEFAULT_MAX_SOURCE_BYTES,
        nowMs: Long = System.currentTimeMillis()
    ): File {
        require(source.isFile) { "Log source does not exist or is not a regular file" }
        require(source.length() in 0..maxSourceBytes) { "Log source exceeds sanitized share limit" }
        require(outputDir.exists() || outputDir.mkdirs()) { "Cannot create sanitized share directory" }
        require(outputDir.isDirectory) { "Sanitized share output is not a directory" }

        cleanupExpired(outputDir, nowMs)
        val output = File(outputDir, "sanitized-log-${nowMs}-${UUID.randomUUID()}.txt")
        try {
            source.bufferedReader(Charsets.UTF_8).use { reader ->
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    reader.forEachLine { line ->
                        writer.append(ReportSanitizer.sanitizeText(line, scope))
                        writer.newLine()
                    }
                }
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        }
        return output
    }

    fun cleanupExpired(
        outputDir: File,
        nowMs: Long = System.currentTimeMillis(),
        retentionMs: Long = DEFAULT_RETENTION_MS
    ): Int {
        if (!outputDir.isDirectory) return 0
        var removed = 0
        outputDir.listFiles()?.forEach { file ->
            if (
                file.isFile &&
                file.name.startsWith("sanitized-log-") &&
                nowMs - file.lastModified() >= retentionMs &&
                file.delete()
            ) {
                removed++
            }
        }
        return removed
    }
}
