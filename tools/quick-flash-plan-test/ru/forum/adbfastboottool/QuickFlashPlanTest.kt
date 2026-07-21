package ru.forum.adbfastboottool

private const val SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private fun candidate(
    target: QuickFlashTarget,
    slot: QuickFlashSlot,
    evidence: QuickFlashCandidateEvidence = QuickFlashCandidateEvidence.INVENTORY,
    base: String = requireNotNull(target.fixedBasePartition),
    partition: String = base + slot.suffix
): QuickFlashCandidate = QuickFlashCandidate(
    target = target,
    basePartition = base,
    partitionName = partition,
    slot = slot,
    evidence = evidence
)

private fun request(
    target: QuickFlashTarget,
    candidate: QuickFlashCandidate,
    candidates: List<QuickFlashCandidate> = listOf(candidate),
    expertMode: Boolean = target.isExpert,
    manualConfirmation: String? = null
): QuickFlashPlanRequest = QuickFlashPlanRequest(
    deviceSessionId = "session-42",
    deviceDisplayName = "fastboot device",
    target = target,
    selectedPartitionName = candidate.partitionName,
    candidates = candidates,
    imageUri = "file:///data/user/0/ru.forum.adbfastboottool/files/quick-flash/recovery.img",
    imageDisplayName = "recovery.img",
    imageSizeBytes = 64L * 1024L * 1024L,
    imageSha256 = SHA256,
    expertModeEnabled = expertMode,
    manualPartitionConfirmation = manualConfirmation
)

private fun expectRejected(
    validation: QuickFlashPlanValidation,
    error: QuickFlashPlanError
) {
    check(!validation.canProceed) { "Expected rejection, got ${validation.plan}" }
    check(error in validation.errors) { "Expected $error, got ${validation.errors}" }
}

fun main() {
    targetCatalogIsSeparated()
    mainTargetsAcceptConfirmedSingleAndAbCandidates()
    validatorFailsClosedOnMissingAmbiguousOrSyntheticTargets()
    expertAndManualTargetsRequireExplicitGates()
    imageAndDeviceIdentityAreValidated()
    confirmationSerializationIsStableAndSingleMutation()

    println("QUICK FLASH PLAN TESTS: OK")
}

private fun targetCatalogIsSeparated() {
    check(QuickFlashTarget.primaryTargets == listOf(
        QuickFlashTarget.RECOVERY,
        QuickFlashTarget.BOOT,
        QuickFlashTarget.INIT_BOOT,
        QuickFlashTarget.VENDOR_BOOT
    ))
    check(QuickFlashTarget.expertTargets == listOf(
        QuickFlashTarget.DTBO,
        QuickFlashTarget.VBMETA,
        QuickFlashTarget.VENDOR_KERNEL_BOOT,
        QuickFlashTarget.MANUAL
    ))
}

private fun mainTargetsAcceptConfirmedSingleAndAbCandidates() {
    QuickFlashTarget.primaryTargets.forEach { target ->
        val single = candidate(target, QuickFlashSlot.UNSLOTTED)
        val singlePlan = QuickFlashPlanValidator.validate(request(target, single)).plan
        check(singlePlan?.partitionName == target.fixedBasePartition) { "singlePlan=$singlePlan" }
        check(singlePlan?.slot == QuickFlashSlot.UNSLOTTED)

        val slotA = candidate(target, QuickFlashSlot.SLOT_A)
        val slotAPlan = QuickFlashPlanValidator.validate(request(target, slotA)).plan
        check(slotAPlan?.partitionName == "${target.fixedBasePartition}_a") { "slotAPlan=$slotAPlan" }

        val slotB = candidate(target, QuickFlashSlot.SLOT_B, QuickFlashCandidateEvidence.POINT_QUERY)
        val slotBPlan = QuickFlashPlanValidator.validate(request(target, slotB)).plan
        check(slotBPlan?.partitionName == "${target.fixedBasePartition}_b") { "slotBPlan=$slotBPlan" }
    }
}

private fun validatorFailsClosedOnMissingAmbiguousOrSyntheticTargets() {
    val boot = candidate(QuickFlashTarget.BOOT, QuickFlashSlot.SLOT_A)

    expectRejected(
        QuickFlashPlanValidator.validate(request(QuickFlashTarget.BOOT, boot, candidates = emptyList())),
        QuickFlashPlanError.TARGET_NOT_FOUND
    )
    expectRejected(
        QuickFlashPlanValidator.validate(request(QuickFlashTarget.BOOT, boot, candidates = listOf(boot, boot))),
        QuickFlashPlanError.TARGET_AMBIGUOUS
    )
    expectRejected(
        QuickFlashPlanValidator.validate(request(
            QuickFlashTarget.BOOT,
            boot.copy(evidence = QuickFlashCandidateEvidence.UNCONFIRMED)
        )),
        QuickFlashPlanError.CANDIDATE_NOT_CONFIRMED
    )
    expectRejected(
        QuickFlashPlanValidator.validate(request(
            QuickFlashTarget.BOOT,
            boot.copy(partitionName = "boot_b")
        )),
        QuickFlashPlanError.CANDIDATE_SLOT_MISMATCH
    )
    expectRejected(
        QuickFlashPlanValidator.validate(request(
            QuickFlashTarget.BOOT,
            boot.copy(target = QuickFlashTarget.RECOVERY)
        )),
        QuickFlashPlanError.CANDIDATE_TARGET_MISMATCH
    )
    expectRejected(
        QuickFlashPlanValidator.validate(request(
            QuickFlashTarget.BOOT,
            boot.copy(basePartition = "recovery", partitionName = "recovery_a")
        )),
        QuickFlashPlanError.CANDIDATE_BASE_MISMATCH
    )
}

