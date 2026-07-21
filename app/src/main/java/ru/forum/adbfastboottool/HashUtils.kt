package ru.forum.adbfastboottool

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Проверка контрольных сумм и целостности копирования.
 * SHA-256 и MD5 считаются за один проход файла.
 */
object HashUtils {
    private const val BUFFER_SIZE = 1024 * 1024

    data class FileHashes(val sha256: String, val md5: String)

    data class SidecarVerification(
        val ok: Boolean,
        val anyPresent: Boolean,
        val failureMessage: String? = null
    )

    data class VerifiedCopyResult(
        val ok: Boolean,
        val bytesCopied: Long,
        val sourceSha256: String?,
        val destinationSha256: String?,
        val message: String
    )

    /** Считает SHA-256 и MD5 за один проход. */
    fun calculateHashes(
        file: File,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): FileHashes {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        val total = file.length().coerceAtLeast(0L)
        var processed = 0L
        file.inputStream().buffered(BUFFER_SIZE).use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                sha256.update(buffer, 0, read)
                md5.update(buffer, 0, read)
                processed += read.toLong()
                onProgress?.invoke(processed, total)
            }
        }
        return FileHashes(
            sha256 = sha256.digest().toHex(),
            md5 = md5.digest().toHex()
        )
    }

    fun calculateSha256(
        file: File,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        val total = file.length().coerceAtLeast(0L)
        var processed = 0L
        file.inputStream().buffered(BUFFER_SIZE).use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                sha256.update(buffer, 0, read)
                processed += read.toLong()
                onProgress?.invoke(processed, total)
            }
        }
        return sha256.digest().toHex()
    }

    /**
     * Копирует входной поток, считает SHA-256 источника во время копирования,
     * затем повторно считает SHA-256 готового файла и сравнивает оба значения.
     */
    fun copyToFileVerified(
        input: InputStream,
        target: File,
        expectedSize: Long? = null,
        onProgress: ((copiedBytes: Long, expectedBytes: Long?) -> Unit)? = null
    ): VerifiedCopyResult {
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L

        try {
            target.outputStream().buffered(BUFFER_SIZE).use { output ->
                input.buffered(BUFFER_SIZE).use { source ->
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        sourceDigest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied += read.toLong()
                        onProgress?.invoke(copied, expectedSize)
                    }
                }
                output.flush()
            }
        } catch (e: Exception) {
            return VerifiedCopyResult(
                ok = false,
                bytesCopied = copied,
                sourceSha256 = null,
                destinationSha256 = null,
                message = "Ошибка копирования: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        if (expectedSize != null && expectedSize >= 0L && copied != expectedSize) {
            return VerifiedCopyResult(
                ok = false,
                bytesCopied = copied,
                sourceSha256 = sourceDigest.digest().toHex(),
                destinationSha256 = null,
                message = "Размер импортированного файла не совпадает: ожидалось $expectedSize, скопировано $copied"
            )
        }
        if (!target.exists() || target.length() != copied) {
            return VerifiedCopyResult(
                ok = false,
                bytesCopied = copied,
                sourceSha256 = sourceDigest.digest().toHex(),
                destinationSha256 = null,
                message = "Готовая копия имеет неверный размер: файл=${target.length()}, скопировано=$copied"
            )
        }

        val sourceSha256 = sourceDigest.digest().toHex()
        val destinationSha256 = try {
            calculateSha256(target)
        } catch (e: Exception) {
            return VerifiedCopyResult(
                ok = false,
                bytesCopied = copied,
                sourceSha256 = sourceSha256,
                destinationSha256 = null,
                message = "Не удалось перечитать готовую копию: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        if (!sourceSha256.equals(destinationSha256, ignoreCase = true)) {
            return VerifiedCopyResult(
                ok = false,
                bytesCopied = copied,
                sourceSha256 = sourceSha256,
                destinationSha256 = destinationSha256,
                message = "SHA-256 источника и готовой копии не совпадает"
            )
        }

        return VerifiedCopyResult(
            ok = true,
            bytesCopied = copied,
            sourceSha256 = sourceSha256,
            destinationSha256 = destinationSha256,
            message = "Копирование подтверждено SHA-256"
        )
    }

    /**
     * Проверяет sidecar-файлы, используя уже вычисленные хэши.
     * Это позволяет PackageIntegrityVerifier не читать большой ZIP третий раз.
     */
    fun verifyCalculatedHashesWithSidecars(
        file: File,
        hashes: FileHashes,
        onLog: (String) -> Unit
    ): SidecarVerification {
        val sha256Sidecar = findSidecar(file, "sha256")
        val md5Sidecar = findSidecar(file, "md5")
        val anyPresent = sha256Sidecar != null || md5Sidecar != null

        if (!anyPresent) {
            onLog("ℹ️ Sidecar .sha256/.md5 не найден. SHA-256 всё равно вычислен и сохранён в логе.")
            return SidecarVerification(ok = true, anyPresent = false)
        }

        if (sha256Sidecar != null) {
            val error = verifySidecarValue(sha256Sidecar, "sha256", 64, hashes.sha256, onLog)
            if (error != null) return SidecarVerification(false, true, error)
        }
        if (md5Sidecar != null) {
            val error = verifySidecarValue(md5Sidecar, "md5", 32, hashes.md5, onLog)
            if (error != null) return SidecarVerification(false, true, error)
        }
        return SidecarVerification(ok = true, anyPresent = true)
    }

    /**
     * Совместимый старый entrypoint для остальных операций прошивки.
     */
    fun verifyFileWithSidecars(file: File, onLog: (String) -> Unit): Boolean {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            onLog("❌ ОШИБКА: файл недоступен: ${file.name}")
            return false
        }
        if (file.length() <= 0L) {
            onLog("❌ ОШИБКА: файл пустой: ${file.name}")
            return false
        }

        val sha256Sidecar = findSidecar(file, "sha256")
        val md5Sidecar = findSidecar(file, "md5")
        if (sha256Sidecar == null && md5Sidecar == null) {
            onLog("ℹ️ Файлы .sha256 и .md5 не найдены. Проверка контрольных сумм пропущена.")
            return true
        }

        onLog("=== ПРОВЕРКА КОНТРОЛЬНЫХ СУММ ===")
        onLog("Файл: ${file.name}")
        onLog("Вычисление SHA-256 и MD5 (один проход)...")
        val hashes = calculateHashes(file)
        onLog("SHA-256: ${hashes.sha256}")
        onLog("MD5: ${hashes.md5}")
        return verifyCalculatedHashesWithSidecars(file, hashes, onLog).ok
    }

    private fun verifySidecarValue(
        sidecar: File,
        extension: String,
        hashLength: Int,
        actual: String,
        onLog: (String) -> Unit
    ): String? {
        val expected = extractHash(sidecar.readText(), hashLength)
        if (expected == null) {
            val message = "не удалось прочитать хэш из ${sidecar.name}"
            onLog("❌ ОШИБКА: $message")
            return message
        }
        return if (actual.equals(expected, ignoreCase = true)) {
            onLog("✅ ${extension.uppercase()} совпадает: ${sidecar.name}")
            null
        } else {
            val message = "${extension.uppercase()} не совпадает"
            onLog("❌ ОШИБКА: $message!")
            onLog("Ожидалось: $expected")
            onLog("Получено:  $actual")
            message
        }
    }

    private fun findSidecar(file: File, extension: String): File? {
        val parent = file.parentFile ?: return null
        val candidates = listOf(
            File(file.absolutePath + ".$extension"),
            File(parent, file.nameWithoutExtension + ".$extension")
        )
        return candidates.distinctBy { it.absolutePath }
            .firstOrNull { it.exists() && it.isFile && it.canRead() }
    }

    private fun extractHash(text: String, length: Int): String? =
        Regex("[A-Fa-f0-9]{$length}").find(text)?.value

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
