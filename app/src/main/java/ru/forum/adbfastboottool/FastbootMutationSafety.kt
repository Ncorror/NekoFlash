package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Pure safety policy for destructive Fastboot mutations.
 *
 * This object never talks to USB. FastbootProtocol collects fresh device evidence
 * and passes it here so every mutation path uses the same deterministic rules.
 */
object FastbootMutationSafety {
    enum class SnapshotState { NONE, SNAPSHOTTED, MERGING, UNKNOWN }

    enum class MutationKind {
        FLASH,
        ERASE,
        FORMAT,
        SET_ACTIVE,
        UPDATE_SUPER,
        LOGICAL_PARTITION,
        BOOTLOADER_UNLOCK,
        BOOTLOADER_LOCK,
        BOOTLOADER_CRITICAL_STATE,
        OEM_DESTRUCTIVE_CONTROL,
        GSI_CONTROL,
        SNAPSHOT_CANCEL,
        SNAPSHOT_MERGE
    }

    enum class BootloaderStateTransition(val expectedUnlocked: Boolean?) {
        UNLOCK(true),
        LOCK(false),
        UNLOCK_CRITICAL(null),
        LOCK_CRITICAL(null)
    }

    data class SlotHealth(
        val slot: String,
        val successful: Boolean? = null,
        val unbootable: Boolean? = null,
        val retryCount: Int? = null
    )

    data class Context(
        val kind: MutationKind,
        val snapshotState: SnapshotState,
        val partition: String? = null,
        val targetIsLogical: Boolean? = null,
        val slotHealth: SlotHealth? = null
    )

    data class Decision(
        val allowed: Boolean,
        val blockedReason: String? = null,
        val warnings: List<String> = emptyList()
    )

    fun parseSnapshotState(raw: String?): SnapshotState = when (raw?.trim()?.lowercase(Locale.US)) {
        "none", "cancelled" -> SnapshotState.NONE
        "snapshotted" -> SnapshotState.SNAPSHOTTED
        "merging" -> SnapshotState.MERGING
        else -> SnapshotState.UNKNOWN
    }

