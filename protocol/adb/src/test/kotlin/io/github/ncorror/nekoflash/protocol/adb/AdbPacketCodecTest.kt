package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPacketCodecTest {
    @Test
    fun headerRoundTripPreservesCommandArgumentsLengthChecksumAndMagic() {
        val frame = AdbFrame(
            command = AdbPacketCodec.A_CNXN,
            arg0 = AdbPacketCodec.VERSION_WITH_CHECKSUM,
            arg1 = 1024 * 1024,
            payload = "host::NekoFlash\u0000".toByteArray(),
        )

        val header = AdbPacketCodec.decodeHeader(AdbPacketCodec.encodeHeader(frame), 1024 * 1024)

        assertEquals(frame.command, header.command)
        assertEquals(frame.arg0, header.arg0)
        assertEquals(frame.arg1, header.arg1)
        assertEquals(frame.payload.size, header.dataLength)
        assertEquals(AdbPacketCodec.checksum(frame.payload), header.checksum)
        assertEquals(frame.command.inv(), header.magic)
    }

    @Test
    fun checksumValidationRejectsChangedPayloadForChecksumProtocol() {
        val payload = byteArrayOf(1, 2, 3)
        val checksum = AdbPacketCodec.checksum(payload)

        assertTrue(
            AdbPacketCodec.checksumMatches(
                checksum,
                payload,
                AdbPacketCodec.VERSION_WITH_CHECKSUM,
                AdbPacketCodec.VERSION_WITH_CHECKSUM,
            ),
        )
        assertFalse(
            AdbPacketCodec.checksumMatches(
                checksum,
                byteArrayOf(1, 2, 4),
                AdbPacketCodec.VERSION_WITH_CHECKSUM,
                AdbPacketCodec.VERSION_WITH_CHECKSUM,
            ),
        )
    }
}
