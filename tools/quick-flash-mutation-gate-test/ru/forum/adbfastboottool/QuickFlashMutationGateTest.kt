package ru.forum.adbfastboottool

private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private fun confirmedCandidate(
    evidence: QuickFlashCandidateEvidence = QuickFlashCandidateEvidence.INVENTORY
) = QuickFlashCandidate(
    target = QuickFlashTarget.RECOVERY,
    basePartition = "recovery",
    partitionName = "recovery_a",
    slot = QuickFlashSlot.SLOT_A,
    evidence = evidence
)

private fun plan(): QuickFlashPlan {
    val candidate = confirmedCandidate()
    return checkNotNull(
        QuickFlashPlanValidator.validate(
            QuickFlashPlanRequest(
                deviceSessionId = "session-quick-flash-42",
                deviceDisplayName = "fastboot device",
                target = QuickFlashTarget.RECOVERY,
                selectedPartitionName = candidate.partitionName,
                candidates = listOf(candidate),
                imageUri = "file:/data/local/tmp/recovery.img",
                imageDisplayName = "recovery.img",
                imageSizeBytes = 4096,
                imageSha256 = HASH
            )
        ).plan
    )
}

private fun runtime(
    plan: QuickFlashPlan,
    confirmationAvailable: Boolean = true,
    sessionId: String? = plan.deviceSessionId,
    connected: Boolean = true,
    broken: Boolean = false,
    readOnly: Boolean = false,
    expert: Boolean = false,
    unlocked: Boolean? = true,
    uri: String = plan.imageUri,
    name: String = plan.imageDisplayName,
    size: Long = plan.imageSizeBytes,
    sha256: String = plan.imageSha256,
    candidates: List<QuickFlashCandidate> = listOf(confirmedCandidate())
) = QuickFlashMutationGate.RuntimeEvidence(
    currentSessionId = sessionId,
    fastbootConnected = connected,
    sessionBroken = broken,
    readOnlyMutationLock = readOnly,
    expertModeEnabled = expert,
    bootloaderUnlocked = unlocked,
    currentImageUri = uri,
    currentImageDisplayName = name,
    currentImageSizeBytes = size,
    currentImageSha256 = sha256,
    currentCandidates = candidates,
    confirmationAvailable = confirmationAvailable
)

private fun decide(
    plan: QuickFlashPlan,
    ticket: QuickFlashMutationGate.ConfirmationTicket,
    runtime: QuickFlashMutationGate.RuntimeEvidence
) = QuickFlashMutationGate.evaluate(
    QuickFlashMutationGate.Request(plan, ticket, runtime)
)

private fun reject(decision: QuickFlashMutationGate.Decision, error: QuickFlashMutationGate.Error) {
    check(!decision.allowed) { "Expected rejection, got ${decision.authorization}" }
    check(error in decision.errors) { "Expected $error, got ${decision.errors}" }
}

fun main() {
    exactConfirmationAuthorizesExactlyOneCommand()
    confirmationIsBoundToPlanAndOneShotRegistry()
    sessionAndTransportChangesFailClosed()
    fileIdentityChangesFailClosed()
    topologyChangesFailClosed()
    expertAndReadOnlyGatesRemainActive()
    println("QUICK FLASH MUTATION GATE TESTS: OK")
}

private fun exactConfirmationAuthorizesExactlyOneCommand() {
    val plan = plan()
    val issue = QuickFlashMutationGate.issueConfirmation(plan, "confirm-quick-flash-0001")
    check(issue.issued)
    val ticket = checkNotNull(issue.ticket)
    val decision = decide(plan, ticket, runtime(plan))
    check(decision.allowed) { decision.errors.toString() }
    val auth = checkNotNull(decision.authorization)
    check(auth.fastbootArguments == listOf("flash", "recovery_a"))
    check(auth.commandCount == 1)
    check(!auth.retryAllowed)
    check(auth.executionFingerprint.length == 64)
}

