package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutation
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationClass
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome

/**
 * Ответ так, как он идёт в журнал.
 *
 * Токен разблокировки в выгрузку не попадает, но факт ответа — попадает: длина
 * говорит, что устройство ответило, и не говорит чем. Скрыть обе вещи значило бы
 * потерять наблюдение вместе с секретом (`07` §6.79).
 */
internal fun journalledPayload(command: String, payload: String): String =
    if (payload.isNotBlank() && SECRET_ANSWERS.any { command.contains(it, ignoreCase = true) }) {
        "$HIDDEN, символов: ${payload.length}"
    } else {
        payload
    }

/**
 * Класс мутации у исхода.
 *
 * У `Departed` его нет в самом исходе: устройство ушло, и единственное, что о
 * команде известно, — что она была перезагрузкой. У `NotStarted` он берётся из
 * разбора строки, потому что команда не уходила и ответа нет вовсе.
 */
internal fun mutationClassOf(outcome: FastbootMutationOutcome): FastbootMutationClass = when (outcome) {
    is FastbootMutationOutcome.Applied -> outcome.mutation
    is FastbootMutationOutcome.Refused -> outcome.mutation
    is FastbootMutationOutcome.Unconfirmed -> outcome.mutation
    is FastbootMutationOutcome.Unknown -> outcome.mutation
    is FastbootMutationOutcome.Departed -> FastbootMutationClass.REBOOT
    is FastbootMutationOutcome.NotStarted -> FastbootMutation.classify(outcome.command)
}

/** Вопросы, ответ на которые в выгрузку не идёт. Совпадение — по вопросу. */
private val SECRET_ANSWERS = listOf("token")

private const val HIDDEN = "не записано"

internal fun describe(outcome: FastbootMutationOutcome): String = when (outcome) {
    is FastbootMutationOutcome.Applied -> "выполнено"
    is FastbootMutationOutcome.Refused -> "устройство отказало: ${outcome.detail}"
    is FastbootMutationOutcome.Unconfirmed -> "OKAY без подтверждения: ${outcome.detail}"
    is FastbootMutationOutcome.Departed -> "устройство ушло, не ответив"
    is FastbootMutationOutcome.Unknown -> "неизвестно: ${outcome.detail}"
    is FastbootMutationOutcome.NotStarted -> "не отправлено: ${outcome.detail}"
}

internal fun mutationFields(state: FastbootConsoleState.Mutated): Map<String, String> {
    val outcome = state.outcome
    val common = mapOf(
        "command" to outcome.command,
        "mutation" to mutationClassOf(outcome).name,
        "lane" to state.lane.name,
    )
    return common + when (outcome) {
        is FastbootMutationOutcome.Applied -> mapOf(
            "claim" to "applied",
            "reply" to "OKAY",
            "payload" to journalledPayload(outcome.command, outcome.payload),
            "infoLines" to outcome.info.size.toString(),
            "confirmation" to (outcome.confirmation ?: "none"),
        )

        is FastbootMutationOutcome.Refused -> mapOf(
            "claim" to "refused",
            "reply" to "FAIL",
            "detail" to outcome.detail,
        )

        is FastbootMutationOutcome.Unconfirmed -> mapOf(
            "claim" to "unconfirmed",
            "reply" to "OKAY",
            "expected" to outcome.expected,
            "observed" to outcome.observed,
        )

        is FastbootMutationOutcome.Departed -> mapOf(
            "claim" to "departed",
            "reply" to "none",
            "waitedMillis" to outcome.waitedMillis.toString(),
            "infoLines" to outcome.info.size.toString(),
        )

        is FastbootMutationOutcome.Unknown -> mapOf(
            "claim" to "unknown",
            "reply" to "none",
            "detail" to outcome.detail,
        )

        is FastbootMutationOutcome.NotStarted -> mapOf(
            "claim" to "not_started",
            "reply" to "none",
            "detail" to outcome.detail,
        )
    }
}
