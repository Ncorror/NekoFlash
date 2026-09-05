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

    /**
     * Маршрутизатор один на соединение.
     *
     * Идентификаторы потоков выдаёт он, и второй экземпляр начал бы выдавать их
     * заново — устройство получило бы два разных потока под одним номером.
     */
    private val router = AdbStreamRouter()

    private val services = AdbServiceCall(
        reader = reader,
        writer = writer,
        router = router,
        diagnostics = diagnostics,
    )

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

    /**
     * Вызывает сервис и ждёт его вывод.
     *
     * Блокирует вызывающий поток. Вызовы обязаны идти по одному: читатель
     * физически один, и два одновременных вызова разобрали бы пакеты друг
     * друга. Сериализацию обеспечивает владелец соединения — у него для этого
     * есть исполнитель с единственным потоком.
     */
    public fun call(
        service: String,
        maxOutputBytes: Int = AdbServiceCall.DEFAULT_MAX_OUTPUT_BYTES,
        timeoutMillis: Int = AdbServiceCall.DEFAULT_TIMEOUT_MS,
    ): AdbServiceOutcome = services.run(service, maxOutputBytes, timeoutMillis)
}
