package ru.forum.adbfastboottool

import java.util.Locale

/** Pure formatters used by diagnostic ZIPs and host-side tests. */
object DiagnosticReportFormatter {
    fun sessionSummaryText(snapshot: DiagnosticSessionTracker.Snapshot): String = buildString {
        appendLine("NekoFlash diagnostic session summary")
        appendLine("Session ID: ${snapshot.sessionId}")
        appendLine("Build ID: ${snapshot.buildId}")
        appendLine("Transport session: ${snapshot.activeTransportSessionId ?: "none"}")
        appendLine("Diagnostic mode: ${snapshot.diagnosticMode}")
        appendLine("Read-only mutation lock: ${snapshot.readOnlyMutationLock}")
        appendLine("Started: ${snapshot.startedAtMs}")
        appendLine("Ended: ${snapshot.endedAtMs ?: "not ended"}")
        appendLine("Termination: ${snapshot.terminationReason ?: "none"}")
        appendLine("Updated: ${snapshot.updatedAtMs}")
        appendLine("Messages: info=${snapshot.infoCount}, warnings=${snapshot.warningCount}, errors=${snapshot.errorCount}")
        appendLine("Streams: compact=${snapshot.compactMessageCount}, trace=${snapshot.traceMessageCount}, duplicates-suppressed=${snapshot.duplicateMessagesSuppressed}")
        appendLine(
            "Operations: started=${snapshot.operationsStarted}, success=${snapshot.operationsSucceeded}, " +
                "failed=${snapshot.operationsFailed}, cancelled=${snapshot.operationsCancelled}, " +
                "verify-pending=${snapshot.operationsVerificationPending}"
        )
        appendLine("Last operation: ${snapshot.lastOperation ?: "none"} / ${snapshot.lastOperationOutcome ?: "none"}")
        appendLine("Connection: ${snapshot.lastConnectionMode ?: "none"} / ${snapshot.lastConnectionInfo ?: "none"}")
        appendLine("Last warning: ${snapshot.lastWarning ?: "none"}")
        appendLine("Last error: ${snapshot.lastError ?: "none"}")
        appendLine("Milestones:")
        if (snapshot.milestonesMs.isEmpty()) appendLine("- none")
        snapshot.milestonesMs.entries.sortedBy { it.key }.forEach { (name, duration) ->
            appendLine("- $name: ${duration} ms")
        }
        appendLine("Categories:")
        snapshot.categoryCounts.entries.sortedBy { it.key.name }.forEach { (category, count) ->
            appendLine("- ${category.name.lowercase(Locale.US)}: $count")
        }
    }

    fun partitionInventoryText(snapshot: FastbootPartitionInventory.Snapshot): String = buildString {
        appendLine("NekoFlash partition inventory")
        appendLine("Product: ${snapshot.product ?: "unknown"}")
        appendLine("Topology: ${snapshot.topology.name}")
        appendLine("Current slot: ${snapshot.currentSlot ?: "none"}")
        appendLine("Complete: ${snapshot.complete}")
        appendLine("Final status: ${snapshot.finalStatus}${snapshot.finalMessage?.let { " ($it)" }.orEmpty()}")
        appendLine("Entries: ${snapshot.entries.size}")
        appendLine("Point queries: ${snapshot.pointQueryCount}; unresolved=${snapshot.unresolvedPointQueryCount}")
        appendLine("Duplicate metadata: ${snapshot.duplicateMetadataCount}")
        appendLine()
        snapshot.entries.forEach { entry ->
            appendLine(
                "${entry.name} | base=${entry.baseName} | slot=${entry.slotBinding.name} | " +
                    "size=${entry.sizeBytes ?: "unknown"} | type=${entry.type ?: "unknown"} | " +
                    "storage=${entry.storage.name} | logical=${entry.logical ?: "unknown"} | " +
                    "risk=${entry.risk.name} | evidence=${entry.evidenceSources.joinToString(",") { it.name }}"
            )
            if (entry.missingFields.isNotEmpty()) {
                appendLine("  missing=${entry.missingFields.joinToString(",") { it.name }}")
            }
            entry.warnings.forEach { warning -> appendLine("  ${warning.severity.name}: ${warning.code}: ${warning.message}") }
        }
        if (snapshot.warnings.isNotEmpty()) {
            appendLine()
            appendLine("Snapshot warnings:")
            snapshot.warnings.forEach { warning ->
                appendLine("- ${warning.severity.name}: ${warning.code}: ${warning.message}")
            }
        }
    }

