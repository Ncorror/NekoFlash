package ru.forum.adbfastboottool

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * User-visible Quick Flash target. A target classifies intent; it never proves
 * that a concrete partition exists and never authorizes a Fastboot mutation.
 */
enum class QuickFlashTarget(
    val fixedBasePartition: String?,
    val visibility: Visibility
) {
    RECOVERY("recovery", Visibility.PRIMARY),
    BOOT("boot", Visibility.PRIMARY),
    INIT_BOOT("init_boot", Visibility.PRIMARY),
    VENDOR_BOOT("vendor_boot", Visibility.PRIMARY),
    DTBO("dtbo", Visibility.EXPERT),
    VBMETA("vbmeta", Visibility.EXPERT),
    VENDOR_KERNEL_BOOT("vendor_kernel_boot", Visibility.EXPERT),
    MANUAL(null, Visibility.EXPERT);

    enum class Visibility { PRIMARY, EXPERT }

    val isExpert: Boolean get() = visibility == Visibility.EXPERT

    companion object {
        val primaryTargets: List<QuickFlashTarget> = entries.filter { !it.isExpert }
        val expertTargets: List<QuickFlashTarget> = entries.filter { it.isExpert }
    }
}

/** One concrete partition selected for one future `fastboot flash` command. */
enum class QuickFlashSlot(val suffix: String) {
    UNSLOTTED(""),
    SLOT_A("_a"),
    SLOT_B("_b")
}

/** Read-only evidence that the concrete partition exists on the current device. */
enum class QuickFlashCandidateEvidence {
    UNCONFIRMED,
    INVENTORY,
    POINT_QUERY
}

/**
 * Candidate produced by the read-only topology slice.
 *
 * [basePartition] and [partitionName] are both explicit so the validator can
 * reject synthetic or inconsistent slot suffixes instead of repairing them.
 */
data class QuickFlashCandidate(
    val target: QuickFlashTarget,
    val basePartition: String,
    val partitionName: String,
    val slot: QuickFlashSlot,
    val evidence: QuickFlashCandidateEvidence
)

/** Input used to build a deterministic, confirmation-ready plan. */
data class QuickFlashPlanRequest(
    val deviceSessionId: String,
    val deviceDisplayName: String,
    val target: QuickFlashTarget,
    val selectedPartitionName: String,
    val candidates: List<QuickFlashCandidate>,
    val imageUri: String,
    val imageDisplayName: String,
    val imageSizeBytes: Long,
    val imageSha256: String,
    val expertModeEnabled: Boolean = false,
    val manualPartitionConfirmation: String? = null
)

/**
 * Immutable plan shown at confirmation time and later passed to the mutation gate.
 *
 * The plan intentionally contains exactly one concrete partition. A/B "both"
 * is represented by two separately confirmed plans, never by one implicit retry
 * or multi-mutation command.
 */
