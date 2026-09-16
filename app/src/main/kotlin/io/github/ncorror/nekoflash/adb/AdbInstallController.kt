package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStability
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamps
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbInstall
import io.github.ncorror.nekoflash.protocol.adb.AdbInstallFile
import io.github.ncorror.nekoflash.protocol.adb.AdbInstallOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbInstallStage
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncSendOutcome
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что происходит с установкой пакета. */
public sealed interface AdbInstallState {
    /** Не начинали. */
    public data object None : AdbInstallState

    /** Идёт: файл кладётся на устройство либо работает пакетный менеджер. */
    public data class Running(val name: String, val stage: AdbInstallStage) : AdbInstallState

    /** Пакетный менеджер сказал, что поставил. */
    public data class Installed(val name: String, val output: String) : AdbInstallState

    /** Устройство отказало и назвало причину. */
    public data class Refused(
        val name: String,
        val stage: AdbInstallStage,
        val detail: String,
        val output: String,
    ) : AdbInstallState

    /**
     * Чем кончилось — неизвестно.
     *
     * Отдельное состояние, а не разновидность отказа: после обрыва на границе
     * мутации пакет мог установиться, и предлагать «повторить», как после
     * отказа, значило бы предлагать поставить поверх неизвестного (`03` §3).
     */
    public data class Unknown(
        val name: String,
        val stage: AdbInstallStage,
        val detail: String,
    ) : AdbInstallState

    /** Источник изменился между выбором и отправкой. */
    public data class SourceChanged(val name: String, val detail: String) : AdbInstallState
}

/**
 * Установка APK, выбранного пользователем.
 *
 * Стоит отдельно от [AdbSyncController], хотя и пользуется тем же `sync:`:
 * запись файла кончается файлом, а установка — **изменением состояния
 * устройства**, и исходы у них разные по существу. Слить их значило бы
 * показать «записано» там, где пакетный менеджер ещё ничего не сказал.
 *
 * Пункт `install/install-multiple` стоял в Phase 4 нерешённым не из-за
 * протокола, а потому что APK неоткуда было взять: выбор файла — это artifact
 * source из Phase 8. Он есть, и пункт разблокирован.
 */
