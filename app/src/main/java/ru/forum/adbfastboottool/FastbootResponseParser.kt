package ru.forum.adbfastboottool

/**
 * Pure parser for one Fastboot USB response packet.
 *
 * The wire protocol uses a four-byte response prefix (INFO/TEXT/OKAY/FAIL/DATA)
 * followed by an optional ASCII payload. Keeping this parser side-effect free
 * lets regression tests pin the existing response classification without
 * touching USB transport behavior.
 */
object FastbootResponseParser {
    data class Packet(
        val type: String,
        val payload: String,
        val raw: String
    )

    fun parse(bytes: ByteArray, length: Int = bytes.size): Packet {
        require(length in 0..bytes.size)
        val raw = String(bytes, 0, length, Charsets.US_ASCII)
            .replace("\u0000", "")
            .trim()
        return parse(raw)
    }

    fun parse(rawValue: String): Packet {
        val raw = rawValue.replace("\u0000", "").trim()
        return if (raw.length < 4) {
            Packet(type = "UNKNOWN", payload = raw, raw = raw)
        } else {
            Packet(type = raw.take(4), payload = raw.drop(4).trim(), raw = raw)
        }
    }
}
