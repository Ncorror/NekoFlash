package ru.forum.adbfastboottool

/**
 * Pure classification for an ADB sideload stream that closes before DONEDONE.
 *
 * This preserves the existing behavior: a close at or above 95% served bytes
 * becomes verification-pending, while an earlier close remains a transport or
 * protocol failure. It does not classify DONEDONE as installation success.
 */
object SideloadCompletionPolicy {
    const val VERIFY_PENDING_THRESHOLD_PERCENT = 95

    enum class CloseClassification {
        VERIFY_PENDING,
        FAILED
    }

    fun servedPercent(servedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        return ((servedBytes.coerceAtLeast(0L) * 100L) / totalBytes)
            .toInt()
            .coerceIn(0, 100)
    }

    fun classifyCloseBeforeDoneDone(servedBytes: Long, totalBytes: Long): CloseClassification =
        if (servedPercent(servedBytes, totalBytes) >= VERIFY_PENDING_THRESHOLD_PERCENT) {
            CloseClassification.VERIFY_PENDING
        } else {
            CloseClassification.FAILED
        }
}
