package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Slice B: builds confirmation candidates from read-only Fastboot evidence.
 *
 * The builder never selects a target, never emits a mutation command and never
 * turns a filename hint into authorization. Every returned candidate is backed
 * by a concrete inventory entry and a slot resolution that matches that exact
 * entry. Unknown topology, unstable sessions and synthetic suffixes fail closed.
 */
object QuickFlashTopologyCandidateBuilder {

    enum class Status {
        READY,
        NEEDS_POINT_QUERY,
        BLOCKED
    }

    enum class Error {
        SESSION_BROKEN,
        IMAGE_ARCHIVE_REQUIRES_SIDELOAD,
        SLOT_TOPOLOGY_UNKNOWN,
        NO_CONFIRMED_CANDIDATES,
        MANUAL_PARTITION_REQUIRES_EXPERT_MODE,
        INVALID_MANUAL_PARTITION,
        MANUAL_PARTITION_DUPLICATES_DEFINED_TARGET,
        MANUAL_PARTITION_FORBIDDEN,
        MANUAL_PARTITION_NOT_CONFIRMED
    }

    data class Request(
        val source: FastbootGetVarAllParser.Snapshot,
        val imageDisplayName: String,
        val fallbackProduct: String? = null,
        val supplementalVariables: Map<String, String> = emptyMap(),
        val pointProbes: List<FastbootPartitionInventory.PointProbe> = emptyList(),
        val collectionWarnings: List<FastbootPartitionInventory.Warning> = emptyList(),
        val expertModeEnabled: Boolean = false,
        val manualPartitionName: String? = null,
        val maxPointQueries: Int = 24,
        val sessionBroken: Boolean = false
    )

    /** Android/UI adapter input after the existing read-only inventory refresh. */
    data class InventoryRequest(
        val inventory: FastbootPartitionInventory.Snapshot,
        val imageDisplayName: String,
        val expertModeEnabled: Boolean = false,
        val manualPartitionName: String? = null,
        val maxPointQueries: Int = 0,
        val sessionBroken: Boolean = false
    )

    data class Result(
        val status: Status,
        val inventory: FastbootPartitionInventory.Snapshot,
        val candidates: List<QuickFlashCandidate>,
        val imageSuggestion: PartitionNameResolver.Suggestion,
        val suggestedTargets: List<QuickFlashTarget>,
        val pointQueryPlan: FastbootPartitionProbePlanner.Plan,
        val errors: List<Error>,
        val notes: List<String>
    ) {
        val canChooseTarget: Boolean
            get() = status == Status.READY && candidates.isNotEmpty() && errors.none {
                it == Error.SESSION_BROKEN ||
                    it == Error.IMAGE_ARCHIVE_REQUIRES_SIDELOAD ||
                    it == Error.SLOT_TOPOLOGY_UNKNOWN
            }
    }

    /**
     * Reuses an already collected read-only inventory without issuing Android or
     * Fastboot calls. GETVAR variables are reconstructed as parser evidence and
     * point-query entries remain explicit point probes.
     */
    fun buildFromInventory(request: InventoryRequest): Result {
        val source = FastbootGetVarAllParser.parse(
            lines = request.inventory.variables.entries.map { (name, value) -> "$name:$value" },
            complete = request.inventory.complete,
            finalStatus = request.inventory.finalStatus,
            finalMessage = request.inventory.finalMessage
        )
        val pointProbes = request.inventory.entries
            .filter { FastbootPartitionInventory.EvidenceSource.POINT_QUERY in it.evidenceSources }
            .map { entry ->
                val resolved = linkedSetOf<FastbootGetVarAllParser.MetadataField>()
                if (entry.sizeBytes != null) resolved += FastbootGetVarAllParser.MetadataField.SIZE
                if (entry.type != null) resolved += FastbootGetVarAllParser.MetadataField.TYPE
                if (entry.logical != null) resolved += FastbootGetVarAllParser.MetadataField.LOGICAL
                if (entry.hasSlot != null) resolved += FastbootGetVarAllParser.MetadataField.HAS_SLOT
                FastbootPartitionInventory.PointProbe(
                    name = entry.name,
                    sizeBytes = entry.sizeBytes,
                    type = entry.type,
                    logical = entry.logical,
                    hasSlot = entry.hasSlot,
                    attemptedFields = resolved,
                    resolvedFields = resolved
                )
            }
        return build(
            Request(
                source = source,
                imageDisplayName = request.imageDisplayName,
                fallbackProduct = request.inventory.product,
                pointProbes = pointProbes,
                collectionWarnings = request.inventory.warnings,
                expertModeEnabled = request.expertModeEnabled,
                manualPartitionName = request.manualPartitionName,
                maxPointQueries = request.maxPointQueries,
                sessionBroken = request.sessionBroken
            )
        )
    }

