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
    /**
     * Возможности, объявленные устройством в `CNXN`.
     *
     * До рукопожатия пусто. Хост о своих возможностях не объявляет вовсе, и
     * решение о `shell,v2` принимается только по этому набору — так же, как в
     * Legacy.
     */
    @Volatile
    public var peerFeatures: Set<String> = emptySet()
        private set

    public fun connect(): AdbHandshakeOutcome = handshake.connect().also { outcome ->
        if (outcome is AdbHandshakeOutcome.Connected) {
            peerFeatures = outcome.banner.features
        }
    }

    /** Поддерживает ли устройство `shell,v2`. */
    public val supportsShellV2: Boolean
        get() = peerFeatures.contains(SHELL_V2_FEATURE)

    /**
     * Выполняет команду в оболочке устройства.
     *
     * При поддержке `shell,v2` идёт через него: он разделяет stdout и stderr и
     * сообщает код возврата. Иначе — обычный `shell:`, где кода возврата нет и
     * подставлять его нельзя.
     *
     * Откат на `shell:` делается и тогда, когда `shell,v2` объявлен, но поток
     * не открылся: устройство может отказать в сервисе, которым похвасталось,
     * и терять из-за этого команду незачем. Так же поступает Legacy.
     */
    public fun shell(
        command: String,
        maxOutputBytes: Int = AdbServiceCall.DEFAULT_MAX_OUTPUT_BYTES,
        timeoutMillis: Int = AdbServiceCall.DEFAULT_TIMEOUT_MS,
    ): AdbShellOutcome {
        if (supportsShellV2) {
            val outcome = services.run(
                service = "shell,v2,raw:$command",
                maxOutputBytes = maxOutputBytes,
                timeoutMillis = timeoutMillis,
                payloadOnOpen = AdbShellProtocol.closeStdinFrame(),
            )
            when (outcome) {
                is AdbServiceOutcome.Completed ->
                    return AdbShellOutcome.Finished(AdbShellProtocol.decode(outcome.output))

                is AdbServiceOutcome.Failed ->
                    if (outcome.reason != AdbServiceFailure.REJECTED) {
                        return AdbShellOutcome.Failed(outcome.reason, outcome.detail)
                    }
            }
        }

        return when (val legacy = services.run("shell:$command", maxOutputBytes, timeoutMillis)) {
            is AdbServiceOutcome.Completed -> AdbShellOutcome.Finished(
                AdbShellOutput(stdout = legacy.text(), stderr = "", exitCode = null),
            )

            is AdbServiceOutcome.Failed -> AdbShellOutcome.Failed(legacy.reason, legacy.detail)
        }
    }

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

    private companion object {
        const val SHELL_V2_FEATURE = "shell_v2"
    }
}

/** Исход команды в оболочке. */
public sealed interface AdbShellOutcome {
    /** Команда отработала; вывод и код возврата внутри. */
    public data class Finished(val output: AdbShellOutput) : AdbShellOutcome

    /** Команда не выполнилась. */
    public data class Failed(val reason: AdbServiceFailure, val detail: String) : AdbShellOutcome
}
