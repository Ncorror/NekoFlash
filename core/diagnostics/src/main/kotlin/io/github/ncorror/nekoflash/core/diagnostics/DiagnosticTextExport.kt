package io.github.ncorror.nekoflash.core.diagnostics

private val sensitiveFieldFragments = listOf(
    "token",
    "secret",
    "password",
    "private",
    "payload",
    "signature",
    "rawbytes",
    "keymaterial",
)

/**
 * Stable, shareable diagnostics text format.
 *
 * Field names that look like secret/raw protocol material are redacted defensively.
 * The diagnostics contract still requires producers to avoid emitting secrets in the first place.
 */
fun formatDiagnosticEvidence(
    events: List<DiagnosticEvent>,
    generatedAtEpochMillis: Long,
): String = buildString {
    appendLine("NekoFlash diagnostics export v1")
    appendLine("generatedAtEpochMillis=$generatedAtEpochMillis")
    appendLine("eventCount=${events.size}")
    appendLine()

    events.forEachIndexed { index, event ->
        append(index + 1)
        append('\t')
        append("monotonicNanos=")
        append(event.monotonicNanos)
        append('\t')
        append("category=")
        append(escapeEvidenceValue(event.category))
        append('\t')
        append("code=")
        append(escapeEvidenceValue(event.code))

        event.targetId?.let {
            append('\t')
            append("targetId=")
            append(escapeEvidenceValue(it.value))
        }
        event.generation?.let {
            append('\t')
            append("generation=")
            append(it.value)
        }

        event.fields.toSortedMap().forEach { (key, value) ->
            append('\t')
            append(escapeEvidenceValue(key))
            append('=')
            append(
                if (isSensitiveEvidenceField(key)) {
                    "<redacted>"
                } else {
                    escapeEvidenceValue(value)
                },
            )
        }
        appendLine()
    }
}

private fun isSensitiveEvidenceField(key: String): Boolean {
    val normalized = key.lowercase().filter { it.isLetterOrDigit() }
    return sensitiveFieldFragments.any(normalized::contains)
}

private fun escapeEvidenceValue(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\t", "\\t")
    .replace("\r", "\\r")
    .replace("\n", "\\n")
