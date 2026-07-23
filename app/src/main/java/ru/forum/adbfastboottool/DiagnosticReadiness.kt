package ru.forum.adbfastboottool

/** Dependency-free readiness evaluation. It performs no USB commands. */
object DiagnosticReadiness {
    enum class Severity { PASS, WARNING, BLOCKER }

    data class Check(
        val code: String,
        val severity: Severity,
        val message: String,
        val detail: String? = null
    )

    data class Input(
        val buildId: String,
        val workspaceReady: Boolean,
        val logsReady: Boolean,
        val reportsReady: Boolean,
        val usbPermissionGranted: Boolean?,
        val usbCandidatePresent: Boolean,
        val bulkEndpointsPresent: Boolean,
        val protocolConnected: Boolean,
        val connectionMode: String?,
        val mutationLockEnabled: Boolean,
        val operationActive: Boolean,
        val transportRestartRequired: Boolean,
        val nativeTransferActive: Boolean,
        val freeBytes: Long?
    )

    data class Result(val checks: List<Check>) {
        val blockerCount: Int get() = checks.count { it.severity == Severity.BLOCKER }
        val warningCount: Int get() = checks.count { it.severity == Severity.WARNING }
        val ready: Boolean get() = blockerCount == 0

        fun summary(): String = if (ready) {
            "Готово к диагностическому тесту · предупреждений: $warningCount"
        } else {
            "Диагностический тест не готов · блокировок: $blockerCount · предупреждений: $warningCount"
        }
    }

    fun evaluate(input: Input): Result {
        val checks = mutableListOf<Check>()
        fun add(code: String, ok: Boolean, message: String, fail: String, warningOnly: Boolean = false, detail: String? = null) {
            checks += if (ok) Check(code, Severity.PASS, message, detail)
            else Check(code, if (warningOnly) Severity.WARNING else Severity.BLOCKER, fail, detail)
        }

        add("BUILD_ID", input.buildId.isNotBlank(), "Build ID определён", "Build ID отсутствует", detail = input.buildId)
        add("WORKSPACE", input.workspaceReady, "Workspace доступен", "Workspace недоступен")
        add("LOG_STORAGE", input.logsReady, "Папка логов доступна", "Папка логов недоступна")
        add("REPORT_STORAGE", input.reportsReady, "Папка отчётов доступна", "Папка отчётов недоступна")
        add("USB_CANDIDATE", input.usbCandidatePresent, "Совместимое USB-устройство обнаружено", "Совместимое USB-устройство не обнаружено")
        add("USB_PERMISSION", input.usbPermissionGranted == true, "USB permission выдан", "USB permission не выдан", warningOnly = input.usbPermissionGranted == null)
        add("BULK_ENDPOINTS", input.bulkEndpointsPresent, "Bulk IN/OUT endpoints найдены", "Bulk IN/OUT endpoints не найдены")
        add("PROTOCOL", input.protocolConnected, "Протокол подключён", "ADB/Fastboot протокол не подключён", detail = input.connectionMode)
        add("READ_ONLY", input.mutationLockEnabled, "Глобальный запрет mutation включён", "Перед диагностическим тестом включите READ-ONLY lock")
        add("OPERATION_IDLE", !input.operationActive, "Активных операций нет", "Другая операция уже выполняется")
        add("TRANSPORT_CLEAN", !input.transportRestartRequired, "Transport не требует перезапуска", "Transport требует полного перезапуска NekoFlash")
        add("NATIVE_IDLE", !input.nativeTransferActive, "Native USBFS не активен", "Native USBFS ещё выполняет transfer/drain")

        val minimumFree = 96L * 1024L * 1024L
        val free = input.freeBytes
        add(
            "FREE_SPACE",
            free != null && free >= minimumFree,
            "Свободного места достаточно",
            if (free == null) "Свободное место определить не удалось" else "Свободного места меньше 96 MiB",
            warningOnly = free == null,
            detail = free?.toString()
        )
        return Result(checks)
    }
}
