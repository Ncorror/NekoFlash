package io.github.ncorror.nekoflash.protocol.adb

/** Minimal ADB aprotocol framing used by the Phase 2 CNXN/AUTH evidence probe. */
object AdbPacketCodec {
    const val HEADER_SIZE = 24
    const val VERSION_WITH_CHECKSUM = 0x01000000
    const val VERSION_SKIP_CHECKSUM = 0x01000001

    const val A_CNXN = 0x4E584E43
    const val A_AUTH = 0x48545541

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    data class Header(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val checksum: Int,
        val magic: Int,
    )

    fun encodeHeader(frame: AdbFrame): ByteArray = ByteArray(HEADER_SIZE).also { header ->
        putIntLe(header, 0, frame.command)
        putIntLe(header, 4, frame.arg0)
        putIntLe(header, 8, frame.arg1)
        putIntLe(header, 12, frame.payload.size)
        putIntLe(header, 16, checksum(frame.payload))
        putIntLe(header, 20, frame.command.inv())
    }

    fun decodeHeader(bytes: ByteArray, maxPayloadBytes: Int): Header {
        require(bytes.size == HEADER_SIZE) { "ADB header must be $HEADER_SIZE bytes, got ${bytes.size}" }
        val command = readIntLe(bytes, 0)
        val dataLength = readIntLe(bytes, 12)
        val magic = readIntLe(bytes, 20)
        require(magic == command.inv()) { "ADB header magic mismatch" }
        require(dataLength in 0..maxPayloadBytes) {
            "ADB payload length out of range: $dataLength (max=$maxPayloadBytes)"
        }
        return Header(
            command = command,
            arg0 = readIntLe(bytes, 4),
            arg1 = readIntLe(bytes, 8),
            dataLength = dataLength,
            checksum = readIntLe(bytes, 16),
            magic = magic,
        )
    }

    fun checksum(payload: ByteArray): Int {
        var sum = 0
        payload.forEach { byte -> sum += byte.toInt() and 0xFF }
        return sum
    }

    fun checksumMatches(expected: Int, payload: ByteArray, localVersion: Int, peerVersion: Int): Boolean =
        !checksumRequired(localVersion, peerVersion) || expected == checksum(payload)

    fun commandName(command: Int): String = when (command) {
        A_CNXN -> "CNXN"
        A_AUTH -> "AUTH"
        else -> "0x%08X".format(command)
    }

    fun authTypeName(arg0: Int): String = when (arg0) {
        AUTH_TOKEN -> "TOKEN"
        AUTH_SIGNATURE -> "SIGNATURE"
        AUTH_RSAPUBLICKEY -> "RSAPUBLICKEY"
        else -> "UNKNOWN_$arg0"
    }

    private fun checksumRequired(localVersion: Int, peerVersion: Int): Boolean =
        minOf(localVersion, peerVersion) < VERSION_SKIP_CHECKSUM

    private fun putIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readIntLe(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)
}

data class AdbFrame(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray,
)
