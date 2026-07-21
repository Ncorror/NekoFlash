package ru.forum.adbfastboottool

/**
 * Builds a bounded read-only point-query plan for metadata omitted by
 * `getvar:all`. The plan never turns a guessed name into an inventory entry;
 * only a concrete bootloader response can do that.
 */
object FastbootPartitionProbePlanner {

    data class Request(
        val partition: String,
        val field: FastbootGetVarAllParser.MetadataField
    )

    data class Plan(
        val requests: List<Request>,
        val omittedRequestCount: Int,
        val discoveryFallbackUsed: Boolean
    )

    private val fallbackDiscoveryBases = listOf(
        "boot",
        "init_boot",
        "vendor_boot",
        "vendor_kernel_boot",
        "recovery",
        "dtbo",
        "vbmeta",
        "super"
    )

    fun plan(
        source: FastbootGetVarAllParser.Snapshot,
        inventory: FastbootPartitionInventory.Snapshot,
        maxQueries: Int = 24
    ): Plan {
        require(maxQueries >= 0)
        val requests = linkedSetOf<Request>()

        fun addMissingFields(name: String, existing: FastbootPartitionInventory.Entry?) {
            if (existing?.sizeBytes == null) requests += Request(name, FastbootGetVarAllParser.MetadataField.SIZE)
            if (existing?.type.isNullOrBlank()) requests += Request(name, FastbootGetVarAllParser.MetadataField.TYPE)
            if (existing?.logical == null) requests += Request(name, FastbootGetVarAllParser.MetadataField.LOGICAL)
            if (existing != null && existing.slotBinding == FastbootPartitionInventory.SlotBinding.UNKNOWN) {
                requests += Request(existing.baseName, FastbootGetVarAllParser.MetadataField.HAS_SLOT)
            } else if (
                existing == null &&
                inventory.topology == FastbootPartitionInventory.SlotTopology.UNKNOWN &&
                !name.endsWith("_a") && !name.endsWith("_b")
            ) {
                requests += Request(name, FastbootGetVarAllParser.MetadataField.HAS_SLOT)
            }
        }

        inventory.entries.forEach { entry -> addMissingFields(entry.name, entry) }

        // Family-only metadata, for example has-slot:boot=yes, is not an actual
        // partition. Probe concrete names, then expose only those confirmed by
        // size/type/is-logical.
        source.partitions
            .filter { !it.hasConcreteEvidence && FastbootGetVarAllParser.MetadataField.HAS_SLOT in it.metadataFields }
            .forEach { family ->
                val base = family.name
                when {
                    inventory.topology == FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY || family.hasSlot == false -> {
                        addMissingFields(base, inventory.partition(base))
                    }
                    family.hasSlot == true -> {
                        addMissingFields("${base}_a", inventory.partition("${base}_a"))
                        addMissingFields("${base}_b", inventory.partition("${base}_b"))
                    }
                }
            }

        var discoveryFallbackUsed = false
        if (inventory.entries.isEmpty()) {
            discoveryFallbackUsed = true
            fallbackDiscoveryBases.forEach { base ->
                when (inventory.topology) {
                    FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY -> addMissingFields(base, null)
                    FastbootPartitionInventory.SlotTopology.A_B -> {
                        addMissingFields("${base}_a", null)
                        addMissingFields("${base}_b", null)
                    }
                    FastbootPartitionInventory.SlotTopology.UNKNOWN -> addMissingFields(base, null)
                }
            }
        }

        val sorted = requests.sortedWith(
            compareBy<Request> { riskPriority(it.partition) }
                .thenBy { fieldPriority(it.field) }
                .thenBy { it.partition }
        )
        val limited = sorted.take(maxQueries)
        return Plan(
            requests = limited,
            omittedRequestCount = (sorted.size - limited.size).coerceAtLeast(0),
            discoveryFallbackUsed = discoveryFallbackUsed
        )
    }

    private fun riskPriority(partition: String): Int = when (FastbootPartitionInventory.riskTier(partition)) {
        FastbootPartitionInventory.RiskTier.NORMAL -> 0
        FastbootPartitionInventory.RiskTier.ADVANCED -> 1
        FastbootPartitionInventory.RiskTier.CRITICAL -> 2
    }

    private fun fieldPriority(field: FastbootGetVarAllParser.MetadataField): Int = when (field) {
        FastbootGetVarAllParser.MetadataField.SIZE -> 0
        FastbootGetVarAllParser.MetadataField.TYPE -> 1
        FastbootGetVarAllParser.MetadataField.LOGICAL -> 2
        FastbootGetVarAllParser.MetadataField.HAS_SLOT -> 3
    }
}
