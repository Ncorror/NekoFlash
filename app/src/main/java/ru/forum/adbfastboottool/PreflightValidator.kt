package ru.forum.adbfastboottool

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.util.Locale

/**
 * Инженерная «защита от дурака» перед опасными операциями.
 *
 * Проверяет:
 *  - заряд батареи ХОСТА (этого устройства) — read через BatteryManager;
 *  - заряд ЦЕЛЕВОГО устройства — если есть активная ADB-сессия (dumpsys battery);
 *  - соответствие имени файла разделу (boot.img → boot);
 *  - корректность слота A/B относительно файла (*_a / *_b);
 *  - что раздел входит в безопасный список.
 *
 * Не делает сетевых запросов и не пишет на цель — только читает.
 */
object PreflightValidator {

    private val doubleConfirmPartitions = setOf("boot", "init_boot", "recovery", "vbmeta", "vendor_boot")
    private val bootImageNameRegex = Regex("(^|[-_ .])boot([-_ .]|\\.img$)")

    /** Минимальный заряд хоста для старта прошивки. */
    const val MIN_HOST_BATTERY_PERCENT = 20

    /** Минимальный заряд цели (если удалось прочитать). */
    const val MIN_TARGET_BATTERY_PERCENT = 20

    data class Check(val ok: Boolean, val critical: Boolean, val message: String)

    data class Result(val checks: List<Check>) {
        /** true, если ни одна КРИТИЧЕСКАЯ проверка не провалена. */
        val canProceed: Boolean get() = checks.none { !it.ok && it.critical }

        fun toDisplayText(): String = buildString {
            checks.forEach { c ->
                val icon = when {
                    c.ok -> "✅"
                    c.critical -> "⛔"
                    else -> "⚠️"
                }
                appendLine("$icon ${c.message}")
            }
        }
    }

    /** Заряд хоста в процентах (0..100) или null, если не удалось прочитать. */
    fun hostBatteryPercent(context: Context): Int? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val viaManager = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (viaManager != null && viaManager in 0..100) return viaManager

            // Fallback через sticky-broadcast
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Полная предполётная проверка перед прошивкой раздела.
     *
     * @param targetBatteryPercent заряд цели, если был прочитан заранее через ADB (иначе null)
     */
    fun validateFlash(
        context: Context,
        partition: String,
        file: File,
        currentSlot: String?,
        safePartitions: Set<String>,
        targetBatteryPercent: Int? = null
    ): Result {
        val checks = mutableListOf<Check>()
        val normalized = partition.trim().lowercase(Locale.US)

        // 1. Раздел в безопасном списке
        checks += if (normalized in safePartitions) {
            Check(true, true, "Раздел '$normalized' разрешён к прошивке")
        } else {
            Check(false, true, "Раздел '$normalized' НЕ в безопасном списке")
        }

        // 2. Файл существует и не пуст
        checks += when {
            !file.exists() || !file.isFile -> Check(false, true, "Файл не найден: ${file.name}")
            file.length() <= 0L -> Check(false, true, "Файл пустой: ${file.name}")
            else -> Check(true, true, "Файл: ${file.name} (${formatSize(file.length())})")
        }

        // 3. Заряд хоста
        val hostBattery = hostBatteryPercent(context)
        checks += when {
            hostBattery == null -> Check(false, false, "Заряд хоста прочитать не удалось")
            hostBattery < MIN_HOST_BATTERY_PERCENT ->
                Check(false, true, "Заряд хоста $hostBattery% < $MIN_HOST_BATTERY_PERCENT% — зарядите устройство")
            else -> Check(true, true, "Заряд хоста: $hostBattery%")
        }

        // 4. Заряд цели (если известен)
        checks += when {
            targetBatteryPercent == null ->
                Check(true, false, "Заряд цели неизвестен (норма для fastboot-режима)")
            targetBatteryPercent < MIN_TARGET_BATTERY_PERCENT ->
                Check(false, true, "Заряд цели $targetBatteryPercent% < $MIN_TARGET_BATTERY_PERCENT%")
            else -> Check(true, false, "Заряд цели: $targetBatteryPercent%")
        }

        // 5. Соответствие имени файла разделу
        val guessed = guessPartitionFromFileName(file.name)
        checks += when {
            guessed == null ->
                Check(true, false, "Имя файла не указывает на раздел — проверьте вручную")
            guessed == normalized ->
                Check(true, false, "Имя файла совпадает с разделом ($guessed)")
            else ->
                Check(false, false, "Файл похож на '$guessed', а прошиваете в '$normalized'")
        }

        // 6. Слот A/B
        val fileSlot = when {
            file.name.contains("_a.", ignoreCase = true) -> "a"
            file.name.contains("_b.", ignoreCase = true) -> "b"
            else -> null
        }
        val devSlot = currentSlot?.removePrefix("_")?.lowercase(Locale.US)
        checks += when {
            fileSlot == null -> Check(true, false, "Файл без явного слота — точный target будет выбран и проверен перед прошивкой")
            devSlot == null -> Check(true, false, "Слот устройства неизвестен")
            fileSlot == devSlot -> Check(true, false, "Слот файла ($fileSlot) совпадает с активным")
            else -> Check(false, false, "Файл для слота '$fileSlot', активен слот '$devSlot'")
        }

        return Result(checks)
    }

    /** Разделы, для которых обязателен двойной Double-Check. */
    fun requiresDoubleConfirm(partition: String): Boolean =
        partition.trim().lowercase(Locale.US) in doubleConfirmPartitions

    fun guessPartitionFromFileName(fileName: String): String? {
        val name = fileName.lowercase(Locale.US)
        return when {
            "vendor_boot" in name -> "vendor_boot"
            "init_boot" in name -> "init_boot"
            "vbmeta" in name -> "vbmeta"
            "recovery" in name -> "recovery"
            "dtbo" in name -> "dtbo"
            bootImageNameRegex.containsMatchIn(name) -> "boot"
            else -> null
        }
    }

    private fun formatSize(bytes: Long): String =
        "%.2f MB".format(Locale.US, bytes.toDouble() / 1024.0 / 1024.0)
}
