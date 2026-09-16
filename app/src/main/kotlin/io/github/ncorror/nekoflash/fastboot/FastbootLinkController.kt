package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactWriteOutcome
import io.github.ncorror.nekoflash.core.artifact.ArtifactWriter
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootIdentity
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockProbe
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockStatus
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.payload.DigestingSink
import io.github.ncorror.nekoflash.payload.GeneratedPayload
import io.github.ncorror.nekoflash.payload.GeneratedPayloadStream
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownload
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownloadOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootFetch
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootFetchOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootModeProbe
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutation
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationClass
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlan
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlanOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
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
        /**
         * Замок **этой** generation.
         *
         * Принадлежит текущей сессии и в новую не переезжает: после
         * перезагрузки старая generation инвалидируется, и замок определяется
         * заново (`03` §5.1). Ничего не разрешает и не запрещает — задаёт
         * форму предупреждения перед разрушающим действием.
         */
        val lock: FastbootLockStatus,
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

    /** Замеры чтений. Голова каждого обмена, чтобы не вытеснить журнал собой. */
    private val reads = FastbootReadRecorder(::emit)

    /**
     * Один lock владеет link lifecycle и запуском transaction.
     *
     * Executor может быть многопоточным, поэтому single-lane invariant нельзя
     * оставлять соглашением вызывающего. Epoch инвалидирует любой async-result,
     * начатый до disconnect/reconnect.
     */
    private val lifecycleLock = Any()
    private var lifecycleEpoch = 0L
    private var operationSerial = 0L
    private var activeOperation: Long? = null

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
        val epoch = synchronized(lifecycleLock) {
            if (mutableState.value is FastbootLinkState.Probing) return

            // Новый connect — новый ownership epoch. Старый handle/lane больше
            // не вправе публиковать ни state, ни console result.
            lifecycleEpoch += 1L
            activeOperation = null
            lane?.close()
            lane = null
            handle?.close()
            handle = null
            mutableConsole.value = FastbootConsoleState.Idle
            mutableState.value = FastbootLinkState.Probing(generation)
            lifecycleEpoch
        }

        when (val claimed = claim(generation)) {
            is UsbClaimResult.Failed -> {
                emit("fastboot_claim_failed", mapOf("reason" to claimed.reason.name))
                synchronized(lifecycleLock) {
                    val probing = (mutableState.value as? FastbootLinkState.Probing)?.generation
                    if (lifecycleEpoch == epoch && probing == generation) {
                        mutableState.value = FastbootLinkState.Failed(generation, claimed.reason.name)
                    }
                }
            }

            is UsbClaimResult.Claimed -> {
                val accepted = synchronized(lifecycleLock) {
                    val probing = (mutableState.value as? FastbootLinkState.Probing)?.generation
                    if (lifecycleEpoch == epoch && probing == generation) {
                        handle = claimed.handle
                        true
                    } else {
                        false
                    }
                }
                if (accepted) {
                    executor.execute { probe(generation, claimed.handle, epoch) }
                } else {
                    claimed.handle.close()
                }
            }
        }
    }

    /**
     * Забывает соединение, чья сессия закрылась без нас.
     *
     * Устройство отключили, перезагрузили или подменили другим — generation при
     * этом инвалидируется, а полоса, ручка и исход последней команды остаются
     * висеть. Прогон `07` §6.96 показал, чем это кончается: F5 отключили,
     * подключили `vayu`, и `fetch:` ответил `полоса занята: STALLED` **полосой
     * отключённого телефона**. Экран при этом всё ещё показывал роль и замок
     * прошлого аппарата.
     *
     * Для замка это не косметика. `lock` принадлежит **своей** generation
     * (`03` §5.1), и показывать `UNLOCKED` от снятого телефона рядом с
     * подключённым запертым значит задать не ту форму предупреждения перед
     * разрушающим действием.
     *
     * Вызов с чужой generation ничего не делает: забывается только то
     * соединение, о котором сказано.
     */
    public fun forget(generation: SessionGeneration) {
        val forgotten = synchronized(lifecycleLock) {
            if (generationOf(mutableState.value) != generation) {
                false
            } else {
                disconnectLocked()
                true
            }
        }
        if (forgotten) {
            emit("fastboot_forgotten", mapOf("generation" to generation.value.toString()))
        }
    }

    /**
     * Сверяет Fastboot ownership с application-scoped USB registry.
     *
     * Это transport lifecycle, а не UI lifecycle: отключение/ручной release
     * обязаны инвалидировать link даже когда Activity не нарисована.
     */
    public fun onUsbSessionsChanged(sessions: List<UsbSession>) {
        val shown = mutableState.value
        val generation = generationOf(shown) ?: return
        val alive = sessions.any { session ->
            session.generation == generation && !session.closed && when (shown) {
                is FastbootLinkState.Connected,
                is FastbootLinkState.Probing -> session.state == UsbSessionState.CLAIMED

                is FastbootLinkState.Failed -> true
                FastbootLinkState.Idle -> false
            }
        }
        if (!alive) forget(generation)
    }

    /** Отпускает интерфейс. Повторный вызов безопасен. */
    public fun disconnect() {
        synchronized(lifecycleLock) { disconnectLocked() }
    }

    private fun disconnectLocked() {
        lifecycleEpoch += 1L
        activeOperation = null
        lane?.close()
        lane = null
        handle?.close()
        handle = null
        mutableConsole.value = FastbootConsoleState.Idle
        mutableState.value = FastbootLinkState.Idle
    }

    private fun generationOf(state: FastbootLinkState): SessionGeneration? = when (state) {
        is FastbootLinkState.Connected -> state.generation
        is FastbootLinkState.Probing -> state.generation
        is FastbootLinkState.Failed -> state.generation
        FastbootLinkState.Idle -> null
    }

    /**
     * Отправляет команду как набрана.
     *
     * Ни имя, ни форма не проверяются и не ограничиваются списком: что бывает у
     * Fastboot, знает устройство, и его `FAIL` — это ответ, а не наша ошибка
     * (`01` §3). Отказать полоса может только по двум причинам провода, и обе
     * она называет: команда не передаётся в ASCII либо не помещается в кадр.
     *
     * **Через этот же вызов идут кнопки типизованной секции.** Они не ходят в
     * полосу мимо него и не строят свой обмен: кнопка собирает строку через
     * `FastbootCommands` и отдаёт её сюда. Так «один движок» становится
     * проверяемым утверждением, а не обещанием: второго пути просто нет.
     *
     * Исход читается через границу мутации (`03` §3), и для набранной руками
     * команды это так же важно, как для кнопки: оборванный `erase:` обязан
     * читаться как неизвестное состояние раздела, а не как «ответа нет».
     */
    public fun runCommand(command: String) {
        busy("fastboot_command", command) { lane, trimmed ->
            FastbootConsoleState.Mutated(
                outcome = FastbootMutation(lane, FastbootGetVar(lane)).run(trimmed),
                lane = lane.state,
            )
        }
    }

    /**
     * Выполняет план — последовательность команд как одно действие.
     *
     * Второго пути к полосе не заводит: каждый шаг уходит тем же
     * `FastbootMutation`, что и набранная руками команда. План не добавляет
     * возможностей — он показывает оператору весь список **до** нажатия и
     * останавливается на первом непринятом шаге, чтобы не складывать
     * неизвестность с неизвестностью.
     */
    public fun runPlan(commands: List<String>) {
        busy("fastboot_plan", commands.joinToString(" ; ")) { lane, _ ->
            val outcome = FastbootPlan(commands).run(FastbootMutation(lane, FastbootGetVar(lane)))
            planned(commands, outcome, lane.state)
        }
    }

    private fun planned(
        commands: List<String>,
        outcome: FastbootPlanOutcome,
        lane: FastbootLaneState,
    ): FastbootConsoleState = when (outcome) {
        is FastbootPlanOutcome.Completed -> FastbootConsoleState.Planned(
            commands = commands,
            applied = outcome.applied.size,
            stoppedAt = null,
            detail = "",
            lane = lane,
        )

        is FastbootPlanOutcome.Stopped -> FastbootConsoleState.Planned(
            commands = commands,
            applied = outcome.applied.size,
            stoppedAt = outcome.index,
            detail = "${outcome.command}: ${describe(outcome.outcome)}",
            lane = lane,
        )
    }

    /** Короткое слово об исходе шага — то же, что пишется в `claim`. */
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

    /**
     * Загружает в буфер устройства **выбранный пользователем** файл.
     *
     * Произвольный доступ здесь не нужен: фаза данных Fastboot читает источник
     * подряд, и стажировать поэтому нечего — этим `download:` и отличается от
     * Sideload, где Recovery просит блоки в своём порядке.
     *
     * Размер обязан быть известен заранее: он объявляется в самой команде
     * восемью шестнадцатеричными цифрами. Источник, длины которого провайдер не
     * сообщает, поэтому отвергается **до** первого байта, а не на середине.
     *
     * **Ничего не прошивает**, как и [downloadGenerated].
     */
    public fun downloadFrom(origin: () -> ArtifactSource) {
        busy("fastboot_download_file", "выбранный файл") { lane, _ ->
            val source = origin()
            val size = source.identity.sizeBytes
            if (size == null) {
                FastbootConsoleState.NotAnswered(
                    command = "download:",
                    detail = "провайдер не сообщает размер файла, " +
                        "а download: обязан объявить его до первого байта",
                    lane = lane.state,
                )
            } else {
                source.open().use { stream ->
                    downloaded(size, FastbootDownload(lane).send(stream, size), lane.state)
                }
            }
        }
    }

    /** Читает раздел в место, выбранное пользователем. */
    public fun fetchPartitionTo(partition: String, destination: () -> ArtifactSink) {
        busy("fastboot_fetch_file", partition) { lane, name ->
            val sink = destination()
            val writer = ArtifactWriter(sink)
            val outcome = FastbootFetch(lane, FastbootGetVar(lane)).fetch(name, WriterStream(writer))
            saved(name, writer, outcome, lane.state)
        }
    }

    /** Приёмник Fastboot говорит на `OutputStream`; запись артефакта — на кусках. */

    /**
     * Читает раздел с устройства — фаза DATA IN.
     *
     * Содержимое нигде не собирается: приёмник считает байты и отпечаток, как
     * это уже принято для ADB `pull` (`07` §6.32). Сохранение в пользовательское
     * место требует artifact sink из Phase 8.
     *
     * **Ничего не меняет.** `fetch:` только читает; на запертом загрузчике
     * устройство, скорее всего, откажет — Legacy предупреждает об этом и
     * запрещать не пытается, решение принадлежит устройству.
     */
    public fun fetchPartition(partition: String) {
        busy("fastboot_fetch", partition) { lane, name ->
            val sink = DigestingSink()
            val outcome = FastbootFetch(lane, FastbootGetVar(lane))
                .fetch(name, sink)
            fetched(name, sink, outcome, lane.state)
        }
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
        val trimmed = raw.trim()
        val start = synchronized(lifecycleLock) {
            val live = lane
            when {
                live == null -> {
                    mutableConsole.value =
                        FastbootConsoleState.NotAnswered(trimmed, "соединения нет", FastbootLaneState.CLOSED)
                    null
                }

                activeOperation != null -> null

                else -> {
                    reads.reset()
                    operationSerial += 1L
                    val operation = operationSerial
                    activeOperation = operation
                    mutableConsole.value = FastbootConsoleState.Running(trimmed, clock().toEpochMilli())
                    PendingOperation(operation, lifecycleEpoch, live)
                }
            }
        } ?: return

        executor.execute {
            emit(event, mapOf("command" to trimmed))
            val outcome = runCatching { action(start.lane, trimmed) }.getOrElse { failure ->
                FastbootConsoleState.NotAnswered(
                    command = trimmed,
                    detail = failure.message ?: failure.javaClass.simpleName,
                    lane = start.lane.state,
                )
            }

            val published = synchronized(lifecycleLock) {
                if (activeOperation == start.id) activeOperation = null
                if (lifecycleEpoch == start.epoch && lane === start.lane) {
                    mutableConsole.value = outcome
                    true
                } else {
                    false
                }
            }
            if (published) record(event, outcome)
        }
    }

    private data class PendingOperation(
        val id: Long,
        val epoch: Long,
        val lane: FastbootLane,
    )

    private fun record(event: String, state: FastbootConsoleState) {
        val fields = when (state) {
            is FastbootConsoleState.Answered -> mapOf(
                "command" to state.command,
                "reply" to state.reply.name,
                "payload" to journalledPayload(state.command, state.payload),
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

            // Класс мутации и утверждение о состоянии пишутся всегда, включая
            // команды, набранные руками. Без класса запись «ответа не было» не
            // отличить от «раздел в неизвестном состоянии», а это ровно та
            // разница, ради которой написан `03` §3.
            is FastbootConsoleState.Mutated -> mutationFields(state)

            // Полнота пишется отдельным полем, а не выводится из числа байт:
            // прочитанный целиком маленький раздел и оборванный большой дают
            // одинаково правдоподобные числа.
            is FastbootConsoleState.Fetched -> mapOf(
                "partition" to state.partition,
                "bytes" to state.bytes.toString(),
                "sha256" to state.sha256,
                "complete" to state.complete.toString(),
                "detail" to state.detail,
                "lane" to state.lane.name,
            )

            is FastbootConsoleState.Planned -> mapOf(
                "commands" to state.commands.joinToString(" ; "),
                "steps" to state.commands.size.toString(),
                "applied" to state.applied.toString(),
                // Место остановки пишется отдельно от числа сделанного: по
                // одному только счётчику не отличить «всё прошло» от «первый шаг
                // прошёл, а второго не было».
                "stoppedAt" to (state.stoppedAt?.toString() ?: "none"),
                "detail" to state.detail,
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

    /**
     * Исход мутации в поля журнала.
     *
     * `claim` — то, что можно утверждать о состоянии устройства, одним словом:
     * по нему прогон разбирается без чтения текста. `applied` означает, что
     * устройство сказало «сделал»; `refused` — что оно сказало «не сделал», и
     * доказательством целости раздела это не является; `unknown` — что мы не
     * знаем; `departed` — что устройство ушло по нашей же просьбе.
     */
    /**
     * Значение ответа так, как оно попадёт в **выгрузку диагностики**.
     *
     * Токен разблокировки в журнал не пишется. Решение об этом было принято в
     * §6.72 для `getvar:all` — там записываются имена и счётчики, но не
     * значения, — и обходилось двумя путями, которые тогда не заметили:
     * одиночное чтение переменной по имени и ответ произвольной команды. Оба
     * закрыты здесь.
     *
     * **Оператор при этом не теряет ничего**: значение показывается на экране
     * целиком, как он и просил. Скрыто оно только в архиве, который человек
     * отдаёт кому-то ещё. Это решение о **нашем отчёте**, а не ограничение
     * возможностей (`01` §3): команда уходит как набрана, ответ приходит
     * целиком, прячется одна строка в файле, который мы же и составляем.
     *
     * Совпадение ищется по **вопросу**, а не по ответу: спросили про токен —
     * значение не записываем. Это узкая заглушка на известный случай, а не
     * общее правило. Общее — чьи ещё ответы нельзя выгружать — принадлежит
     * `diagnostics privacy review` из Phase 11, и придумывать его на бегу
     * значило бы дать ложную уверенность списком, который заведомо неполон.
     */
    private fun probe(generation: SessionGeneration, claimed: UsbTransportHandle, epoch: Long) {
        // Замеры чтений идут в тот же журнал, что и всё остальное: разбор
        // прогона не должен требовать ещё одного прогона (`07` §6.96).
        val opened = FastbootLane(claimed, trace = reads)
        emit("fastboot_probe_started", mapOf("generation" to generation.value.toString()))

        val variables = FastbootGetVar(opened)
        val attempt = runCatching { FastbootModeProbe.probe(variables) }
        val identity = attempt.getOrElse { failure ->
            // Исключение — программистская ошибка, а не ответ устройства.
            // Роль от этого не становится известной, поэтому UNKNOWN с текстом.
            FastbootIdentity(FastbootMode.UNKNOWN, failure.message ?: failure.javaClass.simpleName)
        }

        // Замок читается тем же опросом, что и роль: он нужен до первого
        // разрушающего действия, а не в момент нажатия, и принадлежит этой
        // generation.
        val lock = runCatching { FastbootLockProbe.probe(variables) }.getOrElse { failure ->
            FastbootLockStatus(FastbootLockState.UNKNOWN, failure.message ?: failure.javaClass.simpleName)
        }

        val published = synchronized(lifecycleLock) {
            if (
                lifecycleEpoch == epoch &&
                handle === claimed &&
                (mutableState.value as? FastbootLinkState.Probing)?.generation == generation
            ) {
                lane = opened
                mutableState.value = FastbootLinkState.Connected(generation, identity, lock)
                true
            } else {
                false
            }
        }

        if (published) {
            emit(
                "fastboot_probe_finished",
                mapOf(
                    "mode" to identity.mode.name,
                    "detail" to identity.detail,
                    "lock" to lock.state.name,
                    "lockDetail" to lock.detail,
                    "lane" to opened.state.name,
                ),
            )
        } else {
            // Старая async-работа не имеет права воскресить уже закрытую или
            // заменённую generation. Ресурс принадлежит ей и закрывается здесь.
            opened.close()
            claimed.close()
            emit("fastboot_probe_discarded", mapOf("generation" to generation.value.toString()))
        }
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