    fun build(request: Request): Result {
        require(request.maxPointQueries >= 0)

        val inventory = FastbootPartitionInventory.from(
            source = request.source,
            fallbackProduct = request.fallbackProduct,
            supplementalVariables = request.supplementalVariables,
            pointProbes = request.pointProbes,
            collectionWarnings = request.collectionWarnings
        )
        val imageSuggestion = PartitionNameResolver.resolve(request.imageDisplayName)
        val errors = linkedSetOf<Error>()
        val notes = mutableListOf<String>()

        if (request.sessionBroken) {
            errors += Error.SESSION_BROKEN
        }
        if (imageSuggestion.kind == PartitionNameResolver.Kind.ARCHIVE) {
            errors += Error.IMAGE_ARCHIVE_REQUIRES_SIDELOAD
        }
        if (inventory.topology == FastbootPartitionInventory.SlotTopology.UNKNOWN) {
            errors += Error.SLOT_TOPOLOGY_UNKNOWN
        }

        val visibleTargets = buildList {
            addAll(QuickFlashTarget.primaryTargets)
            if (request.expertModeEnabled) {
                addAll(QuickFlashTarget.expertTargets.filter { it != QuickFlashTarget.MANUAL })
            }
        }

        val hintedTargets = targetHints(imageSuggestion)
        val orderedTargets = (hintedTargets + visibleTargets).distinct()
        val candidates = mutableListOf<QuickFlashCandidate>()

        if (!request.sessionBroken &&
            imageSuggestion.kind != PartitionNameResolver.Kind.ARCHIVE &&
            inventory.topology != FastbootPartitionInventory.SlotTopology.UNKNOWN
        ) {
            orderedTargets.forEach { target ->
                if (target !in visibleTargets) return@forEach
                val base = target.fixedBasePartition ?: return@forEach
                inventory.entries
                    .filter { it.baseName == base }
                    .mapNotNullTo(candidates) { entry ->
                        candidateFromEntry(target, entry, inventory, request.sessionBroken)
                    }
            }
        }

        val normalizedManual = request.manualPartitionName
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }

        if (normalizedManual != null) {
            when {
                !request.expertModeEnabled -> errors += Error.MANUAL_PARTITION_REQUIRES_EXPERT_MODE
                !QuickFlashPlanValidator.isCanonicalPartitionName(normalizedManual) -> {
                    errors += Error.INVALID_MANUAL_PARTITION
                }
                QuickFlashPlanValidator.definedTargetForBase(normalizedManual) != null -> {
                    errors += Error.MANUAL_PARTITION_DUPLICATES_DEFINED_TARGET
                }
                !QuickFlashPlanValidator.isManualPartitionAllowed(normalizedManual) -> {
                    errors += Error.MANUAL_PARTITION_FORBIDDEN
                }
                !request.sessionBroken &&
                    imageSuggestion.kind != PartitionNameResolver.Kind.ARCHIVE &&
                    inventory.topology != FastbootPartitionInventory.SlotTopology.UNKNOWN -> {
                    val entry = inventory.partition(normalizedManual)
                    val manualCandidate = entry?.let {
                        candidateFromEntry(QuickFlashTarget.MANUAL, it, inventory, request.sessionBroken)
                    }
                    if (manualCandidate != null) {
                        candidates += manualCandidate
                    } else {
                        errors += Error.MANUAL_PARTITION_NOT_CONFIRMED
                    }
                }
                else -> errors += Error.MANUAL_PARTITION_NOT_CONFIRMED
            }
        }

