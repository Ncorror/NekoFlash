package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Dependency-free policy for compact user logs and structured diagnostics.
 *
 * Severity is determined only by a declared level or by an explicit leading
 * marker. Human-readable message text is never scanned for words such as
 * "warning", "failed" or "blocked" because counters and explanatory prose
 * may legitimately contain those words.
 */
object DiagnosticLogPolicy {
    enum class Level { INFO, SUCCESS, WARNING, ERROR }
    enum class Category { SYSTEM, USB, ADB, FASTBOOT, DATA, INVENTORY, OPERATION, FILE, UNKNOWN }

    data class Classification(
        val level: Level,
        val category: Category,
        val significant: Boolean
    )

    /**
     * Priority: declared structured level -> explicit text marker -> leading
     * icon marker -> INFO fallback. Message body never changes severity.
     */
    fun classify(message: String, declaredLevel: Level? = null): Classification {
        val text = message.trim()
        val lower = text.lowercase(Locale.US)
        val level = declaredLevel ?: explicitMarkerLevel(text) ?: Level.INFO

        val category = when {
            lower.contains("inventory") || lower.contains("getvar:all") ||
                lower.contains("partitions") || lower.contains("partition inventory") -> Category.INVENTORY
            lower.contains("fastboot data") || lower.contains("data phase") ||
                lower.contains("transferred:") || lower.contains("transfer ") ||
                lower.contains("usb-request-tx") || lower.contains("urb") -> Category.DATA
            lower.contains("fastboot") || lower.contains("getvar:") ||
                lower.startsWith("-> flash") || lower.startsWith("<- okay") -> Category.FASTBOOT
            lower.contains("adb") || lower.contains("sideload") || lower.contains("shell_v2") -> Category.ADB
            lower.contains("usb") || lower.contains("otg") || lower.contains("endpoint") -> Category.USB
            lower.contains("operation") || lower.contains("operation") || lower.contains("wake lock") -> Category.OPERATION
            lower.contains("file") || lower.contains("sha-256") || lower.contains("checksum") ||
                lower.contains("folder") || lower.contains("report") || lower.contains("zip") -> Category.FILE
            text.startsWith("===") || text.startsWith("[") -> Category.SYSTEM
            else -> Category.UNKNOWN
        }

        val significant = level != Level.INFO ||
            text.startsWith("===") ||
            category in setOf(Category.DATA, Category.INVENTORY)

        return Classification(level, category, significant)
    }

    private fun explicitMarkerLevel(message: String): Level? {
        val text = message.trimStart()
        val upper = text.uppercase(Locale.US)

        // Text markers are checked before icons, so "[INFO] ❌ ..." remains INFO.
        return when {
            upper.startsWith("[ERROR]") || upper.startsWith("ERROR:") -> Level.ERROR
            upper.startsWith("[WARNING]") || upper.startsWith("[WARN]") ||
                upper.startsWith("WARNING:") || upper.startsWith("WARN:") -> Level.WARNING
            upper.startsWith("[SUCCESS]") || upper.startsWith("SUCCESS:") -> Level.SUCCESS
            upper.startsWith("[INFO]") || upper.startsWith("INFO:") -> Level.INFO
            text.startsWith("⛔") || text.startsWith("🚫") || text.startsWith("🛑") || text.startsWith("🔒") -> Level.ERROR
            text.startsWith("❌") -> Level.ERROR
            text.startsWith("⚠") || text.startsWith("⏳") -> Level.WARNING
            text.startsWith("✅") || text.startsWith("🎉") -> Level.SUCCESS
            text.startsWith("ℹ") || text.startsWith("💡") -> Level.INFO
            else -> null
        }
    }

    /** Main file progress cadence. UI progress is intentionally more frequent. */
    fun progressLogStepPercent(debugLogging: Boolean): Int = if (debugLogging) 5 else 10

    fun progressLogIntervalMs(debugLogging: Boolean): Long = if (debugLogging) 5_000L else 10_000L

    fun uiProgressStepPercent(): Int = 1

    fun uiProgressIntervalMs(): Long = 1_000L

    /** Exact duplicate messages inside this window are coalesced in the compact log. */
    fun duplicateWindowMs(): Long = 1_500L
}
