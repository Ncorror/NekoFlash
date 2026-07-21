package ru.forum.adbfastboottool

private typealias R = FastbootSlotResolver.RequestedSlot

private fun expectBlocked(r: FastbootSlotResolver.Resolution) {
    check(!r.canProceed) { "Expected blocked resolution, got $r" }
}

fun main() {
    // slot-count alone is not proof that a concrete partition is slotted.
    expectBlocked(FastbootSlotResolver.resolve(
        "recovery", R.BOTH,
        FastbootSlotResolver.Evidence(slotCount = 2, currentSlot = "b")
    ))

    // has-slot=yes confirms the A/B layout.
    val both = FastbootSlotResolver.resolve(
        "boot", R.BOTH,
        FastbootSlotResolver.Evidence(slotCount = 2, currentSlot = "b", hasSlot = true)
    )
    check(both.canProceed && both.targets == listOf("boot_a", "boot_b")) { both }

    // Active slot is resolved to a concrete partition, not an unsuffixed guess.
    val activeB = FastbootSlotResolver.resolve(
        "boot", R.ACTIVE,
        FastbootSlotResolver.Evidence(slotCount = 2, currentSlot = "b", hasSlot = true)
    )
    check(activeB.targets == listOf("boot_b")) { activeB }

    // has-slot=no forces the unslotted target and blocks explicit A/B writes.
    val unslotted = FastbootSlotResolver.resolve(
        "recovery", R.ACTIVE,
        FastbootSlotResolver.Evidence(slotCount = 2, currentSlot = "b", hasSlot = false, unsuffixedExists = true)
    )
    check(unslotted.targets == listOf("recovery")) { unslotted }
    expectBlocked(FastbootSlotResolver.resolve(
        "recovery", R.SLOT_A,
        FastbootSlotResolver.Evidence(slotCount = 2, hasSlot = false, unsuffixedExists = true)
    ))

    // Concrete partition probes can recover from unsupported has-slot.
    val probed = FastbootSlotResolver.resolve(
        "recovery", R.BOTH,
        FastbootSlotResolver.Evidence(
            slotCount = 2,
            currentSlot = "a",
            hasSlot = null,
            slotAExists = true,
            slotBExists = true
        )
    )
    check(probed.targets == listOf("recovery_a", "recovery_b")) { probed }

    // A single concrete slot can be targeted explicitly without pretending BOTH exists.
    val onlyA = FastbootSlotResolver.Evidence(slotCount = 2, currentSlot = "a", slotAExists = true)
    check(FastbootSlotResolver.resolve("vendor_boot", R.SLOT_A, onlyA).targets == listOf("vendor_boot_a"))
    expectBlocked(FastbootSlotResolver.resolve("vendor_boot", R.SLOT_B, onlyA))
    expectBlocked(FastbootSlotResolver.resolve("vendor_boot", R.BOTH, onlyA))

    // Explicit suffix with AUTO remains explicit.
    val explicit = FastbootSlotResolver.resolve(
        "boot_a", R.AUTO,
        FastbootSlotResolver.Evidence(slotCount = 2, hasSlot = true)
    )
    check(explicit.targets == listOf("boot_a")) { explicit }

    check(FastbootSlotResolver.explicitSlotFromFileName("boot_a.img") == "a")
    check(FastbootSlotResolver.explicitSlotFromFileName("boot_b.img.lz4") == "b")
    check(FastbootSlotResolver.explicitSlotFromFileName("boot.img") == null)
    check(FastbootSlotResolver.validateExplicitFileSlot("boot_a.img", listOf("boot_a")) == null)
    check(FastbootSlotResolver.validateExplicitFileSlot("boot_a.img", listOf("boot_b")) != null)
    check(FastbootSlotResolver.validateExplicitFileSlot("boot_a.img", listOf("boot_a", "boot_b")) != null)

    v567TopologyRegression()

    println("FASTBOOT SLOT RESOLVER TESTS: OK")
}

private fun v567TopologyRegression() {
    val vayuRecovery = FastbootSlotResolver.Evidence(
        slotCount = null,
        currentSlot = null,
        hasSlot = null,
        unsuffixedExists = true,
        slotAExists = null,
        slotBExists = null,
        sessionBroken = false
    )
    check(FastbootSlotResolver.detectTopology(vayuRecovery) == FastbootSlotResolver.SlotTopology.SINGLE_SLOT)
    val resolved = FastbootSlotResolver.resolve("recovery", R.ACTIVE, vayuRecovery)
    check(resolved.canProceed && resolved.targets == listOf("recovery") && resolved.topology == FastbootSlotResolver.SlotTopology.SINGLE_SLOT) { resolved }

    val unstable = FastbootSlotResolver.Evidence(sessionBroken = true)
    check(FastbootSlotResolver.detectTopology(unstable) == FastbootSlotResolver.SlotTopology.UNKNOWN_UNSTABLE)
    expectBlocked(FastbootSlotResolver.resolve("recovery", R.ACTIVE, unstable))

    val onyxRecovery = FastbootSlotResolver.Evidence(
        slotCount = 2,
        currentSlot = "b",
        hasSlot = null,
        unsuffixedExists = null,
        slotAExists = true,
        slotBExists = true
    )
    check(FastbootSlotResolver.detectTopology(onyxRecovery) == FastbootSlotResolver.SlotTopology.AB)
    check(FastbootSlotResolver.resolve("recovery", R.ACTIVE, onyxRecovery).targets == listOf("recovery_b"))
}
