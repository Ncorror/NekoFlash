package ru.forum.adbfastboottool

import java.io.File

/**
 * Privacy filter for user-shareable reports.
 *
 * The filter keeps operationally useful facts such as command names, modes,
 * VID/PID, partition names, sizes, ADB features and bootloader state, but masks
 * identifiers that are usually not needed in a public forum thread.
 */
object ReportSanitizer {

    data class Scope(
        val workspace: File? = null,
        val logFile: File? = null,
        val adbKeyDir: File? = null,
        val packageName: String? = null
    )

    const val REDACTED_SERIAL = "<redacted:serial>"
    const val REDACTED_PATH = "<redacted:path>"
    const val REDACTED_HOST = "<redacted:host>"
    const val REDACTED_USB_NAME = "<redacted:usb-device-name>"
    const val REDACTED_USB_PATH = "<redacted:usb-device-path>"
    const val REDACTED_ADB_KEY_PATH = "<redacted:adb-key-path>"
    const val REDACTED_ACCOUNT_ID = "<redacted:account-id>"

    private val emptyScope = Scope()

    private val adbPublicKeyRegex = Regex("(?i)(ADB public key:\\s*)[^\\s\"]+")
    private val publicKeyPathRegex = Regex("(?i)(publicKeyPath\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+")
    private val adbKeyDirTextRegex = Regex("(?i)(ADB key dir:\\s*)[^\\s\"]+")
    private val adbKeyDirFieldRegex = Regex("(?i)(adbKeyDir\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+")
    private val serialTextRegex = Regex("(?i)(serialno|serial|ro\\.serialno|ro\\.boot\\.serialno)(\\s*[:=]\\s*)[^\\s,;|\"]+")
    private val serialJsonRegex = Regex("(?i)(\"(?:serialno|serial|ro\\.serialno|ro\\.boot\\.serialno)\"\\s*:\\s*\")[^\"]*\"")
    private val accountIdTextRegex = Regex("(?i)(userId|cUserId|uid)(\\s*[:=]\\s*)[^\\s,;|\"]+")
    private val accountIdJsonRegex = Regex("(?i)(\"(?:userId|cUserId|uid)\"\\s*:\\s*\")[^\"]*\"")
    private val miLoginIdRegex = Regex("(?i)(Вход выполнен \\(ID:\\s*)[^)]+")
    private val miAuthorizedIdRegex = Regex("(?i)(Авторизован\\.\\s*ID:\\s*)[^\\s,)]+")
    private val usbDevicePathRegex = Regex("(?i)(DeviceName:\\s*)/dev/bus/usb/[^\\s\"]+")
    private val deviceTextRegex = Regex("(?i)(Device:\\s*)(?!<redacted)[^\\n\"]+")
    private val fastbootDeviceTextRegex = Regex("(?i)(Fastboot —\\s*)(?!<redacted)[^\\n\"]+")
    private val adbDeviceTextRegex = Regex("(?i)(ADB —\\s*)(?!<redacted)[^\\n\"]+")
    private val russianDeviceTextRegex = Regex("(?i)(Устройство:\\s*)([^|\\n\"]+)")
    private val manufacturerTextRegex = Regex("(?i)(Manufacturer:\\s*)[^\\n\"]+")
    private val modelTextRegex = Regex("(?i)(Model:\\s*)[^\\n\"]+")
    private val alreadyRedactedDeviceRegex = Regex("(?i)(Device:\\s*)$REDACTED_USB_NAME")
    private val hostAndroidReleaseRegex = Regex("(?i)(Host Android release:\\s*)[^\\n\"]+")
    private val manufacturerJsonRegex = Regex("(?i)(\"manufacturer\"\\s*:\\s*)\"[^\"]*\"")
    private val modelJsonRegex = Regex("(?i)(\"model\"\\s*:\\s*)\"[^\"]*\"")
    private val deviceJsonRegex = Regex("(?i)(\"device\"\\s*:\\s*)\"[^\"]*\"")
    private val releaseJsonRegex = Regex("(?i)(\"release\"\\s*:\\s*)\"[^\"]*\"")
    private val deviceStoragePathRegex = Regex("(?<![A-Za-z0-9_])/(sdcard|storage/emulated/0|data/media/0)(/[^\\s\"'`<>]*)?")
    private val privateAppPathRegex = Regex("(?<![A-Za-z0-9_])/(data/user/0|data/data)/[^\\s\"'`<>]+")
    private val longHexIdentifierRegex = Regex("(?i)\\b[0-9a-f]{16,}\\b")

    private data class ReplacementPlan(val knownPaths: List<Pair<String, String>>)

