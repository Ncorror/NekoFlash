package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/** Почему обмен по `sync:` не состоялся. */
public enum class AdbSyncFailure {
    /** Сессия не открыта или уже закрыта. */
    NOT_OPEN,

    /** Устройство отказало: путь недоступен, нет прав, нет места. */
    DEVICE_REFUSED,

    /** Ответ, которого в этом месте протокола быть не может. */
    UNEXPECTED_RESPONSE,

    /** Устройство назвало длину, которой доверять нельзя. */
    INVALID_LENGTH,

    /** Ответа не дождались. */
    TIMED_OUT,

    /** Интерфейс больше не удерживается. */
    TRANSPORT_CLOSED,

    /** Кадр ADB потерян: дальше по этому соединению недостоверно. */
    FRAMING_LOST,

    /** Отправить запрос не удалось. */
    SEND_FAILED,
}

/** Исход обмена по `sync:`. */
public sealed interface AdbSyncOutcome<out T> {
    /** Устройство ответило. */
    public data class Done<T>(val value: T) : AdbSyncOutcome<T>

    /** Ответа нет. */
    public data class Failed(
        val reason: AdbSyncFailure,
        val detail: String,
    ) : AdbSyncOutcome<Nothing>
}

/**
 * Сессия сервиса `sync:`.
 *
 * Обмен строго по очереди: запрос — ответ, без незапрошенных данных. Поэтому
 * методы блокирующие и цикла качания снаружи не требуется — в отличие от
 * интерактивной оболочки, где поток живёт сам по себе.
 *
 * Пока сессия открыта, других вызовов по этому соединению быть не должно:
 * физический читатель один. Запрет держит владелец соединения.
 *
 * Здесь только чтение. `SEND` — запись на устройство, первая мутация в проекте
 * — появится отдельно и с отдельным разбором правил `docs/03` §3, а не
 * дописыванием метода в этот класс.
 */
