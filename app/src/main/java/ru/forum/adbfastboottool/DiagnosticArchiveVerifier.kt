package ru.forum.adbfastboottool

import java.io.File
import java.util.zip.ZipFile

/** Post-build integrity validation for exported diagnostic archives. */
object DiagnosticArchiveVerifier {
    data class Result(
        val valid: Boolean,
        val fileCount: Int,
        val sizeBytes: Long,
        val missingEntries: List<String>,
        val invalidJsonEntries: List<String>,
        val tracePresent: Boolean,
        val message: String
    )

    private val required = setOf(
        "manifest.txt",
        "app-info.txt",
        "usb-info.txt",
        "adb-info.txt",
        "fastboot-info.txt",
        "diagnostic-summary.json",
        "visible-log.txt"
    )

    fun verify(file: File, requireTrace: Boolean): Result {
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            return Result(false, 0, file.length().coerceAtLeast(0L), required.sorted(), emptyList(), false, "Архив отсутствует или пуст")
        }
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val names = entries.map { it.name }.toSet()
                val missing = required.filterNot { it in names }.sorted()
                val tracePresent = names.any { it.startsWith("logs/trace-") }
                val invalidJson = entries
                    .filter { it.name.endsWith(".json", ignoreCase = true) }
                    .mapNotNull { entry ->
                        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                        if (looksLikeJson(text)) null else entry.name
                    }
                val valid = missing.isEmpty() && invalidJson.isEmpty() && (!requireTrace || tracePresent)
                val message = if (valid) {
                    "Диагностический архив проверен: ${entries.size} файлов, ${file.length()} байт"
                } else {
                    buildString {
                        append("Архив не прошёл проверку")
                        if (missing.isNotEmpty()) append("; missing=").append(missing.joinToString(","))
                        if (invalidJson.isNotEmpty()) append("; invalid-json=").append(invalidJson.joinToString(","))
                        if (requireTrace && !tracePresent) append("; trace отсутствует")
                    }
                }
                Result(valid, entries.size, file.length(), missing, invalidJson, tracePresent, message)
            }
        } catch (e: Exception) {
            Result(false, 0, file.length(), required.sorted(), emptyList(), false, "ZIP read failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun looksLikeJson(text: String): Boolean = runCatching {
        JsonValidator(text).validate()
    }.getOrDefault(false)

    private class JsonValidator(private val source: String) {
        private var index = 0

        fun validate(): Boolean {
            skipWhitespace()
            if (!parseValue()) return false
            skipWhitespace()
            return index == source.length
        }

        private fun parseValue(): Boolean {
            skipWhitespace()
            if (index >= source.length) return false
            return when (source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> consumeLiteral("true")
                'f' -> consumeLiteral("false")
                'n' -> consumeLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> false
            }
        }

        private fun parseObject(): Boolean {
            index++
            skipWhitespace()
            if (consume('}')) return true
            while (true) {
                if (!parseString()) return false
                skipWhitespace()
                if (!consume(':')) return false
                if (!parseValue()) return false
                skipWhitespace()
                if (consume('}')) return true
                if (!consume(',')) return false
                skipWhitespace()
            }
        }

        private fun parseArray(): Boolean {
            index++
            skipWhitespace()
            if (consume(']')) return true
            while (true) {
                if (!parseValue()) return false
                skipWhitespace()
                if (consume(']')) return true
                if (!consume(',')) return false
                skipWhitespace()
            }
        }

        private fun parseString(): Boolean {
            if (!consume('"')) return false
            while (index < source.length) {
                val ch = source[index++]
                when {
                    ch == '"' -> return true
                    ch == '\u0000' || ch < ' ' -> return false
                    ch == '\\' -> {
                        if (index >= source.length) return false
                        when (source[index++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> {
                                if (index + 4 > source.length) return false
                                repeat(4) {
                                    if (source[index++] !in "0123456789abcdefABCDEF") return false
                                }
                            }
                            else -> return false
                        }
                    }
                }
            }
            return false
        }

        private fun parseNumber(): Boolean {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < source.length && source[index].isDigit()) return false
            } else {
                if (index >= source.length || source[index] !in '1'..'9') return false
                while (index < source.length && source[index].isDigit()) index++
            }
            if (consume('.')) {
                if (index >= source.length || !source[index].isDigit()) return false
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                if (index >= source.length || !source[index].isDigit()) return false
                while (index < source.length && source[index].isDigit()) index++
            }
            return index > start
        }

        private fun consumeLiteral(literal: String): Boolean {
            if (!source.regionMatches(index, literal, 0, literal.length)) return false
            index += literal.length
            return true
        }

        private fun consume(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index++
            return true
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in charArrayOf(' ', '\t', '\r', '\n')) index++
        }
    }

}
