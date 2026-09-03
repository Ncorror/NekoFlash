package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionBannerTest {
    @Test
    fun deviceBannerIsRecognisedWithItsFeatures() {
        val banner = AdbConnectionBanner.parse(
            "device::ro.product.name=vayu;ro.product.model=POCO X3 Pro;features=shell_v2,cmd,stat_v2\u0000"
                .toByteArray(),
        )

        assertEquals(AdbPeerMode.DEVICE, banner.peerMode)
        assertEquals(setOf("shell_v2", "cmd", "stat_v2"), banner.features)
    }

    @Test
    fun recoveryAndSideloadAreDistinguished() {
        assertEquals(AdbPeerMode.RECOVERY, AdbConnectionBanner.parse("recovery::\u0000".toByteArray()).peerMode)
        assertEquals(AdbPeerMode.SIDELOAD, AdbConnectionBanner.parse("sideload::\u0000".toByteArray()).peerMode)
    }

    /** Незнакомый peer не выдаётся за обычное устройство. */
    @Test
    fun unknownPrefixStaysUnknown() {
        assertEquals(AdbPeerMode.UNKNOWN, AdbConnectionBanner.parse("bootloader::x\u0000".toByteArray()).peerMode)
    }

    @Test
    fun bannerWithoutFeaturesGivesAnEmptySet() {
        val banner = AdbConnectionBanner.parse("device::ro.product.name=vayu\u0000".toByteArray())

        assertTrue(banner.features.isEmpty())
    }

    @Test
    fun trailingNulIsStrippedFromTheBannerText() {
        assertEquals("device::x", AdbConnectionBanner.parse("device::x\u0000".toByteArray()).banner)
    }

    @Test
    fun emptyPayloadIsParsedAsAnUnknownPeer() {
        val banner = AdbConnectionBanner.parse(ByteArray(0))

        assertEquals(AdbPeerMode.UNKNOWN, banner.peerMode)
        assertTrue(banner.features.isEmpty())
    }
}

class AdbHandshakeTest {
    @Test
    fun deviceThatAnswersWithCnxnConnectsWithoutAuthorization() = withKeyStore { keyStore ->
        val device = ScriptedDevice(cnxn(DEVICE_BANNER))
        val handshake = device.handshake(keyStore)

        val outcome = handshake.connect() as AdbHandshakeOutcome.Connected

        assertEquals(AdbPeerMode.DEVICE, outcome.banner.peerMode)
        assertEquals(setOf("shell_v2"), outcome.banner.features)
        assertEquals(AdbCommand.CNXN, device.sent[0].command)
    }

    @Test
    fun hostAdvertisesItsOwnMaxPayloadNotThePeerOne() = withKeyStore { keyStore ->
        val device = ScriptedDevice(cnxn(DEVICE_BANNER))

        device.handshake(keyStore, localMaxPayload = AdbInboundFraming.PRE_P_MAX_PAYLOAD_BYTES).connect()

        assertEquals(AdbInboundFraming.PRE_P_MAX_PAYLOAD_BYTES, device.sent[0].arg1)
        assertEquals(AdbHandshake.LOCAL_VERSION, device.sent[0].arg0)
    }

    @Test
    fun negotiatedPeerVersionReachesBothReaderAndWriter() = withKeyStore { keyStore ->
        val device = ScriptedDevice(cnxn(DEVICE_BANNER, version = AdbChecksum.VERSION_SKIP_CHECKSUM))
        val reader = device.reader()
        val writer = device.writer()

        AdbHandshake(reader, writer, keyStore, MAX_PAYLOAD).connect()

        assertEquals(AdbChecksum.VERSION_SKIP_CHECKSUM, reader.peerVersion)
        assertEquals(AdbChecksum.VERSION_SKIP_CHECKSUM, writer.peerVersion)
    }

    /** Обычный путь авторизации: подпись сохранённым ключом принята. */
    @Test
    fun savedKeySignatureIsAcceptedWithoutSendingThePublicKey() = withKeyStore { keyStore ->
        val device = ScriptedDevice(authToken(), cnxn(DEVICE_BANNER))

        val outcome = device.handshake(keyStore).connect()

        assertTrue(outcome is AdbHandshakeOutcome.Connected)
        assertEquals(
            listOf(AdbCommand.CNXN, AdbCommand.AUTH),
            device.sent.map { it.command },
        )
        assertEquals(AdbHandshake.AUTH_SIGNATURE, device.sent[1].arg0)
    }

