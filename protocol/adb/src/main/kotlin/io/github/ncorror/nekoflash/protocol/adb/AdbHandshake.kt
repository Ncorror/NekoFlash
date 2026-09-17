package io.github.ncorror.nekoflash.protocol.adb

/** USB/protocol-neutral frame transport used only for the Phase 2 CNXN/AUTH evidence probe. */
interface AdbHandshakeTransport {
    fun send(frame: AdbFrame, timeoutMs: Int): AdbWriteResult
    fun receive(timeoutMs: Int): AdbReadResult
}

enum class AdbIoFailure {
    CLOSED,
    USB_OUT_NOT_COMPLETED,
    USB_IN_NOT_COMPLETED,
    PARTIAL_HEADER,
    SHORT_PAYLOAD,
    INVALID_HEADER,
    CHECKSUM_MISMATCH,
    PAYLOAD_TOO_LARGE,
    UNKNOWN,
}

sealed interface AdbWriteResult {
    data object Success : AdbWriteResult
    data class Failed(val failure: AdbIoFailure, val confirmedBytes: Int = 0) : AdbWriteResult
}

sealed interface AdbReadResult {
    data class Frame(val frame: AdbFrame) : AdbReadResult
    data class Failed(val failure: AdbIoFailure, val confirmedBytes: Int = 0) : AdbReadResult
}

interface AdbAuthProvider {
    fun signToken(token: ByteArray): ByteArray
    fun publicKeyPayload(): ByteArray
}

enum class AdbAuthPath {
    NONE,
    SIGNATURE,
    PUBLIC_KEY,
}

enum class AdbPeerKind {
    DEVICE,
    RECOVERY,
    SIDELOAD,
    UNKNOWN,
}

sealed interface AdbHandshakeOutcome {
    data class Connected(
        val peerVersion: Int,
        val peerMaxPayload: Int,
        val peerKind: AdbPeerKind,
        val authPath: AdbAuthPath,
    ) : AdbHandshakeOutcome

    data class TransportFailed(val stage: String, val failure: AdbIoFailure) : AdbHandshakeOutcome
    data class ProtocolFailed(val reason: String) : AdbHandshakeOutcome
    data class AuthKeyFailed(val reason: String) : AdbHandshakeOutcome
    data object AuthResponseLimit : AdbHandshakeOutcome
}

data class AdbHandshakeTraceEvent(
    val code: String,
    val fields: Map<String, String> = emptyMap(),
)

/**
 * Minimal ADB handshake state machine. It sends exactly one CNXN and then only AUTH responses.
 * No OPEN/WRTE/shell/push/service traffic exists in this slice.
 */
