package ru.forum.adbfastboottool

/**
 * Pure ADB packet checksum policy.
 *
 * NekoFlash currently advertises protocol 0x01000000, therefore the classic
 * unsigned-byte payload checksum remains mandatory for the negotiated session.
 * The helper still models 0x01000001 correctly so a future protocol bump does
 * not accidentally reject peers that are allowed to send data_check=0.
 */
object AdbPacketChecksum {
    const val VERSION_WITH_CHECKSUM: Int = 0x01000000
    const val VERSION_SKIP_CHECKSUM: Int = 0x01000001

    fun compute(payload: ByteArray): Int =
        payload.fold(0) { sum, byte -> sum + (byte.toInt() and 0xFF) }

    fun checksumRequired(localVersion: Int, peerVersion: Int): Boolean =
        minOf(localVersion, peerVersion) < VERSION_SKIP_CHECKSUM

    fun isValid(
        expected: Int,
        payload: ByteArray,
        localVersion: Int,
        peerVersion: Int
    ): Boolean = !checksumRequired(localVersion, peerVersion) || expected == compute(payload)
}