    /** Устройство не узнало ключ и просит снова: тогда уходит публичный ключ. */
    @Test
    fun repeatedTokenMakesTheHostSendItsPublicKey() = withKeyStore { keyStore ->
        val device = ScriptedDevice(authToken(), authToken(), cnxn(DEVICE_BANNER))

        val outcome = device.handshake(keyStore).connect()

        assertTrue(outcome is AdbHandshakeOutcome.Connected)
        assertEquals(AdbHandshake.AUTH_SIGNATURE, device.sent[1].arg0)
        assertEquals(AdbHandshake.AUTH_RSAPUBLICKEY, device.sent[2].arg0)
        assertEquals(3, device.sent.size)
    }

    /** Публичный ключ отправляется один раз, дальше хост просто ждёт человека. */
    @Test
    fun publicKeyIsSentOnceWhileTheDeviceKeepsAsking() = withKeyStore { keyStore ->
        val device = ScriptedDevice(authToken(), authToken(), authToken(), authToken(), cnxn(DEVICE_BANNER))

        device.handshake(keyStore).connect()

        assertEquals(1, device.sent.count { it.arg0 == AdbHandshake.AUTH_RSAPUBLICKEY && it.command == AdbCommand.AUTH })
    }

    @Test
    fun waitingForConfirmationUsesTheLongerTimeout() = withKeyStore { keyStore ->
        val device = ScriptedDevice(authToken(), authToken(), cnxn(DEVICE_BANNER))

        device.handshake(keyStore).connect()

        assertEquals(
            AdbHandshake.AUTH_CONFIRMATION_TIMEOUT_MS,
            device.receiveTimeouts.last(),
        )
    }

    @Test
    fun silenceAfterThePublicKeyMeansTheDialogWasNeverConfirmed() = withKeyStore { keyStore ->
        val device = ScriptedDevice(authToken(), authToken(), silence())

        val outcome = device.handshake(keyStore).connect() as AdbHandshakeOutcome.Failed

        assertEquals(AdbHandshakeFailure.AUTHORIZATION_NOT_CONFIRMED, outcome.reason)
    }

    @Test
    fun deviceThatKeepsAskingForeverIsGivenUpOn() = withKeyStore { keyStore ->
        val tokens = Array(AdbHandshake.AUTH_RESPONSE_LIMIT + 2) { authToken() }
        val device = ScriptedDevice(*tokens)

        val outcome = device.handshake(keyStore).connect() as AdbHandshakeOutcome.Failed

        assertEquals(AdbHandshakeFailure.AUTHORIZATION_NOT_CONFIRMED, outcome.reason)
    }

    @Test
    fun unsupportedAuthTypeIsRejected() = withKeyStore { keyStore ->
        val device = ScriptedDevice(packet(AdbCommand.AUTH, arg0 = AdbHandshake.AUTH_SIGNATURE))

        val outcome = device.handshake(keyStore).connect() as AdbHandshakeOutcome.Failed

        assertEquals(AdbHandshakeFailure.UNSUPPORTED_AUTH_TYPE, outcome.reason)
    }

    @Test
    fun unexpectedCommandEndsTheHandshake() = withKeyStore { keyStore ->
        val device = ScriptedDevice(packet(AdbCommand.WRTE))

        val outcome = device.handshake(keyStore).connect() as AdbHandshakeOutcome.Failed

        assertEquals(AdbHandshakeFailure.UNEXPECTED_COMMAND, outcome.reason)
    }

    @Test
    fun silenceAfterCnxnIsReportedAsNoResponse() = withKeyStore { keyStore ->
        val device = ScriptedDevice(silence())

        val outcome = device.handshake(keyStore).connect() as AdbHandshakeOutcome.Failed

        assertEquals(AdbHandshakeFailure.NO_RESPONSE, outcome.reason)
    }

    @Test
    fun releasedInterfaceIsReportedAsClosedTransport() = withKeyStore { keyStore ->
        val device = ScriptedDevice(released())

        val outcome = device.handshake(keyStore).connect() as AdbHandshakeOutcome.Failed

        assertEquals(AdbHandshakeFailure.TRANSPORT_CLOSED, outcome.reason)
    }

    @Test
    fun lostFramingIsNotMistakenForSilence() = withKeyStore { keyStore ->
        val device = ScriptedDevice(shortPayload())

        val outcome = device.handshake(keyStore).connect() as AdbHandshakeOutcome.Failed

        assertEquals(AdbHandshakeFailure.FRAMING_LOST, outcome.reason)
        assertTrue(outcome.detail.contains(AdbReadFailure.SHORT_PAYLOAD.name))
    }

