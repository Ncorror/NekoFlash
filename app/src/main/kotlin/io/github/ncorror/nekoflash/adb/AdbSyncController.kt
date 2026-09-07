package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncSession
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncStat
import java.security.MessageDigest
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что известно про путь на устройстве. */
public sealed interface AdbFileState {
    /** Ничего не спрашивали. */
    public data object None : AdbFileState

    /** Идёт запрос. */
    public data class Busy(val path: String) : AdbFileState

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

    /** Не получилось. */
    public data class Failed(val path: String, val reason: String) : AdbFileState
}

/**
 * Читающие операции с файлами устройства.
 *
 * Отделён от владельца соединения и от владельца оболочки: у всех троих разное
 * время жизни. Здесь операция живёт от запроса до ответа и не переживает его.
 *
 * Только чтение. Запись на устройство — первая мутация в проекте — появится
 * отдельно и не дописыванием метода сюда.
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

    /** Состояние последней операции. */
    public val state: StateFlow<AdbFileState> = mutableState.asStateFlow()

    /** Идёт ли операция прямо сейчас. */
    public val active: Boolean
        get() = running

    /** Спрашивает сведения о пути. */
    public fun describe(connection: AdbConnection, path: String) {
        start(connection, path) { session ->
            when (val outcome = session.stat(path)) {
                is AdbSyncOutcome.Done -> AdbFileState.Described(path, outcome.value)
                is AdbSyncOutcome.Failed -> failed(path, outcome)
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
        start(connection, path) { session ->
            val digest = MessageDigest.getInstance("SHA-256")
            when (val outcome = session.receive(path) { chunk -> digest.update(chunk) }) {
                is AdbSyncOutcome.Done -> AdbFileState.Read(
                    path = path,
                    bytes = outcome.value,
                    sha256 = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) },
                )

                is AdbSyncOutcome.Failed -> failed(path, outcome)
            }
        }
    }

    private fun start(
        connection: AdbConnection,
        path: String,
        work: (AdbSyncSession) -> AdbFileState,
    ) {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || running) return

        running = true
        mutableState.value = AdbFileState.Busy(trimmed)
        executor.execute {
            val session = connection.syncSession(diagnostics)
            mutableState.value = when (val opened = session.open()) {
                is AdbSyncOutcome.Done -> runCatching { work(session) }.getOrElse { error ->
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
}
