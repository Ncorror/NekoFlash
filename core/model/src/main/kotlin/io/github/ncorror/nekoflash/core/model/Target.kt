package io.github.ncorror.nekoflash.core.model

@JvmInline
value class TargetId(val value: String) {
    init {
        require(value.isNotBlank()) { "TargetId must not be blank" }
    }
}

@JvmInline
value class SessionGeneration(val value: Long) {
    init {
        require(value > 0L) { "SessionGeneration must be positive" }
    }
}

enum class TargetMode {
    ADB,
    RECOVERY,
    SIDELOAD,
    FASTBOOT,
    FASTBOOTD,
    UNKNOWN,
}
