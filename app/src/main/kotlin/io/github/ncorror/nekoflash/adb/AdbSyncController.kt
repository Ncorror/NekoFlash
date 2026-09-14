package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.artifact.SafArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactDigest
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStability
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamps
import io.github.ncorror.nekoflash.core.artifact.ArtifactWriteOutcome
import io.github.ncorror.nekoflash.core.artifact.ArtifactWriter
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.payload.GeneratedPayload
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncDestination
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncSendOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncSession
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryBaseline
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryCorrelator
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryInstall
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryLog
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryResult
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryVerdict
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncStat
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что известно про путь на устройстве. */
public sealed interface AdbFileState {
    /** Ничего не спрашивали. */
    public data object None : AdbFileState

    /**
     * Идёт запрос.
     *
     * [bytes] — сколько уже прошло, либо `null`, если у запроса хода нет
     * (`STAT` отвечает сразу, и показывать у него ноль байт значило бы
     * изображать передачу). Ноль и «нечего показывать» — разные вещи.
     *
     * [cancellable] — можно ли ещё остановить. Не то же, что «идёт»: у `STAT`
     * останавливать нечего, а у записи после первого ушедшего блока отмена
     * остаётся возможной, но назначение после неё **неизвестно**.
     */
    public data class Busy(
        val path: String,
        val bytes: Long? = null,
        val cancellable: Boolean = false,
    ) : AdbFileState

    /** Устройство ответило про путь. */
    public data class Described(val path: String, val stat: AdbSyncStat) : AdbFileState

    /**
     * Файл прочитан целиком.
     *
     * [sha256] считается на лету и нужен для сверки: тот же файл можно
     * посчитать на устройстве командой `sha256sum` и сравнить. Совпадение
     * доказывает, что чтение побайтно верное, а не просто «что-то пришло».
     */
    public data class Read(val path: String, val bytes: Long, val sha256: String) : AdbFileState

    /**
     * Исход установки по словам Recovery.
     *
     * Отдельное состояние, а не строка в `Read`: вердикт — это не содержимое
     * файла, а вывод из него, и путать их значило бы показывать оператору
     * оценку там, где он просил данные.
     */
    public data class Verdict(
        val verdict: AdbRecoveryVerdict,
        val detail: String,
        val evidence: String?,
        val baselineTaken: Boolean,
    ) : AdbFileState

    /**
     * Файл записан, и устройство это подтвердило.
     *
     * [sha256] посчитан по тому, что хост отдал в USB. Сверка с `sha256sum` на
     * устройстве доказывает запись побайтно.
     */
    public data class Written(val path: String, val bytes: Long, val sha256: String) : AdbFileState

    /**
     * Запись не удалась.
     *
     * [destination] — отдельное поле, а не часть [reason], и это существенно:
     * причина говорит, что пошло не так, а состояние назначения — что из этого
     * следует для файла на устройстве. Из отказа не следует, что файл цел
     * (`03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §7).
     */
    public data class WriteFailed(
        val path: String,
        val reason: String,
        val destination: AdbSyncDestination,
    ) : AdbFileState

    /**
     * Файл прочитан и **сохранён туда, куда попросил пользователь**.
     *
     * [atomic] называется, а не подразумевается: у SAF атомарной замены нет, и
     * `06` §7 требует отражать это ограничение честно, а не изображать гарантию.
     */
    public data class Saved(
        val path: String,
        val destination: String,
        val bytes: Long,
        val sha256: String,
        val atomic: Boolean,
    ) : AdbFileState

    /**
     * Сохранить не вышло.
     *
     * [destinationRemoved] отвечает на вопрос, который важнее причины: остался
     * ли на выбранном месте недописанный файл. Усечённый файл там выглядит
     * целым, и заметить подмену будет уже нечем.
     */
    public data class SaveFailed(
        val path: String,
        val reason: String,
        val destinationRemoved: Boolean,
    ) : AdbFileState

