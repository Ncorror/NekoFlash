package io.github.ncorror.nekoflash.protocol.adb

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionTest {
    @Test
    fun hostBelowAndroidPAdvertisesTheSmallerPayload() = withKeyStore { keyStore ->
        val connection = AdbConnection(FakeUsbTransportHandle(), keyStore, apiLevel = 26)

        assertEquals(AdbInboundFraming.PRE_P_MAX_PAYLOAD_BYTES, connection.advertisedMaxPayload)
    }

    @Test
    fun modernHostAdvertisesTheFullPayload() = withKeyStore { keyStore ->
        val connection = AdbConnection(FakeUsbTransportHandle(), keyStore, apiLevel = 36)

        assertEquals(AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES, connection.advertisedMaxPayload)
    }

    /**
     * Объявленное в `CNXN` и проверяемое читателем — одно число. Разъезд между
     * ними и был исходной причиной inbound framing invariant.
     */
    @Test
    fun cnxnAdvertisesExactlyWhatTheReaderWillAccept() = withKeyStore { keyStore ->
        val handle = FakeUsbTransportHandle(
            inbound = mutableListOf(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(
                        AdbCommand.CNXN,
                        arg0 = AdbChecksum.VERSION_WITH_CHECKSUM,
                        arg1 = AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
                        payload = BANNER,
                    ),
                ),
                FakeUsbTransportHandle.Transfer.Completed(BANNER.size, BANNER),
            ),
        )
        val connection = AdbConnection(handle, keyStore, apiLevel = 26)

        connection.connect()

        assertEquals(connection.advertisedMaxPayload, handle.sentFrames()[0].arg1)
    }

    @Test
    fun connectionCarriesTheHandshakeOutcomeThrough() = withKeyStore { keyStore ->
        val handle = FakeUsbTransportHandle(
            inbound = mutableListOf(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(
                        AdbCommand.CNXN,
                        arg0 = AdbChecksum.VERSION_WITH_CHECKSUM,
                        arg1 = AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
                        payload = BANNER,
                    ),
                ),
                FakeUsbTransportHandle.Transfer.Completed(BANNER.size, BANNER),
            ),
        )

        val outcome = AdbConnection(handle, keyStore, apiLevel = 36).connect()

        assertTrue(outcome is AdbHandshakeOutcome.Connected)
        assertEquals(
            AdbPeerMode.RECOVERY,
            (outcome as AdbHandshakeOutcome.Connected).banner.peerMode,
        )
    }

    private companion object {
        val BANNER = "recovery::ro.product.name=vayu\u0000".toByteArray()

        fun withKeyStore(block: (AdbKeyStore) -> Unit) {
            val directory: File = Files.createTempDirectory("nekoflash-connection").toFile()
            try {
                block(AdbKeyStore(directory))
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
