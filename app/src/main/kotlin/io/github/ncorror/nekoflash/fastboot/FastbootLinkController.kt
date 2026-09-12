package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootIdentity
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.payload.GeneratedPayload
import io.github.ncorror.nekoflash.payload.GeneratedPayloadStream
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownload
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownloadOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootExchange
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootModeProbe
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable
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

    private val mutableConsole = MutableStateFlow<FastbootConsoleState>(FastbootConsoleState.Idle)

    /** Исход последнего обмена. */
    public val console: StateFlow<FastbootConsoleState> = mutableConsole.asStateFlow()

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
        mutableConsole.value = FastbootConsoleState.Idle
        mutableState.value = FastbootLinkState.Idle
    }

    /**
     * Отправляет команду как набрана.
     *
     * Ни имя, ни форма не проверяются и не ограничиваются списком: что бывает у
     * Fastboot, знает устройство, и его `FAIL` — это ответ, а не наша ошибка
     * (`01` §3). Отказать полоса может только по двум причинам провода, и обе
     * она называет: команда не передаётся в ASCII либо не помещается в кадр.
     */
    public fun runCommand(command: String) {
        busy("fastboot_command", command) { lane, trimmed -> report(trimmed, lane.run(trimmed), lane) }
    }

    /** Спрашивает одну переменную по имени. */
    public fun readVariable(name: String) {
        busy("fastboot_getvar", name) { lane, trimmed ->
            val variable = FastbootGetVar(lane).read(trimmed)
            when (variable) {
                is FastbootVariable.Present -> FastbootConsoleState.Answered(
                    command = "getvar:$trimmed",
                    reply = FastbootReply.OKAY,
                    payload = variable.value,
                    info = emptyList(),
                    lane = lane.state,
                )

                is FastbootVariable.Unsupported -> FastbootConsoleState.Answered(
                    command = "getvar:$trimmed",
                    reply = FastbootReply.FAIL,
                    payload = variable.detail,
                    info = emptyList(),
                    lane = lane.state,
                )

                is FastbootVariable.Unavailable ->
                    FastbootConsoleState.NotAnswered("getvar:$trimmed", variable.detail, lane.state)
            }
        }
    }

    /** Спрашивает всё, что устройство готово рассказать. */
    public fun readAllVariables() {
        busy("fastboot_getvar_all", "all") { lane, _ ->
            FastbootConsoleState.Variables(FastbootGetVar(lane).readAll(), lane.state)
        }
    }

    /**
     * Загружает в буфер устройства [sizeBytes] порождённых приложением байт.
     *
     * Содержимое своё, а не выбранный файл: выбор файла — это artifact source
     * из Phase 8. Для протокольного пути этого достаточно, и тот же приём уже
     * принят для sync `SEND` (`07` §6.36).
     *
     * **Ничего не прошивает.** `download:` наполняет буфер загрузки; раздел
     * меняет `flash:`, которого в этой фазе нет вовсе.
     */
    public fun downloadGenerated(sizeBytes: Long) {
        busy("fastboot_download", sizeBytes.toString()) { lane, _ ->
            val outcome = FastbootDownload(lane).send(
                source = GeneratedPayloadStream(GeneratedPayload(sizeBytes)),
                sizeBytes = sizeBytes,
            )
            downloaded(sizeBytes, outcome, lane.state)
        }
    }

    private fun downloaded(
        declared: Long,
        outcome: FastbootDownloadOutcome,
        lane: FastbootLaneState,
    ): FastbootConsoleState = when (outcome) {
        is FastbootDownloadOutcome.Answered -> FastbootConsoleState.Downloaded(
            declaredBytes = declared,
            sentBytes = outcome.bytesSent,
            reply = outcome.reply,
            detail = outcome.payload,
            // Байты дошли все, но принял ли их приёмник — сказало устройство.
            // Про «не изменилось» речи нет: буфер наполнен.
            untouched = false,
            lane = lane,
        )

        // Единственный исход, про который можно честно сказать, что состояние
        // устройства не тронуто: ни одного байта не ушло.
        is FastbootDownloadOutcome.Refused -> FastbootConsoleState.Downloaded(
            declaredBytes = declared,
            sentBytes = 0L,
            reply = FastbootReply.FAIL,
            detail = outcome.detail,
            untouched = true,
            lane = lane,
        )

        is FastbootDownloadOutcome.Unknown -> FastbootConsoleState.Downloaded(
            declaredBytes = outcome.expectedBytes,
            sentBytes = outcome.bytesSent,
            reply = null,
            detail = outcome.detail,
            untouched = false,
            lane = lane,
        )

        // Обмен не начался: команда не ушла, устройство её не видело.
        is FastbootDownloadOutcome.NotStarted -> FastbootConsoleState.Downloaded(
            declaredBytes = declared,
            sentBytes = 0L,
            reply = null,
            detail = outcome.detail,
            untouched = true,
            lane = lane,
        )
    }

    /**
     * Выполняет обмен, если полоса свободна.
     *
     * Второй обмен поверх идущего не ставится в очередь и не отбрасывается
     * молча: у Fastboot полоса одна, и очередь здесь означала бы, что оператор
     * не знает, когда его команда уйдёт. Отказ виден сразу.
     */
    private fun busy(
        event: String,
        raw: String,
        action: (FastbootLane, String) -> FastbootConsoleState,
    ) {
        val live = lane
        val trimmed = raw.trim()
        when {
            live == null -> mutableConsole.value =
                FastbootConsoleState.NotAnswered(trimmed, "соединения нет", FastbootLaneState.CLOSED)

            mutableConsole.value is FastbootConsoleState.Running -> Unit

            else -> {
                mutableConsole.value = FastbootConsoleState.Running(trimmed)
                executor.execute {
                    emit(event, mapOf("command" to trimmed))
                    val outcome = runCatching { action(live, trimmed) }
                    mutableConsole.value = outcome.getOrElse { failure ->
                        FastbootConsoleState.NotAnswered(
                            command = trimmed,
                            detail = failure.message ?: failure.javaClass.simpleName,
                            lane = live.state,
                        )
                    }
                    record(event, mutableConsole.value)
                }
            }
        }
    }

    private fun report(command: String, exchange: FastbootExchange, live: FastbootLane): FastbootConsoleState =
        when (exchange) {
            is FastbootExchange.Completed ->
                FastbootConsoleState.Answered(command, exchange.reply, exchange.payload, exchange.info, live.state)

            is FastbootExchange.TimedOut -> FastbootConsoleState.NotAnswered(
                command = command,
                detail = "ответа не было ${exchange.waitedMillis} мс",
                lane = live.state,
            )

            // Фаза данных открыта, а передавать нечего: рамка потеряна, и
            // сказать об этом обязательно. Передача данных — пункт Phase 5,
            // который ещё не начат.
            is FastbootExchange.DataPhase -> {
                live.stall()
                FastbootConsoleState.NotAnswered(
                    command = command,
                    detail = "устройство ждёт ${exchange.declaredSize ?: "?"} байт, передавать их пока нечем",
                    lane = live.state,
                )
            }

            is FastbootExchange.NotReady ->
                FastbootConsoleState.NotAnswered(command, "полоса занята: ${exchange.state}", live.state)

            is FastbootExchange.NotSent ->
                FastbootConsoleState.NotAnswered(command, exchange.reason, live.state)
        }

    private fun record(event: String, state: FastbootConsoleState) {
        val fields = when (state) {
            is FastbootConsoleState.Answered -> mapOf(
                "command" to state.command,
                "reply" to state.reply.name,
                "payload" to state.payload,
                "infoLines" to state.info.size.toString(),
                "lane" to state.lane.name,
            )

            is FastbootConsoleState.NotAnswered -> mapOf(
                "command" to state.command,
                "reply" to "none",
                "detail" to state.detail,
                "lane" to state.lane.name,
            )

            // Имена расхождений и неразобранные строки записываются, а числа
            // одни — нет. Прогон §6.72 дал `duplicates=2 ignored=2` на живом
            // устройстве, и по журналу нельзя было узнать, какие именно: видно,
            // что что-то есть, и не видно что. Тот же дефект наблюдаемости
            // стоил прогона в §6.55.
            //
            // Значения переменных при этом **не** записываются, и это
            // намеренно: среди них `token` разблокировки. Имя расхождения и
            // строка, которую не удалось разобрать, для разбора достаточны, а
            // выгружать весь ответ устройства в отчёт — нет.
            is FastbootConsoleState.Downloaded -> mapOf(
                "declaredBytes" to state.declaredBytes.toString(),
                "sentBytes" to state.sentBytes.toString(),
                "reply" to (state.reply?.name ?: "none"),
                "detail" to state.detail,
                // Ключевое поле для разбора прогона: можно ли утверждать, что
                // состояние устройства не изменилось.
                "untouched" to state.untouched.toString(),
                "lane" to state.lane.name,
            )

            is FastbootConsoleState.Variables -> mapOf(
                "variables" to state.snapshot.variables.size.toString(),
                "duplicates" to state.snapshot.duplicates.size.toString(),
                "duplicateNames" to state.snapshot.duplicates.joinToString(",") { it.name },
                "conflicting" to state.snapshot.duplicates.count { it.conflicting }.toString(),
                "ignored" to state.snapshot.ignored.size.toString(),
                "ignoredLines" to state.snapshot.ignored.joinToString(" | "),
                "complete" to state.snapshot.complete.toString(),
                "reply" to state.snapshot.finalReply.name,
                "lane" to state.lane.name,
            )

            else -> mapOf("state" to state.javaClass.simpleName)
        }
        emit(event + "_finished", fields)
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
