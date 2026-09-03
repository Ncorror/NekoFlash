package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.usb.api.UsbAutoConnectPolicy
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
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
 * Подключение происходит само, как только устройство готово: приложение
 * существует ради работы с устройством, и требовать нажатия ради того, что
 * всё равно будет нажато, — не осторожность, а лишний шаг. Так это было
 * устроено и в Legacy.
 *
 * У автоматизма есть две границы, и обе перенесены из Legacy, а не придуманы.
 * Первая: подключение автоматично только для интерфейсов, в которых мы
 * уверены (`UsbAutoConnectPolicy`) — захват исключителен, и отбирать чужое
 * устройство по одному лишь совпадению класса `0xFF` нельзя. Вторая: попытка
 * делается **один раз на поколение сессии**. Отключившись вручную,
 * пользователь остаётся отключённым; неудачное рукопожатие не повторяется по
 * кругу. Новая попытка — это новое подключение устройства.
 */
public class AdbLinkController(
    private val coordinator: UsbSessionCoordinator,
    private val keyStore: AdbKeyStore,
    private val apiLevel: Int,
    private val executor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
) {
    private val mutableState = MutableStateFlow<AdbLinkState>(AdbLinkState.Idle)

    /**
     * Поколения, к которым автоматически подключаться больше не нужно.
     *
     * Попытка была: она удалась, провалилась или пользователь отключился сам.
     * Множество живёт до конца процесса, а поколения монотонны и не
     * переиспользуются, так что перепутать их между устройствами нельзя.
     */
    private val handled = ConcurrentHashMap.newKeySet<Long>()

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
        // Второе подключение поверх живого — это второй CNXN и второй читатель.
        // Оба запрещены контрактом, поэтому отказ здесь, а не попытка.
        if (mutableState.value !is AdbLinkState.Idle && mutableState.value !is AdbLinkState.Failed) {
            return
        }
        handled.add(generation.value)
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
        handled.add(generation.value)
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
     * Сверяет соединение с действительным состоянием сессий USB.
     *
     * Соединение существует ровно столько, сколько удерживается интерфейс.
     * Отпустить его можно не только кнопкой «Отключиться»: устройство могли
     * выдернуть, сессию — закрыть, интерфейс — освободить другим путём. Во всех
     * этих случаях `UsbTransportHandle` уже закрыт, и оставлять на экране
     * «подключено» значит выдавать несуществующее за существующее.
     *
     * Прогон 2026-09-03 показал это ровно так: после освобождения интерфейса
     * экран продолжал утверждать, что ADB подключён (`07` §6.12).
     */
    public fun onUsbSessionsChanged(sessions: List<UsbSession>) {
        dropLinkIfInterfaceNoLongerHeld(sessions)
        connectToNewlyReadyDevice(sessions)
    }

    private fun dropLinkIfInterfaceNoLongerHeld(sessions: List<UsbSession>) {
        when (val state = mutableState.value) {
            AdbLinkState.Idle -> Unit

            // Живое соединение существует ровно столько, сколько удерживается
            // интерфейс.
            is AdbLinkState.Connecting -> dropUnless(state.generation, sessions, ::isHeld)
            is AdbLinkState.WaitingForAuthorization -> dropUnless(state.generation, sessions, ::isHeld)
            is AdbLinkState.Connected -> dropUnless(state.generation, sessions, ::isHeld)

            // Причина отказа остаётся на экране до тех пор, пока устройство то
            // же самое: она единственное, что есть у пользователя для разбора.
            // Но пережить это устройство она не должна — иначе одна неудача
            // отменила бы автоподключение до перезапуска приложения.
            is AdbLinkState.Failed -> dropUnless(state.generation, sessions, ::isPresent)
        }
    }

    private fun dropUnless(
        generation: SessionGeneration,
        sessions: List<UsbSession>,
        alive: (UsbSession) -> Boolean,
    ) {
        if (sessions.none { it.generation == generation && alive(it) }) {
            mutableState.value = AdbLinkState.Idle
        }
    }

    private fun isHeld(session: UsbSession): Boolean =
        !session.closed && session.state == UsbSessionState.CLAIMED

    private fun isPresent(session: UsbSession): Boolean = !session.closed

    /**
     * Подключается к устройству, которое только что стало готовым.
     *
     * Занятость проверяется по состоянию, а не по флагу: пока идёт одно
     * рукопожатие, второе начинать нельзя — физический читатель один.
     */
    private fun connectToNewlyReadyDevice(sessions: List<UsbSession>) {
        if (mutableState.value !is AdbLinkState.Idle) return
        val candidate = sessions.firstOrNull { session ->
            !session.closed &&
                session.state == UsbSessionState.READY &&
                session.candidate.kind == UsbInterfaceKind.ADB &&
                UsbAutoConnectPolicy.allowsAutomaticConnect(session.candidate) &&
                !handled.contains(session.generation.value)
        } ?: return
        connect(candidate.generation)
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
