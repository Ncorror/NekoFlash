package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Safe, read-only view of partition metadata reported by the target.
 *
 * Important rules:
 * - only partitions backed by concrete bootloader evidence are exposed;
 * - `_a` / `_b` names are never synthesized into the final inventory;
 * - point queries may confirm a concrete name, but cannot authorize a write;
 * - POCO X3 Pro / vayu remains legacy A-only regardless of missing or noisy
 *   slot variables;
 * - risk classification is informational and never authorizes a mutation.
 */
object FastbootPartitionInventory {

    enum class SlotTopology {
        LEGACY_A_ONLY,
        A_B,
        UNKNOWN
    }

    enum class RiskTier {
        NORMAL,
        ADVANCED,
        CRITICAL
    }

    enum class StorageKind {
        PHYSICAL,
        LOGICAL,
        UNKNOWN
    }

    enum class SlotBinding {
        SLOT_A,
        SLOT_B,
        UNSLOTTED,
        SLOT_FAMILY_BASE,
        UNKNOWN
    }

    enum class EvidenceSource {
        GETVAR_ALL,
        POINT_QUERY
    }

    enum class WarningSeverity {
        INFO,
        WARNING,
        CRITICAL
    }

    data class Warning(
        val code: String,
        val message: String,
        val severity: WarningSeverity = WarningSeverity.WARNING,
        val partitionName: String? = null
    )

    data class PointProbe(
        val name: String,
        val sizeBytes: Long? = null,
        val type: String? = null,
        val logical: Boolean? = null,
        val hasSlot: Boolean? = null,
        val attemptedFields: Set<FastbootGetVarAllParser.MetadataField> = emptySet(),
        val resolvedFields: Set<FastbootGetVarAllParser.MetadataField> = emptySet()
    ) {
        val hasConcreteEvidence: Boolean
            get() = resolvedFields.any {
                it == FastbootGetVarAllParser.MetadataField.SIZE ||
                    it == FastbootGetVarAllParser.MetadataField.TYPE ||
                    it == FastbootGetVarAllParser.MetadataField.LOGICAL
            }
    }

    data class Entry(
        val name: String,
        val baseName: String,
        val slotBinding: SlotBinding,
        val sizeBytes: Long?,
        val type: String?,
        val logical: Boolean?,
        val storage: StorageKind,
        val hasSlot: Boolean?,
        val risk: RiskTier,
        val evidenceSources: Set<EvidenceSource>,
        val missingFields: Set<FastbootGetVarAllParser.MetadataField>,
        val warnings: List<Warning>
    )

    data class Snapshot(
        val product: String?,
        val topology: SlotTopology,
        val currentSlot: String?,
        val entries: List<Entry>,
        val slotFamilies: Map<String, Boolean?>,
        val variables: Map<String, String>,
        val complete: Boolean,
        val finalStatus: String,
        val finalMessage: String?,
        val warnings: List<Warning>,
        val duplicateMetadataCount: Int,
        val pointQueryCount: Int,
        val unresolvedPointQueryCount: Int
    ) {
        fun partition(name: String): Entry? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

        fun filtered(
            query: String = "",
            risk: RiskTier? = null,
            storage: StorageKind? = null
        ): List<Entry> {
            val normalizedQuery = query.trim().lowercase(Locale.US)
            return entries.filter { entry ->
                val queryMatches = normalizedQuery.isBlank() ||
                    entry.name.contains(normalizedQuery) ||
                    entry.baseName.contains(normalizedQuery) ||
                    entry.type?.lowercase(Locale.US)?.contains(normalizedQuery) == true
                queryMatches && (risk == null || entry.risk == risk) &&
                    (storage == null || entry.storage == storage)
            }
        }
    }

    private data class MutableEntry(
        val name: String,
        var sizeBytes: Long? = null,
        var type: String? = null,
        var logical: Boolean? = null,
        var hasSlot: Boolean? = null,
        val evidenceSources: MutableSet<EvidenceSource> = linkedSetOf(),
        val warnings: MutableList<Warning> = mutableListOf()
    )

    private val legacyAOnlyProducts = setOf("vayu")

    internal val normalBaseNames = setOf(
        "boot",
        "init_boot",
        "vendor_boot",
        "vendor_kernel_boot",
        "recovery",
        "dtbo"
    )

