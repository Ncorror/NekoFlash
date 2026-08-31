package io.github.ncorror.nekoflash.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Сведения о хосте и сборке для диагностического отчёта.
 *
 * `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` требует от evidence идентичность
 * сборки и хоста: без них отчёт нельзя сопоставить ни с версией приложения, ни
 * с телефоном, на котором наблюдалось поведение.
 *
 * Ничего не вырезается. Отчёт собирает оператор о собственном устройстве и сам
 * решает, что с ним делать. Урезать состав «на всякий случай» означало бы
 * host-side запрет, который запрещён `01_PRODUCT_CHARTER_RU.md`.
 *
 * Поле, которого платформа не даёт, записывается с причиной, а не пропускается:
 * пустое место читается как «этого не было».
 */
public object HostFacts {
    /**
     * Собирает всё, что доступно без запроса дополнительных разрешений.
     *
     * `Build.getSerial()` требует `READ_PHONE_STATE`, и без него платформа
     * отвечает отказом. Это ограничение самой платформы, а не приложения,
     * поэтому оно записывается как отказ с указанием причины: подменять ответ
     * платформы собственным умолчанием запрещено
     * `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md`.
     */
    public fun collect(context: Context): Map<String, String> = buildMap {
        put("app.applicationId", context.packageName)
        putAll(appVersion(context))

        put("host.manufacturer", Build.MANUFACTURER)
        put("host.brand", Build.BRAND)
        put("host.model", Build.MODEL)
        put("host.device", Build.DEVICE)
        put("host.product", Build.PRODUCT)
        put("host.hardware", Build.HARDWARE)
        put("host.fingerprint", Build.FINGERPRINT)
        put("host.buildId", Build.ID)
        put("host.androidRelease", Build.VERSION.RELEASE)
        put("host.sdkInt", Build.VERSION.SDK_INT.toString())
        put("host.securityPatch", Build.VERSION.SECURITY_PATCH)
        put("host.supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))

        put("host.androidId", androidId(context))
        put("host.serial", hostSerial())
    }

    private fun appVersion(context: Context): Map<String, String> = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        mapOf(
            "app.versionName" to (info.versionName ?: UNAVAILABLE),
            "app.versionCode" to info.longVersionCode.toString(),
        )
    } catch (unavailable: PackageManager.NameNotFoundException) {
        mapOf(
            "app.versionName" to "$UNAVAILABLE:${unavailable.javaClass.simpleName}",
            "app.versionCode" to UNAVAILABLE,
        )
    }

    /**
     * Идентификатор установки, выдаваемый платформой.
     *
     * Помогает сопоставить несколько отчётов с одного хоста между собой.
     */
    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String =
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: UNAVAILABLE

    @SuppressLint("HardwareIds")
    private fun hostSerial(): String =
        runCatching { Build.getSerial() }
            .getOrElse { return "$UNAVAILABLE:requires_read_phone_state" }
            ?: UNAVAILABLE

    private const val UNAVAILABLE = "unavailable"
}
