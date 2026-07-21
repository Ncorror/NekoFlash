package ru.forum.adbfastboottool

import java.io.File

class AdbKeyStore(
    @Suppress("UNUSED_PARAMETER") directory: File,
    @Suppress("UNUSED_PARAMETER") onLog: (String) -> Unit
) {
    fun publicKeyPath(): String = "/tmp/adbkey.pub"
    fun signToken(token: ByteArray): ByteArray = token.copyOf()
    fun publicKeyPayload(): ByteArray = "FAKE_PUBLIC_KEY\u0000".toByteArray()
}
