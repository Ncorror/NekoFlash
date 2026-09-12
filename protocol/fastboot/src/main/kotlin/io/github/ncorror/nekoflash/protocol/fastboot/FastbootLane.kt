package io.github.ncorror.nekoflash.protocol.fastboot

import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/** Состояние единственной полосы обмена, принадлежащей одному захваченному интерфейсу. */
public enum class FastbootLaneState {
    /** Команду можно отправлять. */
    IDLE,

    /** Команда ушла, терминального ответа ещё нет. */
    AWAITING_FINAL,

    /** Устройство объявило фазу данных и ждёт байты. */
    AWAITING_DATA,

    /**
     * Рамка потеряна.
     *
     * Состояние липкое и снимается только новым захватом интерфейса. Слать
     * вторую команду по той же полосе нельзя: устройство ждёт не её, и обмен
     * разойдётся тем сильнее, чем дольше мы будем делать вид, что всё в
     * порядке. Так же устроено в обоих архивах — Legacy `SessionState.BROKEN`
     * (`FastbootProtocol.kt`), A2 `FastbootTransactionState.STALLED`.
     */
    STALLED,

    /** Полоса закрыта вместе с интерфейсом. */
    CLOSED,
}

/** Чем кончился один обмен командой. */
public sealed interface FastbootExchange {
    /**
     * Устройство ответило терминально.
     *
     * [reply] — `OKAY` или `FAIL`. **Отказ сюда попадает как обычный исход**, а
     * не как ошибка: `FAIL` — это ответ устройства, и подменять его своей
     * ошибкой значило бы скрыть слово peer'а (`03` §2, Device authority).
     */
    public data class Completed(
        val reply: FastbootReply,
        val payload: String,
        val info: List<String>,
    ) : FastbootExchange

    /** Устройство объявило фазу данных. Полоса ждёт байты и командой не занята. */
    public data class DataPhase(
        val declaredSize: Long?,
        val payload: String,
        val info: List<String>,
    ) : FastbootExchange

    /**
     * Ответа не дождались.
     *
     * Полоса при этом переходит в [FastbootLaneState.STALLED]: команда ушла, и
     * что с ней стало — неизвестно. Это `Unknown`, а не отказ.
     */
    public data class TimedOut(
        val waitedMillis: Long,
        val info: List<String>,
    ) : FastbootExchange

    /** По этой полосе сейчас нельзя: она занята, закрыта или потеряла рамку. */
    public data class NotReady(val state: FastbootLaneState) : FastbootExchange

    /** Команду не удалось отправить. Устройство её не видело. */
    public data class NotSent(val reason: String) : FastbootExchange
}

/** Чем кончилась передача байтов в открытой фазе данных. */
public sealed interface FastbootDataOutcome {
    /**
     * Байты переданы целиком, и устройство ответило.
     *
     * [reply] — `OKAY` или `FAIL`: отказ после полной передачи это ответ
     * устройства, а не наша ошибка.
     */
    public data class Completed(
        val reply: FastbootReply,
        val payload: String,
        val info: List<String>,
        val bytesSent: Long,
    ) : FastbootDataOutcome

    /**
     * Передача не дошла до конца, и **состояние приёмника неизвестно**.
     *
     * Это `Unknown`, а не отказ (`03` §3). Устройство получило часть байтов и
     * осталось ждать остальные; что лежит в его буфере загрузки — мы не знаем,
     * и прошивать из него нельзя. Повторять ту же передачу по той же полосе
     * тоже нельзя: рамка потеряна.
     */
    public data class Interrupted(
        val bytesSent: Long,
        val expectedBytes: Long,
        val detail: String,
    ) : FastbootDataOutcome

    /** Фаза данных не открыта: передавать некуда. */
    public data class NotReady(val state: FastbootLaneState) : FastbootDataOutcome
}

