package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/** Почему вызов сервиса не дал результата. */
public enum class AdbServiceFailure {
    /** Устройство закрыло поток, не подтвердив открытие: сервиса нет или он отказал. */
    REJECTED,

    /** Ответ не уложился в отведённое время. */
    TIMED_OUT,

    /** Вывод превысил объявленный предел. */
    OUTPUT_TOO_LARGE,

    /** Интерфейс больше не удерживается. */
    TRANSPORT_CLOSED,

    /** Кадр потерян: соединение дальше недостоверно. */
    FRAMING_LOST,

    /** Отправить пакет не удалось. */
    SEND_FAILED,
}

/** Исход вызова сервиса. */
public sealed interface AdbServiceOutcome {
    /** Сервис отработал и закрыл поток. */
    public data class Completed(val output: ByteArray) : AdbServiceOutcome {
        /** Вывод как текст без завершающих переводов строки. */
        public fun text(): String = output.toString(Charsets.UTF_8).trimEnd('\n', '\r')

        override fun toString(): String = "Completed(${output.size} bytes)"
    }

    /** Результата нет. */
    public data class Failed(
        val reason: AdbServiceFailure,
        val detail: String,
    ) : AdbServiceOutcome
}

/**
 * Один вызов сервиса ADB от начала до закрытия потока.
 *
 * Блокирующий и однопоточный: открывает поток, крутит приём, собирает вывод и
 * возвращает его целиком. Читающего цикла в фоне здесь нет намеренно — пока
 * поток один, он не нужен, а лишний поток исполнения принёс бы гонки раньше,
 * чем пользу. Постоянный цикл появится вместе с интерактивным shell, где
 * потоков действительно несколько.
 *
 * Устроено так же, как ограниченный read-only probe в A2
 * (`AdbUsbTransport.runReadOnlyProbe`): общий дедлайн на весь вызов, отдельный
 * потолок на одну операцию приёма, предел на объём вывода и закрытие потока при
 * любом из них. Ограничения обязательны: сервис может не закрыть поток никогда,
 * а `cat` большого файла — переполнить память.
 *
 * Разбор пакетов делегируется [AdbStreamRouter], который отвечает за чужие
 * пакеты и подтверждения. Здесь остаётся только время, предел и сбор вывода.
 */
