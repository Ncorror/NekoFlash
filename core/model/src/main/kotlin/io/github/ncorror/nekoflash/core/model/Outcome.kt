package io.github.ncorror.nekoflash.core.model

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>

    data class Failed(
        val code: String,
        val detail: String? = null,
    ) : Outcome<Nothing>

    data object Cancelled : Outcome<Nothing>

    data class Unknown(
        val reason: String,
    ) : Outcome<Nothing>
}
