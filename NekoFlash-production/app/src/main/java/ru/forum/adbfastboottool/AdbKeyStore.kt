package ru.forum.adbfastboottool

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

/**
 * Persistent ADB host key storage.
 *
 * ADB does not use a regular X.509 public key line. The public key sent to adbd
 * is base64(android_pubkey) + comment + NUL, where android_pubkey is the
 * historical mincrypt RSAPublicKey structure used by platform-tools.
 */
class AdbKeyStore(
    private val directory: File,
    private val onLog: (String) -> Unit
) {
    private val privateKeyFile = File(directory, "adbkey.pk8")
    private val publicKeyFile = File(directory, "adbkey.pub")

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        cachedKeyPair?.let { return it }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Не удалось создать папку ADB-ключей: ${directory.absolutePath}")
        }

        val keyPair = if (privateKeyFile.exists()) {
            loadPrivateKeyPair()
        } else {
            generateKeyPair().also {
                savePrivateKey(it.private)
                onLog("🔐 Создан новый ADB RSA-ключ приложения")
            }
        }

        writePublicKeyFileIfNeeded(keyPair)
        cachedKeyPair = keyPair
        return keyPair
    }

    fun signToken(token: ByteArray): ByteArray {
        val privateKey = getOrCreateKeyPair().private
        if (token.size != ADB_AUTH_TOKEN_SIZE) {
            throw IllegalArgumentException("ADB TOKEN должен быть 20 байт, получено: ${token.size}")
        }

        // AOSP adb_auth_sign() calls RSA_sign(NID_sha1, token, 20, ...), i.e. it
        // treats the 20-byte token as an already prepared SHA-1 digest. Therefore
        // SHA1withRSA would be wrong here because it would hash the token again.
        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        return try {
            Signature.getInstance("NONEwithRSA").run {
                initSign(privateKey)
                update(digestInfo)
                sign()
            }
        } catch (_: Exception) {
            Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.ENCRYPT_MODE, privateKey)
                doFinal(digestInfo)
            }
        }
    }

    fun publicKeyPayload(): ByteArray {
        val publicKeyLine = adbPublicKeyLine(getOrCreateKeyPair())
        return "$publicKeyLine\u0000".toByteArray(Charsets.US_ASCII)
    }

    fun publicKeyPath(): String = publicKeyFile.absolutePath

    private fun loadPrivateKeyPair(): KeyPair {
        val encoded = Base64.getDecoder().decode(privateKeyFile.readText().trim())
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(encoded)) as RSAPrivateCrtKey
        val publicSpec = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
        val publicKey = keyFactory.generatePublic(publicSpec) as RSAPublicKey
        return KeyPair(publicKey, privateKey)
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        return generator.generateKeyPair()
    }

    private fun savePrivateKey(privateKey: PrivateKey) {
        val encoded = Base64.getEncoder().encodeToString(privateKey.encoded)
        privateKeyFile.writeText(encoded)
        privateKeyFile.setReadable(false, false)
        privateKeyFile.setWritable(false, false)
        privateKeyFile.setReadable(true, true)
        privateKeyFile.setWritable(true, true)
    }

    private fun writePublicKeyFileIfNeeded(keyPair: KeyPair) {
        val publicKeyLine = adbPublicKeyLine(keyPair)
        if (!publicKeyFile.exists() || publicKeyFile.readText().trim() != publicKeyLine) {
            publicKeyFile.writeText(publicKeyLine)
        }
    }

    private fun adbPublicKeyLine(keyPair: KeyPair): String {
        val publicKey = keyPair.public as RSAPublicKey
        val encoded = Base64.getEncoder().encodeToString(androidPubkeyBytes(publicKey))
        return "$encoded NekoFlash@Android"
    }

    private fun androidPubkeyBytes(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent
        require(modulus.bitLength() <= ADB_RSA_BITS) { "Поддерживается только RSA-2048 для ADB" }

        val two32 = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(ADB_RSA_WORDS * 32)
        val rr = r.modPow(BigInteger.valueOf(2L), modulus)
        val n0 = modulus.mod(two32)
        val n0inv = two32.subtract(n0.modInverse(two32)).mod(two32)

        return ByteBuffer.allocate(ANDROID_PUBKEY_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putUInt32(ADB_RSA_WORDS.toLong())
                putUInt32(n0inv.toLong())
                putLittleEndianWords(modulus)
                putLittleEndianWords(rr)
                putUInt32(exponent.toLong())
            }
            .array()
    }

    private fun ByteBuffer.putLittleEndianWords(value: BigInteger) {
        val mask = BigInteger.valueOf(0xFFFF_FFFFL)
        for (i in 0 until ADB_RSA_WORDS) {
            val word = value.shiftRight(i * 32).and(mask).toLong()
            putUInt32(word)
        }
    }

    private fun ByteBuffer.putUInt32(value: Long) {
        putInt((value and 0xFFFF_FFFFL).toInt())
    }

    companion object {
        private const val ADB_AUTH_TOKEN_SIZE = 20
        private const val ADB_RSA_BITS = 2048
        private const val ADB_RSA_WORDS = ADB_RSA_BITS / 32
        private const val ANDROID_PUBKEY_SIZE = 4 + 4 + ADB_RSA_WORDS * 4 + ADB_RSA_WORDS * 4 + 4

        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b,
            0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
    }
}