        val distinctCandidates = candidates
            .distinctBy { listOf(it.target.name, it.partitionName) }
            .sortedWith(compareBy<QuickFlashCandidate> { targetOrder(it.target) }.thenBy { it.partitionName })

        if (distinctCandidates.isEmpty() && errors.none {
                it == Error.SESSION_BROKEN || it == Error.IMAGE_ARCHIVE_REQUIRES_SIDELOAD
            }
        ) {
            errors += Error.NO_CONFIRMED_CANDIDATES
        }

        val discoveryPartitions = if (
            request.sessionBroken || imageSuggestion.kind == PartitionNameResolver.Kind.ARCHIVE
        ) {
            emptyList()
        } else {
            discoveryPartitions(
                inventory = inventory,
                targets = orderedTargets.filter { it in visibleTargets },
                manualPartition = normalizedManual?.takeIf {
                    request.expertModeEnabled &&
                        QuickFlashPlanValidator.isCanonicalPartitionName(it) &&
                        QuickFlashPlanValidator.isManualPartitionAllowed(it)
                }
            )
        }

        val pointPlan = if (
            request.sessionBroken || imageSuggestion.kind == PartitionNameResolver.Kind.ARCHIVE
        ) {
            FastbootPartitionProbePlanner.Plan(
                requests = emptyList(),
                omittedRequestCount = 0,
                discoveryFallbackUsed = false
            )
        } else {
            FastbootPartitionProbePlanner.plan(
                source = request.source,
                inventory = inventory,
                maxQueries = request.maxPointQueries,
                discoveryPartitions = discoveryPartitions
            )
        }

        notes += "topology=${inventory.topology}"
        notes += "candidate-count=${distinctCandidates.size}"
        notes += "point-query-count=${pointPlan.requests.size}"
        notes += "image-hint=${imageSuggestion.kind}"

        val fatal = errors.any {
            it == Error.SESSION_BROKEN || it == Error.IMAGE_ARCHIVE_REQUIRES_SIDELOAD
        }
        val status = when {
            fatal -> Status.BLOCKED
            distinctCandidates.isNotEmpty() && inventory.topology != FastbootPartitionInventory.SlotTopology.UNKNOWN -> Status.READY
            pointPlan.requests.isNotEmpty() -> Status.NEEDS_POINT_QUERY
            else -> Status.BLOCKED
        }