    private val legacyTopologyEvidenceBases = setOf(
        "boot",
        "init_boot",
        "vendor_boot",
        "recovery"
    )

    internal val advancedBaseNames = setOf(
        "vbmeta",
        "vbmeta_system",
        "vbmeta_vendor",
        "logo",
        "splash",
        "modem",
        "radio"
    )

    fun from(
        source: FastbootGetVarAllParser.Snapshot,
        fallbackProduct: String? = null,
        supplementalVariables: Map<String, String> = emptyMap(),
        pointProbes: List<PointProbe> = emptyList(),
        collectionWarnings: List<Warning> = emptyList()
    ): Snapshot {
        val warnings = collectionWarnings.toMutableList()
        val variables = linkedMapOf<String, String>()
        source.variables.forEach { (key, value) ->
            variables[key.trim().lowercase(Locale.US)] = value.trim()
        }
        supplementalVariables.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim().lowercase(Locale.US)
            val value = rawValue.trim()
            if (key.isBlank() || value.isBlank()) return@forEach
            val existing = variables[key]
            if (existing == null) {
                variables[key] = value
            } else if (!existing.equals(value, ignoreCase = true)) {
                warnings += Warning(
                    code = "VARIABLE_CONFLICT",
                    message = "$key отличается между getvar:all ($existing) и точечной диагностикой ($value). " +
                        "Для отображения сохранено значение getvar:all.",
                    severity = WarningSeverity.WARNING
                )
            }
        }

        val product = variables["product"]
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: fallbackProduct?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
        val currentSlot = variables["current-slot"]
            ?.trim()
            ?.removePrefix("_")
            ?.lowercase(Locale.US)
            ?.takeIf { it == "a" || it == "b" }

        val slotFamilies = linkedMapOf<String, Boolean?>()
        val mutableEntries = linkedMapOf<String, MutableEntry>()

        fun normalizeName(raw: String): String = raw.trim().lowercase(Locale.US)

        fun mergeField(
            entry: MutableEntry,
            fieldName: String,
            existing: Any?,
            incoming: Any?,
            sourceName: String,
            applyIncoming: () -> Unit
        ) {
            if (incoming == null) return
            if (existing == null) {
                applyIncoming()
                return
            }
            if (existing != incoming) {
                entry.warnings += Warning(
                    code = "PARTITION_METADATA_CONFLICT",
                    message = "Поле $fieldName раздела ${entry.name} отличается в источнике $sourceName: " +
                        "$existing против $incoming. Использовано более позднее точечное значение.",
                    severity = WarningSeverity.WARNING,
                    partitionName = entry.name
                )
                applyIncoming()
            }
        }

        source.partitions.forEach { partition ->
            val name = normalizeName(partition.name)
            if (name.isBlank()) return@forEach
            if (FastbootGetVarAllParser.MetadataField.HAS_SLOT in partition.metadataFields) {
                slotFamilies[name] = partition.hasSlot
            }
            if (!partition.hasConcreteEvidence) return@forEach
            val entry = mutableEntries.getOrPut(name) { MutableEntry(name) }
            entry.sizeBytes = partition.sizeBytes
            entry.type = partition.type?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
            entry.logical = partition.logical
            entry.hasSlot = partition.hasSlot
            entry.evidenceSources += EvidenceSource.GETVAR_ALL
        }

        pointProbes.forEach { probe ->
            val name = normalizeName(probe.name)
            if (name.isBlank()) return@forEach
            if (FastbootGetVarAllParser.MetadataField.HAS_SLOT in probe.resolvedFields) {
                slotFamilies[name] = probe.hasSlot
            }
            if (!probe.hasConcreteEvidence) return@forEach
            val entry = mutableEntries.getOrPut(name) { MutableEntry(name) }
            mergeField(entry, "size", entry.sizeBytes, probe.sizeBytes, "point-query") {
                entry.sizeBytes = probe.sizeBytes
            }
            mergeField(entry, "type", entry.type, probe.type?.trim()?.lowercase(Locale.US), "point-query") {
                entry.type = probe.type?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
            }
            mergeField(entry, "logical", entry.logical, probe.logical, "point-query") {
                entry.logical = probe.logical
            }
            mergeField(entry, "has-slot", entry.hasSlot, probe.hasSlot, "point-query") {
                entry.hasSlot = probe.hasSlot
            }
            entry.evidenceSources += EvidenceSource.POINT_QUERY
        }