public class AdbSyncSession(
    private val reader: AdbPacketReader,
    private val writer: AdbPacketWriter,
    private val router: AdbStreamRouter,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    private val incoming = AdbStreamBuffer()

    private var localId: Int = 0
    private var opened = false

    /** Открыта ли сессия. */
    public val active: Boolean
        get() = opened

    /** Открывает сервис и ждёт подтверждения. */
    public fun open(timeoutMillis: Int = DEFAULT_TIMEOUT_MS): AdbSyncOutcome<Unit> {
        val (id, packet) = router.openRequest(SERVICE)
        localId = id
        emit("sync_open", mapOf("stream" to id.toString()))
        send(packet)?.let { failure -> return failure }

        val deadline = deadlineFrom(timeoutMillis)
        while (!opened) {
            pump(deadline)?.let { failure -> return failure }
        }
        emit("sync_opened", mapOf("stream" to id.toString()))
        return AdbSyncOutcome.Done(Unit)
    }

    /**
     * Спрашивает сведения о пути.
     *
     * Отсутствующий путь — это **успех** с нулевым режимом, а не отказ:
     * `adbd` отвечает `STAT` с нулями. Отказ приходит только когда устройство
     * не может ответить вовсе.
     */
    public fun stat(path: String, timeoutMillis: Int = DEFAULT_TIMEOUT_MS): AdbSyncOutcome<AdbSyncStat> {
        if (!opened) return failure(AdbSyncFailure.NOT_OPEN, "stat $path")
        val deadline = deadlineFrom(timeoutMillis)
        request(AdbSyncProtocol.ID_STAT, path)?.let { failure -> return failure }

        val header = when (val read = readHeader(deadline)) {
            is AdbSyncOutcome.Done -> read.value
            is AdbSyncOutcome.Failed -> return read
        }
        return when (header.id) {
            AdbSyncProtocol.ID_STAT -> {
                val body = when (val read = readExactly(STAT_BODY_BYTES, deadline)) {
                    is AdbSyncOutcome.Done -> read.value
                    is AdbSyncOutcome.Failed -> return read
                }
                AdbSyncOutcome.Done(decodeStat(header, body))
            }

            AdbSyncProtocol.ID_FAIL -> refusal(header, deadline, "stat $path")

            else -> failure(AdbSyncFailure.UNEXPECTED_RESPONSE, "stat $path got ${header.id}")
        }
    }

    /**
     * Читает файл с устройства, отдавая его блоками.
     *
     * Блоки отдаются [sink] по мере поступления и нигде не накапливаются: файл
     * может быть больше памяти, и собирать его целиком, чтобы потом записать,
     * значило бы ограничить размер оперативной памятью.
     */
    public fun receive(
        path: String,
        timeoutMillis: Int = TRANSFER_TIMEOUT_MS,
        sink: (ByteArray) -> Unit,
    ): AdbSyncOutcome<Long> {
        if (!opened) return failure(AdbSyncFailure.NOT_OPEN, "recv $path")
        val deadline = deadlineFrom(timeoutMillis)
        request(AdbSyncProtocol.ID_RECV, path)?.let { failure -> return failure }

        var received = 0L
        while (true) {
            val header = when (val read = readHeader(deadline)) {
                is AdbSyncOutcome.Done -> read.value
                is AdbSyncOutcome.Failed -> return read
            }
            when (header.id) {
                AdbSyncProtocol.ID_DATA -> {
                    // Длину называет устройство. Блок больше предела протокола
                    // означает, что мы читаем не то, что думаем.
                    if (header.value < 0 || header.value > AdbSyncProtocol.DATA_CHUNK_BYTES) {
                        return failure(AdbSyncFailure.INVALID_LENGTH, "recv $path chunk=${header.value}")
                    }
                    val chunk = when (val read = readExactly(header.value, deadline)) {
                        is AdbSyncOutcome.Done -> read.value
                        is AdbSyncOutcome.Failed -> return read
                    }
                    sink(chunk)
                    received += chunk.size
                }

                AdbSyncProtocol.ID_DONE -> {
                    emit("sync_received", mapOf("path" to path, "bytes" to received.toString()))
                    return AdbSyncOutcome.Done(received)
                }

                AdbSyncProtocol.ID_FAIL -> return refusal(header, deadline, "recv $path")

                else -> return failure(
                    AdbSyncFailure.UNEXPECTED_RESPONSE,
                    "recv $path got ${header.id}",
                )
            }
        }
    }

    /** Закрывает сессию. */
    public fun close() {
        if (localId == 0) return
        opened = false
        router.closeRequest(localId)?.let(::sendIgnoringResult)
        emit("sync_closed", mapOf("stream" to localId.toString()))
        localId = 0
    }

    private fun request(id: String, path: String): AdbSyncOutcome.Failed? {
        val payload = AdbSyncProtocol.request(id, path)
        val packet = router.writeRequest(localId, payload)
            ?: return failure(AdbSyncFailure.NOT_OPEN, "$id $path")
        return send(packet)
    }

    /**
     * Читает сообщение об отказе.
     *
     * Устройство называет длину сообщения само, поэтому она проверяется:
     * иначе один испорченный ответ заставил бы ждать мегабайт текста.
     */
    private fun refusal(
        header: AdbSyncHeader,
        deadline: Long,
        stage: String,
    ): AdbSyncOutcome.Failed {
        if (header.value < 0 || header.value > AdbSyncProtocol.MAX_STRING_BYTES) {
            return failure(AdbSyncFailure.INVALID_LENGTH, "$stage message=${header.value}")
        }
        val message = when (val read = readExactly(header.value, deadline)) {
            is AdbSyncOutcome.Done -> read.value.toString(Charsets.UTF_8)
            is AdbSyncOutcome.Failed -> return read
        }
        return failure(AdbSyncFailure.DEVICE_REFUSED, "$stage: $message")
    }

    private fun readHeader(deadline: Long): AdbSyncOutcome<AdbSyncHeader> =
        when (val read = readExactly(AdbSyncProtocol.HEADER_SIZE_BYTES, deadline)) {
            is AdbSyncOutcome.Done -> AdbSyncOutcome.Done(AdbSyncProtocol.decodeHeader(read.value))
            is AdbSyncOutcome.Failed -> read
        }

    /** Добирает из потока ровно столько байт, сколько названо. */
    private fun readExactly(count: Int, deadline: Long): AdbSyncOutcome<ByteArray> {
        while (true) {
            incoming.take(count)?.let { bytes -> return AdbSyncOutcome.Done(bytes) }
            pump(deadline)?.let { failure -> return failure }
        }
    }

    /**
     * Один приём: принять пакет и разложить его по маршрутизатору.
     *
     * Возвращает отказ, когда продолжать нельзя, и `null`, когда можно.
     */
    private fun pump(deadline: Long): AdbSyncOutcome.Failed? {
        val remaining = remainingMillis(deadline)
        if (remaining <= 0) return failure(AdbSyncFailure.TIMED_OUT, "sync")

        when (val outcome = reader.read(remaining.coerceAtMost(READ_SLICE_MS))) {
            AdbReadOutcome.Idle -> return null
            AdbReadOutcome.Closed -> return failure(AdbSyncFailure.TRANSPORT_CLOSED, "sync")
            is AdbReadOutcome.Failed -> return failure(
                AdbSyncFailure.FRAMING_LOST,
                "${outcome.reason.name} ${outcome.detail}",
            )

            is AdbReadOutcome.Received -> {
                val step = router.onPacket(outcome.packet)
                step.outbound.forEach(::sendIgnoringResult)
                for (event in step.events) {
                    when (event) {
                        is AdbStreamEvent.Opened -> if (event.localId == localId) opened = true
                        is AdbStreamEvent.Data -> if (event.localId == localId) incoming.append(event.payload)
                        is AdbStreamEvent.Closed -> if (event.localId == localId) {
                            opened = false
                            return failure(AdbSyncFailure.TRANSPORT_CLOSED, "device closed sync")
                        }

                        is AdbStreamEvent.Stale -> Unit
                        is AdbStreamEvent.Unexpected -> Unit
                    }
                }
            }
        }
        return null
    }

    /**
     * Разбирает ответ `STAT`.
     *
     * Число в заголовке здесь — **режим**, а не длина: у `STAT` поле `value`
     * означает именно его, и следом идут только размер и время. Первая версия
     * прочитала его как длину и ждала на четыре байта больше, чем присылает
     * устройство; тест это поймал.
     */
    private fun decodeStat(header: AdbSyncHeader, body: ByteArray): AdbSyncStat {
        val size = readIntLe(body, 0).toLong() and 0xFFFF_FFFFL
        emit(
            "sync_stat",
            mapOf("mode" to header.value.toString(), "size" to size.toString()),
        )
        return AdbSyncStat(
            mode = header.value,
            size = size,
            modifiedAtSeconds = readIntLe(body, 4),
        )
    }

    private fun send(packet: AdbOutboundPacket): AdbSyncOutcome.Failed? =
        when (val outcome = writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)) {
            AdbWriteOutcome.Sent -> null
            AdbWriteOutcome.Closed -> failure(AdbSyncFailure.TRANSPORT_CLOSED, "send")
            is AdbWriteOutcome.Interrupted -> failure(
                AdbSyncFailure.SEND_FAILED,
                "${outcome.detail} (sent=${outcome.sentBytes})",
            )
        }

    /** Подтверждения и закрытия отправляются без разбора: их исход ничего не меняет. */
    private fun sendIgnoringResult(packet: AdbOutboundPacket) {
        writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)
    }

    private fun deadlineFrom(timeoutMillis: Int): Long {
        require(timeoutMillis > 0) { "Sync timeout must be positive: $timeoutMillis" }
        return elapsedNanos() + timeoutMillis * NANOS_PER_MILLI
    }

    private fun remainingMillis(deadlineNanos: Long): Int =
        ((deadlineNanos - elapsedNanos()) / NANOS_PER_MILLI)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun failure(reason: AdbSyncFailure, detail: String): AdbSyncOutcome.Failed {
        emit("sync_failed", mapOf("reason" to reason.name, "detail" to detail))
        return AdbSyncOutcome.Failed(reason, detail)
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

    private fun readIntLe(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)

    public companion object {
        /** Имя сервиса. */
        public const val SERVICE: String = "sync:"

        public const val DEFAULT_TIMEOUT_MS: Int = 10_000

        /** Передача файла может идти долго, но не бесконечно. */
        public const val TRANSFER_TIMEOUT_MS: Int = 300_000

        /**
         * Размер и время изменения в ответе `STAT`.
         *
         * Режим приходит числом заголовка, поэтому следом за ним остаётся
         * восемь байт, а не двенадцать.
         */
        private const val STAT_BODY_BYTES = 8

        private const val READ_SLICE_MS = 2_000
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
