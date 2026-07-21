package ru.forum.adbfastboottool

import java.security.MessageDigest
import java.util.Locale

/**
 * Slice D: pure one-shot authorization gate between a confirmed QuickFlashPlan
 * and the existing Fastboot flash service.
 *
 * This gate never performs USB I/O. It verifies that the exact plan shown to the
 * user is still bound to the same transport session, concrete topology evidence
 * and image identity immediately before execution. A successful authorization
 * contains exactly one `flash <partition>` command and never encodes retry.
 */
object QuickFlashMutationGate {

    data class ConfirmationTicket(
        val confirmationId: String,
        val encodedPlan: String,
        val planFingerprint: String
    )

    data class IssueResult(
        val ticket: ConfirmationTicket? = null,
        val errors: List<Error> = emptyList()
    ) {
        val issued: Boolean get() = ticket != null && errors.isEmpty()
    }

    data class RuntimeEvidence(
        val currentSessionId: String?,
        val fastbootConnected: Boolean,
        val sessionBroken: Boolean,
        val readOnlyMutationLock: Boolean,
        val expertModeEnabled: Boolean,
        val bootloaderUnlocked: Boolean?,
        val currentImageUri: String,
        val currentImageDisplayName: String,
        val currentImageSizeBytes: Long,
        val currentImageSha256: String,
        val currentCandidates: List<QuickFlashCandidate>,
        /** Result of an atomic one-shot consume in the execution owner. */
        val confirmationAvailable: Boolean
    )

    data class Request(
        val plan: QuickFlashPlan,
        val ticket: ConfirmationTicket,
        val runtime: RuntimeEvidence
    )

    data class Authorization(
        val confirmationId: String,
        val planFingerprint: String,
        val partitionName: String,
        val fastbootArguments: List<String>,
        val commandCount: Int,
        val retryAllowed: Boolean,
        val executionFingerprint: String
    )

    data class Decision(
        val authorization: Authorization? = null,
        val errors: List<Error> = emptyList(),
        val warnings: List<String> = emptyList()
    ) {
        val allowed: Boolean get() = authorization != null && errors.isEmpty()
    }

    enum class Error {
        INVALID_PLAN,
        INVALID_CONFIRMATION_ID,
        CONFIRMATION_PLAN_MISMATCH,
        CONFIRMATION_FINGERPRINT_MISMATCH,
        CONFIRMATION_ALREADY_CONSUMED,
        FASTBOOT_DISCONNECTED,
        SESSION_BROKEN,
        SESSION_CHANGED,
        READ_ONLY_MUTATION_LOCK,
        EXPERT_MODE_DISABLED,
        BOOTLOADER_LOCKED,
        IMAGE_URI_CHANGED,
        IMAGE_NAME_CHANGED,
        IMAGE_SIZE_CHANGED,
        IMAGE_SHA256_CHANGED,
        TARGET_NOT_FOUND,
        TARGET_AMBIGUOUS,
        TARGET_CHANGED,
        TARGET_NOT_CONFIRMED,
        INVALID_COMMAND_SHAPE
    }

    private val CONFIRMATION_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{8,128}$")
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    fun issueConfirmation(plan: QuickFlashPlan, confirmationId: String): IssueResult {
        val errors = linkedSetOf<Error>()
        if (!QuickFlashPlanValidator.validatePlan(plan).canProceed) {
            errors += Error.INVALID_PLAN
        }
        if (!CONFIRMATION_ID_PATTERN.matches(confirmationId)) {
            errors += Error.INVALID_CONFIRMATION_ID
        }
        if (errors.isNotEmpty()) return IssueResult(errors = errors.toList())

        return IssueResult(
            ticket = ConfirmationTicket(
                confirmationId = confirmationId,
                encodedPlan = plan.encodeForConfirmation(),
                planFingerprint = plan.confirmationFingerprint()
            )
        )
    }

