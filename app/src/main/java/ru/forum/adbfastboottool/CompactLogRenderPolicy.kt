package ru.forum.adbfastboottool

/** Pure decision helper for the rolling 600-line compact console. */
object CompactLogRenderPolicy {
    data class State(
        val renderedCount: Int = 0,
        val renderedFirstLine: String? = null,
        val renderedLastLine: String? = null
    )
    data class Decision(val reset: Boolean, val startIndex: Int, val nextState: State)

    fun decide(lines: List<String>, state: State): Decision {
        if (lines.isEmpty()) {
            return Decision(reset = state.renderedCount != 0, startIndex = 0, nextState = State())
        }

        val previousAnchorStillPresent = state.renderedCount > 0 &&
            lines.size >= state.renderedCount &&
            lines.firstOrNull() == state.renderedFirstLine &&
            lines.getOrNull(state.renderedCount - 1) == state.renderedLastLine
        val appendOnly = state.renderedCount == 0 || previousAnchorStillPresent
        val reset = !appendOnly
        val start = if (reset) 0 else state.renderedCount.coerceAtMost(lines.size)
        return Decision(
            reset = reset,
            startIndex = start,
            nextState = State(lines.size, lines.firstOrNull(), lines.lastOrNull())
        )
    }
}