        // has-slot:<base> is family metadata. Propagate it only to already
        // confirmed concrete names; it never creates a partition by itself.
        mutableEntries.values.forEach { entry ->
            if (entry.hasSlot == null) {
                slotFamilies[baseName(entry.name)]?.let { entry.hasSlot = it }
            }
        }

        val topology = detectTopology(product, variables, mutableEntries.values.toList(), slotFamilies)

        if (!source.complete) {
            warnings += Warning(
                code = "PARTIAL_GETVAR_ALL",
                message = "getvar:all завершён частично; список может быть неполным.",
                severity = WarningSeverity.WARNING
            )
        }
        if (!source.finalStatus.equals("OKAY", ignoreCase = true)) {
            warnings += Warning(
                code = "GETVAR_ALL_FINAL_STATUS",
                message = "Финальный статус getvar:all: ${source.finalStatus}" +
                    source.finalMessage?.let { " ($it)" }.orEmpty(),
                severity = WarningSeverity.WARNING
            )
        }
        source.duplicateVariables.forEach { duplicate ->
            warnings += Warning(
                code = if (duplicate.conflicting) "CONFLICTING_DUPLICATE" else "DUPLICATE_METADATA",
                message = if (duplicate.conflicting) {
                    "Переменная ${duplicate.name} повторилась с разными значениями: ${duplicate.values.joinToString()}"
                } else {
                    "Переменная ${duplicate.name} повторилась ${duplicate.values.size} раза."
                },
                severity = if (duplicate.conflicting) WarningSeverity.WARNING else WarningSeverity.INFO
            )
        }

        val hasSlottedConcreteNames = mutableEntries.keys.any { hasSlotSuffix(it) }
        val hasPositiveFamily = slotFamilies.values.any { it == true }
        if (topology == SlotTopology.LEGACY_A_ONLY && (hasSlottedConcreteNames || hasPositiveFamily)) {
            warnings += Warning(
                code = "LEGACY_SLOT_CONTRADICTION",
                message = "Устройство определено как legacy A-only, но загрузчик сообщил противоречивые A/B-данные. " +
                    "Суффиксы автоматически не создаются и не выбираются.",
                severity = WarningSeverity.CRITICAL
            )
        }
        if (topology == SlotTopology.A_B && currentSlot == null) {
            warnings += Warning(
                code = "CURRENT_SLOT_UNKNOWN",
                message = "A/B-разметка подтверждена, но текущий слот не определён.",
                severity = WarningSeverity.WARNING
            )
        }
        if (topology == SlotTopology.UNKNOWN) {
            warnings += Warning(
                code = "SLOT_TOPOLOGY_UNKNOWN",
                message = "Топология слотов не подтверждена. Инвентаризация остаётся только справочной.",
                severity = WarningSeverity.WARNING
            )
        }
        if (mutableEntries.isEmpty()) {
            warnings += Warning(
                code = "NO_CONCRETE_PARTITIONS",
                message = "Загрузчик не подтвердил ни одного раздела через size/type/is-logical.",
                severity = WarningSeverity.WARNING
            )
        }

        val entries = mutableEntries.values.map { mutable ->
            val base = baseName(mutable.name)
            val storage = when (mutable.logical) {
                true -> StorageKind.LOGICAL
                false -> StorageKind.PHYSICAL
                null -> StorageKind.UNKNOWN
            }
            val missing = linkedSetOf<FastbootGetVarAllParser.MetadataField>()
            if (mutable.sizeBytes == null) missing += FastbootGetVarAllParser.MetadataField.SIZE
            if (mutable.type.isNullOrBlank()) missing += FastbootGetVarAllParser.MetadataField.TYPE
            if (mutable.logical == null) missing += FastbootGetVarAllParser.MetadataField.LOGICAL

            val entryWarnings = mutable.warnings.toMutableList()
            if (missing.isNotEmpty()) {
                entryWarnings += Warning(
                    code = "PARTITION_METADATA_INCOMPLETE",
                    message = "Не заполнены поля: ${missing.joinToString { it.name.lowercase(Locale.US) }}.",
                    severity = WarningSeverity.INFO,
                    partitionName = mutable.name
                )
            }

            Entry(
                name = mutable.name,
                baseName = base,
                slotBinding = slotBinding(mutable.name, mutable.hasSlot, topology),
                sizeBytes = mutable.sizeBytes,
                type = mutable.type,
                logical = mutable.logical,
                storage = storage,
                hasSlot = mutable.hasSlot,
                risk = riskTier(mutable.name),
                evidenceSources = mutable.evidenceSources.toSet(),
                missingFields = missing,
                warnings = entryWarnings
            )
        }.sortedBy { it.name }

