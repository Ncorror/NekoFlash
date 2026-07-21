package ru.forum.adbfastboottool

import android.content.Context
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette

/**
 * Извлекает динамическую палитру из bg_welcome.png через Android Palette API
 * и кеширует результат в SharedPreferences, чтобы не декодировать картинку
 * на каждом старте.
 *
 * Если ресурс отсутствует или Palette не смог выделить цвета — возвращаются
 * безопасные значения по умолчанию в фирменном тёмном/оранжевом стиле.
 */
object ThemePalette {

    data class Scheme(
        val primary: Int,
        val secondary: Int,
        val background: Int,
        val onPrimary: Int
    )

    private const val PREFS = "theme_palette"
    private const val KEY_PRIMARY = "primary"
    private const val KEY_SECONDARY = "secondary"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_ON_PRIMARY = "on_primary"
    private const val KEY_READY = "ready"
    private const val KEY_VERSION = "cache_version"

    // Увеличивайте при смене bg_welcome.png — старый кеш палитры сбросится.
    private const val CACHE_VERSION = 3

    // Фолбэк — текущая фирменная схема (тёмный фон + оранжевый акцент)
    private val DEFAULT = Scheme(
        primary = 0xFFF97316.toInt(),
        secondary = 0xFF38BDF8.toInt(),
        background = 0xFF0A0C10.toInt(),
        onPrimary = 0xFF0A0C10.toInt()
    )

    /** Возвращает кешированную схему или дефолт. Не декодирует картинку. */
    fun current(context: Context): Scheme {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Кеш считается валидным только если он готов И версия совпадает
        if (!p.getBoolean(KEY_READY, false) || p.getInt(KEY_VERSION, 0) != CACHE_VERSION) {
            return DEFAULT
        }
        return Scheme(
            primary = p.getInt(KEY_PRIMARY, DEFAULT.primary),
            secondary = p.getInt(KEY_SECONDARY, DEFAULT.secondary),
            background = p.getInt(KEY_BACKGROUND, DEFAULT.background),
            onPrimary = p.getInt(KEY_ON_PRIMARY, DEFAULT.onPrimary)
        )
    }

    /**
     * Декодирует bg_welcome.png и извлекает палитру. Тяжёлая операция —
     * вызывать на IO-потоке. Результат кешируется.
     *
     * @return извлечённая схема (или дефолт, если не удалось).
     */
    fun extractAndCache(context: Context): Scheme {
        val resId = context.resources.getIdentifier("bg_welcome", "drawable", context.packageName)
        if (resId == 0) return DEFAULT

        return try {
            // inSampleSize=4 — Palette не нужен полный размер, экономим память
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeResource(context.resources, resId, opts)
                ?: return DEFAULT

            val palette = Palette.from(bitmap).generate()
            bitmap.recycle()

            val primary = palette.getVibrantColor(
                palette.getDominantColor(DEFAULT.primary)
            )
            val secondary = palette.getLightVibrantColor(
                palette.getMutedColor(DEFAULT.secondary)
            )
            val background = palette.getDarkMutedColor(DEFAULT.background)
            val onPrimary = pickContrast(primary)

            val scheme = Scheme(primary, secondary, background, onPrimary)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_PRIMARY, scheme.primary)
                .putInt(KEY_SECONDARY, scheme.secondary)
                .putInt(KEY_BACKGROUND, scheme.background)
                .putInt(KEY_ON_PRIMARY, scheme.onPrimary)
                .putInt(KEY_VERSION, CACHE_VERSION)
                .putBoolean(KEY_READY, true)
                .apply()
            scheme
        } catch (_: Exception) {
            DEFAULT
        }
    }

    /** Чёрный или белый текст в зависимости от яркости фона. */
    private fun pickContrast(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        // Относительная яркость (perceived luminance)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.5) 0xFF0A0C10.toInt() else 0xFFFFFFFF.toInt()
    }
}