class AdbHandshakeEngine(
    private val localVersion: Int = AdbPacketCodec.VERSION_WITH_CHECKSUM,
    private val localMaxPayload: Int,
    private val hostBanner: ByteArray = "host::NekoFlash\u0000".toByteArray(Charsets.UTF_8),
    private val writeTimeoutMs: Int = 5_000,
    private val initialResponseTimeoutMs: Int = 10_000,
    private val authorizationResponseTimeoutMs: Int = 60_000,
    private val responseLimit: Int = 12,
) {
    fun run(
        transport: AdbHandshakeTransport,
        authProvider: AdbAuthProvider,
        trace: (AdbHandshakeTraceEvent) -> Unit = {},
    ): AdbHandshakeOutcome {
        val cnxn = AdbFrame(
            command = AdbPacketCodec.A_CNXN,
            arg0 = localVersion,
            arg1 = localMaxPayload,
            payload = hostBanner,
        )
        trace(txEvent(cnxn, "CNXN"))
        when (val sent = transport.send(cnxn, writeTimeoutMs)) {
            AdbWriteResult.Success -> Unit
            is AdbWriteResult.Failed -> return AdbHandshakeOutcome.TransportFailed("send_cnxn", sent.failure)
        }

        var signatureSent = false
        var publicKeySent = false
        var authPath = AdbAuthPath.NONE

        repeat(responseLimit) {
            val timeoutMs = if (publicKeySent) authorizationResponseTimeoutMs else initialResponseTimeoutMs
            val frame = when (val read = transport.receive(timeoutMs)) {
                is AdbReadResult.Frame -> read.frame
                is AdbReadResult.Failed -> return AdbHandshakeOutcome.TransportFailed("receive_handshake", read.failure)
            }
            trace(rxEvent(frame))

            when (frame.command) {
                AdbPacketCodec.A_CNXN -> {
                    return AdbHandshakeOutcome.Connected(
                        peerVersion = frame.arg0,
                        peerMaxPayload = frame.arg1,
                        peerKind = peerKind(frame.payload),
                        authPath = authPath,
                    )
                }

                AdbPacketCodec.A_AUTH -> {
                    if (frame.arg0 != AdbPacketCodec.AUTH_TOKEN) {
                        return AdbHandshakeOutcome.ProtocolFailed("unsupported_auth_type_${frame.arg0}")
                    }
                    if (frame.payload.size != AUTH_TOKEN_BYTES) {
                        return AdbHandshakeOutcome.ProtocolFailed("auth_token_size_${frame.payload.size}")
                    }

                    if (!signatureSent) {
                        val signature = runCatching { authProvider.signToken(frame.payload) }.getOrElse {
                            return AdbHandshakeOutcome.AuthKeyFailed("sign_token")
                        }
                        if (signature.isEmpty()) return AdbHandshakeOutcome.AuthKeyFailed("empty_signature")
                        val auth = AdbFrame(
                            command = AdbPacketCodec.A_AUTH,
                            arg0 = AdbPacketCodec.AUTH_SIGNATURE,
                            arg1 = 0,
                            payload = signature,
                        )
                        trace(txEvent(auth, "AUTH_SIGNATURE"))
                        when (val sent = transport.send(auth, writeTimeoutMs)) {
                            AdbWriteResult.Success -> Unit
                            is AdbWriteResult.Failed -> {
                                return AdbHandshakeOutcome.TransportFailed("send_auth_signature", sent.failure)
                            }
                        }
                        signatureSent = true
                        authPath = AdbAuthPath.SIGNATURE
                    } else if (!publicKeySent) {
                        val publicKey = runCatching { authProvider.publicKeyPayload() }.getOrElse {
                            return AdbHandshakeOutcome.AuthKeyFailed("public_key")
                        }
                        if (publicKey.isEmpty()) return AdbHandshakeOutcome.AuthKeyFailed("empty_public_key")
                        val auth = AdbFrame(
                            command = AdbPacketCodec.A_AUTH,
                            arg0 = AdbPacketCodec.AUTH_RSAPUBLICKEY,
                            arg1 = 0,
                            payload = publicKey,
                        )
                        trace(txEvent(auth, "AUTH_RSAPUBLICKEY"))
                        when (val sent = transport.send(auth, writeTimeoutMs)) {
                            AdbWriteResult.Success -> Unit
                            is AdbWriteResult.Failed -> {
                                return AdbHandshakeOutcome.TransportFailed("send_auth_public_key", sent.failure)
                            }
                        }
                        publicKeySent = true
                        authPath = AdbAuthPath.PUBLIC_KEY
                        trace(AdbHandshakeTraceEvent("auth_waiting_for_confirmation"))
                    } else {
                        trace(AdbHandshakeTraceEvent("auth_token_repeated_after_public_key"))
                    }
                }

                else -> return AdbHandshakeOutcome.ProtocolFailed(
                    "unexpected_command_${AdbPacketCodec.commandName(frame.command)}",
                )
            }
        }

        return AdbHandshakeOutcome.AuthResponseLimit
    }

    private fun txEvent(frame: AdbFrame, semantic: String): AdbHandshakeTraceEvent = AdbHandshakeTraceEvent(
        code = "packet_tx_attempt",
        fields = mapOf(
            "semantic" to semantic,
            "command" to AdbPacketCodec.commandName(frame.command),
            "arg0" to frame.arg0.toString(),
            "dataBytes" to frame.payload.size.toString(),
        ),
    )

    private fun rxEvent(frame: AdbFrame): AdbHandshakeTraceEvent {
        val semantic = if (frame.command == AdbPacketCodec.A_AUTH) {
            "AUTH_${AdbPacketCodec.authTypeName(frame.arg0)}"
        } else {
            AdbPacketCodec.commandName(frame.command)
        }
        return AdbHandshakeTraceEvent(
            code = "packet_rx",
            fields = mapOf(
                "semantic" to semantic,
                "command" to AdbPacketCodec.commandName(frame.command),
                "arg0" to frame.arg0.toString(),
                "dataBytes" to frame.payload.size.toString(),
            ),
        )
    }

    private fun peerKind(payload: ByteArray): AdbPeerKind {
        val banner = payload.toString(Charsets.UTF_8).trimEnd('\u0000')
        return when {
            banner.startsWith("device::", ignoreCase = true) -> AdbPeerKind.DEVICE
            banner.startsWith("recovery::", ignoreCase = true) -> AdbPeerKind.RECOVERY
            banner.startsWith("sideload::", ignoreCase = true) -> AdbPeerKind.SIDELOAD
            else -> AdbPeerKind.UNKNOWN
        }
    }

    private companion object {
        const val AUTH_TOKEN_BYTES = 20
    }
}