public class AdbServiceCall(
    private val reader: AdbPacketReader,
    private val writer: AdbPacketWriter,
    private val router: AdbStreamRouter,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    /**
     * Вызывает сервис и ждёт его вывод.
     *
     * @param service имя сервиса ADB, например `shell:getprop ro.product.device`.
     * @param maxOutputBytes сколько вывода готов принять вызывающий.
     * @param timeoutMillis общий предел на весь вызов.
     */
    public fun run(
        service: String,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    ): AdbServiceOutcome {
        require(maxOutputBytes > 0) { "ADB service output cap must be positive: $maxOutputBytes" }
        require(timeoutMillis > 0) { "ADB service timeout must be positive: $timeoutMillis" }

        val (localId, open) = router.openRequest(service)
        emit("service_open", mapOf("service" to service, "stream" to localId.toString()))
        send(open)?.let { failure -> return abandon(localId, failure) }

        val deadline = elapsedNanos() + timeoutMillis * NANOS_PER_MILLI
        val output = ByteArrayBuilder(maxOutputBytes)

        while (true) {
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) {
                return abandon(localId, failure(AdbServiceFailure.TIMED_OUT, "service=$service"))
            }

            when (val read = reader.read(remaining.coerceAtMost(PACKET_TIMEOUT_MS))) {
                is AdbReadOutcome.Received -> {
                    val step = router.onPacket(read.packet)
                    step.outbound.forEach { packet ->
                        send(packet)?.let { failure -> return abandon(localId, failure) }
                    }
                    resolve(localId, step, output, service)?.let { outcome -> return outcome }
                }

                // Тишина — обычное состояние ожидания: сервис думает. Решает
                // дедлайн, а не одна неудачная попытка приёма.
                AdbReadOutcome.Idle -> Unit

                AdbReadOutcome.Closed -> return abandon(
                    localId,
                    failure(AdbServiceFailure.TRANSPORT_CLOSED, "service=$service"),
                )

                is AdbReadOutcome.Failed -> return abandon(
                    localId,
                    failure(
                        AdbServiceFailure.FRAMING_LOST,
                        "service=$service ${read.reason.name} ${read.detail}",
                    ),
                )
            }
        }
    }

    /**
     * Превращает события маршрутизатора в исход вызова.
     *
     * `null` означает «продолжаем ждать».
     */
    private fun resolve(
        localId: Int,
        step: AdbRouterStep,
        output: ByteArrayBuilder,
        service: String,
    ): AdbServiceOutcome? {
        for (event in step.events) {
            when (event) {
                is AdbStreamEvent.Opened ->
                    if (event.localId == localId) {
                        emit("service_opened", mapOf("service" to service, "remote" to event.remoteId.toString()))
                    }

                is AdbStreamEvent.Data ->
                    if (event.localId == localId && !output.append(event.payload)) {
                        return abandon(
                            localId,
                            failure(
                                AdbServiceFailure.OUTPUT_TOO_LARGE,
                                "service=$service cap=${output.capacity}",
                            ),
                        )
                    }

                is AdbStreamEvent.Closed ->
                    if (event.localId == localId) {
                        return finish(event.reason, output, service)
                    }

                // Чужие и неожиданные пакеты маршрутизатор уже отработал: ответ,
                // если он нужен, лежит в step.outbound и уже отправлен.
                is AdbStreamEvent.Stale -> Unit
                is AdbStreamEvent.Unexpected -> Unit
            }
        }
        return null
    }

    private fun finish(
        reason: AdbStreamClosure,
        output: ByteArrayBuilder,
        service: String,
    ): AdbServiceOutcome = when (reason) {
        AdbStreamClosure.COMPLETED -> {
            emit("service_completed", mapOf("service" to service, "bytes" to output.size.toString()))
            AdbServiceOutcome.Completed(output.toByteArray())
        }

        AdbStreamClosure.REJECTED ->
            failure(AdbServiceFailure.REJECTED, "service=$service closed before OKAY")

        AdbStreamClosure.LOCAL ->
            failure(AdbServiceFailure.TRANSPORT_CLOSED, "service=$service abandoned")
    }

    /**
     * Закрывает поток и возвращает уже готовый отказ.
     *
     * Закрытие делается всегда: брошенный поток остаётся открытым на стороне
     * устройства и держит там сервис.
     */
    private fun abandon(localId: Int, failure: AdbServiceOutcome.Failed): AdbServiceOutcome {
        router.closeRequest(localId)?.let { packet -> writer.write(packet.command, packet.arg0, packet.arg1, packet.payload) }
        return failure
    }

    private fun send(packet: AdbOutboundPacket): AdbServiceOutcome.Failed? =
        when (val outcome = writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)) {
            AdbWriteOutcome.Sent -> null
            AdbWriteOutcome.Closed -> failure(AdbServiceFailure.TRANSPORT_CLOSED, "send")
            is AdbWriteOutcome.Interrupted -> failure(
                AdbServiceFailure.SEND_FAILED,
                "${outcome.detail} (sent=${outcome.sentBytes})",
            )
        }

    private fun remainingMillis(deadlineNanos: Long): Int =
        ((deadlineNanos - elapsedNanos()) / NANOS_PER_MILLI)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun failure(reason: AdbServiceFailure, detail: String): AdbServiceOutcome.Failed {
        emit("service_failed", mapOf("reason" to reason.name, "detail" to detail))
        return AdbServiceOutcome.Failed(reason, detail)
    }

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

    /** Накопитель вывода с жёстким потолком. */
    private class ByteArrayBuilder(val capacity: Int) {
        private val chunks = mutableListOf<ByteArray>()
        var size: Int = 0
            private set

        /** `false` означает, что потолок превышен и добавлять больше нечего. */
        fun append(payload: ByteArray): Boolean {
            if (size + payload.size > capacity) return false
            chunks += payload
            size += payload.size
            return true
        }

        fun toByteArray(): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            return result
        }
    }

    public companion object {
        /** Значения из A2 (`READ_ONLY_PROBE_*`). */
        public const val DEFAULT_TIMEOUT_MS: Int = 30_000
        public const val DEFAULT_MAX_OUTPUT_BYTES: Int = 64 * 1024

        /**
         * Потолок одной операции приёма.
         *
         * Ожидание режется на куски, чтобы дедлайн проверялся, даже когда
         * устройство молчит.
         */
        private const val PACKET_TIMEOUT_MS = 10_000

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
