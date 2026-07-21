package ru.forum.adbfastboottool

private fun checkDecision(value: Boolean, message: String) { if (!value) error(message) }

fun main() {
    checkDecision(FastbootMutationSafety.parseSnapshotState("none") == FastbootMutationSafety.SnapshotState.NONE, "none parse")
    checkDecision(FastbootMutationSafety.parseSnapshotState("merging") == FastbootMutationSafety.SnapshotState.MERGING, "merging parse")
    checkDecision(FastbootMutationSafety.parseSnapshotState("snapshotted") == FastbootMutationSafety.SnapshotState.SNAPSHOTTED, "snapshotted parse")
    checkDecision(FastbootMutationSafety.parseSnapshotState(null) == FastbootMutationSafety.SnapshotState.UNKNOWN, "unknown parse")
    checkDecision(FastbootMutationSafety.likelyLogicalPartition("system_a"), "system_a logical fallback")
    checkDecision(!FastbootMutationSafety.likelyLogicalPartition("recovery_a"), "recovery_a must remain physical fallback")

    val mergeFlash = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.FLASH,
            snapshotState = FastbootMutationSafety.SnapshotState.MERGING,
            partition = "boot_a",
            targetIsLogical = false
        )
    )
    checkDecision(!mergeFlash.allowed, "MERGING must block flash")

    val snapLogical = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.FLASH,
            snapshotState = FastbootMutationSafety.SnapshotState.SNAPSHOTTED,
            partition = "system_a",
            targetIsLogical = true
        )
    )
    checkDecision(!snapLogical.allowed, "SNAPSHOTTED must block logical flash")

    val snapPhysical = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.FLASH,
            snapshotState = FastbootMutationSafety.SnapshotState.SNAPSHOTTED,
            partition = "boot_a",
            targetIsLogical = false
        )
    )
    checkDecision(snapPhysical.allowed && snapPhysical.warnings.isNotEmpty(), "SNAPSHOTTED physical flash should warn")

    val unbootable = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.SET_ACTIVE,
            snapshotState = FastbootMutationSafety.SnapshotState.NONE,
            slotHealth = FastbootMutationSafety.SlotHealth("b", successful = false, unbootable = true, retryCount = 0)
        )
    )
    checkDecision(!unbootable.allowed, "unbootable slot must block set_active")

    val healthy = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.SET_ACTIVE,
            snapshotState = FastbootMutationSafety.SnapshotState.NONE,
            slotHealth = FastbootMutationSafety.SlotHealth("b", successful = true, unbootable = false, retryCount = 7)
        )
    )
    checkDecision(healthy.allowed, "healthy slot must allow set_active")

    val unknownSnapshot = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.ERASE,
            snapshotState = FastbootMutationSafety.SnapshotState.UNKNOWN,
            partition = "userdata"
        )
    )
    checkDecision(unknownSnapshot.allowed && unknownSnapshot.warnings.isNotEmpty(), "unknown snapshot must be explicit warning")

    checkDecision(
        FastbootMutationSafety.parseBootloaderStateTransition("flashing unlock") ==
            FastbootMutationSafety.BootloaderStateTransition.UNLOCK,
        "flashing unlock transition parse"
    )
    checkDecision(
        FastbootMutationSafety.parseBootloaderStateTransition("oem lock") ==
            FastbootMutationSafety.BootloaderStateTransition.LOCK,
        "oem lock transition parse"
    )
    checkDecision(
        FastbootMutationSafety.parseBootloaderStateTransition("flashing:unlock_critical") ==
            FastbootMutationSafety.BootloaderStateTransition.UNLOCK_CRITICAL,
        "critical unlock transition parse"
    )

    val snapshotCancel = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.SNAPSHOT_CANCEL,
            snapshotState = FastbootMutationSafety.SnapshotState.SNAPSHOTTED
        )
    )
    checkDecision(snapshotCancel.allowed && snapshotCancel.warnings.isNotEmpty(), "SNAPSHOTTED must allow explicit cancel with warning")

    val cancelDuringMerge = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.SNAPSHOT_CANCEL,
            snapshotState = FastbootMutationSafety.SnapshotState.MERGING
        )
    )
    checkDecision(!cancelDuringMerge.allowed, "MERGING must block snapshot cancel")

    val cancelUnknown = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.SNAPSHOT_CANCEL,
            snapshotState = FastbootMutationSafety.SnapshotState.UNKNOWN
        )
    )
    checkDecision(!cancelUnknown.allowed, "UNKNOWN must block snapshot cancel")

    val mergeControl = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.SNAPSHOT_MERGE,
            snapshotState = FastbootMutationSafety.SnapshotState.MERGING
        )
    )
    checkDecision(mergeControl.allowed, "MERGING must allow explicit merge completion request")

    val mergeFromSnapshotted = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.SNAPSHOT_MERGE,
            snapshotState = FastbootMutationSafety.SnapshotState.SNAPSHOTTED
        )
    )
    checkDecision(!mergeFromSnapshotted.allowed, "SNAPSHOTTED must block merge control")

    val relock = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.BOOTLOADER_LOCK,
            snapshotState = FastbootMutationSafety.SnapshotState.NONE
        )
    )
    checkDecision(!relock.allowed, "bootloader relock must remain blocked without verified-stock proof")

    val critical = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.BOOTLOADER_CRITICAL_STATE,
            snapshotState = FastbootMutationSafety.SnapshotState.NONE
        )
    )
    checkDecision(!critical.allowed, "critical lock-state mutation must remain blocked")

    val unlock = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.BOOTLOADER_UNLOCK,
            snapshotState = FastbootMutationSafety.SnapshotState.NONE
        )
    )
    checkDecision(unlock.allowed && unlock.warnings.isNotEmpty(), "unlock with clean snapshot state should pass with warning")

    val snapshottedOemControl = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.OEM_DESTRUCTIVE_CONTROL,
            snapshotState = FastbootMutationSafety.SnapshotState.SNAPSHOTTED
        )
    )
    checkDecision(!snapshottedOemControl.allowed, "SNAPSHOTTED must block opaque destructive OEM control")

    val snapshottedGsiControl = FastbootMutationSafety.evaluate(
        FastbootMutationSafety.Context(
            kind = FastbootMutationSafety.MutationKind.GSI_CONTROL,
            snapshotState = FastbootMutationSafety.SnapshotState.SNAPSHOTTED
        )
    )
    checkDecision(!snapshottedGsiControl.allowed, "SNAPSHOTTED must block GSI control mutation")

    println("FASTBOOT MUTATION SAFETY TESTS: OK")
}