public class AdbInstallController(
    private val executor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
) {
    private val mutableState = MutableStateFlow<AdbInstallState>(AdbInstallState.None)
    private val lifecycleLock = Any()
    private var lifecycleEpoch = 0L

    @Volatile
    private var running = false

    /** Состояние последней установки. */
    public val state: StateFlow<AdbInstallState> = mutableState.asStateFlow()

    /** Идёт ли установка прямо сейчас. */
    public val active: Boolean
        get() = running

    /**
     * Ставит один APK из выбранного источника.
     *
     * Источник открывается на исполнителе, а не здесь: разговор с чужим
     * провайдером — это ввод-вывод, и на главном потоке он подвесил бы экран.
     */
    public fun install(
        connection: AdbConnection,
        name: String,
        options: List<String>,
        origin: () -> ArtifactSource,
    ) {
        val epoch = synchronized(lifecycleLock) {
            if (running) return
            running = true
            mutableState.value = AdbInstallState.Running(name, AdbInstallStage.UPLOAD)
            lifecycleEpoch
        }
        executor.execute {
            val result = runCatching { run(connection, name, options, origin, epoch) }
                .getOrElse { error ->
                    AdbInstallState.Unknown(
                        name,
                        AdbInstallStage.UPLOAD,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            synchronized(lifecycleLock) {
                if (lifecycleEpoch == epoch) {
                    mutableState.value = result
                    running = false
                }
            }
        }
    }

    /** Инвалидирует установку, если её транспорт больше не принадлежит ADB owner. */
    internal fun invalidate() {
        synchronized(lifecycleLock) {
            lifecycleEpoch += 1L
            running = false
            mutableState.value = AdbInstallState.None
        }
    }

    private fun publish(epoch: Long, state: AdbInstallState) {
        synchronized(lifecycleLock) {
            if (lifecycleEpoch == epoch) mutableState.value = state
        }
    }

    private fun run(
        connection: AdbConnection,
        name: String,
        options: List<String>,
        origin: () -> ArtifactSource,
        epoch: Long,
    ): AdbInstallState {
        val source = origin()
        val stability = ArtifactStamps.compare(source.openedStamp, source.stamp())
        return if (stability is ArtifactStability.Changed) {
            AdbInstallState.SourceChanged(name, stability.detail)
        } else {
            stateOf(name, push(connection, name, source, options, epoch))
        }
    }

    private fun push(
        connection: AdbConnection,
        name: String,
        source: ArtifactSource,
        options: List<String>,
        epoch: Long,
    ): AdbInstallOutcome {
        val session = connection.syncSession(diagnostics)
        return try {
            when (val opened = session.open()) {
                is AdbSyncOutcome.Failed -> AdbInstallOutcome.Refused(
                    AdbInstallStage.UPLOAD,
                    "${opened.reason.name}: ${opened.detail}",
                    "",
                )

                is AdbSyncOutcome.Done -> {
                    val installer = AdbInstall(
                        diagnostics = diagnostics,
                        shell = { command -> connection.call("shell:$command", timeoutMillis = PM_TIMEOUT_MS) },
                        push = { _, remote ->
                            publish(epoch, AdbInstallState.Running(name, AdbInstallStage.UPLOAD))
                            sendTo(session, remote, source).also { failure ->
                                // Граница COMMIT начинается только после того,
                                // как временный APK действительно долетел. До
                                // этого UI не должен утверждать, что pm уже
                                // меняет пакетное состояние.
                                if (failure == null) {
                                    publish(epoch, AdbInstallState.Running(name, AdbInstallStage.COMMIT))
                                }
                            }
                        },
                    )
                    val file = AdbInstallFile(source.identity.name, source.identity.sizeBytes ?: UNKNOWN_SIZE)
                    installer.install(file, options)
                }
            }
        } finally {
            // `sync:` принадлежит этой установке и закрывается при любом
            // исходе, включая исключение провайдера/протокола.
            session.close()
        }
    }

    /** Кладёт содержимое источника; `null` — легло. */
    private fun sendTo(
        session: io.github.ncorror.nekoflash.protocol.adb.AdbSyncSession,
        remote: String,
        source: ArtifactSource,
    ): String? = source.open().use { input ->
        val outcome = session.send(
            path = remote,
            modifiedAtSeconds = (System.currentTimeMillis() / MILLIS_PER_SECOND).toInt(),
        ) { buffer -> input.read(buffer).coerceAtLeast(0) }
        when (outcome) {
            is AdbSyncSendOutcome.Committed -> null
            is AdbSyncSendOutcome.Failed -> "${outcome.reason.name}: ${outcome.detail}"
        }
    }

    private fun stateOf(name: String, outcome: AdbInstallOutcome): AdbInstallState = when (outcome) {
        is AdbInstallOutcome.Installed -> AdbInstallState.Installed(name, outcome.output)
        is AdbInstallOutcome.Refused ->
            AdbInstallState.Refused(name, outcome.stage, outcome.detail, outcome.output)

        is AdbInstallOutcome.Unknown -> AdbInstallState.Unknown(name, outcome.stage, outcome.detail)
        is AdbInstallOutcome.NotStarted -> AdbInstallState.Refused(
            name,
            AdbInstallStage.UPLOAD,
            outcome.detail,
            "",
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * Сколько ждать пакетный менеджер.
         *
         * Установка большого пакета — это распаковка, проверка подписи и
         * компиляция; минута здесь мало. Ограничение общее на вызов, а не на
         * бездействие: `shell:` кадрами не размечен, и продлевать отсчёт нечем.
         */
        const val PM_TIMEOUT_MS = 600_000

        /** Источник не назвал размер: `install-write` его всё равно не спросит для одиночного APK. */
        const val UNKNOWN_SIZE = -1L
    }
}
