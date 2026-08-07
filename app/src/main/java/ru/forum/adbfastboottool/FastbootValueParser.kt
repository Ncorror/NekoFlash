package ru.forum.adbfastboottool

import java.util.Locale

object FastbootValueParser {
    enum class SnapshotState { NONE, SNAPSHOTTED, MERGING, UNKNOWN }

    fun parseBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase(Locale.US)) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }

    fun parseSnapshotState(raw: String?): SnapshotState = when (raw?.trim()?.lowercase(Locale.US)) {
        "none", "cancelled" -> SnapshotState.NONE
        "snapshotted" -> SnapshotState.SNAPSHOTTED
        "merging" -> SnapshotState.MERGING
        else -> SnapshotState.UNKNOWN
    }
}
