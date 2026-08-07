package ru.forum.adbfastboottool

/**
 * Pure UI policy for the connection badge. It does not inspect USB or protocol
 * traffic; it only translates already-qualified transport diagnostics into a
 * user-visible mode. Keeping this pure makes mode transitions testable.
 */
object ConnectionModeUiPolicy {
    enum class Transport { NONE, CONNECTING, FASTBOOT, ADB, ERROR }
    enum class AdbMode { SYSTEM, RECOVERY, SIDELOAD, UNKNOWN }
    enum class DisplayMode {
        NO_DEVICE,
        CONNECTING,
        FASTBOOT_BOOTLOADER,
        FASTBOOTD,
        FASTBOOT_UNKNOWN,
        ADB_SYSTEM,
        ADB_RECOVERY,
        ADB_SIDELOAD,
        ADB_UNKNOWN,
        ERROR
    }

    fun logLabel(mode: DisplayMode): String = when (mode) {
        DisplayMode.NO_DEVICE -> "NO DEVICE"
        DisplayMode.CONNECTING -> "CONNECTING"
        DisplayMode.FASTBOOT_BOOTLOADER -> "FASTBOOT"
        DisplayMode.FASTBOOTD -> "FASTBOOTD"
        DisplayMode.FASTBOOT_UNKNOWN -> "FASTBOOT (UNKNOWN)"
        DisplayMode.ADB_SYSTEM -> "ADB"
        DisplayMode.ADB_RECOVERY -> "ADB RECOVERY"
        DisplayMode.ADB_SIDELOAD -> "ADB SIDELOAD"
        DisplayMode.ADB_UNKNOWN -> "ADB (UNKNOWN)"
        DisplayMode.ERROR -> "ERROR"
    }

    fun resolve(
        transport: Transport,
        adbMode: AdbMode? = null,
        fastbootd: Boolean? = null
    ): DisplayMode = when (transport) {
        Transport.NONE -> DisplayMode.NO_DEVICE
        Transport.CONNECTING -> DisplayMode.CONNECTING
        Transport.ERROR -> DisplayMode.ERROR
        Transport.FASTBOOT -> when (fastbootd) {
            true -> DisplayMode.FASTBOOTD
            false -> DisplayMode.FASTBOOT_BOOTLOADER
            null -> DisplayMode.FASTBOOT_UNKNOWN
        }
        Transport.ADB -> when (adbMode) {
            AdbMode.SYSTEM -> DisplayMode.ADB_SYSTEM
            AdbMode.RECOVERY -> DisplayMode.ADB_RECOVERY
            AdbMode.SIDELOAD -> DisplayMode.ADB_SIDELOAD
            AdbMode.UNKNOWN, null -> DisplayMode.ADB_UNKNOWN
        }
    }
}