    fun partitionInventoryJson(snapshot: FastbootPartitionInventory.Snapshot): String {
        val q = DiagnosticJson::quote

        fun warningJson(warning: FastbootPartitionInventory.Warning, indent: String): String = buildString {
            appendLine("$indent{")
            appendLine("$indent  \"code\": ${q(warning.code)},")
            appendLine("$indent  \"severity\": ${q(warning.severity.name)},")
            appendLine("$indent  \"partitionName\": ${q(warning.partitionName)},")
            appendLine("$indent  \"message\": ${q(warning.message)}")
            append("$indent}")
        }

        fun warningsJson(warnings: List<FastbootPartitionInventory.Warning>, indent: String): String {
            if (warnings.isEmpty()) return "[]"
            return warnings.joinToString(prefix = "[\n", postfix = "\n${indent.dropLast(2)}]", separator = ",\n") {
                warningJson(it, indent)
            }
        }

        val entries = snapshot.entries.joinToString(separator = ",\n") { entry ->
            buildString {
                appendLine("    {")
                appendLine("      \"name\": ${q(entry.name)},")
                appendLine("      \"baseName\": ${q(entry.baseName)},")
                appendLine("      \"slotBinding\": ${q(entry.slotBinding.name)},")
                appendLine("      \"sizeBytes\": ${DiagnosticJson.number(entry.sizeBytes)},")
                appendLine("      \"type\": ${q(entry.type)},")
                appendLine("      \"logical\": ${entry.logical?.let { DiagnosticJson.bool(it) } ?: "null"},")
                appendLine("      \"storage\": ${q(entry.storage.name)},")
                appendLine("      \"hasSlot\": ${entry.hasSlot?.let { DiagnosticJson.bool(it) } ?: "null"},")
                appendLine("      \"risk\": ${q(entry.risk.name)},")
                appendLine("      \"evidenceSources\": ${DiagnosticJson.stringArray(entry.evidenceSources.map { it.name }, "        ")},")
                appendLine("      \"missingFields\": ${DiagnosticJson.stringArray(entry.missingFields.map { it.name }, "        ")},")
                appendLine("      \"warnings\": ${warningsJson(entry.warnings, "        ")}")
                append("    }")
            }
        }

        val variables = snapshot.variables.entries.sortedBy { it.key }.joinToString(separator = ",\n") { (name, value) ->
            "    ${q(name)}: ${q(value)}"
        }
        val slotFamilies = snapshot.slotFamilies.entries.sortedBy { it.key }.joinToString(separator = ",\n") { (name, value) ->
            "    ${q(name)}: ${value?.let { DiagnosticJson.bool(it) } ?: "null"}"
        }

        return buildString {
            appendLine("{")
            appendLine("  \"schema\": \"ru.forum.adbfastboottool.partition-inventory.v1\",")
            appendLine("  \"product\": ${q(snapshot.product)},")
            appendLine("  \"topology\": ${q(snapshot.topology.name)},")
            appendLine("  \"currentSlot\": ${q(snapshot.currentSlot)},")
            appendLine("  \"complete\": ${DiagnosticJson.bool(snapshot.complete)},")
            appendLine("  \"finalStatus\": ${q(snapshot.finalStatus)},")
            appendLine("  \"finalMessage\": ${q(snapshot.finalMessage)},")
            appendLine("  \"duplicateMetadataCount\": ${snapshot.duplicateMetadataCount},")
            appendLine("  \"pointQueryCount\": ${snapshot.pointQueryCount},")
            appendLine("  \"unresolvedPointQueryCount\": ${snapshot.unresolvedPointQueryCount},")
            appendLine("  \"variables\": {")
            if (variables.isNotBlank()) appendLine(variables)
            appendLine("  },")
            appendLine("  \"slotFamilies\": {")
            if (slotFamilies.isNotBlank()) appendLine(slotFamilies)
            appendLine("  },")
            appendLine("  \"warnings\": ${warningsJson(snapshot.warnings, "    ")},")
            appendLine("  \"entries\": [")
            if (entries.isNotBlank()) appendLine(entries)
            appendLine("  ]")
            appendLine("}")
        }
    }

}