        return Result(
            status = status,
            inventory = inventory,
            candidates = distinctCandidates,
            imageSuggestion = imageSuggestion,
            suggestedTargets = hintedTargets,
            pointQueryPlan = pointPlan,
            errors = errors.toList(),
            notes = notes
        )
    }

    private fun candidateFromEntry(
        target: QuickFlashTarget,
        entry: FastbootPartitionInventory.Entry,
        inventory: FastbootPartitionInventory.Snapshot,
        sessionBroken: Boolean
    ): QuickFlashCandidate? {
        if (inventory.topology == FastbootPartitionInventory.SlotTopology.UNKNOWN) return null

        val expectedBase = target.fixedBasePartition
        if (expectedBase != null && entry.baseName != expectedBase) return null
        if (target == QuickFlashTarget.MANUAL &&
            !QuickFlashPlanValidator.isManualPartitionAllowed(entry.name)
        ) return null

        val slot = when (entry.slotBinding) {
            FastbootPartitionInventory.SlotBinding.UNSLOTTED -> QuickFlashSlot.UNSLOTTED
            FastbootPartitionInventory.SlotBinding.SLOT_A -> QuickFlashSlot.SLOT_A
            FastbootPartitionInventory.SlotBinding.SLOT_B -> QuickFlashSlot.SLOT_B
            FastbootPartitionInventory.SlotBinding.SLOT_FAMILY_BASE,
            FastbootPartitionInventory.SlotBinding.UNKNOWN -> return null
        }

        val requestedSlot = when (slot) {
            QuickFlashSlot.UNSLOTTED -> FastbootSlotResolver.RequestedSlot.ACTIVE
            QuickFlashSlot.SLOT_A -> FastbootSlotResolver.RequestedSlot.SLOT_A
            QuickFlashSlot.SLOT_B -> FastbootSlotResolver.RequestedSlot.SLOT_B
        }
        val base = entry.baseName
        val resolution = FastbootSlotResolver.resolve(
            partition = base,
            requested = requestedSlot,
            evidence = slotEvidence(base, inventory, sessionBroken)
        )
        if (!resolution.canProceed || resolution.targets != listOf(entry.name)) return null

        val evidence = if (FastbootPartitionInventory.EvidenceSource.POINT_QUERY in entry.evidenceSources) {
            QuickFlashCandidateEvidence.POINT_QUERY
        } else {
            QuickFlashCandidateEvidence.INVENTORY
        }

        return QuickFlashCandidate(
            target = target,
            basePartition = base,
            partitionName = entry.name,
            slot = slot,
            evidence = evidence
        )
    }

    private fun slotEvidence(
        base: String,
        inventory: FastbootPartitionInventory.Snapshot,
        sessionBroken: Boolean
    ): FastbootSlotResolver.Evidence = FastbootSlotResolver.Evidence(
        slotCount = inventory.variables["slot-count"]?.toIntOrNull(),
        currentSlot = inventory.currentSlot,
        hasSlot = inventory.slotFamilies[base]
            ?: inventory.partition(base)?.hasSlot
            ?: inventory.partition("${base}_a")?.hasSlot
            ?: inventory.partition("${base}_b")?.hasSlot,
        unsuffixedExists = inventory.partition(base) != null,
        slotAExists = inventory.partition("${base}_a") != null,
        slotBExists = inventory.partition("${base}_b") != null,
        sessionBroken = sessionBroken
    )

    private fun targetHints(suggestion: PartitionNameResolver.Suggestion): List<QuickFlashTarget> {
        val bases = when (suggestion.kind) {
            PartitionNameResolver.Kind.EXACT_PARTITION -> listOfNotNull(suggestion.partition)
            PartitionNameResolver.Kind.RECOVERY_IMAGE -> suggestion.candidates
            PartitionNameResolver.Kind.ARCHIVE,
            PartitionNameResolver.Kind.UNKNOWN -> emptyList()
        }
        return bases.mapNotNull { base ->
            QuickFlashTarget.entries.firstOrNull { it.fixedBasePartition == base }
        }.distinct()
    }

    private fun discoveryPartitions(
        inventory: FastbootPartitionInventory.Snapshot,
        targets: List<QuickFlashTarget>,
        manualPartition: String?
    ): List<String> = buildList {
        manualPartition?.let { add(it) }
        targets.mapNotNull { it.fixedBasePartition }.distinct().forEach { base ->
            val family = inventory.slotFamilies[base]
            when (inventory.topology) {
                FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY -> add(base)
                FastbootPartitionInventory.SlotTopology.A_B -> when (family) {
                    false -> add(base)
                    true -> {
                        add("${base}_a")
                        add("${base}_b")
                    }
                    null -> {
                        add(base)
                        add("${base}_a")
                        add("${base}_b")
                    }
                }
                FastbootPartitionInventory.SlotTopology.UNKNOWN -> {
                    add(base)
                    add("${base}_a")
                    add("${base}_b")
                }
            }
        }
    }.distinct()

    private fun targetOrder(target: QuickFlashTarget): Int = when (target) {
        QuickFlashTarget.RECOVERY -> 0
        QuickFlashTarget.BOOT -> 1
        QuickFlashTarget.INIT_BOOT -> 2
        QuickFlashTarget.VENDOR_BOOT -> 3
        QuickFlashTarget.DTBO -> 4
        QuickFlashTarget.VBMETA -> 5
        QuickFlashTarget.VENDOR_KERNEL_BOOT -> 6
        QuickFlashTarget.MANUAL -> 7
    }
}