    /**
     * Одно рукопожатие на один транспорт: второй `CNXN` в том же соединении на
     * ряде Android USB host вызывал цикл detach/attach.
     */
    @Test
    fun secondHandshakeOnTheSameTransportIsRefused() = withKeyStore { keyStore ->
        val device = ScriptedDevice(cnxn(DEVICE_BANNER))
        val handshake = device.handshake(keyStore)
        handshake.connect()

        val outcome = handshake.connect() as AdbHandshakeOutcome.Failed

        assertEquals(AdbHandshakeFailure.ALREADY_ATTEMPTED, outcome.reason)
        assertEquals("второй CNXN не должен уходить на устройство", 1, device.sent.count { it.command == AdbCommand.CNXN })
    }

    @Test
    fun diagnosticsRecordTheAuthorizationPath() = withKeyStore { keyStore ->
        val sink = InMemoryDiagnosticSink()
        val device = ScriptedDevice(authToken(), authToken(), cnxn(DEVICE_BANNER))

        device.handshake(keyStore, diagnostics = sink).connect()

        val messages = sink.snapshot().map { it.message }
        assertEquals(
            listOf("cnxn_sent", "auth_required", "auth_signature_sent", "auth_public_key_sent", "connected"),
            messages,
        )
        assertTrue(sink.snapshot().all { it.category == AdbHandshake.DIAGNOSTIC_CATEGORY })
    }

    @Test
    fun failedHandshakeIsAlsoRecorded() = withKeyStore { keyStore ->
        val sink = InMemoryDiagnosticSink()
        val device = ScriptedDevice(silence())

        device.handshake(keyStore, diagnostics = sink).connect()

        assertTrue(sink.snapshot().any { it.message == "handshake_failed" })
        assertFalse(sink.snapshot().any { it.message == "connected" })
    }

    /** Одно устройство, отвечающее по заранее заданному сценарию. */
    private class ScriptedDevice(vararg responses: Response) {
        private val inbound = mutableListOf<FakeUsbTransportHandle.Transfer>()
        private val handle: FakeUsbTransportHandle

        init {
            responses.forEach { response -> inbound += response.transfers() }
            handle = FakeUsbTransportHandle(inbound = inbound)
        }

        val sent: List<SentPacket>
            get() = handle.sentFrames()

        val receiveTimeouts: List<Int>
            get() = handle.receiveTimeouts

        fun reader() = AdbPacketReader(handle, MAX_PAYLOAD)

        fun writer() = AdbPacketWriter(handle)

        private val sharedReader by lazy { reader() }
        private val sharedWriter by lazy { writer() }

        fun handshake(
            keyStore: AdbKeyStore,
            localMaxPayload: Int = MAX_PAYLOAD,
            diagnostics: InMemoryDiagnosticSink? = null,
        ) = AdbHandshake(
            reader = sharedReader,
            writer = sharedWriter,
            keyStore = keyStore,
            localMaxPayload = localMaxPayload,
            diagnostics = diagnostics ?: InMemoryDiagnosticSink(),
        )
    }

    private fun interface Response {
        fun transfers(): List<FakeUsbTransportHandle.Transfer>
    }

    private companion object {
        const val MAX_PAYLOAD = AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES
        const val DEVICE_BANNER = "device::ro.product.name=vayu;features=shell_v2\u0000"

        fun packet(
            command: Long,
            arg0: Int = 0,
            arg1: Int = 0,
            payload: ByteArray = ByteArray(0),
        ) = Response {
            val frames = mutableListOf<FakeUsbTransportHandle.Transfer>(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(command, arg0, arg1, payload),
                ),
            )
            if (payload.isNotEmpty()) {
                frames += FakeUsbTransportHandle.Transfer.Completed(payload.size, payload)
            }
            frames
        }

        fun cnxn(banner: String, version: Int = AdbChecksum.VERSION_WITH_CHECKSUM) =
            packet(AdbCommand.CNXN, arg0 = version, arg1 = MAX_PAYLOAD, payload = banner.toByteArray())

        fun authToken() = packet(
            AdbCommand.AUTH,
            arg0 = AdbHandshake.AUTH_TOKEN,
            payload = ByteArray(AdbTokenSigner.TOKEN_SIZE_BYTES) { it.toByte() },
        )

        fun silence() = Response {
            listOf(FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED))
        }

        fun released() = Response {
            listOf(FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_HELD))
        }

        fun shortPayload() = Response {
            val payload = ByteArray(64) { 1 }
            listOf(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(AdbCommand.CNXN, payload = payload),
                ),
                FakeUsbTransportHandle.Transfer.Completed(32, payload),
            )
        }

        fun withKeyStore(block: (AdbKeyStore) -> Unit) {
            val directory: File = Files.createTempDirectory("nekoflash-handshake").toFile()
            try {
                block(AdbKeyStore(directory))
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