data class QuickFlashPlan(
    val schemaVersion: Int,
    val deviceSessionId: String,
    val deviceDisplayName: String,
    val target: QuickFlashTarget,
    val basePartition: String,
    val partitionName: String,
    val slot: QuickFlashSlot,
    val candidateEvidence: QuickFlashCandidateEvidence,
    val imageUri: String,
    val imageDisplayName: String,
    val imageSizeBytes: Long,
    val imageSha256: String,
    val expertModeConfirmed: Boolean,
    val manualPartitionConfirmation: String?
) {
    /** Arguments only; no shell string and no hidden Fastboot flags. */
    val fastbootArguments: List<String> get() = listOf("flash", partitionName)

    val commandPreview: String
        get() = "fastboot flash $partitionName <verified-image>"

    fun encodeForConfirmation(): String = QuickFlashPlanCodec.encode(this)

    fun confirmationFingerprint(): String = QuickFlashPlanCodec.fingerprint(this)

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

enum class QuickFlashPlanError {
    UNSUPPORTED_SCHEMA,
    INVALID_DEVICE_SESSION,
    INVALID_DEVICE_LABEL,
    EXPERT_MODE_REQUIRED,
    INVALID_PARTITION,
    TOO_MANY_CANDIDATES,
    TARGET_NOT_FOUND,
    TARGET_AMBIGUOUS,
    CANDIDATE_TARGET_MISMATCH,
    CANDIDATE_BASE_MISMATCH,
    CANDIDATE_SLOT_MISMATCH,
    CANDIDATE_NOT_CONFIRMED,
    MANUAL_CONFIRMATION_REQUIRED,
    MANUAL_TARGET_DUPLICATES_DEFINED_TARGET,
    MANUAL_TARGET_FORBIDDEN,
    INVALID_IMAGE_URI,
    INVALID_IMAGE_NAME,
    INVALID_IMAGE_SIZE,
    INVALID_IMAGE_SHA256
}

data class QuickFlashPlanValidation(
    val plan: QuickFlashPlan? = null,
    val errors: List<QuickFlashPlanError> = emptyList()
) {
    val canProceed: Boolean get() = plan != null && errors.isEmpty()
}

/** Pure, fail-closed validator for Slice A. */
object QuickFlashPlanValidator {
    private const val MAX_DEVICE_SESSION_LENGTH = 256
    private const val MAX_DEVICE_LABEL_LENGTH = 256
    private const val MAX_URI_LENGTH = 4096
    private const val MAX_IMAGE_NAME_LENGTH = 256
    private const val MAX_CANDIDATES = 64

    private val PARTITION_PATTERN = Regex("^[a-z0-9._-]{1,64}$")
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    private val DEFINED_BASE_PARTITIONS = QuickFlashTarget.entries
        .mapNotNull { it.fixedBasePartition }
        .toSet()

    /** V6 manual mode must not become a hidden full-ROM/radio/bootloader flasher. */
    private val FORBIDDEN_MANUAL_BASE_PARTITIONS = setOf(
        "system", "system_ext", "vendor", "product", "odm", "super",
        "userdata", "metadata", "cache", "cust", "persist", "misc", "frp",
        "modem", "radio", "bootloader", "abl", "xbl", "xbl_config",
        "tz", "hyp", "rpm", "devinfo"
    )

    fun validate(request: QuickFlashPlanRequest): QuickFlashPlanValidation {
        val errors = linkedSetOf<QuickFlashPlanError>()

        validateCommon(
            schemaVersion = QuickFlashPlan.SCHEMA_VERSION,
            deviceSessionId = request.deviceSessionId,
            deviceDisplayName = request.deviceDisplayName,
            target = request.target,
            expertModeConfirmed = request.expertModeEnabled,
            basePartition = null,
            partitionName = request.selectedPartitionName,
            slot = null,
            candidateEvidence = null,
            imageUri = request.imageUri,
            imageDisplayName = request.imageDisplayName,
            imageSizeBytes = request.imageSizeBytes,
            imageSha256 = request.imageSha256,
            manualPartitionConfirmation = request.manualPartitionConfirmation,
            errors = errors
        )

        if (request.candidates.size > MAX_CANDIDATES) {
            errors += QuickFlashPlanError.TOO_MANY_CANDIDATES
        }

        val matches = request.candidates.filter {
            it.partitionName == request.selectedPartitionName
        }
        when {
            matches.isEmpty() -> errors += QuickFlashPlanError.TARGET_NOT_FOUND
            matches.size > 1 -> errors += QuickFlashPlanError.TARGET_AMBIGUOUS
        }

        val candidate = matches.singleOrNull()
        if (candidate != null) {
            validateCandidate(candidate, request.target, errors)
            validateManualTarget(
                target = request.target,
                basePartition = candidate.basePartition,
                partitionName = candidate.partitionName,
                manualPartitionConfirmation = request.manualPartitionConfirmation,
                errors = errors
            )
        }

        if (errors.isNotEmpty() || candidate == null) {
            return QuickFlashPlanValidation(errors = errors.toList())
        }

        val plan = QuickFlashPlan(
            schemaVersion = QuickFlashPlan.SCHEMA_VERSION,
            deviceSessionId = request.deviceSessionId,
            deviceDisplayName = request.deviceDisplayName,
            target = request.target,
            basePartition = candidate.basePartition,
            partitionName = candidate.partitionName,
            slot = candidate.slot,
            candidateEvidence = candidate.evidence,
            imageUri = request.imageUri,
            imageDisplayName = request.imageDisplayName,
            imageSizeBytes = request.imageSizeBytes,
            imageSha256 = request.imageSha256.lowercase(Locale.US),
            expertModeConfirmed = request.expertModeEnabled,
            manualPartitionConfirmation = request.manualPartitionConfirmation
        )

        return validatePlan(plan)
    }

    /** Structural validation used after decoding a confirmation payload. */
    fun validatePlan(plan: QuickFlashPlan): QuickFlashPlanValidation {
        val errors = linkedSetOf<QuickFlashPlanError>()
        validateCommon(
            schemaVersion = plan.schemaVersion,
            deviceSessionId = plan.deviceSessionId,
            deviceDisplayName = plan.deviceDisplayName,
            target = plan.target,
            expertModeConfirmed = plan.expertModeConfirmed,
            basePartition = plan.basePartition,
            partitionName = plan.partitionName,
            slot = plan.slot,
            candidateEvidence = plan.candidateEvidence,
            imageUri = plan.imageUri,
            imageDisplayName = plan.imageDisplayName,
            imageSizeBytes = plan.imageSizeBytes,
            imageSha256 = plan.imageSha256,
            manualPartitionConfirmation = plan.manualPartitionConfirmation,
            errors = errors
        )
        validateManualTarget(
            target = plan.target,
            basePartition = plan.basePartition,
            partitionName = plan.partitionName,
            manualPartitionConfirmation = plan.manualPartitionConfirmation,
            errors = errors
        )
        return if (errors.isEmpty()) {
            QuickFlashPlanValidation(plan = plan)
        } else {
            QuickFlashPlanValidation(errors = errors.toList())
        }
    }

    private fun validateCommon(
        schemaVersion: Int,
        deviceSessionId: String,
        deviceDisplayName: String,
        target: QuickFlashTarget,
        expertModeConfirmed: Boolean,
        basePartition: String?,
        partitionName: String,
        slot: QuickFlashSlot?,
        candidateEvidence: QuickFlashCandidateEvidence?,
        imageUri: String,
        imageDisplayName: String,
        imageSizeBytes: Long,
        imageSha256: String,
        manualPartitionConfirmation: String?,
        errors: MutableSet<QuickFlashPlanError>
    ) {
        if (schemaVersion != QuickFlashPlan.SCHEMA_VERSION) {
            errors += QuickFlashPlanError.UNSUPPORTED_SCHEMA
        }
        if (!isBoundedText(deviceSessionId, MAX_DEVICE_SESSION_LENGTH)) {
            errors += QuickFlashPlanError.INVALID_DEVICE_SESSION
        }
        if (!isBoundedText(deviceDisplayName, MAX_DEVICE_LABEL_LENGTH)) {
            errors += QuickFlashPlanError.INVALID_DEVICE_LABEL
        }
        if (target.isExpert && !expertModeConfirmed) {
            errors += QuickFlashPlanError.EXPERT_MODE_REQUIRED
        }
        if (!isCanonicalPartition(partitionName)) {
            errors += QuickFlashPlanError.INVALID_PARTITION
        }
        if (!isBoundedText(imageUri, MAX_URI_LENGTH)) {
            errors += QuickFlashPlanError.INVALID_IMAGE_URI
        }
        if (!isBoundedText(imageDisplayName, MAX_IMAGE_NAME_LENGTH)) {
            errors += QuickFlashPlanError.INVALID_IMAGE_NAME
        }
        if (imageSizeBytes <= 0L) {
            errors += QuickFlashPlanError.INVALID_IMAGE_SIZE
        }
        if (!SHA256_PATTERN.matches(imageSha256)) {
            errors += QuickFlashPlanError.INVALID_IMAGE_SHA256
        }

        if (basePartition != null && slot != null && candidateEvidence != null) {
            if (!isCanonicalPartition(basePartition)) {
                errors += QuickFlashPlanError.INVALID_PARTITION
            }
            val fixedBase = target.fixedBasePartition
            if (fixedBase != null && basePartition != fixedBase) {
                errors += QuickFlashPlanError.CANDIDATE_BASE_MISMATCH
            }
            if (partitionName != expectedPartitionName(basePartition, slot)) {
                errors += QuickFlashPlanError.CANDIDATE_SLOT_MISMATCH
            }
            if (candidateEvidence == QuickFlashCandidateEvidence.UNCONFIRMED) {
                errors += QuickFlashPlanError.CANDIDATE_NOT_CONFIRMED
            }
        }

        if (target == QuickFlashTarget.MANUAL && manualPartitionConfirmation != partitionName) {
            errors += QuickFlashPlanError.MANUAL_CONFIRMATION_REQUIRED
        }
    }

    private fun validateCandidate(
        candidate: QuickFlashCandidate,
        requestedTarget: QuickFlashTarget,
        errors: MutableSet<QuickFlashPlanError>
    ) {
        if (candidate.target != requestedTarget) {
            errors += QuickFlashPlanError.CANDIDATE_TARGET_MISMATCH
        }
        if (!isCanonicalPartition(candidate.basePartition) ||
            !isCanonicalPartition(candidate.partitionName)
        ) {
            errors += QuickFlashPlanError.INVALID_PARTITION
        }
        val fixedBase = requestedTarget.fixedBasePartition
        if (fixedBase != null && candidate.basePartition != fixedBase) {
            errors += QuickFlashPlanError.CANDIDATE_BASE_MISMATCH
        }
        if (candidate.partitionName != expectedPartitionName(candidate.basePartition, candidate.slot)) {
            errors += QuickFlashPlanError.CANDIDATE_SLOT_MISMATCH
        }
        if (candidate.evidence == QuickFlashCandidateEvidence.UNCONFIRMED) {
            errors += QuickFlashPlanError.CANDIDATE_NOT_CONFIRMED
        }
    }

    private fun validateManualTarget(
        target: QuickFlashTarget,
        basePartition: String,
        partitionName: String,
        manualPartitionConfirmation: String?,
        errors: MutableSet<QuickFlashPlanError>
    ) {
        if (target != QuickFlashTarget.MANUAL) return
        if (manualPartitionConfirmation != partitionName) {
            errors += QuickFlashPlanError.MANUAL_CONFIRMATION_REQUIRED
        }
        if (basePartition in DEFINED_BASE_PARTITIONS) {
            errors += QuickFlashPlanError.MANUAL_TARGET_DUPLICATES_DEFINED_TARGET
        }
        if (basePartition in FORBIDDEN_MANUAL_BASE_PARTITIONS) {
            errors += QuickFlashPlanError.MANUAL_TARGET_FORBIDDEN
        }
    }

    private fun expectedPartitionName(basePartition: String, slot: QuickFlashSlot): String =
        basePartition + slot.suffix

    private fun isCanonicalPartition(value: String): Boolean =
        PARTITION_PATTERN.matches(value) && value == value.trim().lowercase(Locale.US)

    private fun isBoundedText(value: String, maxLength: Int): Boolean =
        value.isNotBlank() && value.length <= maxLength && value == value.trim()
}

/** Stable, dependency-free confirmation serialization. */
object QuickFlashPlanCodec {
    private const val SEPARATOR = '|'
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(plan: QuickFlashPlan): String = listOf(
        plan.schemaVersion.toString(),
        encodeField(plan.deviceSessionId),
        encodeField(plan.deviceDisplayName),
        plan.target.name,
        encodeField(plan.basePartition),
        encodeField(plan.partitionName),
        plan.slot.name,
        plan.candidateEvidence.name,
        encodeField(plan.imageUri),
        encodeField(plan.imageDisplayName),
        plan.imageSizeBytes.toString(),
        plan.imageSha256.lowercase(Locale.US),
        plan.expertModeConfirmed.toString(),
        encodeField(plan.manualPartitionConfirmation.orEmpty())
    ).joinToString(SEPARATOR.toString())

    fun decode(encoded: String): QuickFlashPlan? {
        val fields = encoded.split(SEPARATOR)
        if (fields.size != 14) return null
        val plan = runCatching {
            QuickFlashPlan(
                schemaVersion = fields[0].toInt(),
                deviceSessionId = decodeField(fields[1]),
                deviceDisplayName = decodeField(fields[2]),
                target = QuickFlashTarget.valueOf(fields[3]),
                basePartition = decodeField(fields[4]),
                partitionName = decodeField(fields[5]),
                slot = QuickFlashSlot.valueOf(fields[6]),
                candidateEvidence = QuickFlashCandidateEvidence.valueOf(fields[7]),
                imageUri = decodeField(fields[8]),
                imageDisplayName = decodeField(fields[9]),
                imageSizeBytes = fields[10].toLong(),
                imageSha256 = fields[11].lowercase(Locale.US),
                expertModeConfirmed = fields[12].toBooleanStrict(),
                manualPartitionConfirmation = decodeField(fields[13]).ifEmpty { null }
            )
        }.getOrNull() ?: return null

        return QuickFlashPlanValidator.validatePlan(plan).plan
    }

    fun fingerprint(plan: QuickFlashPlan): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encode(plan).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String =
        decoder.decode(value).toString(Charsets.UTF_8)
}
