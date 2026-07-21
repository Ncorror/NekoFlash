package ru.forum.adbfastboottool

/** Conservative allow-list used while the global diagnostic READ-ONLY lock is enabled. */
object AdbReadOnlyPolicy {
    fun isShellReadOnly(command: String): Boolean {
        val normalized = command.trim()
        if (normalized.isBlank()) return false
        val dangerousSyntax = listOf(
            ">", "<", "|", "&&", "||", "`", "$(", "\${", "\n", "\r"
        )
        if (dangerousSyntax.any { normalized.contains(it) }) return false
        return normalized.split(';').all { segment ->
            val cmd = segment.trim()
            when {
                cmd == "id" -> true
                cmd == "uname" || cmd.startsWith("uname ") -> true
                cmd == "df" || cmd.startsWith("df ") -> true
                cmd == "mount" -> true
                cmd.startsWith("getprop") -> true
                cmd.startsWith("cat /") -> true
                cmd.startsWith("tail ") -> true
                cmd.startsWith("ls ") || cmd == "ls" -> true
                cmd.startsWith("stat ") -> true
                cmd == "pm path android" -> true
                cmd == "echo AFT_SHELL_OK" -> true
                cmd == "sh -c 'exit 7'" || cmd == "sh -c \"exit 7\"" -> true
                else -> false
            }
        }
    }

    fun isServiceReadOnly(service: String): Boolean {
        val normalized = service.trim()
        if (!normalized.startsWith("shell:")) return false
        return isShellReadOnly(normalized.removePrefix("shell:"))
    }
}
