package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Parses the read-only output of `fastboot getvar all` without inventing
 * partitions or slot suffixes. The parser accepts the common INFO/TEXT and
 * `(bootloader)` prefixes used by bootloader-fastboot and fastbootd.
 */
object FastbootGetVarAllParser {

    enum class MetadataField {
        SIZE,
        TYPE,
        LOGICAL,
        HAS_SLOT
    }

    data class DuplicateVariable(
        val name: String,
        val values: List<String>,
        val conflicting: Boolean
    )

    data class Partition(
        val name: String,
        val sizeBytes: Long? = null,
        val type: String? = null,
        val logical: Boolean? = null,
        val hasSlot: Boolean? = null,
        val metadataFields: Set<MetadataField> = emptySet()
    ) {
        /**
         * has-slot:<base> is family metadata and by itself is not proof that an
         * actual flashable partition named <base> exists. Size/type/is-logical
         * are treated as concrete device evidence.
         */
        val hasConcreteEvidence: Boolean
            get() = metadataFields.any {
                it == MetadataField.SIZE || it == MetadataField.TYPE || it == MetadataField.LOGICAL
            }
    }

    data class Snapshot(
        val variables: Map<String, String>,
        val partitions: List<Partition>,
        val complete: Boolean,
        val finalStatus: String,
        val finalMessage: String? = null,
        val ignoredLines: List<String> = emptyList(),
        val duplicateVariables: List<DuplicateVariable> = emptyList()
    ) {
        fun value(name: String): String? = variables[name.trim().lowercase(Locale.US)]
        fun partition(name: String): Partition? =
            partitions.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }

    fun parse(
        lines: Iterable<String>,
        complete: Boolean = true,
        finalStatus: String = "OKAY",
        finalMessage: String? = null
    ): Snapshot {
        val variables = linkedMapOf<String, String>()
        val allValues = linkedMapOf<String, MutableList<String>>()
        val ignored = mutableListOf<String>()

        lines.forEach { rawLine ->
            rawLine.lineSequence().forEach inner@{ raw ->
                val clean = normalizeLine(raw)
                if (clean.isBlank()) return@inner
                val pair = splitVariable(clean)
                if (pair == null) {
                    ignored += clean
                    return@inner
                }
                val (name, value) = pair
                if (name == "all" && value.equals("done!", ignoreCase = true)) return@inner
                allValues.getOrPut(name) { mutableListOf() } += value
                // Last value wins, matching fastboot CLI behaviour, while all
                // duplicates/conflicts remain visible to the inventory audit.
                variables[name] = value
            }
        }

        val metadataByPartition = linkedMapOf<String, MutableSet<MetadataField>>()
        variables.keys.forEach { key ->
            val field = metadataFieldForKey(key) ?: return@forEach
            val partition = key.substringAfter(':').trim().lowercase(Locale.US)
            if (partition.isNotBlank()) {
                metadataByPartition.getOrPut(partition) { linkedSetOf() } += field
            }
        }

        val partitions = metadataByPartition.keys
            .sorted()
            .map { name ->
                Partition(
                    name = name,
                    sizeBytes = parseSizeValue(variables["partition-size:$name"]),
                    type = variables["partition-type:$name"]?.takeIf { it.isNotBlank() },
                    logical = parseBooleanValue(variables["is-logical:$name"]),
                    hasSlot = parseBooleanValue(variables["has-slot:$name"]),
                    metadataFields = metadataByPartition.getValue(name).toSet()
                )
            }

        val duplicates = allValues
            .filterValues { it.size > 1 }
            .map { (name, values) ->
                DuplicateVariable(
                    name = name,
                    values = values.toList(),
                    conflicting = values.map { it.trim().lowercase(Locale.US) }.distinct().size > 1
                )
            }

        return Snapshot(
            variables = variables.toMap(),
            partitions = partitions,
            complete = complete,
            finalStatus = finalStatus,
            finalMessage = finalMessage?.takeIf { it.isNotBlank() },
            ignoredLines = ignored,
            duplicateVariables = duplicates
        )
    }

    private fun normalizeLine(raw: String): String {
        var value = raw.trim().replace("\u0000", "")
        while (true) {
            val next = when {
                value.startsWith("INFO", ignoreCase = true) -> value.drop(4).trim()
                value.startsWith("TEXT", ignoreCase = true) -> value.drop(4).trim()
                value.startsWith("(bootloader)", ignoreCase = true) -> value.drop("(bootloader)".length).trim()
                else -> value
            }
            if (next == value) return value
            value = next
        }
    }

    private fun splitVariable(line: String): Pair<String, String>? {
        val partitionScoped = Regex(
            "^(partition-size|partition-type|is-logical|has-slot|slot-successful|" +
                "slot-unbootable|slot-retry-count):([^:]+):\\s*(.*)$",
            RegexOption.IGNORE_CASE
        ).matchEntire(line)
        if (partitionScoped != null) {
            val family = partitionScoped.groupValues[1].lowercase(Locale.US)
            val target = partitionScoped.groupValues[2].trim().lowercase(Locale.US)
            val value = partitionScoped.groupValues[3].trim()
            if (target.isBlank() || value.isBlank()) return null
            return "$family:$target" to value
        }

        val separator = line.indexOf(':')
        if (separator <= 0 || separator == line.lastIndex) return null
        val name = line.substring(0, separator).trim().lowercase(Locale.US)
        val value = line.substring(separator + 1).trim()
        if (name.isBlank() || value.isBlank()) return null
        return name to value
    }

    private fun metadataFieldForKey(key: String): MetadataField? = when {
        key.startsWith("partition-size:") -> MetadataField.SIZE
        key.startsWith("partition-type:") -> MetadataField.TYPE
        key.startsWith("is-logical:") -> MetadataField.LOGICAL
        key.startsWith("has-slot:") -> MetadataField.HAS_SLOT
        else -> null
    }

    fun parseBooleanValue(raw: String?): Boolean? = when (raw?.trim()?.lowercase(Locale.US)) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }

    fun parseSizeValue(raw: String?): Long? {
        val clean = raw?.trim()?.lowercase(Locale.US) ?: return null
        return when {
            clean.startsWith("0x") -> clean.removePrefix("0x").toLongOrNull(16)
            else -> clean.toLongOrNull()
        }?.takeIf { it >= 0L }
    }
}
