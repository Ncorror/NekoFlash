package ru.forum.adbfastboottool

private fun requireMode(
    expected: ConnectionModeUiPolicy.DisplayMode,
    transport: ConnectionModeUiPolicy.Transport,
    adb: ConnectionModeUiPolicy.AdbMode? = null,
    fastbootd: Boolean? = null
) {
    val actual = ConnectionModeUiPolicy.resolve(transport, adb, fastbootd)
    check(actual == expected) { "expected=$expected actual=$actual transport=$transport adb=$adb fastbootd=$fastbootd" }
}

fun main() {
    requireMode(ConnectionModeUiPolicy.DisplayMode.NO_DEVICE, ConnectionModeUiPolicy.Transport.NONE)
    requireMode(ConnectionModeUiPolicy.DisplayMode.CONNECTING, ConnectionModeUiPolicy.Transport.CONNECTING)
    requireMode(ConnectionModeUiPolicy.DisplayMode.ERROR, ConnectionModeUiPolicy.Transport.ERROR)

    requireMode(
        ConnectionModeUiPolicy.DisplayMode.ADB_SYSTEM,
        ConnectionModeUiPolicy.Transport.ADB,
        ConnectionModeUiPolicy.AdbMode.SYSTEM
    )
    requireMode(
        ConnectionModeUiPolicy.DisplayMode.ADB_RECOVERY,
        ConnectionModeUiPolicy.Transport.ADB,
        ConnectionModeUiPolicy.AdbMode.RECOVERY
    )
    requireMode(
        ConnectionModeUiPolicy.DisplayMode.ADB_SIDELOAD,
        ConnectionModeUiPolicy.Transport.ADB,
        ConnectionModeUiPolicy.AdbMode.SIDELOAD
    )
    requireMode(
        ConnectionModeUiPolicy.DisplayMode.ADB_UNKNOWN,
        ConnectionModeUiPolicy.Transport.ADB,
        ConnectionModeUiPolicy.AdbMode.UNKNOWN
    )
    requireMode(ConnectionModeUiPolicy.DisplayMode.ADB_UNKNOWN, ConnectionModeUiPolicy.Transport.ADB)

    requireMode(
        ConnectionModeUiPolicy.DisplayMode.FASTBOOT_BOOTLOADER,
        ConnectionModeUiPolicy.Transport.FASTBOOT,
        fastbootd = false
    )
    requireMode(
        ConnectionModeUiPolicy.DisplayMode.FASTBOOTD,
        ConnectionModeUiPolicy.Transport.FASTBOOT,
        fastbootd = true
    )
    requireMode(
        ConnectionModeUiPolicy.DisplayMode.FASTBOOT_UNKNOWN,
        ConnectionModeUiPolicy.Transport.FASTBOOT,
        fastbootd = null
    )

    // Real UI transitions must remain deterministic.
    val sideload = ConnectionModeUiPolicy.resolve(
        ConnectionModeUiPolicy.Transport.ADB,
        ConnectionModeUiPolicy.AdbMode.SIDELOAD
    )
    val recovery = ConnectionModeUiPolicy.resolve(
        ConnectionModeUiPolicy.Transport.ADB,
        ConnectionModeUiPolicy.AdbMode.RECOVERY
    )
    check(sideload == ConnectionModeUiPolicy.DisplayMode.ADB_SIDELOAD)
    check(recovery == ConnectionModeUiPolicy.DisplayMode.ADB_RECOVERY)

    val bootloader = ConnectionModeUiPolicy.resolve(ConnectionModeUiPolicy.Transport.FASTBOOT, fastbootd = false)
    val userspace = ConnectionModeUiPolicy.resolve(ConnectionModeUiPolicy.Transport.FASTBOOT, fastbootd = true)
    check(bootloader == ConnectionModeUiPolicy.DisplayMode.FASTBOOT_BOOTLOADER)
    check(userspace == ConnectionModeUiPolicy.DisplayMode.FASTBOOTD)

    check(ConnectionModeUiPolicy.logLabel(ConnectionModeUiPolicy.DisplayMode.ADB_SYSTEM) == "ADB")
    check(ConnectionModeUiPolicy.logLabel(ConnectionModeUiPolicy.DisplayMode.ADB_RECOVERY) == "ADB RECOVERY")
    check(ConnectionModeUiPolicy.logLabel(ConnectionModeUiPolicy.DisplayMode.ADB_SIDELOAD) == "ADB SIDELOAD")
    check(ConnectionModeUiPolicy.logLabel(ConnectionModeUiPolicy.DisplayMode.FASTBOOT_BOOTLOADER) == "FASTBOOT")
    check(ConnectionModeUiPolicy.logLabel(ConnectionModeUiPolicy.DisplayMode.FASTBOOTD) == "FASTBOOTD")

    println("CONNECTION MODE UI TESTS: OK")
}
