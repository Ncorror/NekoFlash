package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/** Исход отправки кадра. */
public sealed interface AdbWriteOutcome {
    /** Кадр отправлен целиком. */
    public data object Sent : AdbWriteOutcome

    /** Интерфейс больше не удерживается. */
    public data object Closed : AdbWriteOutcome

    /**
     * Отправка не завершилась.
     *
     * [sentBytes] — сколько байт кадра ушло до обрыва. Это не диагностика для
     * красоты: по нему видно, успел ли peer увидеть заголовок, а значит —
     * мутировал ли запрос что-нибудь. Повторять такую отправку нельзя, пока
     * ответ на этот вопрос неизвестен
     * (`docs/03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §3).
     */
    public data class Interrupted(val sentBytes: Int, val detail: String) : AdbWriteOutcome
}

/**
 * Отправка кадров ADB в единственный поток записи.
 *
 * Экземпляр не сериализует вызовы: два одновременных отправителя перемешали бы
 * кадры. Владелец транспорта обязан сделать запись последовательной — так же
 * это решено в A2 (`adbWriteLock`).
 *
 * Кадр уходит как заголовок и следом payload. Обе части дописываются до конца
 * кусками не больше [USB_BULK_CHUNK_BYTES]: в сторону хост → устройство это
 * законно и сделано одинаково в Legacy (`bulkWriteFully`) и в A2. Обратная
 * сторона так не работает — там дробление объявленного payload разрушает
 * рамку, и правило записано в [AdbInboundFraming].
 */
public class AdbPacketWriter(
    private val handle: UsbTransportHandle,
    private val localVersion: Int = AdbChecksum.VERSION_WITH_CHECKSUM,
) {
    private val headerBuffer = ByteArray(AdbPacketHeader.SIZE_BYTES)

    /**
     * Версия протокола peer'а: определяет, считается ли контрольная сумма.
     *
     * До `CNXN` равна нашей, как и у читателя.
     */
    @Volatile
    public var peerVersion: Int = localVersion
        private set

    /** Запоминает версию, объявленную peer'ом в `CNXN`. */
    public fun negotiate(version: Int) {
        peerVersion = version
    }

    /**
     * Отправляет один кадр.
     *
     * Ограничение `maxdata`, объявленное peer'ом, сюда не спускается: кадр
     * формирует слой, который знает, какой сервис его отправляет. Задача этого
     * класса — довести уже сформированный кадр до провода без искажения.
     */
    public fun write(
        command: Long,
        arg0: Int,
        arg1: Int,
        payload: ByteArray = EMPTY_PAYLOAD,
        timeoutMillis: Int = DEFAULT_SEND_TIMEOUT_MS,
    ): AdbWriteOutcome {
        val checksum = if (AdbChecksum.isRequired(localVersion, peerVersion)) {
            AdbChecksum.compute(payload)
        } else {
            0
        }
        AdbPacketHeader.encode(headerBuffer, command, arg0, arg1, payload, checksum)

        val header = sendFully(headerBuffer, headerBuffer.size, 0, timeoutMillis)
        if (header !is AdbWriteOutcome.Sent) return header
        if (payload.isEmpty()) return AdbWriteOutcome.Sent

        return sendFully(payload, payload.size, headerBuffer.size, timeoutMillis)
    }

    /**
     * @param alreadySent сколько байт кадра ушло раньше: нужно, чтобы
     * прерванная отправка payload сообщала о кадре целиком, а не о своём куске.
     */
    private fun sendFully(
        source: ByteArray,
        length: Int,
        alreadySent: Int,
        timeoutMillis: Int,
    ): AdbWriteOutcome {
        var sent = 0
        while (sent < length) {
            val chunk = minOf(USB_BULK_CHUNK_BYTES, length - sent)
            val result = handle.send(
                source = source,
                offset = sent,
                length = chunk,
                timeoutMillis = timeoutMillis,
            )
            when (result) {
                is UsbTransferResult.Completed -> {
                    if (result.bytes == 0) {
                        return AdbWriteOutcome.Interrupted(
                            alreadySent + sent,
                            "transfer moved no bytes with $chunk requested",
                        )
                    }
                    sent += result.bytes
                }

                is UsbTransferResult.Failed -> return when (result.reason) {
                    UsbTransferFailure.NOT_HELD -> AdbWriteOutcome.Closed
                    UsbTransferFailure.NOT_COMPLETED -> AdbWriteOutcome.Interrupted(
                        alreadySent + sent,
                        "transfer failed after $sent/$length bytes of this part",
                    )
                }
            }
        }
        return AdbWriteOutcome.Sent
    }

    public companion object {
        /** Значение из A2 (`USB_WRITE_TIMEOUT_MS`). */
        public const val DEFAULT_SEND_TIMEOUT_MS: Int = 5_000

        /**
         * Размер одной передачи при записи.
         *
         * Из Legacy и A2 (`USB_BULK_CHUNK_BYTES`): держит отдельные транзакции
         * контроллера в границах, которые платформа принимает на любом
         * поддерживаемом уровне API.
         */
        public const val USB_BULK_CHUNK_BYTES: Int = 16 * 1024

        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