/**
 * Единственная синхронная полоса обмена Fastboot.
 *
 * **Почему полоса одна.** Fastboot не мультиплексирует: у него нет ни
 * идентификаторов потоков, ни порядковых номеров — есть команда и ответ на неё.
 * Вторая команда, отправленная до терминального ответа на первую, необратимо
 * перепутает, чей ответ читается следующим. Поэтому одновременность здесь
 * невозможна не по нашему решению, а по устройству протокола, и это ровно тот
 * случай, когда ограничение принадлежит классу hard invariant (`03` §2).
 *
 * Тем и отличается от ADB, где `AdbStreamDispatcher` раздаёт потоки по
 * идентификаторам и одновременность законна (ADR-0004).
 *
 * **Ожидание считается по бездействию, а не по общему времени.** Каждый
 * пришедший кадр продлевает бюджет: `erase` может слать `INFO` минутами и быть
 * при этом совершенно живым. Считать такой обмен мёртвым по общему таймеру
 * значило бы рвать исправную операцию тем вернее, чем больше она делает. Взято
 * у A2 (`FastbootInactivityBudget`), где это записано прямо.
 *
 * **Неуспешный приём сам по себе ничего не значит.** Транспорт не различает
 * таймаут и ошибку — об этом прямо сказано в `UsbTransferFailure.NOT_COMPLETED`.
 * Различает слой выше, и различает по своему состоянию: ожидание, в котором не
 * пришло ни кадра, — обычный таймаут, и его надо продолжать в пределах бюджета.
 * Legacy делает так же: `readPacket(2000) == null` не кончает цикл.
 */
