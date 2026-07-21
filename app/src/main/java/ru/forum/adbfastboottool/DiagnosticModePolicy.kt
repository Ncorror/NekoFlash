package ru.forum.adbfastboottool

/** Diagnostic operating mode is intentionally independent from the flash safety profile. */
object DiagnosticModePolicy {
    enum class Mode { NORMAL, EXTENDED_READ_ONLY, USB_STRESS_READ_ONLY }

    data class State(
        val mode: Mode,
        val mutationLockRequired: Boolean,
        val rawTraceRequired: Boolean,
        val userLabel: String
    )

    fun state(mode: Mode): State = when (mode) {
        Mode.NORMAL -> State(
            mode = mode,
            mutationLockRequired = false,
            rawTraceRequired = false,
            userLabel = "Обычный режим"
        )
        Mode.EXTENDED_READ_ONLY -> State(
            mode = mode,
            mutationLockRequired = true,
            rawTraceRequired = true,
            userLabel = "Расширенная диагностика · READ-ONLY"
        )
        Mode.USB_STRESS_READ_ONLY -> State(
            mode = mode,
            mutationLockRequired = true,
            rawTraceRequired = true,
            userLabel = "USB stress · READ-ONLY"
        )
    }

    fun next(mode: Mode): Mode = when (mode) {
        Mode.NORMAL -> Mode.EXTENDED_READ_ONLY
        Mode.EXTENDED_READ_ONLY -> Mode.USB_STRESS_READ_ONLY
        Mode.USB_STRESS_READ_ONLY -> Mode.NORMAL
    }
}
