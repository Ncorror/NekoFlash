package ru.forum.adbfastboottool

fun main() {
    val vayuSource = FastbootGetVarAllParser.parse(
        listOf(
            "INFOproduct:vayu",
            "INFOpartition-size:recovery:0x08000000",
            "INFOpartition-type:recovery:raw",
            "INFOis-logical:recovery:no",
            "INFOhas-slot:recovery:no"
        )
    )
    val vayu = FastbootPartitionInventory.from(vayuSource)
    check(vayu.topology == FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY)
    check(vayu.partition("recovery") != null)
    check(vayu.partition("recovery_a") == null)
    check(vayu.partition("recovery_b") == null)
    check(vayu.partition("recovery")?.risk == FastbootPartitionInventory.RiskTier.NORMAL)
    check(vayu.partition("recovery")?.storage == FastbootPartitionInventory.StorageKind.PHYSICAL)
    check(vayu.partition("recovery")?.slotBinding == FastbootPartitionInventory.SlotBinding.UNSLOTTED)

    val marbleSource = FastbootGetVarAllParser.parse(
        listOf(
            "INFOproduct:marble",
            "INFOcurrent-slot:b",
            "INFOslot-count:2",
            "INFOhas-slot:boot:yes",
            "INFOpartition-size:boot_a:0x06000000",
            "INFOpartition-size:boot_b:0x06000000",
            "INFOpartition-size:super:0x200000000",
            "INFOis-logical:system_a:yes"
        )
    )
    val probes = listOf(
        FastbootPartitionInventory.PointProbe(
            name = "boot_a",
            type = "raw",
            logical = false,
            attemptedFields = setOf(
                FastbootGetVarAllParser.MetadataField.TYPE,
                FastbootGetVarAllParser.MetadataField.LOGICAL
            ),
            resolvedFields = setOf(
                FastbootGetVarAllParser.MetadataField.TYPE,
                FastbootGetVarAllParser.MetadataField.LOGICAL
            )
        )
    )
    val marble = FastbootPartitionInventory.from(marbleSource, pointProbes = probes)
    check(marble.topology == FastbootPartitionInventory.SlotTopology.A_B)
    // has-slot:boot is family metadata, not a concrete partition named boot.
    check(marble.partition("boot") == null)
    check(marble.partition("boot_a")?.risk == FastbootPartitionInventory.RiskTier.NORMAL)
    check(marble.partition("boot_a")?.slotBinding == FastbootPartitionInventory.SlotBinding.SLOT_A)
    check(marble.partition("boot_b")?.slotBinding == FastbootPartitionInventory.SlotBinding.SLOT_B)
    check(marble.partition("boot_a")?.type == "raw")
    check(marble.partition("boot_a")?.storage == FastbootPartitionInventory.StorageKind.PHYSICAL)
    check(marble.partition("boot_a")?.evidenceSources?.contains(FastbootPartitionInventory.EvidenceSource.POINT_QUERY) == true)
    check(marble.partition("super")?.risk == FastbootPartitionInventory.RiskTier.CRITICAL)
    check(marble.partition("system_a")?.logical == true)
    check(marble.filtered(query = "system").single().name == "system_a")
    check(marble.filtered(risk = FastbootPartitionInventory.RiskTier.NORMAL).map { it.name }.containsAll(listOf("boot_a", "boot_b")))

    val pointConfirmedSource = FastbootGetVarAllParser.parse(
        listOf(
            "INFOproduct:marble",
            "INFOslot-count:2",
            "INFOhas-slot:recovery:yes"
        )
    )
    val pointConfirmed = FastbootPartitionInventory.from(
        pointConfirmedSource,
        pointProbes = listOf(
            FastbootPartitionInventory.PointProbe(
                name = "recovery_a",
                sizeBytes = 0x08000000L,
                attemptedFields = setOf(FastbootGetVarAllParser.MetadataField.SIZE),
                resolvedFields = setOf(FastbootGetVarAllParser.MetadataField.SIZE)
            )
        )
    )
    check(pointConfirmed.partition("recovery") == null)
    check(pointConfirmed.partition("recovery_a")?.sizeBytes == 0x08000000L)
    check(pointConfirmed.partition("recovery_a")?.hasSlot == true)

    val genericLegacy = FastbootPartitionInventory.from(
        FastbootGetVarAllParser.parse(
            listOf(
                "INFOproduct:legacy_device",
                "INFOhas-slot:boot:no",
                "INFOhas-slot:recovery:no",
                "INFOpartition-size:boot:0x04000000",
                "INFOpartition-size:recovery:0x08000000"
            )
        )
    )
    check(genericLegacy.topology == FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY)

    val incomplete = FastbootPartitionInventory.from(
        FastbootGetVarAllParser.parse(
            listOf("INFOproduct:unknown", "INFOpartition-size:recovery:0x08000000"),
            complete = false,
            finalStatus = "FAIL",
            finalMessage = "unknown variable"
        )
    )
    check(incomplete.topology == FastbootPartitionInventory.SlotTopology.UNKNOWN)
    check(incomplete.warnings.any { it.code == "PARTIAL_GETVAR_ALL" })
    check(incomplete.partition("recovery")?.missingFields?.contains(FastbootGetVarAllParser.MetadataField.TYPE) == true)

    val conflicting = FastbootPartitionInventory.from(
        FastbootGetVarAllParser.parse(
            listOf(
                "INFOproduct:marble",
                "INFOpartition-size:boot_a:0x1000",
                "INFOpartition-size:boot_a:0x2000"
            )
        )
    )
    check(conflicting.duplicateMetadataCount == 1)
    check(conflicting.warnings.any { it.code == "CONFLICTING_DUPLICATE" })

    check(FastbootPartitionInventory.riskTier("vbmeta_a") == FastbootPartitionInventory.RiskTier.ADVANCED)
    check(FastbootPartitionInventory.riskTier("userdata") == FastbootPartitionInventory.RiskTier.CRITICAL)
    check(FastbootPartitionInventory.riskTier("xbl_a") == FastbootPartitionInventory.RiskTier.CRITICAL)

    println("FASTBOOT PARTITION INVENTORY TESTS: OK")
}
