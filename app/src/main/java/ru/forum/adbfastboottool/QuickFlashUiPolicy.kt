package ru.forum.adbfastboottool

/** Pure ordering/visibility contract for the Recovery-first Quick Flash screen. */
object QuickFlashUiPolicy {
    val primaryTargets: List<QuickFlashTarget> = listOf(
        QuickFlashTarget.RECOVERY,
        QuickFlashTarget.BOOT,
        QuickFlashTarget.INIT_BOOT,
        QuickFlashTarget.VENDOR_BOOT
    )

    val expertTargets: List<QuickFlashTarget> = listOf(
        QuickFlashTarget.DTBO,
        QuickFlashTarget.VBMETA,
        QuickFlashTarget.VENDOR_KERNEL_BOOT,
        QuickFlashTarget.MANUAL
    )

    /** Multi-item queue is not part of Recovery-first Quick Flash. */
    const val legacyQueueVisible: Boolean = false

    fun visibleTargets(expertModeEnabled: Boolean): List<QuickFlashTarget> =
        if (expertModeEnabled) primaryTargets + expertTargets else primaryTargets

    fun isVisible(target: QuickFlashTarget, expertModeEnabled: Boolean): Boolean =
        target in visibleTargets(expertModeEnabled)
}
