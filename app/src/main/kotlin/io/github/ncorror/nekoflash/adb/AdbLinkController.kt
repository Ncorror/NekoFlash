package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что происходит с ADB-соединением прямо сейчас. */
public sealed interface AdbLinkState {
    /** Соединения нет и не запрашивалось. */
    public data object Idle : AdbLinkState

    /** Идёт рукопожатие. */
    public data class Connecting(val generation: SessionGeneration) : AdbLinkState

    /**
     * Устройство спрашивает у пользователя, доверять ли этому хосту.
     *
     * Отдельное состояние, потому что оно требует действия человека, а не
     * ожидания: без него экран показывал бы «идёт подключение» всё время, пока
     * на устройстве висит неотвеченный диалог.
     */
    public data class WaitingForAuthorization(val generation: SessionGeneration) : AdbLinkState

    /** Соединение установлено. */
    public data class Connected(
        val generation: SessionGeneration,
        val peerMode: AdbPeerMode,
        val banner: String,
        val features: Set<String>,
    ) : AdbLinkState

    /** Соединение не состоялось. */
    public data class Failed(
        val generation: SessionGeneration,
        val reason: AdbHandshakeFailure,
        val detail: String,
    ) : AdbLinkState
}

/**
 * Владелец ADB-соединения на уровне приложения.
 *
 * Захватывает интерфейс через координатор USB и проводит рукопожатие. Захват и
 * рукопожатие живут вместе намеренно: удерживать исключительный ресурс без
 * протокольного обмена незачем, а обмен без удержания невозможен.
 *
 * Работа идёт на [executor] с **единственным** потоком. Это не деталь
 * исполнения, а требование контракта: физический читатель входящего потока
 * должен быть один. Одновременно запущенные рукопожатия разрушили бы кадр
 * ещё до того, как появился бы маршрутизатор потоков.
 *
 * Ничего не подключается само. Пользователь нажимает — приложение подключается;
 * так же устроен и захват интерфейса.
 */
public class AdbLinkController(
    private val coordinator: UsbSessionCoordinator,
    private val keyStore: AdbKeyStore,
    private val apiLevel: Int,
    private val executor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
) {
    private val mutableState = MutableStateFlow<AdbLinkState>(AdbLinkState.Idle)

    /** Состояние соединения. Экран подписывается и ничего не опрашивает. */
    public val state: StateFlow<AdbLinkState> = mutableState.asStateFlow()

    /**
     * Захватывает интерфейс сессии и проводит рукопожатие.
     *
     * Возвращается сразу: рукопожатие уходит на исполнитель, потому что
     * ожидание подтверждения на устройстве длится до минуты и заморозило бы
     * экран.
     */
    public fun connect(generation: SessionGeneration) {
        if (mutableState.value is AdbLinkState.Connecting ||
            mutableState.value is AdbLinkState.WaitingForAuthorization
        ) {
            return
        }
        mutableState.value = AdbLinkState.Connecting(generation)

        when (val claim = coordinator.claim(generation)) {
            is UsbClaimResult.Failed -> {
                mutableState.value = AdbLinkState.Failed(
                    generation = generation,
                    reason = AdbHandshakeFailure.TRANSPORT_CLOSED,
                    detail = claim.reason.name,
                )
            }

            is UsbClaimResult.Claimed -> executor.execute {
                runHandshake(generation, claim)
            }
        }
    }

    /**
     * Разрывает соединение и освобождает интерфейс.
     *
     * Отдельного «закрыть только ADB» нет: соединение и захват начинаются
     * вместе и заканчиваются вместе. Повторное подключение — это новый захват,
     * а не второй `CNXN` в том же соединении.
     */
    public fun disconnect(generation: SessionGeneration) {
        coordinator.release(generation)
        mutableState.value = AdbLinkState.Idle
    }

    private fun runHandshake(generation: SessionGeneration, claim: UsbClaimResult.Claimed) {
        val connection = AdbConnection(
            handle = claim.handle,
            keyStore = keyStore,
            apiLevel = apiLevel,
            diagnostics = diagnostics,
        )
        // Пока идёт рукопожатие, отдельного сигнала «устройство спрашивает
        // пользователя» из него не приходит: рукопожатие синхронное. Ожидание
        // подтверждения видно по тому, что оно длится, поэтому состояние
        // меняется до вызова, а не после.
        mutableState.value = AdbLinkState.WaitingForAuthorization(generation)

        val outcome = runCatching { connection.connect() }
        mutableState.value = when (val result = outcome.getOrNull()) {
            is AdbHandshakeOutcome.Connected -> AdbLinkState.Connected(
                generation = generation,
                peerMode = result.banner.peerMode,
                banner = result.banner.banner,
                features = result.banner.features,
            )

            is AdbHandshakeOutcome.Failed -> {
                releaseAfterFailure(generation)
                AdbLinkState.Failed(generation, result.reason, result.detail)
            }

            null -> {
                releaseAfterFailure(generation)
                AdbLinkState.Failed(
                    generation = generation,
                    reason = AdbHandshakeFailure.TRANSPORT_CLOSED,
                    detail = outcome.exceptionOrNull()?.let { error ->
                        error.message ?: error.javaClass.simpleName
                    } ?: "unknown",
                )
            }
        }
    }

    /**
     * Освобождает интерфейс после неудачи.
     *
     * Держать его дальше нельзя: рукопожатие на этом транспорте больше не
     * повторить, а исключительный захват мешал бы другим владельцам USB.
     */
    private fun releaseAfterFailure(generation: SessionGeneration) {
        coordinator.release(generation)
    }
}
