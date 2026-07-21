package ru.forum.adbfastboottool

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Bounded two-stream log storage.
 *
 * COMPACT contains user-relevant events. TRACE contains raw protocol/timing
 * diagnostics. Each stream rotates independently and keeps its newest tail.
 */
class DiagnosticLogStore(
    private val logsDir: File,
    private val stamp: String,
    private val compactSegmentBytes: Long = 2L * 1024L * 1024L,
    private val traceSegmentBytes: Long = 8L * 1024L * 1024L,
    private val maxSegmentsPerStream: Int = 5
) {
    enum class Stream { COMPACT, TRACE }

    private data class State(
        val stream: Stream,
        val files: MutableList<File> = mutableListOf(),
        var nextPart: Int = 1
    )

    private val compact = State(Stream.COMPACT)
    private val trace = State(Stream.TRACE)

    init {
        require(compactSegmentBytes >= 64L * 1024L)
        require(traceSegmentBytes >= 64L * 1024L)
        require(maxSegmentsPerStream >= 1)
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            throw IllegalStateException("Cannot create logs directory: ${logsDir.absolutePath}")
        }
        pruneDirectory(logsDir)
    }

    @Synchronized
    fun appendCompact(text: String) = append(compact, text, compactSegmentBytes)

    @Synchronized
    fun appendTrace(text: String) = append(trace, text, traceSegmentBytes)

    @Synchronized
    fun compactFiles(): List<File> = compact.files.filter { it.exists() }.toList()

    @Synchronized
    fun traceFiles(): List<File> = trace.files.filter { it.exists() }.toList()

    @Synchronized
    fun currentCompactFile(): File? = compact.files.lastOrNull()?.takeIf { it.exists() }

    @Synchronized
    fun currentTraceFile(): File? = trace.files.lastOrNull()?.takeIf { it.exists() }

    @Synchronized
    fun writeSessionSummary(json: String): File {
        val file = File(logsDir, "session-summary-$stamp.json")
        val temp = File(logsDir, ".${file.name}.tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            temp.delete()
            throw IllegalStateException("Cannot replace ${file.absolutePath}")
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IllegalStateException("Cannot publish ${file.absolutePath}")
        }
        return file
    }

    private fun append(state: State, text: String, limit: Long) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        var file = state.files.lastOrNull()
        if (file == null || (file.length() > 0L && file.length() + bytes.size > limit)) {
            file = createSegment(state)
        }
        file.appendBytes(bytes)
    }

    private fun createSegment(state: State): File {
        val part = state.nextPart++
        val prefix = if (state.stream == Stream.COMPACT) "log" else "trace"
        val suffix = if (part == 1) "" else "-part${part.toString().padStart(2, '0')}"
        val file = File(logsDir, "$prefix-$stamp$suffix.txt")
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Cannot replace log segment: ${file.absolutePath}")
        }
        file.createNewFile()
        state.files.add(file)
        while (state.files.size > maxSegmentsPerStream) {
            val oldest = state.files.removeAt(0)
            runCatching { oldest.delete() }
        }
        return file
    }

    companion object {
        private const val DEFAULT_MAX_FILES = 30
        private const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024L * 1024L

        /** Prunes oldest completed log/trace/summary files before a new session starts. */
        fun pruneDirectory(
            dir: File,
            maxFiles: Int = DEFAULT_MAX_FILES,
            maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
        ) {
            if (!dir.exists() || !dir.isDirectory) return
            val newestFirst = dir.listFiles { file ->
                file.isFile && (
                    file.name.startsWith("log-") ||
                        file.name.startsWith("trace-") ||
                        file.name.startsWith("session-summary-")
                    )
            }?.sortedByDescending { it.lastModified() } ?: return

            val retained = newestFirst.take(maxFiles.coerceAtLeast(0)).toMutableList()
            newestFirst.drop(retained.size).forEach { old -> runCatching { old.delete() } }

            var total = retained.sumOf { it.length().coerceAtLeast(0L) }
            retained.sortedBy { it.lastModified() }.forEach { oldest ->
                if (total <= maxTotalBytes) return@forEach
                val length = oldest.length().coerceAtLeast(0L)
                if (runCatching { oldest.delete() }.getOrDefault(false)) total -= length
            }
        }
    }
}