    fun sanitizeLines(lines: List<String>, scope: Scope = emptyScope): List<String> {
        if (lines.isEmpty()) return lines
        val plan = replacementPlan(scope)
        return lines.map { sanitizeText(it, plan) }
    }

    fun sanitizeText(text: String, scope: Scope = emptyScope): String =
        sanitizeText(text, replacementPlan(scope))

    private fun sanitizeText(text: String, plan: ReplacementPlan): String {
        if (text.isEmpty()) return text
        var out = text

        plan.knownPaths.forEach { (path, token) ->
            out = out.replace(path, token)
        }

        out = out
            .replace(adbPublicKeyRegex, "$1$REDACTED_ADB_KEY_PATH")
            .replace(publicKeyPathRegex, "$1$REDACTED_ADB_KEY_PATH")
            .replace(adbKeyDirTextRegex, "$1$REDACTED_ADB_KEY_PATH")
            .replace(adbKeyDirFieldRegex, "$1$REDACTED_ADB_KEY_PATH")
            .replace(serialTextRegex) { m ->
                m.groupValues[1] + m.groupValues[2] + REDACTED_SERIAL
            }
            .replace(serialJsonRegex, "$1$REDACTED_SERIAL\"")
            .replace(accountIdTextRegex) { m ->
                m.groupValues[1] + m.groupValues[2] + REDACTED_ACCOUNT_ID
            }
            .replace(accountIdJsonRegex, "$1$REDACTED_ACCOUNT_ID\"")
            .replace(miLoginIdRegex, "$1$REDACTED_ACCOUNT_ID")
            .replace(miAuthorizedIdRegex, "$1$REDACTED_ACCOUNT_ID")
            .replace(usbDevicePathRegex, "$1$REDACTED_USB_PATH")
            .replace(deviceTextRegex, "$1$REDACTED_USB_NAME")
            .replace(fastbootDeviceTextRegex, "$1$REDACTED_USB_NAME")
            .replace(adbDeviceTextRegex, "$1$REDACTED_USB_NAME")
            .replace(russianDeviceTextRegex, "$1$REDACTED_USB_NAME ")
            .replace(manufacturerTextRegex, "$1$REDACTED_HOST")
            .replace(modelTextRegex, "$1$REDACTED_HOST")
            .replace(alreadyRedactedDeviceRegex, "$1$REDACTED_USB_NAME")
            .replace(hostAndroidReleaseRegex, "$1<redacted:host-release>")
            .replace(manufacturerJsonRegex, "$1\"$REDACTED_HOST\"")
            .replace(modelJsonRegex, "$1\"$REDACTED_HOST\"")
            .replace(deviceJsonRegex, "$1\"$REDACTED_HOST\"")
            .replace(releaseJsonRegex, "$1\"<redacted:host-release>\"")

        out = sanitizeDeviceStoragePaths(out)
        out = sanitizePrivateAppPaths(out)
        out = sanitizeLongHexIdentifiers(out)
        return out
    }

    private fun replacementPlan(scope: Scope): ReplacementPlan {
        val knownPaths = listOfNotNull(
            scope.workspace?.absolutePath,
            scope.logFile?.absolutePath,
            scope.logFile?.parentFile?.absolutePath,
            scope.adbKeyDir?.absolutePath,
            scope.packageName?.let { "/data/user/0/$it" },
            scope.packageName?.let { "/data/data/$it" }
        )
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .map { it to tokenForKnownPath(it, scope) }
            .toList()
        return ReplacementPlan(knownPaths)
    }

    private fun tokenForKnownPath(path: String, scope: Scope): String = when {
        scope.logFile?.absolutePath == path -> "<log-file>"
        scope.logFile?.parentFile?.absolutePath == path -> "<logs-dir>"
        scope.adbKeyDir?.absolutePath == path -> REDACTED_ADB_KEY_PATH
        scope.workspace?.absolutePath == path -> "<workspace>"
        else -> REDACTED_PATH
    }

    private fun sanitizeDeviceStoragePaths(value: String): String {
        var out = value
        out = out.replace(deviceStoragePathRegex) { m ->
            val root = m.groupValues[1]
            val suffix = m.groupValues.getOrNull(2).orEmpty()
            if (suffix.isBlank() || suffix == "/") "/$root" else "/$root/<path>"
        }
        return out
    }

    private fun sanitizePrivateAppPaths(value: String): String =
        value.replace(privateAppPathRegex, REDACTED_PATH)

    private fun sanitizeLongHexIdentifiers(value: String): String =
        value.replace(longHexIdentifierRegex, "<redacted:hex-id>")
}
