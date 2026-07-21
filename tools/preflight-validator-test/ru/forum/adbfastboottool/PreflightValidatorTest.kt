package ru.forum.adbfastboottool

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

fun main() {
    partitionInferenceIsStable()
    criticalPartitionPolicyIsStable()
    batteryReadingUsesManagerThenBroadcastFallback()
    resultBlocksOnlyCriticalFailures()
    println("PREFLIGHT VALIDATOR TESTS: OK")
}

private fun partitionInferenceIsStable() {
    val cases = mapOf(
        "vendor_boot.img" to "vendor_boot",
        "init_boot-a.img" to "init_boot",
        "vbmeta_system.img" to "vbmeta",
        "OrangeFox-recovery.img" to "recovery",
        "dtbo.img" to "dtbo",
        "stock-boot.img" to "boot",
        "BOOT.IMG" to "boot",
        "bootloader.img" to null,
        "payload.bin" to null
    )
    cases.forEach { (name, expected) ->
        check(PreflightValidator.guessPartitionFromFileName(name) == expected) {
            "Unexpected partition guess for $name"
        }
    }
}

private fun criticalPartitionPolicyIsStable() {
    listOf("boot", "init_boot", "recovery", "vbmeta", "vendor_boot").forEach {
        check(PreflightValidator.requiresDoubleConfirm(it))
        check(PreflightValidator.requiresDoubleConfirm("  ${it.uppercase()}  "))
    }
    check(!PreflightValidator.requiresDoubleConfirm("system"))
    check(!PreflightValidator.requiresDoubleConfirm("dtbo"))
}

private fun batteryReadingUsesManagerThenBroadcastFallback() {
    val managerContext = object : Context() {
        override fun getSystemService(name: String): Any = BatteryManager(73)
    }
    check(PreflightValidator.hostBatteryPercent(managerContext) == 73)

    val fallbackContext = object : Context() {
        override fun getSystemService(name: String): Any = BatteryManager(-1)
        override fun registerReceiver(receiver: Any?, filter: IntentFilter): Intent =
            Intent(mapOf(BatteryManager.EXTRA_LEVEL to 45, BatteryManager.EXTRA_SCALE to 60))
    }
    check(PreflightValidator.hostBatteryPercent(fallbackContext) == 75)
}

private fun resultBlocksOnlyCriticalFailures() {
    val warningsOnly = PreflightValidator.Result(
        listOf(PreflightValidator.Check(ok = false, critical = false, message = "warning"))
    )
    check(warningsOnly.canProceed)

    val criticalFailure = PreflightValidator.Result(
        listOf(PreflightValidator.Check(ok = false, critical = true, message = "stop"))
    )
    check(!criticalFailure.canProceed)
}
