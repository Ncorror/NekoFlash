package ru.forum.adbfastboottool

/** Pure navigation policy for the persistent Console Bottom Sheet. */
object ConsoleSheetPolicy {
    /**
     * Calculates the body height that is actually visible inside a full-height
     * persistent BottomSheet. Material moves the whole sheet down for collapsed
     * and half-expanded states, so bottom-anchored children must be constrained
     * to the visible slice instead of the sheet's full measured height.
     */
    fun visibleBodyHeight(
        rootHeightPx: Int,
        sheetTopPx: Int,
        chromeHeightPx: Int,
    ): Int = (
        rootHeightPx.coerceAtLeast(0) -
            sheetTopPx.coerceAtLeast(0) -
            chromeHeightPx.coerceAtLeast(0)
        ).coerceAtLeast(0)

    enum class StableState {
        COLLAPSED,
        HALF_EXPANDED,
        EXPANDED,
        TRANSIENT,
    }

    enum class BackAction {
        HIDE_IME,
        HALF_EXPAND,
        COLLAPSE,
        DELEGATE,
    }

    /**
     * Resolves one Back press without depending on Android or Material classes.
     * IME/focus always wins, followed by one stable sheet-state transition.
     */
    fun backAction(
        imeOrCommandActive: Boolean,
        state: StableState,
    ): BackAction = when {
        imeOrCommandActive -> BackAction.HIDE_IME
        state == StableState.EXPANDED -> BackAction.HALF_EXPAND
        state == StableState.HALF_EXPANDED -> BackAction.COLLAPSE
        else -> BackAction.DELEGATE
    }
}
