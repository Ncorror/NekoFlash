package io.github.ncorror.nekoflash.protocol.fastboot

import io.github.ncorror.nekoflash.usb.api.UsbTransferResult

/*
 * Разбор одного принятого результата — без состояния полосы.
 *
 * Живут отдельным файлом, а не приватными методами [FastbootLane]: они не
 * трогают ни `currentState`, ни транспорт, и держать их внутри значило бы
 * прятать чистый разбор среди того, что меняет состояние обмена.
 */

/**
 * Что помешало этому чтению, либо `null`.
 *
 * Приём нулевой длины отделён от отказа намеренно: устройство, замолчавшее
 * посреди раздела, и отказ на уровне транспорта — разные наблюдения, и по
 * журналу их надо различать.
 *
 * **Объявленный объём называется в каждой из причин.** Без него «не
 * состоялся на 0» не отличить от «устройство назвало бессмысленный объём»,
 * а по выгрузке `07` §6.89 пришлось именно это и гадать. Смещение без того,
 * от чего оно отсчитано, — половина наблюдения.
 */
internal fun receiveProblem(
    result: UsbTransferResult,
    wanted: Int,
    received: Long,
    expectedBytes: Long,
): String? = when {
    result is UsbTransferResult.Failed ->
        "приём не состоялся на $received из $expectedBytes: ${result.reason}"

    result is UsbTransferResult.Completed && result.bytes <= 0 ->
        "устройство перестало слать на $received из $expectedBytes"

    result is UsbTransferResult.Completed && result.bytes > wanted ->
        "неоднозначный приём на $received из $expectedBytes: принято ${result.bytes} из $wanted"

    else -> null
}

/**
 * Кадр из результата приёма, либо `null`, если кадра не было.
 *
 * Пустой успешный приём — тоже «кадра не было»: устройство молчит, а не
 * прислало пустоту.
 */
internal fun packetOf(received: UsbTransferResult, buffer: ByteArray): FastbootPacket? =
    (received as? UsbTransferResult.Completed)
        ?.takeIf { it.bytes > 0 }
        ?.let { FastbootPacketCodec.parse(buffer, it.bytes) }
