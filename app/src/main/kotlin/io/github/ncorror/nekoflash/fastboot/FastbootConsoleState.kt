package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariableSnapshot

/**
 * Что вышло из последнего обмена по Fastboot.
 *
 * Состояние полосы входит в каждый исход, кроме простоя, и это не отладочная
 * подробность: по нему видно, можно ли слать следующую команду. У Fastboot
 * полоса одна (ADR-0006 §1), и потеря рамки — липкая, поэтому скрыть её от
 * оператора значило бы дать ему нажимать кнопку, которая заведомо не сработает.
 */
public sealed interface FastbootConsoleState {
    /** Ничего не отправлялось. */
    public data object Idle : FastbootConsoleState

    /** Команда ушла, ответа ещё нет. */
    public data class Running(val command: String) : FastbootConsoleState

    /**
     * Устройство ответило терминально.
     *
     * `FAIL` приходит сюда же, как и `OKAY`: отказ — это ответ устройства
     * (`03` §2), и показывать его иначе, чем согласие, значило бы подменять
     * слово peer'а своей оценкой. Отличаются они [reply], а не местом.
     */
    public data class Answered(
        val command: String,
        val reply: FastbootReply,
        val payload: String,
        val info: List<String>,
        val lane: FastbootLaneState,
    ) : FastbootConsoleState

    /**
     * Ответа не получили либо команду не отправили.
     *
     * От [Answered] с `FAIL` отличается принципиально: там устройство сказало
     * «нет», здесь мы не знаем, что оно сказало.
     */
    public data class NotAnswered(
        val command: String,
        val detail: String,
        val lane: FastbootLaneState,
    ) : FastbootConsoleState

    /** Ответ на `getvar:all` — разобранный список переменных. */
    public data class Variables(
        val snapshot: FastbootVariableSnapshot,
        val lane: FastbootLaneState,
    ) : FastbootConsoleState
}
