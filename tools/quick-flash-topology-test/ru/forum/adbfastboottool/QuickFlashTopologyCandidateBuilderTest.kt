package ru.forum.adbfastboottool

fun main() {
    legacyAOnlyExposesOnlyConcreteUnsuffixedTarget()
    abInventoryExposesOnlyConcreteSlotCandidates()
    unknownTopologyFailsClosedAndPlansBoundedReads()
    pointQueryEvidenceCanConfirmOneConcreteCandidate()
    expertAndManualTargetsStayBehindExplicitGates()
    archiveAndBrokenSessionAreBlocked()
    println("QUICK FLASH TOPOLOGY CANDIDATE BUILDER TESTS: OK")
}

private fun legacyAOnlyExposesOnlyConcreteUnsuffixedTarget() {
    val result = build(
        lines = listOf(
            "INFOproduct:vayu",
            "INFOpartition-size:recovery:0x08000000",
            "INFOpartition-type:recovery:raw",
            "INFOis-logical:recovery:no",
            "INFOhas-slot:recovery:no"
        ),
        image = "OrangeFox-R12.img"
    )

    check(result.status == QuickFlashTopologyCandidateBuilder.Status.READY)
    check(result.canChooseTarget)
    check(result.candidates == listOf(
        QuickFlashCandidate(
            target = QuickFlashTarget.RECOVERY,
            basePartition = "recovery",
            partitionName = "recovery",
            slot = QuickFlashSlot.UNSLOTTED,
            evidence = QuickFlashCandidateEvidence.INVENTORY
        )
    )) { "candidates=${result.candidates}" }
    check(result.candidates.none { it.partitionName.endsWith("_a") || it.partitionName.endsWith("_b") })
    check(result.suggestedTargets.first() == QuickFlashTarget.RECOVERY)
}

private fun abInventoryExposesOnlyConcreteSlotCandidates() {
    val result = build(
        lines = listOf(
            "INFOproduct:marble",
            "INFOslot-count:2",
            "INFOcurrent-slot:b",
            "INFOhas-slot:boot:yes",
            "INFOpartition-size:boot_a:0x06000000",
            "INFOpartition-size:boot_b:0x06000000"
        ),
        image = "boot_b.img"
    )

    check(result.status == QuickFlashTopologyCandidateBuilder.Status.READY)
    check(result.suggestedTargets == listOf(QuickFlashTarget.BOOT))
    check(result.candidates.map { it.partitionName } == listOf("boot_a", "boot_b")) {
        "candidates=${result.candidates}"
    }
    check(result.candidates.map { it.slot } == listOf(QuickFlashSlot.SLOT_A, QuickFlashSlot.SLOT_B))
    check(result.candidates.all { it.target == QuickFlashTarget.BOOT })
}

private fun unknownTopologyFailsClosedAndPlansBoundedReads() {
    val source = FastbootGetVarAllParser.parse(
        lines = listOf(
            "INFOproduct:unknown",
            "INFOpartition-size:recovery:0x08000000"
        ),
        complete = false,
        finalStatus = "FAIL",
        finalMessage = "unknown variable"
    )
    val result = QuickFlashTopologyCandidateBuilder.build(
        QuickFlashTopologyCandidateBuilder.Request(
            source = source,
            imageDisplayName = "twrp.img",
            maxPointQueries = 5
        )
    )

    check(result.status == QuickFlashTopologyCandidateBuilder.Status.NEEDS_POINT_QUERY)
    check(!result.canChooseTarget)
    check(result.candidates.isEmpty())
    check(QuickFlashTopologyCandidateBuilder.Error.SLOT_TOPOLOGY_UNKNOWN in result.errors)
    check(result.pointQueryPlan.requests.size <= 5)
    check(result.pointQueryPlan.requests.isNotEmpty())
    check(result.pointQueryPlan.requests.first().partition == "recovery") {
        "requests=${result.pointQueryPlan.requests}"
    }
}

