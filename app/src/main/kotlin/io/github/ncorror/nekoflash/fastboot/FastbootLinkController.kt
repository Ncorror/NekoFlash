package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootIdentity
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootModeProbe
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import java.time.Instant
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что сейчас известно про Fastboot-соединение. */
public sealed interface FastbootLinkState {
    /** Соединения нет. */
    public data object Idle : FastbootLinkState

    /** Интерфейс захвачен, роль ещё выясняется. */
    public data class Probing(val generation: SessionGeneration) : FastbootLinkState

    /**
     * Устройство ответило.
     *
     * [identity] может нести и [FastbootMode.UNKNOWN] — это тоже результат
     * опроса, а не его отсутствие: устройство ответило, но роль по ответу не
     * устанавливается, и причина названа в `identity.detail`.
     */
    public data class Connected(
        val generation: SessionGeneration,
        val identity: FastbootIdentity,
    ) : FastbootLinkState

    /** До обмена дело не дошло. */
    public data class Failed(val generation: SessionGeneration, val detail: String) : FastbootLinkState
}

/**
 * Владелец Fastboot-соединения на уровне приложения.
 *
 * Отдельный от `AdbLinkController` по существу, а не для симметрии: устройство
 * в загрузчике перечисляется **другим интерфейсом**, ADB там не отвечает вовсе,
 * и смены роли (`07` §6.46: `reboot bootloader` поднял generation с интерфейсом
 * **FASTBOOT**) это ровно тот переход, ради которого обе стороны существуют
 * порознь.
 *
 * Полоса обмена здесь одна и синхронная — так устроен сам Fastboot, см.
 * `FastbootLane`. Поэтому владелец ничего не мультиплексирует и не пытается:
 * это не упрощение, а свойство протокола.
 */
public class FastbootLinkController(
    /**
     * Откуда берётся захваченный интерфейс.
     *
     * Швом, а не целым координатором: владельцу нужен один вызов, и зависеть
     * от всего USB-слоя ради него значило бы тащить в тест то, что к делу не
     * относится. В production сюда приходит `UsbSessionCoordinator::claim`.
     */
    private val claim: (SessionGeneration) -> UsbClaimResult,
    private val executor: Executor,
    private val diagnostics: DiagnosticSink,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutableState = MutableStateFlow<FastbootLinkState>(FastbootLinkState.Idle)

    /** Состояние соединения. */
    public val state: StateFlow<FastbootLinkState> = mutableState.asStateFlow()

    private var lane: FastbootLane? = null
    private var handle: UsbTransportHandle? = null

    /**
     * Захватывает интерфейс и спрашивает устройство, кто оно.
     *
     * Опрос уходит на исполнитель: ответа ждать до семи секунд, и держать этим
     * поток раскладки нельзя.
     */
    public fun connect(generation: SessionGeneration) {
        if (mutableState.value is FastbootLinkState.Probing) return
        mutableState.value = FastbootLinkState.Probing(generation)

        when (val claimed = claim(generation)) {
            is UsbClaimResult.Failed -> {
                emit("fastboot_claim_failed", mapOf("reason" to claimed.reason.name))
                mutableState.value = FastbootLinkState.Failed(generation, claimed.reason.name)
            }

            is UsbClaimResult.Claimed -> executor.execute { probe(generation, claimed.handle) }
        }
    }

    /** Отпускает интерфейс. Повторный вызов безопасен. */
    public fun disconnect() {
        lane?.close()
        lane = null
        handle?.close()
        handle = null
        mutableState.value = FastbootLinkState.Idle
    }

    private fun probe(generation: SessionGeneration, claimed: UsbTransportHandle) {
        handle = claimed
        val opened = FastbootLane(claimed)
        lane = opened
        emit("fastboot_probe_started", mapOf("generation" to generation.value.toString()))

        val attempt = runCatching { FastbootModeProbe.probe(FastbootGetVar(opened)) }
        val identity = attempt.getOrElse { failure ->
            // Исключение — программистская ошибка, а не ответ устройства.
            // Роль от этого не становится известной, поэтому UNKNOWN с текстом.
            FastbootIdentity(FastbootMode.UNKNOWN, failure.message ?: failure.javaClass.simpleName)
        }

        emit(
            "fastboot_probe_finished",
            mapOf(
                "mode" to identity.mode.name,
                "detail" to identity.detail,
                "lane" to opened.state.name,
            ),
        )
        mutableState.value = FastbootLinkState.Connected(generation, identity)
    }

    private fun emit(message: String, fields: Map<String, String>) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    private companion object {
        const val DIAGNOSTIC_CATEGORY = "fastboot"
    }
}