private fun expertAndManualTargetsRequireExplicitGates() {
    val vbmeta = candidate(QuickFlashTarget.VBMETA, QuickFlashSlot.UNSLOTTED)
    expectRejected(
        QuickFlashPlanValidator.validate(request(QuickFlashTarget.VBMETA, vbmeta, expertMode = false)),
        QuickFlashPlanError.EXPERT_MODE_REQUIRED
    )
    check(QuickFlashPlanValidator.validate(request(QuickFlashTarget.VBMETA, vbmeta)).canProceed)

    val manual = QuickFlashCandidate(
        target = QuickFlashTarget.MANUAL,
        basePartition = "recovery_ramdisk",
        partitionName = "recovery_ramdisk_a",
        slot = QuickFlashSlot.SLOT_A,
        evidence = QuickFlashCandidateEvidence.POINT_QUERY
    )
    expectRejected(
        QuickFlashPlanValidator.validate(request(QuickFlashTarget.MANUAL, manual)),
        QuickFlashPlanError.MANUAL_CONFIRMATION_REQUIRED
    )
    check(QuickFlashPlanValidator.validate(request(
        QuickFlashTarget.MANUAL,
        manual,
        manualConfirmation = "recovery_ramdisk_a"
    )).canProceed)

    val manualBoot = manual.copy(basePartition = "boot", partitionName = "boot_a")
    expectRejected(
        QuickFlashPlanValidator.validate(request(
            QuickFlashTarget.MANUAL,
            manualBoot,
            manualConfirmation = "boot_a"
        )),
        QuickFlashPlanError.MANUAL_TARGET_DUPLICATES_DEFINED_TARGET
    )

    val manualSystem = manual.copy(basePartition = "system", partitionName = "system_a")
    expectRejected(
        QuickFlashPlanValidator.validate(request(
            QuickFlashTarget.MANUAL,
            manualSystem,
            manualConfirmation = "system_a"
        )),
        QuickFlashPlanError.MANUAL_TARGET_FORBIDDEN
    )
}

private fun imageAndDeviceIdentityAreValidated() {
    val recovery = candidate(QuickFlashTarget.RECOVERY, QuickFlashSlot.UNSLOTTED)
    val valid = request(QuickFlashTarget.RECOVERY, recovery)

    expectRejected(
        QuickFlashPlanValidator.validate(valid.copy(deviceSessionId = " ")),
        QuickFlashPlanError.INVALID_DEVICE_SESSION
    )
    expectRejected(
        QuickFlashPlanValidator.validate(valid.copy(imageSizeBytes = 0L)),
        QuickFlashPlanError.INVALID_IMAGE_SIZE
    )
    expectRejected(
        QuickFlashPlanValidator.validate(valid.copy(imageSha256 = "deadbeef")),
        QuickFlashPlanError.INVALID_IMAGE_SHA256
    )
    expectRejected(
        QuickFlashPlanValidator.validate(valid.copy(imageUri = "")),
        QuickFlashPlanError.INVALID_IMAGE_URI
    )
}

private fun confirmationSerializationIsStableAndSingleMutation() {
    val recovery = candidate(QuickFlashTarget.RECOVERY, QuickFlashSlot.UNSLOTTED)
    val plan = checkNotNull(QuickFlashPlanValidator.validate(
        request(QuickFlashTarget.RECOVERY, recovery)
    ).plan)

    check(plan.fastbootArguments == listOf("flash", "recovery"))
    check(plan.fastbootArguments.count { it == "flash" } == 1)
    check(plan.commandPreview == "fastboot flash recovery <verified-image>")

    val encoded1 = plan.encodeForConfirmation()
    val encoded2 = plan.encodeForConfirmation()
    check(encoded1 == encoded2)
    check(QuickFlashPlanCodec.decode(encoded1) == plan)

    val fingerprint = plan.confirmationFingerprint()
    check(fingerprint.length == 64)
    check(fingerprint == plan.confirmationFingerprint())

    val tampered = encoded1.replace("|RECOVERY|", "|BOOT|")
    check(QuickFlashPlanCodec.decode(tampered) == null)
}
