package io.github.ncorror.nekoflash.protocol.adb

import java.nio.file.Files
import java.util.Base64
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbHostKeyTest {

    @Test
    fun signatureVerifiesAsPrehashedSha1DigestInfo() {
        val directory = Files.createTempDirectory("nekoflash-adb-signature-test").toFile()
        try {
            val hostKey = AdbHostKey(directory)
            val token = ByteArray(20) { index -> (index * 3).toByte() }
            val signature = hostKey.signToken(token)
            val digestInfo = byteArrayOf(
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b,
                0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
            ) + token

            val verified = Signature.getInstance("NONEwithRSA").run {
                initVerify(hostKey.getOrCreateKeyPair().public)
                update(digestInfo)
                verify(signature)
            }

            assertTrue(verified)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keyPersistsAndProducesMincryptPublicPayload() {
        val directory = Files.createTempDirectory("nekoflash-adb-key-test").toFile()
        try {
            val first = AdbHostKey(directory)
            val token = ByteArray(20) { index -> index.toByte() }
            val signature = first.signToken(token)
            val publicPayload = first.publicKeyPayload()

            assertEquals(256, signature.size)
            assertEquals(0, publicPayload.last().toInt())
            val line = publicPayload.copyOf(publicPayload.size - 1).toString(Charsets.US_ASCII)
            val encoded = line.substringBefore(' ')
            assertEquals(524, Base64.getDecoder().decode(encoded).size)
            assertTrue(line.endsWith(" NekoFlash@Android"))

            val second = AdbHostKey(directory)
            assertArrayEquals(publicPayload, second.publicKeyPayload())
        } finally {
            directory.deleteRecursively()
        }
    }
}
