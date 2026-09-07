package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/** Что произошло за один шаг интерактивной сессии. */
public sealed interface AdbShellEvent {
    /** Устройство подтвердило OPEN, ввод теперь можно отправлять. */
    public data object Opened : AdbShellEvent

    /** Обычный вывод. */
    public data class Output(val text: String) : AdbShellEvent

    /** Вывод ошибок. Отдельно, потому что `shell,v2` их разделяет. */
    public data class ErrorOutput(val text: String) : AdbShellEvent

    /** Оболочка завершилась. */
    public data class Exited(val code: Int?) : AdbShellEvent

    /** Сессия оборвалась. */
    public data class Broken(val reason: String) : AdbShellEvent
}

/**
 * Живая оболочка на устройстве.
 *
 * Отличается от одноразовой команды тем, что поток **живёт**: данные приходят
 * когда захотят, ввод уходит когда его напечатали, и конца у этого нет, пока
 * оболочка не завершится. Поэтому здесь нет метода «выполнить и дождаться» —
 * есть [pump], один шаг, который владелец вызывает в своём цикле.
 *
 * Шаг сделан обычной функцией намеренно: поток исполнения принадлежит
 * приложению, а тесты обходятся без него и остаются определёнными. Так же
 * разделены пакетный автомат и транспорт во всём остальном модуле.
 *
 * Пока сессия открыта, других вызовов по этому соединению быть не должно:
 * физический читатель один, и одноразовая команда разобрала бы её пакеты.
 * Запрет обеспечивает владелец соединения.
 *
 * Внутри класса потоков **два**: читающий цикл зовёт [pump], а ввод приходит
 * оттуда, где его печатают. Приём и передача идут по разным эндпоинтам и могут
 * идти одновременно, но состояние маршрутизатора и порядок записи у них общие,
 * поэтому всё, что их касается, защищено замком. Само ожидание пакета остаётся
 * снаружи замка: держать его все 250 мс значило бы задерживать каждое нажатие
 * клавиши на это время.
 *
 * Устройство с `shell,v2` получает `shell,v2,pty:` — настоящий терминал с
 * разделением потоков. Без него остаётся `shell:`, где нет ни разделения, ни
 * кода возврата; так же выбирает Legacy.
 */