    /**
     * Источник подменили между выбором и передачей.
     *
     * Отдельное состояние, а не строка в [Failed]: это не сбой, а обнаруженное
     * несоответствие, и оператору надо предложить выбрать файл заново, а не
     * чинить связь.
     */
    public data class SourceChanged(val path: String, val detail: String) : AdbFileState

    /** Не получилось. */
    public data class Failed(val path: String, val reason: String) : AdbFileState
}

/**
 * Операции с файлами устройства.
 *
 * Отделён от владельца соединения и от владельца оболочки: у всех троих разное
 * время жизни. Здесь операция живёт от запроса до ответа и не переживает его.
 *
 * Здесь и чтение, и запись. Разные правила у них не в устройстве класса, а в
 * исходе: [write] отдельно сообщает, что известно про файл назначения, потому
 * что из неудачи не следует, что он цел.
 *
 * Каждая операция открывает свою сессию `sync:` и закрывает её за собой.
 * Держать сессию между запросами можно, но незачем: открытие стоит один пакет,
 * а живая сессия занимала бы единственный читатель и мешала бы оболочке.
 */
public class AdbSyncController(
    private val executor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
) {
    private val mutableState = MutableStateFlow<AdbFileState>(AdbFileState.None)

    @Volatile
    private var running = false

    @Volatile
    private var cancelRequested = false

    /** Когда в последний раз показывали ход: строка тикает, а не мигает. */
    @Volatile
    private var shownAtMillis = 0L

    /**
     * База журнала Recovery этой сессии.
     *
     * Живёт в памяти владельца и переживанию перезапуска не подлежит: `03` §6
     * требует persistent evidence, и это работа хранилища из Phase 8, а не
     * тихая подмена здесь.
     */
    private var baseline: AdbRecoveryBaseline? = null

    /** Состояние последней операции. */
    public val state: StateFlow<AdbFileState> = mutableState.asStateFlow()

    /** Идёт ли операция прямо сейчас. */
    public val active: Boolean
        get() = running

    /**
     * Просит остановить передачу.
     *
     * Останавливает **между блоками**: посреди кадра остановиться нельзя, иначе
     * в потоке останется его половина. У записи после первого ушедшего блока
     * отмена оставляет назначение в неизвестном состоянии — и исход это скажет,
     * а не промолчит (`03` §3).
     */
    public fun cancel() {
        cancelRequested = true
    }

    /**
     * Показывает ход, но не чаще, чем раз в [PROGRESS_INTERVAL_MS].
     *
     * Без ограничения передача в сто мегабайт дала бы шесть тысяч обновлений
     * состояния — экран перерисовывался бы чаще, чем его успевают читать, и
     * тратил бы на это то самое время, за которое идёт передача.
     */
    private fun showProgress(path: String, bytes: Long, cancellable: Boolean) {
        val now = System.currentTimeMillis()
        if (now - shownAtMillis >= PROGRESS_INTERVAL_MS) {
            shownAtMillis = now
            mutableState.value = AdbFileState.Busy(path, bytes, cancellable)
        }
    }

    /** Спрашивает сведения о пути. */
    public fun describe(connection: AdbConnection, path: String) {
        start(connection, path) { session, target ->
            when (val outcome = session.stat(target)) {
                is AdbSyncOutcome.Done -> AdbFileState.Described(target, outcome.value)
                is AdbSyncOutcome.Failed -> failed(target, outcome)
            }
        }
    }

    /**
     * Читает файл целиком, считая размер и отпечаток.
     *
     * Ничего не сохраняет: это проверка чтения, а не загрузка. Сохранение в
     * доступное пользователю место — работа artifact sink из Phase 8, и делать
     * его наспех значило бы прятать файл в приватный каталог, откуда его никто
     * не достанет.
     */
    public fun read(connection: AdbConnection, path: String) {
        start(connection, path) { session, target ->
            val digest = ArtifactDigest()
            var received = 0L
            val outcome = session.receive(target, cancelRequested = { cancelRequested }) { chunk ->
                digest.update(chunk)
                received += chunk.size
                showProgress(target, received, cancellable = true)
            }
            when (outcome) {
                is AdbSyncOutcome.Done -> AdbFileState.Read(
                    path = target,
                    bytes = outcome.value,
                    sha256 = digest.hex(),
                )

                is AdbSyncOutcome.Failed -> failed(target, outcome)
            }
        }
    }

    /**
     * Читает файл устройства в место, выбранное пользователем.
     *
     * Объём заранее не сверяется намеренно. `RECV` не объявляет длины в потоке,
     * а размер из `STAT` — это размер **на момент вопроса**: журнал, который
     * пишется прямо сейчас, за время чтения вырастет. Отказать в сохранении
     * из-за этого значило бы не отдать оператору файл, который он попросил;
     * поэтому объявленный и полученный размеры показываются рядом, а решает
     * оператор.
     */
    public fun readTo(connection: AdbConnection, path: String, destination: () -> ArtifactSink) {
        start(connection, path) { session, target ->
            // Приёмник заводится **здесь**, а не в обработчике системного
            // диалога: открытие документа у чужого провайдера — это ввод-вывод,
            // и делать его на главном потоке значит подвесить экран.
            val sink = destination()
            val writer = ArtifactWriter(sink)
            var saved = 0L
            val outcome = session.receive(target, cancelRequested = { cancelRequested }) { chunk ->
                writer.accept(chunk)
                saved += chunk.size
                showProgress(target, saved, cancellable = true)
            }
            when (outcome) {
                is AdbSyncOutcome.Done -> saved(target, writer.finish(), sink)
                is AdbSyncOutcome.Failed ->
                    saveFailed(target, writer, sink, "${outcome.reason.name}: ${outcome.detail}")
            }
        }
    }

    private fun saved(path: String, outcome: ArtifactWriteOutcome, sink: ArtifactSink): AdbFileState =
        when (outcome) {
            is ArtifactWriteOutcome.Committed -> AdbFileState.Saved(
                path = path,
                destination = outcome.destination,
                bytes = outcome.bytes,
                sha256 = outcome.sha256,
                atomic = outcome.atomic,
            )

            is ArtifactWriteOutcome.CountMismatch -> AdbFileState.SaveFailed(
                path = path,
                reason = "получено ${outcome.actualBytes} байт вместо ${outcome.expectedBytes}",
                destinationRemoved = removed(sink),
            )

            is ArtifactWriteOutcome.Interrupted -> AdbFileState.SaveFailed(
                path = path,
                reason = outcome.detail,
                destinationRemoved = removed(sink),
            )

            is ArtifactWriteOutcome.CommitFailed -> AdbFileState.SaveFailed(
                path = path,
                reason = outcome.detail,
                destinationRemoved = removed(sink),
            )
        }

    /**
     * Обрыв чтения: написанное убирается, и говорится, убралось ли.
     *
     * Недописанный файл на месте, выбранном пользователем, выглядит целым —
     * это единственное, что здесь по-настоящему важно.
     */
    private fun saveFailed(
        path: String,
        writer: ArtifactWriter,
        sink: ArtifactSink,
        reason: String,
    ): AdbFileState {
        writer.interrupted(reason)
        return AdbFileState.SaveFailed(path, reason, removed(sink))
    }

    /** У приёмника SAF удаление возможно не всегда; ответ нужен как есть. */
    private fun removed(sink: ArtifactSink): Boolean =
        (sink as? SafArtifactSink)?.removed() ?: true

    /**
     * Пишет на устройство файл, выбранный пользователем.
     *
     * Перед первым байтом источник проверяется на подмену. Это не паранойя:
     * между выбором файла в системном диалоге и нажатием кнопки проходит
     * сколько угодно времени, а операция мутирующая (`06` §6).
     *
     * «Сравнить нечем» подменой **не** считается и работу не останавливает:
     * провайдер, не сообщающий ни времени изменения, ни версии, — обычное дело,
     * и запретить из-за него запись значило бы ввести ограничение там, где есть
     * только незнание.
     */
    public fun writeFrom(connection: AdbConnection, path: String, origin: () -> ArtifactSource) {
        start(connection, path) { session, target ->
            // Источник открывается здесь по той же причине, что и приёмник:
            // разговор с чужим провайдером — это ввод-вывод.
            val source = origin()
            val stability = ArtifactStamps.compare(source.openedStamp, source.stamp())
            if (stability is ArtifactStability.Changed) {
                AdbFileState.SourceChanged(target, stability.detail)
            } else {
                sendFrom(session, target, source)
            }
        }
    }

    private fun sendFrom(session: AdbSyncSession, path: String, source: ArtifactSource): AdbFileState =
        source.open().use { input ->
            var sent = 0L
            val outcome = session.send(
                path = path,
                modifiedAtSeconds = (System.currentTimeMillis() / MILLIS_PER_SECOND).toInt(),
                cancelRequested = { cancelRequested },
            ) { buffer ->
                input.read(buffer).coerceAtLeast(0).also { read ->
                    // Показывается **прочитанное из источника**, а не
                    // подтверждённое устройством: подтверждения у `sync:` нет до
                    // самого конца, и ждать его, чтобы двинуть счётчик, значило
                    // бы держать строку неподвижной всю передачу.
                    sent += read
                    showProgress(path, sent, cancellable = true)
                }
            }
            when (outcome) {
                is AdbSyncSendOutcome.Committed -> AdbFileState.Written(path, outcome.bytesSent, outcome.sha256)
                is AdbSyncSendOutcome.Failed -> AdbFileState.WriteFailed(
                    path = path,
                    reason = "${outcome.reason.name}: ${outcome.detail}",
                    destination = outcome.destination,
                )
            }
        }

    /**
     * Снимает базу журнала Recovery — **до** Sideload.
     *
     * База это длина и отпечаток начала, а не сам журнал. Без неё вердикт
     * после установки объявить будет нельзя: успех прошлой установки,
     * оставшийся в том же файле, выглядел бы сегодняшним (`03` §6, инвариант
     * 10).
     */
    public fun captureRecoveryBaseline(connection: AdbConnection) {
        start(connection, AdbRecoveryInstall.PRIMARY_PATH) { session, target ->
            when (val outcome = readText(session, target)) {
                is TextOutcome.Read -> {
                    baseline = AdbRecoveryCorrelator.capture(AdbRecoveryLog(target, outcome.text))
                    AdbFileState.Verdict(
                        verdict = AdbRecoveryVerdict.UNKNOWN,
                        detail = "база снята: символов ${baseline?.prefixLength ?: 0}",
                        evidence = null,
                        baselineTaken = true,
                    )
                }

                is TextOutcome.Failed -> failed(target, outcome.outcome)
            }
        }
    }

    /**
     * Читает журнал Recovery и объявляет вердикт — **если** его разрешает база.
     *
     * Сам журнал никуда не сохраняется и в диагностику не выгружается: наружу
     * идут вердикт и одна строка доказательства. Журнал Recovery — это чужой
     * файл целиком, и выгружать его в отчёт по умолчанию незачем.
     */
    public fun readRecoveryVerdict(connection: AdbConnection) {
        start(connection, AdbRecoveryInstall.PRIMARY_PATH) { session, target ->
            when (val outcome = readText(session, target)) {
                is TextOutcome.Read -> verdictOf(AdbRecoveryLog(target, outcome.text))
                is TextOutcome.Failed -> failed(target, outcome.outcome)
            }
        }
    }

    private fun verdictOf(log: AdbRecoveryLog): AdbFileState {
        val result: AdbRecoveryResult = AdbRecoveryCorrelator.verdict(listOf(log), baseline)
        // Значение вердикта и одна строка доказательства — всё, что уходит
        // наружу. Текст журнала остаётся на устройстве.
        return AdbFileState.Verdict(
            verdict = result.verdict,
            detail = result.detail,
            evidence = result.evidence,
            baselineTaken = baseline != null,
        )
    }

    private fun readText(session: AdbSyncSession, path: String): TextOutcome {
        val builder = StringBuilder()
        return when (val outcome = session.receive(path) { chunk -> builder.append(String(chunk, Charsets.UTF_8)) }) {
            is AdbSyncOutcome.Done -> TextOutcome.Read(builder.toString())
            is AdbSyncOutcome.Failed -> TextOutcome.Failed(outcome)
        }
    }

    private sealed interface TextOutcome {
        data class Read(val text: String) : TextOutcome
        data class Failed(val outcome: AdbSyncOutcome.Failed) : TextOutcome
    }

    /**
     * Пишет на устройство файл из содержимого, порождённого приложением.
     *
     * Существующий файл перезаписывается без предупреждения на стороне хоста, и
     * это не упущение: перезапись — обычная профессиональная операция, `adb
     * push` тоже ни о чём не спрашивает. Предупредить о существующем файле
     * можно и сейчас — кнопкой «проверить», — а запрет на хосте был бы ровно
     * тем ограничением, которое запрещает устав.
     *
     * Исход отдельно несёт состояние назначения: при неудаче о файле на
     * устройстве известно ровно то, что доказано, и не больше.
     */
    public fun write(connection: AdbConnection, path: String, sizeBytes: Long) {
        start(connection, path) { session, target ->
            val payload = GeneratedPayload(sizeBytes)
            var sent = 0L
            val outcome = session.send(
                path = target,
                modifiedAtSeconds = (System.currentTimeMillis() / MILLIS_PER_SECOND).toInt(),
                cancelRequested = { cancelRequested },
            ) { buffer ->
                payload.fill(buffer).also { filled ->
                    sent += filled
                    showProgress(target, sent, cancellable = true)
                }
            }
            when (outcome) {
                is AdbSyncSendOutcome.Committed -> AdbFileState.Written(
                    path = target,
                    bytes = outcome.bytesSent,
                    sha256 = outcome.sha256,
                )

                is AdbSyncSendOutcome.Failed -> AdbFileState.WriteFailed(
                    path = target,
                    reason = "${outcome.reason.name}: ${outcome.detail}",
                    destination = outcome.destination,
                )
            }
        }
    }

    /**
     * @param work получает уже очищенный путь: показывать одно, а отправлять
     * устройству другое нельзя.
     */
    private fun start(
        connection: AdbConnection,
        path: String,
        work: (AdbSyncSession, String) -> AdbFileState,
    ) {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || running) return

        running = true
        cancelRequested = false
        shownAtMillis = 0L
        mutableState.value = AdbFileState.Busy(trimmed)
        executor.execute {
            val session = connection.syncSession(diagnostics)
            mutableState.value = when (val opened = session.open()) {
                is AdbSyncOutcome.Done -> runCatching { work(session, trimmed) }.getOrElse { error ->
                    AdbFileState.Failed(trimmed, error.message ?: error.javaClass.simpleName)
                }

                is AdbSyncOutcome.Failed -> failed(trimmed, opened)
            }
            session.close()
            running = false
        }
    }

    private fun failed(path: String, outcome: AdbSyncOutcome.Failed): AdbFileState.Failed =
        AdbFileState.Failed(path, "${outcome.reason.name}: ${outcome.detail}")

    private fun failed(path: String, reason: AdbSyncFailure): AdbFileState.Failed =
        AdbFileState.Failed(path, reason.name)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * Как часто двигать строку хода.
         *
         * Пять раз в секунду — читаемо для человека и дёшево для экрана.
         * Показывать каждый блок значило бы перерисовывать экран шесть тысяч
         * раз на стомегабайтной передаче и тратить на это то самое время, за
         * которое она идёт.
         */
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
