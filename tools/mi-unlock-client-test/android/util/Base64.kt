package android.util

object Base64 {
    const val DEFAULT: Int = 0
    const val NO_WRAP: Int = 2

    fun decode(input: String, @Suppress("UNUSED_PARAMETER") flags: Int): ByteArray =
        java.util.Base64.getMimeDecoder().decode(input)

    fun encodeToString(input: ByteArray, @Suppress("UNUSED_PARAMETER") flags: Int): String =
        java.util.Base64.getEncoder().encodeToString(input)
}
