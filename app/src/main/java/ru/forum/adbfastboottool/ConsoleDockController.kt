package ru.forum.adbfastboottool

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * Owns the persistent Console Bottom Sheet state.
 *
 * The Material behavior performs all vertical dragging. This controller only
 * coordinates stable states and compact-header presentation, keeping manual
 * height mutation and gesture handling out of [MainActivity].
 */
internal class ConsoleDockController(
    private val activity: AppCompatActivity,
    private val consoleOutput: RecyclerView,
) {
    private val root: View by lazy { activity.findViewById(R.id.rootCoordinator) }
    private val panel: View by lazy { activity.findViewById(R.id.consolePanel) }
    private val handle: View by lazy { activity.findViewById(R.id.consoleResizeHandle) }
    private val header: View by lazy { activity.findViewById(R.id.consoleHeader) }
    private val body: View by lazy { activity.findViewById(R.id.consoleBody) }
    private val toggle: TextView by lazy { activity.findViewById(R.id.tvConsoleToggle) }
    private val peek: TextView by lazy { activity.findViewById(R.id.tvConsolePeek) }
    private val logsButton: View by lazy { activity.findViewById(R.id.btnConsoleLogs) }
    private val historyUp: View by lazy { activity.findViewById(R.id.btnHistoryUp) }
    private val historyDown: View by lazy { activity.findViewById(R.id.btnHistoryDown) }
    private val commandInput: EditText by lazy { activity.findViewById(R.id.etCommand) }

    private lateinit var behavior: BottomSheetBehavior<View>
    private var focusCommandWhenExpanded: Boolean = false
    private var imeVisible: Boolean = false
    private var lastPeekLine: String = ""

    /** True while the Console body is visible above its collapsed peek height. */
    var isExpanded: Boolean = false
        private set

    /** Binds the Material Bottom Sheet and compact-header interactions. */
    fun initialize() {
        behavior = BottomSheetBehavior.from(panel).apply {
            isFitToContents = false
            isHideable = false
            skipCollapsed = false
            isDraggable = true
            halfExpandedRatio = HALF_EXPANDED_RATIO
            expandedOffset = 0
            peekHeight = activity.resources.getDimensionPixelSize(
                R.dimen.console_collapsed_height,
            )
            saveFlags = BottomSheetBehavior.SAVE_ALL
            state = BottomSheetBehavior.STATE_COLLAPSED
            addBottomSheetCallback(
                object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        renderState(newState)
                        bottomSheet.post { updateVisibleBodyHeight(bottomSheet) }
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        updateVisibleBodyHeight(bottomSheet)
                    }
                },
            )
        }

        val toggleSheet = View.OnClickListener { toggleExpandedState() }
        header.setOnClickListener(toggleSheet)
        handle.setOnClickListener(toggleSheet)

        commandInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        bindWindowInsets()
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateVisibleBodyHeight()
        }

        renderState(behavior.state)
        root.post { updateVisibleBodyHeight() }
    }

    /** Updates the single-line preview shown while the Console is collapsed. */
    fun updatePeek(lastLine: String) {
        lastPeekLine = lastLine.trim()
        if (!isExpanded) peek.text = compactPeekText()
    }

    /** Raises the Console enough to inspect live logs without opening the keyboard. */
    fun showLiveLogPreview() {
        focusCommandWhenExpanded = false
        commandInput.clearFocus()
        if (behavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
            behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }

    /** Opens the unified Console and optionally focuses its embedded command input. */
    fun open(requestCommandFocus: Boolean = false) {
        focusCommandWhenExpanded = requestCommandFocus
        if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            focusPendingCommandInput()
        } else {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /** Expands the Console to its full-height state without opening the keyboard. */
    fun expand() {
        open(requestCommandFocus = false)
    }

    /** Returns the Console to its compact persistent header. */
    fun collapse() {
        focusCommandWhenExpanded = false
        if (imeVisible || commandInput.hasFocus()) {
            hideCommandIme()
        }
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    /**
     * Handles the Console-specific part of Back navigation.
     *
     * Priority is deliberately local and predictable: hide the IME first,
     * then step the sheet from expanded to half-expanded and finally collapsed.
     * Returning false delegates the remaining Back action to [MainActivity].
     */
    fun handleBack(): Boolean {
        val rootInsets = ViewCompat.getRootWindowInsets(root)
        val imeOrCommandActive = imeVisible ||
            rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true ||
            commandInput.hasFocus()
        val stableState = when (behavior.state) {
            BottomSheetBehavior.STATE_EXPANDED -> ConsoleSheetPolicy.StableState.EXPANDED
            BottomSheetBehavior.STATE_HALF_EXPANDED -> ConsoleSheetPolicy.StableState.HALF_EXPANDED
            BottomSheetBehavior.STATE_COLLAPSED -> ConsoleSheetPolicy.StableState.COLLAPSED
            else -> ConsoleSheetPolicy.StableState.TRANSIENT
        }

        return when (ConsoleSheetPolicy.backAction(imeOrCommandActive, stableState)) {
            ConsoleSheetPolicy.BackAction.HIDE_IME -> {
                hideCommandIme()
                true
            }

            ConsoleSheetPolicy.BackAction.HALF_EXPAND -> {
                focusCommandWhenExpanded = false
                commandInput.clearFocus()
                behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
                true
            }

            ConsoleSheetPolicy.BackAction.COLLAPSE -> {
                collapse()
                true
            }

            ConsoleSheetPolicy.BackAction.DELEGATE -> false
        }
    }

    private fun compactPeekText(): String = lastPeekLine.ifBlank {
        activity.getString(R.string.console_peek_hint)
    }

    private fun toggleExpandedState() {
        when (behavior.state) {
            BottomSheetBehavior.STATE_EXPANDED,
            BottomSheetBehavior.STATE_HALF_EXPANDED -> collapse()

            else -> expand()
        }
    }

    private fun renderState(state: Int) {
        isExpanded = state != BottomSheetBehavior.STATE_COLLAPSED
        toggle.text = activity.getString(
            if (isExpanded) R.string.layout_icon_down else R.string.layout_icon_up,
        )
        logsButton.visibility = if (isExpanded) View.VISIBLE else View.GONE
        historyUp.visibility = View.GONE
        historyDown.visibility = View.GONE
        peek.visibility = if (isExpanded) View.INVISIBLE else View.VISIBLE
        if (!isExpanded) peek.text = compactPeekText()

        if (state == BottomSheetBehavior.STATE_EXPANDED) {
            scrollOutputToBottom()
            focusPendingCommandInput()
        }
    }


    /**
     * Material keeps this sheet measured at full screen height and changes only
     * its top position. Without constraining the body to the currently visible
     * slice, the RecyclerView bottom and command bar stay below the window in
     * half-expanded state.
     */
    private fun updateVisibleBodyHeight(bottomSheet: View = panel) {
        if (root.height <= 0 || handle.height <= 0 || header.height <= 0) return

        val targetHeight = ConsoleSheetPolicy.visibleBodyHeight(
            rootHeightPx = root.height,
            sheetTopPx = bottomSheet.top,
            chromeHeightPx = handle.height + header.height,
        )
        val params = body.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.height == targetHeight && params.weight == 0f) return

        params.height = targetHeight
        params.weight = 0f
        body.layoutParams = params
    }

    private fun scrollOutputToBottom() {
        val lastPosition = (consoleOutput.adapter?.itemCount ?: 0) - 1
        if (lastPosition < 0) return
        consoleOutput.post { consoleOutput.scrollToPosition(lastPosition) }
    }

    private fun focusPendingCommandInput() {
        if (!focusCommandWhenExpanded) return
        panel.post {
            if (!commandInput.isShown || !commandInput.isEnabled) {
                focusCommandWhenExpanded = false
                return@post
            }
            commandInput.requestFocus()
            WindowInsetsControllerCompat(activity.window, root).show(
                WindowInsetsCompat.Type.ime(),
            )
            focusCommandWhenExpanded = false
        }
    }

    /**
     * Observes IME state without consuming insets or applying a second keyboard
     * padding. MainActivity keeps `adjustResize`, so the system already resizes
     * the CoordinatorLayout and the bottom-pinned command bar stays above IME.
     */
    private fun bindWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val nextImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (nextImeVisible != imeVisible) {
                imeVisible = nextImeVisible
                behavior.isDraggable = !imeVisible
                panel.requestLayout()
                panel.post { updateVisibleBodyHeight() }
            }

            if (
                imeVisible &&
                commandInput.hasFocus() &&
                behavior.state != BottomSheetBehavior.STATE_EXPANDED
            ) {
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }

            // Do not consume or duplicate IME/system-bar insets. `adjustResize`
            // remains the single owner of the available window height.
            insets
        }

        ViewCompat.requestApplyInsets(root)
    }

    private fun hideCommandIme() {
        focusCommandWhenExpanded = false
        WindowInsetsControllerCompat(activity.window, root).hide(
            WindowInsetsCompat.Type.ime(),
        )
        imeVisible = false
        behavior.isDraggable = true
        commandInput.clearFocus()
    }

    private companion object {
        const val HALF_EXPANDED_RATIO = 0.55f
    }
}