public class AdbInteractiveShell(
    private val reader: AdbPacketReader,
    private val writer: AdbPacketWriter,
    private val router: AdbStreamRouter,
    private val useShellV2: Boolean,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
) {
    private val frames = AdbShellFrameBuffer()
    private val stdoutText = IncrementalUtf8Decoder()
    private val stderrText = IncrementalUtf8Decoder()
    private val legacyText = IncrementalUtf8Decoder()

    /** Защищает маршрутизатор, писателя и состояние сессии. */
    private val lock = Any()

    private var localId: Int = 0

    @Volatile
    private var opened = false

    @Volatile
    private var finished = false

    /** Открыта ли сессия и имеет ли смысл её качать. */
    public val active: Boolean
        get() = localId != 0 && !finished

    /**
     * Открывает оболочку.
     *
     * Возвращает `false`, если запрос не удалось даже отправить. Подтверждение
     * открытия придёт позже, отдельным шагом: устройство отвечает `OKAY`, когда
     * сочтёт нужным.
     */
    public fun open(): Boolean = synchronized(lock) {
        val service = if (useShellV2) SERVICE_PTY else SERVICE_LEGACY
        val (id, packet) = router.openRequest(service)
        localId = id
        emit("shell_open", mapOf("service" to service, "stream" to id.toString()))
        if (!send(packet)) {
            finished = true
            return false
        }
        return true
    }

    /**
     * Передаёт ввод в оболочку.
     *
     * С `shell,v2` ввод заворачивается в рамку `stdin`, без него уходит как
     * есть. Перевод строки не добавляется: его печатает тот, кто вводит, и
     * дописывать за него означало бы выполнять команду, которую не просили.
     */
    public fun sendInput(text: String): Boolean = sendInput(text.toByteArray(Charsets.UTF_8))

    /** Тот же ввод байтами: для управляющих символов вроде `Ctrl+C`. */
    public fun sendInput(bytes: ByteArray): Boolean = synchronized(lock) {
        if (!opened || finished) return false
        val payload = if (useShellV2) AdbShellProtocol.encode(AdbShellProtocol.ID_STDIN, bytes) else bytes
        val packet = router.writeRequest(localId, payload) ?: return false
        return send(packet)
    }

    /**
     * Сообщает оболочке о конце ввода.
     *
     * В `shell,v2` это отдельная рамка; в обычном `shell:` конца ввода нет, и
     * притворяться, что он отправлен, нельзя.
     */
    public fun closeInput(): Boolean = synchronized(lock) {
        if (!useShellV2 || !opened || finished) return false
        val packet = router.writeRequest(localId, AdbShellProtocol.closeStdinFrame()) ?: return false
        return send(packet)
    }

    /**
     * Один шаг: принять то, что пришло, и разобрать.
     *
     * [timeoutMillis] короткий намеренно: цикл должен возвращать управление,
     * даже когда устройство молчит, иначе остановить сессию можно будет только
     * закрытием транспорта. Значение из Legacy.
     */
    public fun pump(timeoutMillis: Int = PUMP_TIMEOUT_MS): List<AdbShellEvent> {
        if (!active) return emptyList()

        // Ожидание снаружи замка: пока идёт приём, ввод должен уходить без
        // задержки.
        val outcome = reader.read(timeoutMillis)
        return synchronized(lock) {
            if (finished) {
                emptyList()
            } else {
                when (outcome) {
                    // Тишина — обычное состояние оболочки, которая ждёт ввода.
                    AdbReadOutcome.Idle -> drainFrames()

                    AdbReadOutcome.Closed -> finish(AdbShellEvent.Broken("transport closed"))

                    is AdbReadOutcome.Failed -> finish(
                        AdbShellEvent.Broken("${outcome.reason.name} ${outcome.detail}"),
                    )

                    is AdbReadOutcome.Received -> handle(outcome.packet)
                }
            }
        }
    }

    /** Закрывает поток. Устройство узнает, что оболочка больше не нужна. */
    public fun close(): Unit = synchronized(lock) {
        if (localId == 0 || finished) return
        finished = true
        router.closeRequest(localId)?.let(::send)
        emit("shell_closed", mapOf("stream" to localId.toString()))
    }

    private fun handle(packet: AdbPacket): List<AdbShellEvent> {
        val step = router.onPacket(packet)
        step.outbound.forEach(::send)

        val events = mutableListOf<AdbShellEvent>()
        var terminal: List<AdbShellEvent>? = null
        for (event in step.events) {
            if (terminal == null) terminal = applyStreamEvent(event, events)
        }
        return terminal ?: (events + drainFrames())
    }

    private fun applyStreamEvent(
        event: AdbStreamEvent,
        events: MutableList<AdbShellEvent>,
    ): List<AdbShellEvent>? = when (event) {
        is AdbStreamEvent.Opened -> {
            applyOpenedEvent(event, events)
            null
        }

        is AdbStreamEvent.Data -> {
            applyDataEvent(event, events)
            null
        }

        is AdbStreamEvent.Closed -> applyClosedEvent(event, events)
        is AdbStreamEvent.Stale -> null
        is AdbStreamEvent.Unexpected -> null
    }

    private fun applyOpenedEvent(
        event: AdbStreamEvent.Opened,
        events: MutableList<AdbShellEvent>,
    ) {
        if (event.localId != localId) return
        opened = true
        emit("shell_opened", mapOf("remote" to event.remoteId.toString()))
        events += AdbShellEvent.Opened
    }

    private fun applyDataEvent(
        event: AdbStreamEvent.Data,
        events: MutableList<AdbShellEvent>,
    ) {
        if (event.localId != localId) return
        if (useShellV2) {
            frames.append(event.payload)
        } else {
            appendLegacyText(event.payload, events)
        }
    }

    private fun appendLegacyText(
        payload: ByteArray,
        events: MutableList<AdbShellEvent>,
    ) {
        // Без shell,v2 рамок нет вовсе: всё, что пришло, — вывод,
        // и отделить stderr не от чего.
        legacyText.decode(payload)
            .takeIf(String::isNotEmpty)
            ?.let { text -> events += AdbShellEvent.Output(text) }
    }

    private fun applyClosedEvent(
        event: AdbStreamEvent.Closed,
        events: MutableList<AdbShellEvent>,
    ): List<AdbShellEvent>? {
        if (event.localId != localId) return null
        events += drainFrames()
        events += flushTextStreams()
        return events + finish(AdbShellEvent.Exited(null))
    }

    private fun drainFrames(): List<AdbShellEvent> {
        if (!useShellV2) return emptyList()
        val events = mutableListOf<AdbShellEvent>()
        var done = false
        while (!done) {
            when (val poll = frames.poll()) {
                AdbShellFramePoll.Incomplete -> done = true

                is AdbShellFramePoll.Corrupt -> {
                    events += finish(AdbShellEvent.Broken("shell frame: ${poll.detail}"))
                    done = true
                }

                is AdbShellFramePoll.Ready -> done = appendFrame(poll.frame, events)
            }
        }
        return events
    }

    private fun appendFrame(
        frame: AdbShellFrame,
        events: MutableList<AdbShellEvent>,
    ): Boolean = when (frame.id) {
        AdbShellProtocol.ID_STDOUT -> {
            shellV2Text(stdoutText.decode(frame.payload))
                .takeIf(String::isNotEmpty)
                ?.let { text -> events += AdbShellEvent.Output(text) }
            false
        }

        AdbShellProtocol.ID_STDERR -> {
            shellV2Text(stderrText.decode(frame.payload))
                .takeIf(String::isNotEmpty)
                ?.let { text -> events += AdbShellEvent.ErrorOutput(text) }
            false
        }

        AdbShellProtocol.ID_EXIT -> {
            val code = frame.payload.firstOrNull()?.toInt()?.and(0xFF)
            events += flushTextStreams()
            events += finish(AdbShellEvent.Exited(code))
            true
        }

        // Незнакомое поле пропускается вместе с payload: протокол расширяемый,
        // и терять из-за него уже полученный вывод незачем.
        else -> false
    }

    /** Вызывается уже под замком: [close] берёт его повторно, что разрешено. */
    private fun finish(event: AdbShellEvent): List<AdbShellEvent> {
        if (finished) return emptyList()
        close()
        return listOf(event)
    }

    private fun send(packet: AdbOutboundPacket): Boolean =
        writer.write(packet.command, packet.arg0, packet.arg1, packet.payload) == AdbWriteOutcome.Sent

    private fun flushTextStreams(): List<AdbShellEvent> {
        if (!useShellV2) {
            return legacyText.finish()
                .takeIf(String::isNotEmpty)
                ?.let { listOf(AdbShellEvent.Output(it)) }
                ?: emptyList()
        }

        val events = mutableListOf<AdbShellEvent>()
        shellV2Text(stdoutText.finish())
            .takeIf(String::isNotEmpty)
            ?.let { events += AdbShellEvent.Output(it) }
        shellV2Text(stderrText.finish())
            .takeIf(String::isNotEmpty)
            ?.let { events += AdbShellEvent.ErrorOutput(it) }
        return events
    }

    /**
     * `shell,v2` historically strips NUL bytes before showing text. Keep that
     * hardware-proven behaviour while decoding UTF-8 incrementally. Legacy
     * `shell:` deliberately remains byte-for-text compatible with its old path.
     */
    private fun shellV2Text(text: String): String = text.replace("\u0000", "")

    private fun emit(message: String, fields: Map<String, String>) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = AdbHandshake.DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    public companion object {
        /** Настоящий терминал: разделение потоков и код возврата. */
        public const val SERVICE_PTY: String = "shell,v2,pty:"

        /** Без `shell,v2`: ни разделения, ни кода возврата. */
        public const val SERVICE_LEGACY: String = "shell:"

        /** Значение из Legacy: цикл возвращает управление и когда peer молчит. */
        public const val PUMP_TIMEOUT_MS: Int = 250
    }
}