public class FastbootLane(
    private val transport: UsbTransportHandle,
    private val readBufferBytes: Int = DEFAULT_READ_BUFFER,
) {
    private var currentState: FastbootLaneState = FastbootLaneState.IDLE

    /** Состояние полосы прямо сейчас. */
    public val state: FastbootLaneState get() = currentState

    /**
     * Отправляет [command] и читает ответ до терминального кадра или до `DATA`.
     *
     * [inactivityMillis] — сколько ждать **без единого кадра**; каждый кадр
     * отсчёт продлевает.
     */
    public fun run(
        command: String,
        inactivityMillis: Long = DEFAULT_INACTIVITY_MS,
        writeTimeoutMillis: Int = DEFAULT_WRITE_TIMEOUT_MS,
    ): FastbootExchange {
        require(inactivityMillis > 0L) { "бюджет бездействия должен быть положительным" }
        return if (currentState != FastbootLaneState.IDLE) {
            FastbootExchange.NotReady(currentState)
        } else {
            send(command, writeTimeoutMillis, inactivityMillis)
        }
    }

    /** Закрывает полосу. Интерфейсом владеет вызывающий, здесь только состояние. */
    public fun close() {
        currentState = FastbootLaneState.CLOSED
    }

    /**
     * Объявляет рамку потерянной.
     *
     * Нужен тому, кто вошёл в фазу данных и не довёл её: устройство осталось
     * ждать байты, и следующая команда попадёт не туда.
     */
    public fun stall() {
        currentState = FastbootLaneState.STALLED
    }

    /**
     * Передаёт [expectedBytes] байт из [source] в открытую фазу данных.
     *
     * **Правила счёта байтов взяты из архива, а не выведены.** A2
     * `FastbootDataTransfer.transferSyncBulk` и Legacy
     * `FastbootProtocol.transferDownloadPayload` сходятся в трёх вещах:
     *
     * 1. **Короткая запись здесь законна и дописывается.** Хост → устройство
     *    дробится и повторяется, пока не отправлено всё; это прямо разрешено
     *    контрактом [UsbTransportHandle.send] и не путается с приёмом, где
     *    дробление объявленного payload разрушало рамку.
     * 2. **Запись «больше запрошенного» или «ноль и меньше» — неоднозначна.**
     *    A2 называет её `Ambiguous` и не повторяет. Мы тоже: повторить те же
     *    байты после неоднозначной записи запрещено (`03` §3), потому что
     *    доказать, что предыдущая попытка не дошла, нечем.
     * 3. **Ранний конец источника — провал, а не успех.** Передать меньше
     *     объявленного и получить `OKAY` невозможно: устройство ждёт ровно
     *     столько, сколько назвало.
     *
     * Во всех неполных случаях полоса переходит в [FastbootLaneState.STALLED]:
     * устройство осталось ждать байты, и следующая команда попадёт не туда.
     */
    public fun sendData(
        source: java.io.InputStream,
        expectedBytes: Long,
        inactivityMillis: Long = DEFAULT_INACTIVITY_MS,
        writeTimeoutMillis: Int = DATA_WRITE_TIMEOUT_MS,
    ): FastbootDataOutcome {
        require(expectedBytes >= 0L) { "объявленный размер не может быть отрицательным" }
        return if (currentState != FastbootLaneState.AWAITING_DATA) {
            FastbootDataOutcome.NotReady(currentState)
        } else {
            pump(source, expectedBytes, inactivityMillis, writeTimeoutMillis)
        }
    }

    private fun pump(
        source: java.io.InputStream,
        expectedBytes: Long,
        inactivityMillis: Long,
        writeTimeoutMillis: Int,
    ): FastbootDataOutcome {
        val block = ByteArray(DATA_BLOCK_BYTES)
        var sent = 0L
        var failure: String? = null

        while (failure == null && sent < expectedBytes) {
            val wanted = minOf(block.size.toLong(), expectedBytes - sent).toInt()
            val read = runCatching { source.read(block, 0, wanted) }.getOrElse { error ->
                failure = "чтение источника не удалось: ${error.javaClass.simpleName}"
                0
            }
            failure = failure
                ?: if (read <= 0) "источник кончился на $sent из $expectedBytes" else null
            if (failure == null) {
                val written = writeBlock(block, read, sent, writeTimeoutMillis)
                failure = written.second
                sent += written.first
            }
        }

        return finish(sent, expectedBytes, failure, inactivityMillis)
    }

    /** Сколько байт блока ушло и что помешало. Короткая запись дописывается здесь. */
    private fun writeBlock(
        block: ByteArray,
        length: Int,
        alreadySent: Long,
        writeTimeoutMillis: Int,
    ): Pair<Long, String?> {
        var offset = 0
        var problem: String? = null
        while (problem == null && offset < length) {
            val requested = length - offset
            val result = transport.send(block, offset, requested, writeTimeoutMillis)
            problem = when {
                result is UsbTransferResult.Failed ->
                    "запись не состоялась на ${alreadySent + offset}: ${result.reason}"

                result is UsbTransferResult.Completed && (result.bytes <= 0 || result.bytes > requested) ->
                    "неоднозначная запись на ${alreadySent + offset}: " +
                        "отправлено ${result.bytes} из $requested"

                else -> null
            }
            if (problem == null) offset += (result as UsbTransferResult.Completed).bytes
        }
        return offset.toLong() to problem
    }

    private fun finish(
        sent: Long,
        expectedBytes: Long,
        failure: String?,
        inactivityMillis: Long,
    ): FastbootDataOutcome = when {
        failure != null -> {
            currentState = FastbootLaneState.STALLED
            FastbootDataOutcome.Interrupted(sent, expectedBytes, failure)
        }

        sent != expectedBytes -> {
            currentState = FastbootLaneState.STALLED
            FastbootDataOutcome.Interrupted(sent, expectedBytes, "передано $sent из $expectedBytes")
        }

        else -> {
            currentState = FastbootLaneState.AWAITING_FINAL
            terminal(sent, expectedBytes, inactivityMillis)
        }
    }

    private fun terminal(sent: Long, expectedBytes: Long, inactivityMillis: Long): FastbootDataOutcome =
        when (val exchange = readUntilTerminal(inactivityMillis)) {
            is FastbootExchange.Completed ->
                FastbootDataOutcome.Completed(exchange.reply, exchange.payload, exchange.info, sent)

            is FastbootExchange.TimedOut -> FastbootDataOutcome.Interrupted(
                bytesSent = sent,
                expectedBytes = expectedBytes,
                detail = "байты переданы, ответа не было ${exchange.waitedMillis} мс",
            )

            // Вторая фаза данных подряд не предусмотрена протоколом, и что
            // устройство при этом делает — неизвестно. Рамку считаем потерянной.
            else -> {
                currentState = FastbootLaneState.STALLED
                FastbootDataOutcome.Interrupted(sent, expectedBytes, "непредусмотренный ответ после данных")
            }
        }

    private fun send(command: String, writeTimeoutMillis: Int, inactivityMillis: Long): FastbootExchange {
        val bytes = command.toByteArray(Charsets.US_ASCII)
        return when {
            bytes.isEmpty() -> FastbootExchange.NotSent("пустая команда")

            // Провод у Fastboot — ASCII, и `toByteArray` подменяет всё
            // остальное вопросительным знаком **молча**. Отправить подменённое
            // и назвать это отправкой набранного значило бы солгать о том, что
            // ушло на устройство. Поэтому несоответствие называется, а не
            // скрывается: набрать оператор может что угодно (`01` §3), но
            // отправить мы обязаны ровно набранное — или ничего.
            command.any { it.code > MAX_ASCII } ->
                FastbootExchange.NotSent("команда не передаётся в ASCII, а провод Fastboot другого не несёт")

            // Ограничение провода, а не наше: команда Fastboot передаётся одним
            // кадром, и длиннее шестидесяти четырёх байт он не бывает.
            bytes.size > MAX_COMMAND_BYTES ->
                FastbootExchange.NotSent("команда в ${bytes.size} байт не помещается в кадр $MAX_COMMAND_BYTES")

            else -> write(bytes, writeTimeoutMillis, inactivityMillis)
        }
    }

    private fun write(bytes: ByteArray, writeTimeoutMillis: Int, inactivityMillis: Long): FastbootExchange {
        val written = transport.send(bytes, 0, bytes.size, writeTimeoutMillis)
        return when {
            written is UsbTransferResult.Failed -> FastbootExchange.NotSent("запись не состоялась: ${written.reason}")

            // Короткая запись — не «почти отправили». Устройство получило
            // обрезанную команду, и что оно с ней сделало, мы не знаем.
            written is UsbTransferResult.Completed && written.bytes < bytes.size -> {
                currentState = FastbootLaneState.STALLED
                FastbootExchange.NotSent("отправлено ${written.bytes} из ${bytes.size} байт")
            }

            else -> {
                currentState = FastbootLaneState.AWAITING_FINAL
                readUntilTerminal(inactivityMillis)
            }
        }
    }

    private fun readUntilTerminal(inactivityMillis: Long): FastbootExchange {
        val buffer = ByteArray(readBufferBytes)
        val info = mutableListOf<String>()
        var idleMillis = 0L
        var outcome: FastbootExchange? = null

        while (outcome == null) {
            val slice = minOf(READ_SLICE_MS.toLong(), inactivityMillis - idleMillis).toInt().coerceAtLeast(1)
            val received = transport.receive(buffer, 0, buffer.size, slice)
            val packet = packetOf(received, buffer)
            if (packet == null) {
                idleMillis += slice
                if (idleMillis >= inactivityMillis) {
                    currentState = FastbootLaneState.STALLED
                    outcome = FastbootExchange.TimedOut(idleMillis, info.toList())
                }
            } else {
                idleMillis = 0L
                outcome = classify(packet, info)
            }
        }
        return outcome
    }

    /**
     * Кадр из результата приёма, либо `null`, если кадра не было.
     *
     * Пустой успешный приём — тоже «кадра не было»: устройство молчит, а не
     * прислало пустоту.
     */
    private fun packetOf(received: UsbTransferResult, buffer: ByteArray): FastbootPacket? =
        (received as? UsbTransferResult.Completed)
            ?.takeIf { it.bytes > 0 }
            ?.let { FastbootPacketCodec.parse(buffer, it.bytes) }

    private fun classify(packet: FastbootPacket, info: MutableList<String>): FastbootExchange? = when {
        packet.terminal -> {
            currentState = FastbootLaneState.IDLE
            FastbootExchange.Completed(packet.reply, packet.payload, info.toList())
        }

        packet.reply == FastbootReply.DATA -> {
            currentState = FastbootLaneState.AWAITING_DATA
            FastbootExchange.DataPhase(packet.declaredSize(), packet.payload, info.toList())
        }

        // INFO, TEXT и непонятый кадр обмен не кончают. Непонятое копится
        // наравне с остальным: Legacy на нём пишет предупреждение и читает
        // дальше, и терять сказанное устройством мы не станем.
        else -> {
            info += packet.payload.ifBlank { packet.raw }
            null
        }
    }

    public companion object {
        /** Команда Fastboot передаётся одним кадром, и он не длиннее этого. */
        public const val MAX_COMMAND_BYTES: Int = 64

        /** Наибольший код символа, который несёт провод Fastboot. */
        internal const val MAX_ASCII: Int = 0x7F

        /**
         * Буфер приёма.
         *
         * С запасом: ответ `getvar:all` приходит многими кадрами `INFO`, и
         * каждый отдельный кадр невелик, но обрезать его нечем — короткий приём
         * объявленного ответа мы бы не отличили от полного.
         */
        public const val DEFAULT_READ_BUFFER: Int = 512

        /** Столько ждём **без единого кадра**, прежде чем считать обмен мёртвым. */
        public const val DEFAULT_INACTIVITY_MS: Long = 7_000

        /**
         * Размер блока передачи данных.
         *
         * 16 КиБ — то же, что у A2 в `SYNC_BULK` и у Legacy в
         * `bulkWriteFully`. Больший блок принадлежит нативному пути
         * (`usb:native`, 256 КиБ с конвейером), которого здесь нет.
         */
        public const val DATA_BLOCK_BYTES: Int = 16 * 1024

        /** Таймаут одной записи блока. Взят у A2: `SYNC_BULK_TIMEOUT_MS`. */
        internal const val DATA_WRITE_TIMEOUT_MS: Int = 10_000

        internal const val DEFAULT_WRITE_TIMEOUT_MS: Int = 7_000

        /**
         * Шаг ожидания.
         *
         * Дробим не ради точности, а чтобы отмена и подсчёт бездействия имели
         * место случиться: один приём на весь бюджет отдал бы управление только
         * в конце.
         */
        internal const val READ_SLICE_MS: Int = 900
    }
}
