package ru.forum.adbfastboottool

/**
 * Pure policy for the unified Console renderer.
 *
 * The ViewModel currently publishes a rolling snapshot, so a new line may also
 * evict one old line. [decide] detects that overlap and expresses the update as
 * remove-from-start + append-tail instead of rebuilding every visible row.
 */
object CompactLogRenderPolicy {
    const val MAX_VISIBLE_LINES = 3_000
    const val RENDER_DEBOUNCE_MS = 75L

    enum class Tone {
        ERROR,
        HINT,
        SUCCESS,
        COMMAND,
        WARNING,
        SYSTEM,
        INFO,
    }

    data class FormattedLine(
        val text: String,
        val tone: Tone,
    )

    data class State(
        val renderedLines: List<String> = emptyList(),
    ) {
        val renderedCount: Int get() = renderedLines.size
        val renderedFirstLine: String? get() = renderedLines.firstOrNull()
        val renderedLastLine: String? get() = renderedLines.lastOrNull()
    }

    data class Decision(
        val reset: Boolean,
        val removeCount: Int,
        val startIndex: Int,
        val nextState: State,
    )

    fun boundedSnapshot(lines: List<String>): List<String> =
        if (lines.size <= MAX_VISIBLE_LINES) lines.toList() else lines.takeLast(MAX_VISIBLE_LINES)

    fun decide(lines: List<String>, state: State): Decision {
        val bounded = boundedSnapshot(lines)
        val previous = state.renderedLines

        if (bounded.isEmpty()) {
            return Decision(
                reset = previous.isNotEmpty(),
                removeCount = previous.size,
                startIndex = 0,
                nextState = State(),
            )
        }

        if (previous.isEmpty()) {
            return Decision(
                reset = false,
                removeCount = 0,
                startIndex = 0,
                nextState = State(bounded),
            )
        }

        val overlap = longestSuffixPrefixOverlap(previous, bounded)
        val reset = overlap == 0
        return Decision(
            reset = reset,
            removeCount = if (reset) previous.size else previous.size - overlap,
            startIndex = if (reset) 0 else overlap,
            nextState = State(bounded),
        )
    }

    fun format(line: String): FormattedLine {
        val (tone, emoji) = when {
            line.contains("ОШИБКА") || line.contains("БЛОКИРОВКА") || line.contains("❌") ->
                Tone.ERROR to "🙀 "

            line.startsWith("💡") -> Tone.HINT to ""
            line.contains("✅") || line.contains("===") || line.contains("ЗАВЕРШЕНА") ->
                Tone.SUCCESS to "✨ "

            line.startsWith(">") || line.startsWith("->") || line.startsWith("<-") ->
                Tone.COMMAND to "😸 "

            line.startsWith("⏳") || line.startsWith("⚠") -> Tone.WARNING to "💤 "
            line.startsWith("[") -> Tone.SYSTEM to "🐾 "
            else -> Tone.INFO to "🐾 "
        }

        // Preserve explicit emoji/markers already supplied by the transport.
        val prefix = if (emoji.isNotEmpty() && line.firstOrNull()?.isLetterOrDigit() != false) {
            emoji
        } else {
            ""
        }
        return FormattedLine(prefix + line, tone)
    }

    /**
     * Returns the longest suffix of [previous] equal to a prefix of [current].
     * KMP keeps this O(previous + current), including duplicate log lines.
     */
    private fun longestSuffixPrefixOverlap(
        previous: List<String>,
        current: List<String>,
    ): Int {
        if (previous.isEmpty() || current.isEmpty()) return 0

        val prefix = IntArray(current.size)
        for (index in 1 until current.size) {
            var matched = prefix[index - 1]
            while (matched > 0 && current[index] != current[matched]) {
                matched = prefix[matched - 1]
            }
            if (current[index] == current[matched]) matched++
            prefix[index] = matched
        }

        var matched = 0
        previous.forEach { line ->
            while (matched > 0 && (matched == current.size || line != current[matched])) {
                matched = prefix[matched - 1]
            }
            if (matched < current.size && line == current[matched]) matched++
        }
        return matched
    }
}