    fun parseFastbootBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase(Locale.US)) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }

    fun parseBootloaderStateTransition(command: String): BootloaderStateTransition? {
        val clean = normalizeControlCommand(command)
        return when (clean) {
            "flashing unlock", "oem unlock" -> BootloaderStateTransition.UNLOCK
            "flashing lock", "oem lock" -> BootloaderStateTransition.LOCK
            "flashing unlock_critical", "flashing unlock critical" -> BootloaderStateTransition.UNLOCK_CRITICAL
            "flashing lock_critical", "flashing lock critical" -> BootloaderStateTransition.LOCK_CRITICAL
            else -> null
        }
    }

    fun likelyLogicalPartition(partition: String): Boolean {
        val clean = partition.trim().lowercase(Locale.US)
            .removeSuffix("_a").removeSuffix("_b").removeSuffix("_ab")
        return clean in LIKELY_LOGICAL_PARTITIONS ||
            clean.startsWith("system") || clean.startsWith("vendor") ||
            clean.startsWith("product") || clean.startsWith("odm")
    }

    fun evaluate(context: Context): Decision {
        val warnings = mutableListOf<String>()

        when (context.kind) {
            MutationKind.SNAPSHOT_CANCEL -> return evaluateSnapshotCancel(context.snapshotState)
            MutationKind.SNAPSHOT_MERGE -> return evaluateSnapshotMerge(context.snapshotState)
            MutationKind.BOOTLOADER_LOCK -> return Decision(
                allowed = false,
                blockedReason = "Bootloader relock is blocked until NekoFlash can prove stock/verified boot state before relocking"
            )
            MutationKind.BOOTLOADER_CRITICAL_STATE -> return Decision(
                allowed = false,
                blockedReason = "Critical bootloader lock-state changes are blocked until an exact post-command state probe is implemented"
            )
            else -> Unit
        }

        when (context.snapshotState) {
            SnapshotState.MERGING -> return Decision(
                allowed = false,
                blockedReason = "Virtual A/B snapshot merge active: destructive Fastboot mutation is blocked"
            )

            SnapshotState.SNAPSHOTTED -> {
                val blocksSnapshot = context.kind in setOf(
                    MutationKind.SET_ACTIVE,
                    MutationKind.UPDATE_SUPER,
                    MutationKind.LOGICAL_PARTITION,
                    MutationKind.BOOTLOADER_UNLOCK,
                    MutationKind.OEM_DESTRUCTIVE_CONTROL,
                    MutationKind.GSI_CONTROL
                ) || context.targetIsLogical == true
                if (blocksSnapshot) {
                    return Decision(
                        allowed = false,
                        blockedReason = "Virtual A/B snapshots are active: slot/logical/control-plane mutation is blocked"
                    )
                }
                warnings += "snapshot-update-status=snapshotted; physical-partition mutation proceeds with caution"
            }

            SnapshotState.UNKNOWN -> warnings +=
                "snapshot-update-status is unavailable/unknown; result is not treated as proof that no Virtual A/B update is active"

            SnapshotState.NONE -> Unit
        }

        if (context.kind == MutationKind.BOOTLOADER_UNLOCK) {
            warnings += "Bootloader unlock changes persistent device state and may trigger a factory data reset; final success requires reconnect verification"
        }

        if (context.kind == MutationKind.SET_ACTIVE) {
            val health = context.slotHealth
                ?: return Decision(false, "Target slot health was not collected before set_active", warnings)
            val slot = health.slot.trim().removePrefix("_").lowercase(Locale.US)
            if (slot !in setOf("a", "b")) {
                return Decision(false, "Unsupported target slot: ${health.slot}", warnings)
            }
            if (health.unbootable == true) {
                return Decision(false, "Target slot $slot is marked unbootable=yes", warnings)
            }
            if (health.successful == false) {
                warnings += "Target slot $slot is not marked successful"
            }
            if (health.retryCount == 0) {
                warnings += "Target slot $slot reports retry-count=0"
            }
            if (health.unbootable == null) {
                warnings += "slot-unbootable:$slot is unknown"
            }
        }

        return Decision(true, warnings = warnings)
    }

    private fun evaluateSnapshotCancel(snapshotState: SnapshotState): Decision = when (snapshotState) {
        SnapshotState.SNAPSHOTTED -> Decision(
            allowed = true,
            warnings = listOf(
                "snapshot-update cancel discards the pending snapshot update and may leave the device unbootable until it is reflashed"
            )
        )
        SnapshotState.MERGING -> Decision(
            allowed = false,
            blockedReason = "Snapshot merge is already active; NekoFlash blocks cancellation during merge"
        )
        SnapshotState.NONE -> Decision(
            allowed = false,
            blockedReason = "No pending Virtual A/B snapshot update is present to cancel"
        )
        SnapshotState.UNKNOWN -> Decision(
            allowed = false,
            blockedReason = "Snapshot state is unknown; cancellation is blocked because the target state cannot be proven"
        )
    }

    private fun evaluateSnapshotMerge(snapshotState: SnapshotState): Decision = when (snapshotState) {
        SnapshotState.MERGING -> Decision(
            allowed = true,
            warnings = listOf("snapshot-update merge will request completion of the active merge")
        )
        SnapshotState.SNAPSHOTTED -> Decision(
            allowed = false,
            blockedReason = "Snapshot update is not in the merging phase"
        )
        SnapshotState.NONE -> Decision(
            allowed = false,
            blockedReason = "No active Virtual A/B snapshot merge is present"
        )
        SnapshotState.UNKNOWN -> Decision(
            allowed = false,
            blockedReason = "Snapshot state is unknown; merge control is blocked"
        )
    }

    private fun normalizeControlCommand(command: String): String =
        command.trim().lowercase(Locale.US)
            .replace(':', ' ')
            .replace(Regex("\\s+"), " ")

    private val LIKELY_LOGICAL_PARTITIONS = setOf(
        "system", "system_ext", "product", "vendor", "odm", "cust", "mi_ext",
        "vendor_dlkm", "odm_dlkm", "system_dlkm", "product_dlkm",
        "vendor_bootconfig", "system_other", "my_product", "my_company", "my_region"
    )
}
