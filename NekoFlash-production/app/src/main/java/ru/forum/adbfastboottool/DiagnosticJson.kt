package ru.forum.adbfastboottool

/**
 * Small dependency-free JSON helpers for diagnostic reports.
 *
 * Android has org.json, but keeping this formatter local lets the lightweight
 * kotlinc/static checks run without the Android runtime on the host.
 */
object DiagnosticJson {
    fun quote(value: String?): String {
        if (value == null) return "null"
        val out = StringBuilder(value.length + 8)
        out.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) out.append("\\u%04x".format(ch.code))
                    else out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    fun bool(value: Boolean): String = if (value) "true" else "false"
    fun number(value: Long?): String = value?.toString() ?: "null"
    fun number(value: Int?): String = value?.toString() ?: "null"

    fun stringArray(values: List<String>, indent: String = "    "): String {
        if (values.isEmpty()) return "[]"
        return values.joinToString(prefix = "[\n", postfix = "\n${indent.dropLast(2)}]", separator = ",\n") {
            "$indent${quote(it)}"
        }
    }
}