    fun evaluate(request: Request): Decision {
        val plan = request.plan
        val ticket = request.ticket
        val runtime = request.runtime
        val errors = linkedSetOf<Error>()
        val warnings = mutableListOf<String>()

        if (!QuickFlashPlanValidator.validatePlan(plan).canProceed) {
            errors += Error.INVALID_PLAN
        }
        if (!CONFIRMATION_ID_PATTERN.matches(ticket.confirmationId)) {
            errors += Error.INVALID_CONFIRMATION_ID
        }
        if (ticket.encodedPlan != plan.encodeForConfirmation()) {
            errors += Error.CONFIRMATION_PLAN_MISMATCH
        }
        if (ticket.planFingerprint != plan.confirmationFingerprint()) {
            errors += Error.CONFIRMATION_FINGERPRINT_MISMATCH
        }
        if (!runtime.confirmationAvailable) {
            errors += Error.CONFIRMATION_ALREADY_CONSUMED
        }
        if (!runtime.fastbootConnected) {
            errors += Error.FASTBOOT_DISCONNECTED
        }
        if (runtime.sessionBroken) {
            errors += Error.SESSION_BROKEN
        }
        if (runtime.currentSessionId != plan.deviceSessionId) {
            errors += Error.SESSION_CHANGED
        }
        if (runtime.readOnlyMutationLock) {
            errors += Error.READ_ONLY_MUTATION_LOCK
        }
        if (plan.target.isExpert && !runtime.expertModeEnabled) {
            errors += Error.EXPERT_MODE_DISABLED
        }
        if (runtime.bootloaderUnlocked == false) {
            errors += Error.BOOTLOADER_LOCKED
        } else if (runtime.bootloaderUnlocked == null) {
            warnings += "Bootloader unlocked state is unknown; FastbootProtocol must re-check it immediately before flash"
        }

        if (runtime.currentImageUri != plan.imageUri) {
            errors += Error.IMAGE_URI_CHANGED
        }
        if (runtime.currentImageDisplayName != plan.imageDisplayName) {
            errors += Error.IMAGE_NAME_CHANGED
        }
        if (runtime.currentImageSizeBytes != plan.imageSizeBytes) {
            errors += Error.IMAGE_SIZE_CHANGED
        }
        val runtimeSha = runtime.currentImageSha256.lowercase(Locale.US)
        if (!SHA256_PATTERN.matches(runtime.currentImageSha256) || runtimeSha != plan.imageSha256) {
            errors += Error.IMAGE_SHA256_CHANGED
        }

        val exactMatches = runtime.currentCandidates.filter { candidate ->
            candidate.target == plan.target &&
                candidate.basePartition == plan.basePartition &&
                candidate.partitionName == plan.partitionName &&
                candidate.slot == plan.slot
        }
        when {
            exactMatches.isEmpty() -> errors += Error.TARGET_NOT_FOUND
            exactMatches.size > 1 -> errors += Error.TARGET_AMBIGUOUS
        }
        val candidate = exactMatches.singleOrNull()
        if (candidate != null) {
            if (candidate.evidence == QuickFlashCandidateEvidence.UNCONFIRMED) {
                errors += Error.TARGET_NOT_CONFIRMED
            }
            if (candidate.evidence != plan.candidateEvidence) {
                // Evidence may be refreshed from inventory to point-query or vice versa,
                // but the concrete target identity must remain exact. Keep this visible.
                warnings += "Topology evidence source changed from ${plan.candidateEvidence} to ${candidate.evidence}; concrete target remained identical"
            }
        }

        val args = plan.fastbootArguments
        if (args.size != 2 || args[0] != "flash" || args[1] != plan.partitionName) {
            errors += Error.INVALID_COMMAND_SHAPE
        }
        if (runtime.currentCandidates.any { it.partitionName == plan.partitionName && it.target != plan.target }) {
            errors += Error.TARGET_CHANGED
        }

        if (errors.isNotEmpty()) {
            return Decision(errors = errors.toList(), warnings = warnings)
        }

        return Decision(
            authorization = Authorization(
                confirmationId = ticket.confirmationId,
                planFingerprint = ticket.planFingerprint,
                partitionName = plan.partitionName,
                fastbootArguments = args,
                commandCount = 1,
                retryAllowed = false,
                executionFingerprint = executionFingerprint(ticket)
            ),
            warnings = warnings
        )
    }

    private fun executionFingerprint(ticket: ConfirmationTicket): String {
        val input = "${ticket.confirmationId}|${ticket.planFingerprint}|quick-flash-mutation-v1"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/** In-memory one-shot registry. It is cleared whenever the USB transport session changes. */
class QuickFlashConfirmationRegistry {
    private val consumed = linkedSetOf<String>()

    @Synchronized
    fun consume(confirmationId: String): Boolean = consumed.add(confirmationId)

    @Synchronized
    fun clear() = consumed.clear()

    @Synchronized
    fun consumedCount(): Int = consumed.size
}
