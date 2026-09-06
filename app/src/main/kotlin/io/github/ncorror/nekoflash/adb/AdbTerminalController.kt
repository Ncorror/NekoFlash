package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbInteractiveShell
import io.github.ncorror.nekoflash.protocol.adb.AdbShellEvent
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Состояние интерактивной оболочки. */
public data class AdbTerminalState(
    /** Идёт ли сессия. */
    val active: Boolean = false,
    /** Накопленный вывод. */
    val output: String = "",
    /** Чем закончилась последняя сессия, если она закончилась. */
    val ended: String? = null,
)

/**
 * Владелец интерактивной сессии оболочки.
 *
 * Отделён от владельца соединения намеренно: тот следит за жизнью транспорта, а
 * этот — за жизнью одной сессии. Внутри у них разное время жизни и разные
 * причины заканчиваться.
 *
 * Читающий цикл занимает [executor] целиком и держит его, пока сессия жива. Это
 * не расточительство, а прямое следствие контракта: физический читатель должен
 * быть один, и пока он занят оболочкой, заняться чем-то ещё он не может.
 * Поэтому владелец соединения обязан спрашивать [active] перед любой
 * одноразовой командой.
 */
public class AdbTerminalController(
    private val executor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
) {
    private val mutableState = MutableStateFlow(AdbTerminalState())

    @Volatile
    private var shell: AdbInteractiveShell? = null

    /** Состояние сессии. Экран подписывается и ничего не опрашивает. */
    public val state: StateFlow<AdbTerminalState> = mutableState.asStateFlow()

    /** Идёт ли сессия прямо сейчас. */
    public val active: Boolean
        get() = shell != null

    /** Открывает оболочку и запускает её читающий цикл. */
    public fun start(connection: AdbConnection) {
        if (active) return

        val session = connection.interactiveShell(diagnostics)
        shell = session
        mutableState.value = AdbTerminalState(active = true)
        if (!session.open()) {
            shell = null
            mutableState.value = AdbTerminalState(ended = OPEN_FAILED)
            return
        }
        executor.execute { pumpUntilClosed(session) }
    }

    /**
     * Передаёт строку в оболочку вместе с переводом строки.
     *
     * Перевод строки добавляется здесь, а не в протокольном слое: там его
     * дописывание означало бы выполнять команду, которую не просили, а тут
     * пользователь нажал «Отправить» — это и есть Enter.
     */
    public fun sendInput(text: String) {
        shell?.sendInput(text + "\n")
    }

    /**
     * Прерывает текущую команду.
     *
     * Байт `0x03` — то же, что `Ctrl+C` в терминале: оболочка остаётся жива,
     * умирает только то, что она запустила.
     */
    public fun interrupt() {
        shell?.sendInput(byteArrayOf(CTRL_C))
    }

    /** Закрывает оболочку. Цикл увидит это и завершится сам. */
    public fun stop() {
        shell?.close()
    }

    /**
     * Читающий цикл сессии.
     *
     * Заканчивается вместе с ней: сессия сама перестаёт быть активной, когда
     * оболочка вышла или транспорт оборвался.
     */
    private fun pumpUntilClosed(session: AdbInteractiveShell) {
        val text = StringBuilder()
        var ended: String? = null

        while (session.active) {
            session.pump().forEach { event -> ended = apply(event, text) ?: ended }
            trimToLimit(text)
            mutableState.value = AdbTerminalState(active = true, output = text.toString())
        }

        shell = null
        mutableState.value = AdbTerminalState(
            active = false,
            output = text.toString(),
            ended = ended ?: CLOSED,
        )
    }

    /**
     * Прикладывает событие к накопленному выводу.
     *
     * Возвращает причину завершения, когда сессия закончилась, и `null`, пока
     * она продолжается.
     */
    private fun apply(event: AdbShellEvent, text: StringBuilder): String? = when (event) {
        is AdbShellEvent.Output -> {
            text.append(event.text)
            null
        }

        // stdout и stderr идут в один поток текста: в терминале они и должны
        // быть перемешаны в порядке появления, как на настоящем экране.
        is AdbShellEvent.ErrorOutput -> {
            text.append(event.text)
            null
        }

        is AdbShellEvent.Exited -> event.code?.let { code -> "exit $code" } ?: "exit"

        is AdbShellEvent.Broken -> event.reason
    }

    /**
     * Держит вывод в пределах памяти.
     *
     * Терминал может выдавать бесконечный поток: `logcat` не остановится сам.
     * Отбрасывается старое, потому что на экране нужно последнее.
     */
    private fun trimToLimit(text: StringBuilder) {
        if (text.length > MAX_TERMINAL_CHARS) {
            text.delete(0, text.length - MAX_TERMINAL_CHARS)
        }
    }

    private companion object {
        const val CTRL_C: Byte = 3
        const val OPEN_FAILED = "open failed"
        const val CLOSED = "closed"

        /** Сколько символов вывода держится на экране. */
        const val MAX_TERMINAL_CHARS = 64 * 1024
    }
}
