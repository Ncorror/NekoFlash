package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/** Почему рукопожатие не состоялось. */
public enum class AdbHandshakeFailure {
    /** Peer не ответил за отведённое время. */
    NO_RESPONSE,

    /** Транспорт закрыт: интерфейс больше не удерживается. */
    TRANSPORT_CLOSED,

    /** Кадр потерян: продолжать по этому соединению нельзя. */
    FRAMING_LOST,

    /** Отправить кадр не удалось. */
    SEND_FAILED,

    /** Peer ответил командой, которой в рукопожатии быть не может. */
    UNEXPECTED_COMMAND,

    /** `AUTH` неизвестного типа. */
    UNSUPPORTED_AUTH_TYPE,

    /** Ключ хоста недоступен: ни подписать токен, ни отправить публичный ключ. */
    HOST_KEY_UNAVAILABLE,

    /**
     * Устройство так и не подтвердило авторизацию.
     *
     * Не ошибка соединения: скорее всего, диалог на экране остался нетронутым
     * или отклонён. В Recovery подтверждать его обычно некому.
     */
    AUTHORIZATION_NOT_CONFIRMED,

    /** Рукопожатие на этом транспорте уже проводилось. */
    ALREADY_ATTEMPTED,
}

/** Исход рукопожатия. */
public sealed interface AdbHandshakeOutcome {
    /** Соединение установлено, peer представился. */
    public data class Connected(
        val banner: AdbConnectionBanner,
        val peerVersion: Int,
        val peerMaxPayload: Int,
    ) : AdbHandshakeOutcome

    /** Соединения нет. */
    public data class Failed(
        val reason: AdbHandshakeFailure,
        val detail: String,
    ) : AdbHandshakeOutcome
}

/**
 * Рукопожатие `CNXN` и авторизация `AUTH` поверх одного захваченного интерфейса.
 *
 * **Одно рукопожатие на один транспорт.** Повторный [connect] отклоняется, и
 * это не осторожность, а перенесённое ограничение: в Legacy прямо записано, что
 * автоматическое закрытие с повторным открытием и вторым `CNXN` на ряде
 * Android USB host вызывало цикл detach/attach и разрушало нормальную
 * последовательность `AUTH`. Новое соединение — это новый захват интерфейса и
 * новая `SessionGeneration`, а не второй `CNXN` в том же.
 *
 * Порядок и таймауты взяты из Legacy `handleAuthPacket` и A2 `handleAuth`, где
 * они совпадают: сначала попытка подписать токен сохранённым ключом, при
 * неудаче — отправка публичного ключа; затем до [AUTH_RESPONSE_LIMIT] ответов,
 * с ожиданием 10 секунд до отправки публичного ключа и 60 после, потому что во
 * втором случае ждать приходится человека у экрана.
 */
