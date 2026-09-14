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