private fun pointQueryEvidenceCanConfirmOneConcreteCandidate() {
    val source = FastbootGetVarAllParser.parse(
        listOf(
            "INFOproduct:marble",
            "INFOslot-count:2",
            "INFOcurrent-slot:a",
            "INFOhas-slot:recovery:yes"
        )
    )
    val probe = FastbootPartitionInventory.PointProbe(
        name = "recovery_a",
        sizeBytes = 0x08000000L,
        attemptedFields = setOf(FastbootGetVarAllParser.MetadataField.SIZE),
        resolvedFields = setOf(FastbootGetVarAllParser.MetadataField.SIZE)
    )
    val result = QuickFlashTopologyCandidateBuilder.build(
        QuickFlashTopologyCandidateBuilder.Request(
            source = source,
            pointProbes = listOf(probe),
            imageDisplayName = "recovery.img"
        )
    )

    check(result.status == QuickFlashTopologyCandidateBuilder.Status.READY)
    val candidate = result.candidates.single { it.target == QuickFlashTarget.RECOVERY }
    check(candidate.partitionName == "recovery_a")
    check(candidate.slot == QuickFlashSlot.SLOT_A)
    check(candidate.evidence == QuickFlashCandidateEvidence.POINT_QUERY)
    check(result.candidates.none { it.partitionName == "recovery_b" })
}

private fun expertAndManualTargetsStayBehindExplicitGates() {
    val lines = listOf(
        "INFOproduct:marble",
        "INFOslot-count:2",
        "INFOcurrent-slot:a",
        "INFOhas-slot:dtbo:yes",
        "INFOpartition-size:dtbo_a:0x01000000",
        "INFOpartition-size:dtbo_b:0x01000000",
        "INFOhas-slot:recovery_ramdisk:yes",
        "INFOpartition-size:recovery_ramdisk_a:0x02000000"
    )

    val hidden = build(lines, "dtbo.img", expert = false)
    check(hidden.candidates.none { it.target == QuickFlashTarget.DTBO })

    val expert = build(lines, "dtbo.img", expert = true)
    check(expert.candidates.count { it.target == QuickFlashTarget.DTBO } == 2)

    val manualBlocked = build(
        lines = lines,
        image = "custom.img",
        expert = false,
        manual = "recovery_ramdisk_a"
    )
    check(QuickFlashTopologyCandidateBuilder.Error.MANUAL_PARTITION_REQUIRES_EXPERT_MODE in manualBlocked.errors)
    check(manualBlocked.candidates.none { it.target == QuickFlashTarget.MANUAL })

    val manual = build(
        lines = lines,
        image = "custom.img",
        expert = true,
        manual = "recovery_ramdisk_a"
    )
    check(manual.candidates.single { it.target == QuickFlashTarget.MANUAL }.partitionName == "recovery_ramdisk_a")

    val duplicate = build(
        lines = lines,
        image = "custom.img",
        expert = true,
        manual = "dtbo_a"
    )
    check(QuickFlashTopologyCandidateBuilder.Error.MANUAL_PARTITION_DUPLICATES_DEFINED_TARGET in duplicate.errors)
    check(duplicate.candidates.none { it.target == QuickFlashTarget.MANUAL })
}

private fun archiveAndBrokenSessionAreBlocked() {
    val lines = listOf(
        "INFOproduct:vayu",
        "INFOpartition-size:recovery:0x08000000",
        "INFOhas-slot:recovery:no"
    )
    val archive = build(lines, "rom.zip")
    check(archive.status == QuickFlashTopologyCandidateBuilder.Status.BLOCKED)
    check(archive.candidates.isEmpty())
    check(QuickFlashTopologyCandidateBuilder.Error.IMAGE_ARCHIVE_REQUIRES_SIDELOAD in archive.errors)

    val broken = QuickFlashTopologyCandidateBuilder.build(
        QuickFlashTopologyCandidateBuilder.Request(
            source = FastbootGetVarAllParser.parse(lines),
            imageDisplayName = "recovery.img",
            sessionBroken = true
        )
    )
    check(broken.status == QuickFlashTopologyCandidateBuilder.Status.BLOCKED)
    check(broken.candidates.isEmpty())
    check(broken.pointQueryPlan.requests.isEmpty())
    check(QuickFlashTopologyCandidateBuilder.Error.SESSION_BROKEN in broken.errors)
}

private fun build(
    lines: List<String>,
    image: String,
    expert: Boolean = false,
    manual: String? = null
): QuickFlashTopologyCandidateBuilder.Result =
    QuickFlashTopologyCandidateBuilder.build(
        QuickFlashTopologyCandidateBuilder.Request(
            source = FastbootGetVarAllParser.parse(lines),
            imageDisplayName = image,
            expertModeEnabled = expert,
            manualPartitionName = manual
        )
    )