private fun confirmationIsBoundToPlanAndOneShotRegistry() {
    val plan = plan()
    val ticket = checkNotNull(
        QuickFlashMutationGate.issueConfirmation(plan, "confirm-quick-flash-0002").ticket
    )
    val changed = plan.copy(partitionName = "recovery_b", slot = QuickFlashSlot.SLOT_B)
    reject(decide(changed, ticket, runtime(changed)), QuickFlashMutationGate.Error.CONFIRMATION_PLAN_MISMATCH)

    val registry = QuickFlashConfirmationRegistry()
    check(registry.consume(ticket.confirmationId))
    check(!registry.consume(ticket.confirmationId))
    reject(
        decide(plan, ticket, runtime(plan, confirmationAvailable = false)),
        QuickFlashMutationGate.Error.CONFIRMATION_ALREADY_CONSUMED
    )
    registry.clear()
    check(registry.consume(ticket.confirmationId))
}

private fun sessionAndTransportChangesFailClosed() {
    val plan = plan()
    val ticket = checkNotNull(
        QuickFlashMutationGate.issueConfirmation(plan, "confirm-quick-flash-0003").ticket
    )
    reject(decide(plan, ticket, runtime(plan, sessionId = "other-session")), QuickFlashMutationGate.Error.SESSION_CHANGED)
    reject(decide(plan, ticket, runtime(plan, connected = false)), QuickFlashMutationGate.Error.FASTBOOT_DISCONNECTED)
    reject(decide(plan, ticket, runtime(plan, broken = true)), QuickFlashMutationGate.Error.SESSION_BROKEN)
}

private fun fileIdentityChangesFailClosed() {
    val plan = plan()
    val ticket = checkNotNull(
        QuickFlashMutationGate.issueConfirmation(plan, "confirm-quick-flash-0004").ticket
    )
    reject(decide(plan, ticket, runtime(plan, uri = "file:/other/recovery.img")), QuickFlashMutationGate.Error.IMAGE_URI_CHANGED)
    reject(decide(plan, ticket, runtime(plan, size = plan.imageSizeBytes + 1)), QuickFlashMutationGate.Error.IMAGE_SIZE_CHANGED)
    reject(decide(plan, ticket, runtime(plan, sha256 = "f".repeat(64))), QuickFlashMutationGate.Error.IMAGE_SHA256_CHANGED)
}

private fun topologyChangesFailClosed() {
    val plan = plan()
    val ticket = checkNotNull(
        QuickFlashMutationGate.issueConfirmation(plan, "confirm-quick-flash-0005").ticket
    )
    reject(decide(plan, ticket, runtime(plan, candidates = emptyList())), QuickFlashMutationGate.Error.TARGET_NOT_FOUND)
    reject(
        decide(plan, ticket, runtime(plan, candidates = listOf(confirmedCandidate(), confirmedCandidate()))),
        QuickFlashMutationGate.Error.TARGET_AMBIGUOUS
    )
    reject(
        decide(
            plan,
            ticket,
            runtime(plan, candidates = listOf(confirmedCandidate(QuickFlashCandidateEvidence.UNCONFIRMED)))
        ),
        QuickFlashMutationGate.Error.TARGET_NOT_CONFIRMED
    )
}

private fun expertAndReadOnlyGatesRemainActive() {
    val base = plan()
    val expertPlan = base.copy(
        target = QuickFlashTarget.VBMETA,
        basePartition = "vbmeta",
        partitionName = "vbmeta_a",
        expertModeConfirmed = true
    )
    val expertCandidate = QuickFlashCandidate(
        target = QuickFlashTarget.VBMETA,
        basePartition = "vbmeta",
        partitionName = "vbmeta_a",
        slot = QuickFlashSlot.SLOT_A,
        evidence = QuickFlashCandidateEvidence.INVENTORY
    )
    check(QuickFlashPlanValidator.validatePlan(expertPlan).canProceed)
    val ticket = checkNotNull(
        QuickFlashMutationGate.issueConfirmation(expertPlan, "confirm-quick-flash-0006").ticket
    )
    reject(
        decide(expertPlan, ticket, runtime(expertPlan, expert = false, candidates = listOf(expertCandidate))),
        QuickFlashMutationGate.Error.EXPERT_MODE_DISABLED
    )
    reject(
        decide(expertPlan, ticket, runtime(expertPlan, expert = true, readOnly = true, candidates = listOf(expertCandidate))),
        QuickFlashMutationGate.Error.READ_ONLY_MUTATION_LOCK
    )
    reject(
        decide(expertPlan, ticket, runtime(expertPlan, expert = true, unlocked = false, candidates = listOf(expertCandidate))),
        QuickFlashMutationGate.Error.BOOTLOADER_LOCKED
    )
}
