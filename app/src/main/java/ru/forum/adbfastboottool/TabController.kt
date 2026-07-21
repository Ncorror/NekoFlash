package ru.forum.adbfastboottool

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import com.google.android.material.button.MaterialButton

/**
 * Управление переключением вкладок/страниц главного экрана.
 *
 * Вынесено из MainActivity (декомпозиция God-object): это чистая UI-логика,
 * не зависящая от состояния USB/прошивки — только показ/скрытие страниц
 * и подсветка кнопок-табов.
 *
 * Состояние:
 *  - [selectedWindow] — текущая видимая страница (home/fastboot/adb/files/...);
 *  - [commandContext] — контекст для ручного ввода команд (adb/fastboot).
 *    Меняется только при входе в ADB/Fastboot-воркфлоу, не сбрасывается
 *    сервисными экранами (console/settings).
 */
class TabController(private val activity: Activity) {

    var selectedWindow: String = "home"
        private set

    var commandContext: String = "fastboot"
        private set

    /** Страница (контент) для каждого таба. */
    private val pages = mapOf(
        "home" to R.id.pageHome,
        "fastboot" to R.id.containerFastboot,
        "adb" to R.id.containerAdb,
        "console" to R.id.pageReports,
        "unlock" to R.id.pageUnlock,
        "settings" to R.id.pageSettings
    )

    /** Кнопки нижней навигации (5 табов с иконками). */
    private val tabButtons = mapOf(
        "home" to R.id.tabHome,
        "fastboot" to R.id.tabFastboot,
        "adb" to R.id.tabAdb,
        "unlock" to R.id.tabUnlock,
        "settings" to R.id.tabSettings
    )

    /**
     * Переключение на вкладку [tab].
     * "reports" исторически маппится на страницу "console".
     */
    fun switchTab(tab: String) {
        val targetTab = if (tab == "reports") "console" else tab
        selectedWindow = targetTab

        // Контекст команды меняется только при входе в ADB/Fastboot-воркфлоу.
        if (targetTab == "adb" || targetTab == "fastboot") {
            commandContext = targetTab
        }

        pages.forEach { (key, viewId) ->
            activity.findViewById<View>(viewId).visibility =
                if (key == targetTab) View.VISIBLE else View.GONE
        }

        tabButtons.forEach { (key, buttonId) ->
            val button = activity.findViewById<MaterialButton>(buttonId)
            val selected = key == targetTab
            button.alpha = if (selected) 1.0f else 0.7f
            button.setTextColor(Color.parseColor(if (selected) COLOR_ACTIVE_TEXT else COLOR_INACTIVE_TEXT))
            button.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(if (selected) COLOR_ACTIVE_BG else COLOR_INACTIVE_BG)
            )
        }
    }

    private companion object {
        const val COLOR_ACTIVE_TEXT = "#E9782B"
        const val COLOR_INACTIVE_TEXT = "#7F8B9B"
        const val COLOR_ACTIVE_BG = "#1A2532"
        const val COLOR_INACTIVE_BG = "#0D141D"
    }
}
