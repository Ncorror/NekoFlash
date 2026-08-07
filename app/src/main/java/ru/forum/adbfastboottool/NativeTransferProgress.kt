package ru.forum.adbfastboottool

import java.util.Locale

/** Pure, confirmed-byte based progress math shared by Native USBFS UI and tests. */
object NativeTransferProgress {
    data class Metrics(
        val confirmedBytes: Long,
        val totalBytes: Long,
        val elapsedMs: Long,
        val percent: Int,
        val averageBytesPerSecond: Double,
        val etaMs: Long?
    )

    fun calculate(confirmedBytes: Long, totalBytes: Long, elapsedMs: Long): Metrics {
        val safeTotal = totalBytes.coerceAtLeast(0L)
        val confirmed = if (safeTotal > 0L) confirmedBytes.coerceIn(0L, safeTotal) else 0L
        val elapsed = elapsedMs.coerceAtLeast(0L)
        val percent = when {
            safeTotal <= 0L -> 0
            confirmed >= safeTotal -> 100
            else -> ((confirmed.toDouble() / safeTotal.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 99)
        }
        val average = if (elapsed > 0L && confirmed > 0L) confirmed * 1000.0 / elapsed.toDouble() else 0.0
        val remaining = (safeTotal - confirmed).coerceAtLeast(0L)
        val eta = if (safeTotal <= 0L) null else if (remaining == 0L) 0L else if (average > 1.0) {
            ((remaining / average) * 1000.0).toLong().coerceAtLeast(0L)
        } else null
        return Metrics(confirmed, safeTotal, elapsed, percent, average, eta)
    }

    fun formatDetail(metrics: Metrics, transportLabel: String): String = buildString {
        append(formatBytes(metrics.confirmedBytes))
        append(" / ")
        append(formatBytes(metrics.totalBytes))
        append("  ·  avg ")
        append(if (metrics.averageBytesPerSecond > 0.0) formatRate(metrics.averageBytesPerSecond) else "N/A")
        metrics.etaMs?.let {
            append("  ·  ETA ")
            append(formatDuration(it))
        }
        append("  ·  ")
        append(transportLabel)
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${bytes.coerceAtLeast(0L)} B" else String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    private fun formatRate(bytesPerSecond: Double): String =
        "${formatBytes(bytesPerSecond.toLong().coerceAtLeast(0L))}/s"

    private fun formatDuration(ms: Long): String {
        if (ms < 0L) return "unknown"
        if (ms < 1000L) return String.format(Locale.US, "%.1fs", ms / 1000.0)
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