public class AdbHandshake(
    private val reader: AdbPacketReader,
    private val writer: AdbPacketWriter,
    private val keyStore: AdbKeyStore,
    private val localMaxPayload: Int,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
) {
    private var attempted = false

    /**
     * Проводит рукопожатие.
     *
     * После успеха согласованная версия протокола сообщается читателю и
     * писателю: с этого момента правило контрольной суммы меняется, и оба
     * обязаны узнать об этом одновременно.
     */
    public fun connect(): AdbHandshakeOutcome {
        if (attempted) {
            return failed(
                AdbHandshakeFailure.ALREADY_ATTEMPTED,
                "one transport carries one CNXN; open a new session instead",
            )
        }
        attempted = true

        val sent = writer.write(
            command = AdbCommand.CNXN,
            arg0 = LOCAL_VERSION,
            arg1 = localMaxPayload,
            payload = HOST_BANNER,
        )
        if (sent !is AdbWriteOutcome.Sent) return sendFailure(sent, "CNXN")
        emit("cnxn_sent", mapOf("version" to LOCAL_VERSION.toHex(), "maxPayload" to localMaxPayload.toString()))

        val first = when (val outcome = reader.read(RESPONSE_TIMEOUT_MS)) {
            is AdbReadOutcome.Received -> outcome.packet
            else -> return readFailure(outcome, "CNXN response")
        }

        return when (first.command) {
            AdbCommand.CNXN -> connected(first)
            AdbCommand.AUTH -> authorize(first)
            else -> failed(
                AdbHandshakeFailure.UNEXPECTED_COMMAND,
                "command=0x${first.command.toString(16)}",
            )
        }
    }

    private fun authorize(first: AdbPacket): AdbHandshakeOutcome {
        if (first.arg0 != AUTH_TOKEN) {
            return failed(AdbHandshakeFailure.UNSUPPORTED_AUTH_TYPE, "type=${first.arg0}")
        }
        emit("auth_required")
        emitHostKeyProvenance()

        var publicKeySent = false
        val signature = runCatching { keyStore.signToken(first.payload) }
        if (signature.isSuccess) {
            val sent = writer.write(AdbCommand.AUTH, AUTH_SIGNATURE, 0, signature.getOrThrow())
            if (sent !is AdbWriteOutcome.Sent) return sendFailure(sent, "AUTH SIGNATURE")
            emit("auth_signature_sent")
        } else {
            emit("auth_signature_failed", mapOf("cause" to signature.causeName()))
            when (val result = sendPublicKey()) {
                null -> publicKeySent = true
                else -> return result
            }
        }

        repeat(AUTH_RESPONSE_LIMIT) {
            val timeout = if (publicKeySent) AUTH_CONFIRMATION_TIMEOUT_MS else AUTH_SIGNATURE_TIMEOUT_MS
            val packet = when (val outcome = reader.read(timeout)) {
                is AdbReadOutcome.Received -> outcome.packet
                AdbReadOutcome.Idle -> return failed(
                    if (publicKeySent) {
                        AdbHandshakeFailure.AUTHORIZATION_NOT_CONFIRMED
                    } else {
                        AdbHandshakeFailure.NO_RESPONSE
                    },
                    "waited ${timeout}ms",
                )

                else -> return readFailure(outcome, "AUTH response")
            }

            when (packet.command) {
                AdbCommand.CNXN -> return connected(packet)
                AdbCommand.AUTH -> {
                    if (packet.arg0 != AUTH_TOKEN) {
                        return failed(AdbHandshakeFailure.UNSUPPORTED_AUTH_TYPE, "type=${packet.arg0}")
                    }
                    if (!publicKeySent) {
                        when (val result = sendPublicKey()) {
                            null -> publicKeySent = true
                            else -> return result
                        }
                    }
                }

                else -> return failed(
                    AdbHandshakeFailure.UNEXPECTED_COMMAND,
                    "command=0x${packet.command.toString(16)}",
                )
            }
        }

        return failed(
            AdbHandshakeFailure.AUTHORIZATION_NOT_CONFIRMED,
            "device kept asking after $AUTH_RESPONSE_LIMIT responses",
        )
    }

    /**
     * Отправляет публичный ключ.
     *
     * Возвращает `null`, когда ключ ушёл, и готовый исход, когда отправить его
     * не удалось: после этого рукопожатию продолжаться незачем.
     */
    private fun sendPublicKey(): AdbHandshakeOutcome? {
        val payload = runCatching { keyStore.authPayload() }
        if (payload.isFailure) {
            return failed(AdbHandshakeFailure.HOST_KEY_UNAVAILABLE, payload.causeName())
        }
        val sent = writer.write(AdbCommand.AUTH, AUTH_RSAPUBLICKEY, 0, payload.getOrThrow())
        if (sent !is AdbWriteOutcome.Sent) return sendFailure(sent, "AUTH RSAPUBLICKEY")
        emit("auth_public_key_sent", mapOf("path" to keyStore.publicKeyPath()))
        return null
    }

    /**
     * Сообщает журналу, откуда взялся ключ хоста и какой он.
     *
     * Без этой записи диалог авторизации в неожиданный момент невозможно
     * разобрать: непонятно, устройство забыло хост или хост потерял ключ.
     * Прогон 2026-09-03 упёрся ровно в этот вопрос (`07` §6.15).
     */
    private fun emitHostKeyProvenance() {
        val described = runCatching { keyStore.provenance() }
        emit(
            "host_key",
            described.fold(
                onSuccess = { provenance ->
                    mapOf(
                        "origin" to provenance.origin.name,
                        "fingerprint" to provenance.fingerprint,
                    )
                },
                onFailure = { error ->
                    mapOf("unavailable" to (error.message ?: error.javaClass.simpleName))
                },
            ),
        )
    }

    private fun connected(packet: AdbPacket): AdbHandshakeOutcome {
        val banner = AdbConnectionBanner.parse(packet.payload)
        reader.negotiate(packet.arg0)
        writer.negotiate(packet.arg0)
        emit(
            "connected",
            mapOf(
                "peerMode" to banner.peerMode.name,
                "peerVersion" to packet.arg0.toHex(),
                "peerMaxPayload" to packet.arg1.toString(),
                "features" to banner.features.size.toString(),
            ),
        )
        return AdbHandshakeOutcome.Connected(banner, packet.arg0, packet.arg1)
    }

    private fun readFailure(outcome: AdbReadOutcome, stage: String): AdbHandshakeOutcome = when (outcome) {
        AdbReadOutcome.Idle -> failed(AdbHandshakeFailure.NO_RESPONSE, stage)
        AdbReadOutcome.Closed -> failed(AdbHandshakeFailure.TRANSPORT_CLOSED, stage)
        is AdbReadOutcome.Failed -> failed(
            AdbHandshakeFailure.FRAMING_LOST,
            "$stage: ${outcome.reason.name} ${outcome.detail}",
        )

        is AdbReadOutcome.Received -> failed(AdbHandshakeFailure.UNEXPECTED_COMMAND, stage)
    }

    private fun sendFailure(outcome: AdbWriteOutcome, stage: String): AdbHandshakeOutcome = when (outcome) {
        AdbWriteOutcome.Closed -> failed(AdbHandshakeFailure.TRANSPORT_CLOSED, stage)
        is AdbWriteOutcome.Interrupted -> failed(
            AdbHandshakeFailure.SEND_FAILED,
            "$stage: ${outcome.detail} (sent=${outcome.sentBytes})",
        )

        AdbWriteOutcome.Sent -> failed(AdbHandshakeFailure.SEND_FAILED, stage)
    }

    private fun failed(reason: AdbHandshakeFailure, detail: String): AdbHandshakeOutcome.Failed {
        emit("handshake_failed", mapOf("reason" to reason.name, "detail" to detail))
        return AdbHandshakeOutcome.Failed(reason, detail)
    }

    private fun emit(message: String, fields: Map<String, String> = emptyMap()) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    private fun Result<*>.causeName(): String {
        val error = exceptionOrNull() ?: return "unknown"
        return error.message ?: error.javaClass.simpleName
    }

    private fun Int.toHex(): String = "0x${toUInt().toString(16)}"

    public companion object {
        public const val DIAGNOSTIC_CATEGORY: String = "adb"

        /** Версия протокола, на которой представляется хост. */
        public const val LOCAL_VERSION: Int = AdbChecksum.VERSION_WITH_CHECKSUM

        /** `AUTH` с токеном для подписи. */
        public const val AUTH_TOKEN: Int = 1

        /** `AUTH` с подписью токена. */
        public const val AUTH_SIGNATURE: Int = 2

        /** `AUTH` с публичным ключом хоста. */
        public const val AUTH_RSAPUBLICKEY: Int = 3

        /** Сколько ответов подряд разбирается, прежде чем ожидание признаётся напрасным. */
        public const val AUTH_RESPONSE_LIMIT: Int = 12

        /** Значения из A2 и Legacy. */
        public const val RESPONSE_TIMEOUT_MS: Int = 10_000
        public const val AUTH_SIGNATURE_TIMEOUT_MS: Int = 10_000
        public const val AUTH_CONFIRMATION_TIMEOUT_MS: Int = 60_000

        /**
         * Баннер хоста.
         *
         * Ровно тот, что в обоих архивах. Возможностей хост о себе пока не
         * объявляет: `shell,v2` потребует расширить эту строку, и сделать это
         * надо будет осознанно, вместе с самим `shell,v2`, а не заранее.
         */
        private val HOST_BANNER = "host::NekoFlash\u0000".toByteArray(Charsets.UTF_8)
    }
}
