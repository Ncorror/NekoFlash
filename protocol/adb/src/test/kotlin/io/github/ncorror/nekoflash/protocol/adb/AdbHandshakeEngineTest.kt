package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbHandshakeEngineTest {
    @Test
    fun directCnxnCompletesWithoutAuthTraffic() {
        val transport = FakeTransport(
            reads = mutableListOf(
                frame(AdbPacketCodec.A_CNXN, AdbPacketCodec.VERSION_WITH_CHECKSUM, 1024 * 1024, "device::\u0000"),
            ),
        )

        val outcome = engine().run(transport, FakeAuth())

        assertTrue(outcome is AdbHandshakeOutcome.Connected)
        outcome as AdbHandshakeOutcome.Connected
        assertEquals(AdbAuthPath.NONE, outcome.authPath)
        assertEquals(AdbPeerKind.DEVICE, outcome.peerKind)
        assertEquals(listOf(AdbPacketCodec.A_CNXN), transport.writes.map { it.command })
    }

    @Test
    fun knownKeyPathSendsSignatureThenAcceptsCnxn() {
        val token = ByteArray(20) { it.toByte() }
        val transport = FakeTransport(
            reads = mutableListOf(
                AdbFrame(AdbPacketCodec.A_AUTH, AdbPacketCodec.AUTH_TOKEN, 0, token),
                frame(AdbPacketCodec.A_CNXN, AdbPacketCodec.VERSION_WITH_CHECKSUM, 1024 * 1024, "device::\u0000"),
            ),
        )

        val outcome = engine().run(transport, FakeAuth())

        assertEquals(AdbAuthPath.SIGNATURE, (outcome as AdbHandshakeOutcome.Connected).authPath)
        assertEquals(
            listOf(AdbPacketCodec.A_CNXN, AdbPacketCodec.A_AUTH),
            transport.writes.map { it.command },
        )
        assertEquals(AdbPacketCodec.AUTH_SIGNATURE, transport.writes[1].arg0)
        assertEquals(256, transport.writes[1].payload.size)
    }

    @Test
    fun newKeyPathSendsPublicKeyOnlyAfterSecondToken() {
        val token1 = ByteArray(20) { 1 }
        val token2 = ByteArray(20) { 2 }
        val transport = FakeTransport(
            reads = mutableListOf(
                AdbFrame(AdbPacketCodec.A_AUTH, AdbPacketCodec.AUTH_TOKEN, 0, token1),
                AdbFrame(AdbPacketCodec.A_AUTH, AdbPacketCodec.AUTH_TOKEN, 0, token2),
                frame(AdbPacketCodec.A_CNXN, AdbPacketCodec.VERSION_WITH_CHECKSUM, 1024 * 1024, "device::\u0000"),
            ),
        )
        val trace = mutableListOf<AdbHandshakeTraceEvent>()

        val outcome = engine().run(transport, FakeAuth()) { trace += it }

        assertEquals(AdbAuthPath.PUBLIC_KEY, (outcome as AdbHandshakeOutcome.Connected).authPath)
        assertEquals(3, transport.writes.size)
        assertEquals(AdbPacketCodec.AUTH_SIGNATURE, transport.writes[1].arg0)
        assertEquals(AdbPacketCodec.AUTH_RSAPUBLICKEY, transport.writes[2].arg0)
        assertTrue(trace.any { it.code == "auth_waiting_for_confirmation" })
    }

    @Test
    fun wrongAuthTokenSizeFailsBeforeSigning() {
        val transport = FakeTransport(
            reads = mutableListOf(
                AdbFrame(AdbPacketCodec.A_AUTH, AdbPacketCodec.AUTH_TOKEN, 0, ByteArray(19)),
            ),
        )

        val outcome = engine().run(transport, FakeAuth())

        assertEquals(AdbHandshakeOutcome.ProtocolFailed("auth_token_size_19"), outcome)
        assertEquals(1, transport.writes.size)
    }


    @Test
    fun traceContainsOnlySafePacketMetadataAndKeepsByteCountsVisible() {
        val token = ByteArray(20) { index -> (index + 1).toByte() }
        val transport = FakeTransport(
            reads = mutableListOf(
                AdbFrame(AdbPacketCodec.A_AUTH, AdbPacketCodec.AUTH_TOKEN, 0, token),
                frame(AdbPacketCodec.A_CNXN, AdbPacketCodec.VERSION_WITH_CHECKSUM, 1024 * 1024, "device::serial=secret\u0000"),
            ),
        )
        val trace = mutableListOf<AdbHandshakeTraceEvent>()

        engine().run(transport, FakeAuth()) { trace += it }

        assertTrue(trace.any { it.code == "packet_rx" && it.fields["dataBytes"] == "20" })
        assertTrue(trace.any { it.code == "packet_tx_attempt" && it.fields["semantic"] == "AUTH_SIGNATURE" })
        assertTrue(trace.none { event ->
            event.fields.keys.any { key ->
                val normalized = key.lowercase()
                normalized.contains("token") || normalized.contains("signature") || normalized.contains("payload") || normalized.contains("keymaterial")
            }
        })
        assertTrue(trace.none { event -> event.fields.values.any { it.contains("serial=secret") } })
    }

    @Test
    fun transportFailureIsTerminalAndNoAutomaticSecondCnxnIsSent() {
        val transport = FakeTransport(
            reads = mutableListOf(AdbReadResult.Failed(AdbIoFailure.USB_IN_NOT_COMPLETED)),
        )

        val outcome = engine().run(transport, FakeAuth())

        assertEquals(
            AdbHandshakeOutcome.TransportFailed("receive_handshake", AdbIoFailure.USB_IN_NOT_COMPLETED),
            outcome,
        )
        assertEquals(1, transport.writes.size)
        assertEquals(AdbPacketCodec.A_CNXN, transport.writes.single().command)
    }

    private fun engine() = AdbHandshakeEngine(localMaxPayload = 1024 * 1024)

    private fun frame(command: Int, arg0: Int, arg1: Int, payload: String): AdbFrame =
        AdbFrame(command, arg0, arg1, payload.toByteArray())

    private class FakeAuth : AdbAuthProvider {
        override fun signToken(token: ByteArray): ByteArray = ByteArray(256) { 7 }
        override fun publicKeyPayload(): ByteArray = "public-key\u0000".toByteArray()
    }

    private class FakeTransport(
        private val reads: MutableList<Any>,
    ) : AdbHandshakeTransport {
        val writes = mutableListOf<AdbFrame>()

        override fun send(frame: AdbFrame, timeoutMs: Int): AdbWriteResult {
            writes += frame
            return AdbWriteResult.Success
        }

        override fun receive(timeoutMs: Int): AdbReadResult {
            val next = reads.removeAt(0)
            return when (next) {
                is AdbFrame -> AdbReadResult.Frame(next)
                is AdbReadResult -> next
                else -> error("Unexpected fake item")
            }
        }
    }
}