        val allWarnings = (warnings + entries.flatMap { it.warnings })
            .distinctBy { listOf(it.code, it.partitionName, it.message) }

        return Snapshot(
            product = product,
            topology = topology,
            currentSlot = currentSlot,
            entries = entries,
            slotFamilies = slotFamilies.toSortedMap(),
            variables = variables.toMap(),
            complete = source.complete && source.finalStatus.equals("OKAY", ignoreCase = true),
            finalStatus = source.finalStatus,
            finalMessage = source.finalMessage,
            warnings = allWarnings,
            duplicateMetadataCount = source.duplicateVariables.size,
            pointQueryCount = pointProbes.sumOf { it.attemptedFields.size },
            unresolvedPointQueryCount = pointProbes.sumOf { it.attemptedFields.minus(it.resolvedFields).size }
        )
    }

    fun riskTier(partitionName: String): RiskTier {
        val base = baseName(partitionName)
        return when {
            base in normalBaseNames -> RiskTier.NORMAL
            base in advancedBaseNames || base.startsWith("vbmeta_") -> RiskTier.ADVANCED
            else -> RiskTier.CRITICAL
        }
    }

    fun baseName(partitionName: String): String {
        val normalized = partitionName.trim().lowercase(Locale.US)
        return when {
            normalized.endsWith("_a") || normalized.endsWith("_b") -> normalized.dropLast(2)
            else -> normalized
        }
    }

    private fun hasSlotSuffix(name: String): Boolean =
        name.endsWith("_a") || name.endsWith("_b")

    private fun slotBinding(
        name: String,
        hasSlot: Boolean?,
        topology: SlotTopology
    ): SlotBinding = when {
        topology == SlotTopology.LEGACY_A_ONLY -> SlotBinding.UNSLOTTED
        name.endsWith("_a") -> SlotBinding.SLOT_A
        name.endsWith("_b") -> SlotBinding.SLOT_B
        hasSlot == false -> SlotBinding.UNSLOTTED
        hasSlot == true -> SlotBinding.SLOT_FAMILY_BASE
        else -> SlotBinding.UNKNOWN
    }

    private fun detectTopology(
        product: String?,
        variables: Map<String, String>,
        entries: List<MutableEntry>,
        slotFamilies: Map<String, Boolean?>
    ): SlotTopology {
        if (product in legacyAOnlyProducts) return SlotTopology.LEGACY_A_ONLY

        val slotCount = variables["slot-count"]?.trim()?.toIntOrNull()
        val currentSlot = variables["current-slot"]
            ?.trim()
            ?.removePrefix("_")
            ?.lowercase(Locale.US)
        val hasConcreteSlottedName = entries.any { hasSlotSuffix(it.name) }
        val hasPositiveSlotEvidence = slotFamilies.values.any { it == true } || entries.any { it.hasSlot == true }

        if ((slotCount ?: 0) >= 2 || currentSlot == "a" || currentSlot == "b" ||
            hasConcreteSlottedName || hasPositiveSlotEvidence
        ) {
            return SlotTopology.A_B
        }

        if (slotCount == 0 || slotCount == 1) return SlotTopology.LEGACY_A_ONLY

        val explicitNoSlotFamilies = slotFamilies.count { (name, value) ->
            value == false && baseName(name) in legacyTopologyEvidenceBases
        }
        if (explicitNoSlotFamilies >= 2 && !hasPositiveSlotEvidence) {
            return SlotTopology.LEGACY_A_ONLY
        }

        // Missing slot variables alone are not sufficient. Some bootloaders
        // expose a partial getvar:all list, so absence remains UNKNOWN.
        return SlotTopology.UNKNOWN
    }
}
