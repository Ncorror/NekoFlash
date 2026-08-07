package ru.forum.adbfastboottool

import java.io.File

/** Pure policy for the user-facing logs menu. */
object LogMenuPolicy {
    enum class Kind { COMPACT, TRACE, SESSION_SUMMARY }

    data class Entry(
        val file: File,
        val kind: Kind
    )

    fun kindOf(fileName: String): Kind? = when {
        fileName.startsWith("log-") && fileName.endsWith(".txt") -> Kind.COMPACT
        fileName.startsWith("trace-") && fileName.endsWith(".txt") -> Kind.TRACE
        fileName.startsWith("session-summary-") && fileName.endsWith(".json") -> Kind.SESSION_SUMMARY
        else -> null
    }

    fun entries(files: Array<File>?): List<Entry> = files
        .orEmpty()
        .asSequence()
        .filter { it.isFile }
        .mapNotNull { file -> kindOf(file.name)?.let { Entry(file, it) } }
        .sortedWith(compareByDescending<Entry> { it.file.lastModified() }.thenByDescending { it.file.name })
        .toList()

    fun canDelete(entry: Entry, activeFileNames: Set<String>): Boolean =
        entry.file.name !in activeFileNames

    fun kindLabel(kind: Kind): String = when (kind) {
        Kind.COMPACT -> "COMPACT"
        Kind.TRACE -> "TRACE"
        Kind.SESSION_SUMMARY -> "SUMMARY"
    }
}
