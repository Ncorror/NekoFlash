package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/**
 * Одно ADB-соединение поверх одного захваченного интерфейса.
 *
 * Класс существует ради единственного обещания, которое иначе легко разъехалось
 * бы по трём местам: `maxdata`, объявленный peer'у в `CNXN`, и `maxdata`, по
 * которому читатель отвергает слишком длинный кадр, — это одно и то же число,
 * посчитанное один раз по уровню API. В Legacy эти два значения жили порознь,
 * и именно там начиналась история inbound framing invariant.
 *
 * Соединение одноразовое, как и рукопожатие: следующее — это новый захват
 * интерфейса и новая `SessionGeneration`.
 *
 * Все операции блокирующие. Поток выделяет владелец: контракт требует
 * единственного физического читателя, и выбрать его может только тот, кто
 * знает, где этот поток живёт.
 */
public class AdbConnection(
    handle: UsbTransportHandle,
    keyStore: AdbKeyStore,
    apiLevel: Int,
    diagnostics: DiagnosticSink = DiagnosticSink { },
) {
    /** Что объявляется peer'у и чем проверяется входящий кадр. */
    public val advertisedMaxPayload: Int = AdbInboundFraming.advertisedMaxPayload(apiLevel)

    private val reader = AdbPacketReader(handle, advertisedMaxPayload)
    private val writer = AdbPacketWriter(handle)
    private val handshake = AdbHandshake(
        reader = reader,
        writer = writer,
        keyStore = keyStore,
        localMaxPayload = advertisedMaxPayload,
        diagnostics = diagnostics,
    )

    /**
     * Проводит рукопожатие.
     *
     * Блокирует вызывающий поток до ответа устройства или до истечения
     * таймаутов рукопожатия: при ожидании подтверждения диалога это до минуты.
     */
    public fun connect(): AdbHandshakeOutcome = handshake.connect()
}
