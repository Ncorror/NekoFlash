package ru.forum.adbfastboottool

private fun requireUi(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    requireUi(
        QuickFlashUiPolicy.primaryTargets == listOf(
            QuickFlashTarget.RECOVERY,
            QuickFlashTarget.BOOT,
            QuickFlashTarget.INIT_BOOT,
            QuickFlashTarget.VENDOR_BOOT
        ),
        "Recovery must remain the first primary target"
    )
    requireUi(
        QuickFlashUiPolicy.expertTargets == listOf(
            QuickFlashTarget.DTBO,
            QuickFlashTarget.VBMETA,
            QuickFlashTarget.VENDOR_KERNEL_BOOT,
            QuickFlashTarget.MANUAL
        ),
        "Expert target order changed"
    )
    requireUi(
        QuickFlashUiPolicy.visibleTargets(false) == QuickFlashUiPolicy.primaryTargets,
        "Expert targets must be hidden by default"
    )
    val allVisible = QuickFlashUiPolicy.visibleTargets(true)
    requireUi(allVisible.distinct().size == allVisible.size, "Target list contains duplicates")
    requireUi(allVisible.toSet() == QuickFlashTarget.entries.toSet(), "Visible target set is incomplete")
    requireUi(!QuickFlashUiPolicy.isVisible(QuickFlashTarget.MANUAL, false), "Manual target leaked without Expert Mode")
    requireUi(QuickFlashUiPolicy.isVisible(QuickFlashTarget.MANUAL, true), "Manual target missing in Expert Mode")
    requireUi(!QuickFlashUiPolicy.legacyQueueVisible, "Legacy multi-flash queue must stay hidden")
    println("QuickFlashUiPolicyTest: PASS")
}
