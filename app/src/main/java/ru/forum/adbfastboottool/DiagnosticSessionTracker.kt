package ru.forum.adbfastboottool

import java.util.Locale

/** Thread-safe in-memory summary of one NekoFlash process/logging session. */
class DiagnosticSessionTracker(
    private val sessionId: String,
    private val startedAtMs: Long = System.currentTimeMillis(),
    private val buildId: String = "unknown"
) {
    data class Snapshot(
        val sessionId: String,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val buildId: String,
        val activeTransportSessionId: String?,
        val diagnosticMode: String,
        val readOnlyMutationLock: Boolean,
        val terminationReason: String?,
        val endedAtMs: Long?,
        val infoCount: Long,
        val warningCount: Long,
        val errorCount: Long,
        val compactMessageCount: Long,
        val traceMessageCount: Long,
        val duplicateMessagesSuppressed: Long,
        val operationsStarted: Long,
        val operationsSucceeded: Long,
        val operationsFailed: Long,
        val operationsCancelled: Long,
        val operationsVerificationPending: Long,
        val lastOperation: String?,
        val lastOperationOutcome: String?,
        val lastConnectionMode: String?,
        val lastConnectionInfo: String?,
        val lastWarning: String?,
        val lastError: String?,
        val categoryCounts: Map<DiagnosticLogPolicy.Category, Long>,
        val milestonesMs: Map<String, Long>
    )

    private var updatedAtMs = startedAtMs
    private var activeTransportSessionId: String? = null
    private var diagnosticMode: String = DiagnosticModePolicy.Mode.NORMAL.name
    private var readOnlyMutationLock: Boolean = false
    private var terminationReason: String? = null
    private var endedAtMs: Long? = null
    private var infoCount = 0L
    private var warningCount = 0L
    private var errorCount = 0L
    private var compactMessageCount = 0L
    private var traceMessageCount = 0L
    private var duplicateMessagesSuppressed = 0L
    private var operationsStarted = 0L
    private var operationsSucceeded = 0L
    private var operationsFailed = 0L
    private var operationsCancelled = 0L
    private var operationsVerificationPending = 0L
    private var lastOperation: String? = null
    private var lastOperationOutcome: String? = null
    private var lastConnectionMode: String? = null
    private var lastConnectionInfo: String? = null
    private var lastWarning: String? = null
    private var lastError: String? = null
    private val categoryCounts = linkedMapOf<DiagnosticLogPolicy.Category, Long>()
    private val milestonesMs = linkedMapOf<String, Long>()

    @Synchronized
    fun recordTransportSession(id: String?, mode: String?, info: String?) {
        updatedAtMs = System.currentTimeMillis()
        activeTransportSessionId = id?.take(160)
        if (!id.isNullOrBlank()) {
            terminationReason = null
            endedAtMs = null
        }
        lastConnectionMode = mode?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
        lastConnectionInfo = info?.take(500)
    }

    @Synchronized
    fun recordDiagnosticState(mode: DiagnosticModePolicy.Mode, readOnly: Boolean) {
        updatedAtMs = System.currentTimeMillis()
        diagnosticMode = mode.name
        readOnlyMutationLock = readOnly
    }

    @Synchronized
    fun recordMilestone(name: String, durationMs: Long) {
        updatedAtMs = System.currentTimeMillis()
        milestonesMs[name.take(100)] = durationMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun recordTermination(reason: String) {
        val now = System.currentTimeMillis()
        updatedAtMs = now
        endedAtMs = now
        terminationReason = reason.take(300)
    }

    @Synchronized
    fun recordCompact(message: String, classification: DiagnosticLogPolicy.Classification) {
        updatedAtMs = System.currentTimeMillis()
        compactMessageCount += 1
        categoryCounts[classification.category] = (categoryCounts[classification.category] ?: 0L) + 1L
        when (classification.level) {
            DiagnosticLogPolicy.Level.INFO -> infoCount += 1
            DiagnosticLogPolicy.Level.WARNING -> {
                warningCount += 1
                lastWarning = message.take(500)
            }
            DiagnosticLogPolicy.Level.ERROR -> {
                errorCount += 1
                lastError = message.take(500)
            }
        }
    }

    @Synchronized
    fun recordTrace() {
        updatedAtMs = System.currentTimeMillis()
        traceMessageCount += 1
    }

    @Synchronized
    fun recordDuplicateSuppressed(count: Long = 1L) {
        updatedAtMs = System.currentTimeMillis()
        duplicateMessagesSuppressed += count.coerceAtLeast(0L)
    }

    @Synchronized
    fun recordConnection(mode: String, info: String?) {
        updatedAtMs = System.currentTimeMillis()
        lastConnectionMode = mode.trim().uppercase(Locale.US).takeIf { it.isNotBlank() }
        lastConnectionInfo = info?.take(500)
    }

    @Synchronized
    fun recordOperationStarted(title: String) {
        updatedAtMs = System.currentTimeMillis()
        operationsStarted += 1
        lastOperation = title.take(300)
        lastOperationOutcome = "RUNNING"
    }

    @Synchronized
    fun recordOperationFinished(title: String, outcome: String) {
        updatedAtMs = System.currentTimeMillis()
        lastOperation = title.take(300)
        lastOperationOutcome = outcome
        when (outcome.uppercase(Locale.US)) {
            "SUCCESS" -> operationsSucceeded += 1
            "FAILED" -> operationsFailed += 1
            "CANCELLED" -> operationsCancelled += 1
            "VERIFY_PENDING" -> operationsVerificationPending += 1
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        sessionId = sessionId,
        startedAtMs = startedAtMs,
        updatedAtMs = updatedAtMs,
        buildId = buildId,
        activeTransportSessionId = activeTransportSessionId,
        diagnosticMode = diagnosticMode,
        readOnlyMutationLock = readOnlyMutationLock,
        terminationReason = terminationReason,
        endedAtMs = endedAtMs,
        infoCount = infoCount,
        warningCount = warningCount,
        errorCount = errorCount,
        compactMessageCount = compactMessageCount,
        traceMessageCount = traceMessageCount,
        duplicateMessagesSuppressed = duplicateMessagesSuppressed,
        operationsStarted = operationsStarted,
        operationsSucceeded = operationsSucceeded,
        operationsFailed = operationsFailed,
        operationsCancelled = operationsCancelled,
        operationsVerificationPending = operationsVerificationPending,
        lastOperation = lastOperation,
        lastOperationOutcome = lastOperationOutcome,
        lastConnectionMode = lastConnectionMode,
        lastConnectionInfo = lastConnectionInfo,
        lastWarning = lastWarning,
        lastError = lastError,
        categoryCounts = categoryCounts.toMap(),
        milestonesMs = milestonesMs.toMap()
    )

    companion object {
        fun toJson(snapshot: Snapshot): String {
            val q = DiagnosticJson::quote
            val categories = snapshot.categoryCounts.entries
                .sortedBy { it.key.name }
                .joinToString(separator = ",\n") { (category, count) ->
                    "    ${q(category.name.lowercase(Locale.US))}: $count"
                }
            val milestones = snapshot.milestonesMs.entries
                .sortedBy { it.key }
                .joinToString(separator = ",\n") { (name, value) ->
                    "    ${q(name)}: $value"
                }
            return buildString {
                appendLine("{")
                appendLine("  \"schema\": \"ru.forum.adbfastboottool.diagnostic-session.v2\",")
                appendLine("  \"sessionId\": ${q(snapshot.sessionId)},")
                appendLine("  \"buildId\": ${q(snapshot.buildId)},")
                appendLine("  \"activeTransportSessionId\": ${q(snapshot.activeTransportSessionId)},")
                appendLine("  \"diagnosticMode\": ${q(snapshot.diagnosticMode)},")
                appendLine("  \"readOnlyMutationLock\": ${DiagnosticJson.bool(snapshot.readOnlyMutationLock)},")
                appendLine("  \"terminationReason\": ${q(snapshot.terminationReason)},")
                appendLine("  \"endedAtMs\": ${snapshot.endedAtMs ?: "null"},")
                appendLine("  \"startedAtMs\": ${snapshot.startedAtMs},")
                appendLine("  \"updatedAtMs\": ${snapshot.updatedAtMs},")
                appendLine("  \"messages\": {")
                appendLine("    \"info\": ${snapshot.infoCount},")
                appendLine("    \"warnings\": ${snapshot.warningCount},")
                appendLine("    \"errors\": ${snapshot.errorCount},")
                appendLine("    \"compact\": ${snapshot.compactMessageCount},")
                appendLine("    \"trace\": ${snapshot.traceMessageCount},")
                appendLine("    \"duplicatesSuppressed\": ${snapshot.duplicateMessagesSuppressed}")
                appendLine("  },")
                appendLine("  \"operations\": {")
                appendLine("    \"started\": ${snapshot.operationsStarted},")
                appendLine("    \"succeeded\": ${snapshot.operationsSucceeded},")
                appendLine("    \"failed\": ${snapshot.operationsFailed},")
                appendLine("    \"cancelled\": ${snapshot.operationsCancelled},")
                appendLine("    \"verificationPending\": ${snapshot.operationsVerificationPending},")
                appendLine("    \"lastTitle\": ${q(snapshot.lastOperation)},")
                appendLine("    \"lastOutcome\": ${q(snapshot.lastOperationOutcome)}")
                appendLine("  },")
                appendLine("  \"connection\": {")
                appendLine("    \"mode\": ${q(snapshot.lastConnectionMode)},")
                appendLine("    \"info\": ${q(snapshot.lastConnectionInfo)}")
                appendLine("  },")
                appendLine("  \"lastWarning\": ${q(snapshot.lastWarning)},")
                appendLine("  \"lastError\": ${q(snapshot.lastError)},")
                appendLine("  \"milestonesMs\": {")
                if (milestones.isNotBlank()) appendLine(milestones)
                appendLine("  },")
                appendLine("  \"categories\": {")
                if (categories.isNotBlank()) appendLine(categories)
                appendLine("  }")
                appendLine("}")
            }
        }
    }
}
