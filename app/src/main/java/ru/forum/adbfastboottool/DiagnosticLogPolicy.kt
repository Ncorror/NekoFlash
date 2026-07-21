package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Dependency-free policy for compact user logs and structured diagnostics.
 *
 * The protocol layers may keep emitting human-readable messages. This policy
 * classifies them consistently without coupling transports to Android UI code.
 */
object DiagnosticLogPolicy {
    enum class Level { INFO, WARNING, ERROR }
    enum class Category { SYSTEM, USB, ADB, FASTBOOT, DATA, INVENTORY, SAFETY, OPERATION, FILE, UNKNOWN }

    data class Classification(
        val level: Level,
        val category: Category,
        val significant: Boolean
    )

    fun classify(message: String): Classification {
        val text = message.trim()
        val lower = text.lowercase(Locale.US)

        val level = when {
            text.contains("❌") || text.contains("⛔") ||
                lower.contains("ошибка") || lower.contains("failed") ||
                lower.contains("session broken") || lower.contains("блокиров") -> Level.ERROR
            text.contains("⚠") || text.contains("⏳") ||
                lower.contains("warning") || lower.contains("timeout") ||
                lower.contains("неизвест") || lower.contains("отмен") -> Level.WARNING
            else -> Level.INFO
        }

        val category = when {
            lower.contains("inventory") || lower.contains("getvar:all") ||
                lower.contains("разделов") || lower.contains("partition inventory") -> Category.INVENTORY
            lower.contains("fastboot data") || lower.contains("data-фаз") ||
                lower.contains("передано:") || lower.contains("передача ") ||
                lower.contains("usb-request-tx") || lower.contains("urb") -> Category.DATA
            lower.contains("fastboot") || lower.contains("getvar:") ||
                lower.startsWith("-> flash") || lower.startsWith("<- okay") -> Category.FASTBOOT
            lower.contains("adb") || lower.contains("sideload") || lower.contains("shell_v2") -> Category.ADB
            lower.contains("usb") || lower.contains("otg") || lower.contains("endpoint") -> Category.USB
            lower.contains("safety") || lower.contains("preflight") || lower.contains("bootloader locked") ||
                lower.contains("typed confirm") || lower.contains("qualification") -> Category.SAFETY
            lower.contains("операци") || lower.contains("operation") || lower.contains("wake lock") -> Category.OPERATION
            lower.contains("файл") || lower.contains("sha-256") || lower.contains("checksum") ||
                lower.contains("папк") || lower.contains("report") || lower.contains("zip") -> Category.FILE
            text.startsWith("===") || text.startsWith("[") -> Category.SYSTEM
            else -> Category.UNKNOWN
        }

        val significant = level != Level.INFO ||
            text.startsWith("===") || text.contains("✅") ||
            category in setOf(Category.DATA, Category.SAFETY, Category.INVENTORY)

        return Classification(level, category, significant)
    }

    /** Main file progress cadence. UI progress is intentionally more frequent. */
    fun progressLogStepPercent(debugLogging: Boolean): Int = if (debugLogging) 5 else 10

    fun progressLogIntervalMs(debugLogging: Boolean): Long = if (debugLogging) 5_000L else 10_000L

    fun uiProgressStepPercent(): Int = 1

    fun uiProgressIntervalMs(): Long = 1_000L

    /** Exact duplicate messages inside this window are coalesced in the compact log. */
    fun duplicateWindowMs(): Long = 1_500L
}
