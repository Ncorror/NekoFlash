package io.github.ncorror.nekoflash.protocol.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/** Persistent app-private ADB RSA key in the Android/mincrypt public-key wire format. */
class AdbHostKey(private val directory: File) : AdbAuthProvider {
    private val privateKeyFile = File(directory, "adbkey.pk8")

    @Volatile
    private var cached: KeyPair? = null

    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        cached?.let { return it }
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create ADB key directory")
        }
        val keyPair = if (privateKeyFile.exists()) loadKeyPair() else generateKeyPair().also { savePrivateKey(it.private) }
        cached = keyPair
        return keyPair
    }

    override fun signToken(token: ByteArray): ByteArray {
        require(token.size == AUTH_TOKEN_BYTES) { "ADB AUTH token must be $AUTH_TOKEN_BYTES bytes" }
        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        val privateKey = getOrCreateKeyPair().private
        return runCatching {
            Signature.getInstance("NONEwithRSA").run {
                initSign(privateKey)
                update(digestInfo)
                sign()
            }
        }.getOrElse {
            Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.ENCRYPT_MODE, privateKey)
                doFinal(digestInfo)
            }
        }
    }

    override fun publicKeyPayload(): ByteArray {
        val publicKey = getOrCreateKeyPair().public as RSAPublicKey
        val encoded = Base64.getEncoder().encodeToString(androidPublicKeyBytes(publicKey))
        return "$encoded NekoFlash@Android\u0000".toByteArray(Charsets.US_ASCII)
    }

    private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(RSAKeyGenParameterSpec(RSA_BITS, RSAKeyGenParameterSpec.F4))
        generateKeyPair()
    }

    private fun loadKeyPair(): KeyPair {
        val encoded = Base64.getDecoder().decode(privateKeyFile.readText().trim())
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(encoded)) as RSAPrivateCrtKey
        val publicKey = factory.generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent))
        return KeyPair(publicKey, privateKey)
    }

    private fun savePrivateKey(privateKey: PrivateKey) {
        privateKeyFile.writeText(Base64.getEncoder().encodeToString(privateKey.encoded))
        privateKeyFile.setReadable(false, false)
        privateKeyFile.setWritable(false, false)
        privateKeyFile.setReadable(true, true)
        privateKeyFile.setWritable(true, true)
    }

    private fun androidPublicKeyBytes(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        require(modulus.bitLength() <= RSA_BITS) { "ADB host key must be RSA-2048" }
        val two32 = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(RSA_WORDS * 32)
        val rr = r.modPow(BigInteger.valueOf(2L), modulus)
        val n0 = modulus.mod(two32)
        val n0inv = two32.subtract(n0.modInverse(two32)).mod(two32)

        return ByteBuffer.allocate(ANDROID_PUBKEY_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putUInt32(RSA_WORDS.toLong())
                putUInt32(n0inv.toLong())
                putLittleEndianWords(modulus)
                putLittleEndianWords(rr)
                putUInt32(publicKey.publicExponent.toLong())
            }
            .array()
    }

    private fun ByteBuffer.putLittleEndianWords(value: BigInteger) {
        val mask = BigInteger.valueOf(0xFFFF_FFFFL)
        repeat(RSA_WORDS) { index -> putUInt32(value.shiftRight(index * 32).and(mask).toLong()) }
    }

    private fun ByteBuffer.putUInt32(value: Long) {
        putInt((value and 0xFFFF_FFFFL).toInt())
    }

    private companion object {
        const val AUTH_TOKEN_BYTES = 20
        const val RSA_BITS = 2048
        const val RSA_WORDS = RSA_BITS / 32
        const val ANDROID_PUBKEY_BYTES = 4 + 4 + RSA_WORDS * 4 + RSA_WORDS * 4 + 4

        val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b,
            0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
