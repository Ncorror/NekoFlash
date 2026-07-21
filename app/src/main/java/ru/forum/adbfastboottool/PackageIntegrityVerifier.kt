package ru.forum.adbfastboottool

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object PackageIntegrityVerifier {
    private const val BUFFER_SIZE = 1024 * 1024
    private class VerificationCancelledException : RuntimeException()

    enum class Verdict {
        VALID,
        CORRUPTED,
        TRUNCATED,
        UNREADABLE,
        CHECKSUM_MISMATCH,
        CANCELLED
    }

    enum class Stage { HASHING, ZIP_SCAN }

    data class Progress(
        val stage: Stage,
        val percent: Int,
        val detail: String
    )

    data class Result(
        val verdict: Verdict,
        val message: String,
        val fileSize: Long,
        val lastModifiedMs: Long = -1L,
        val sha256: String? = null,
        val md5: String? = null,
        val entriesScanned: Int = 0,
        val uncompressedBytesScanned: Long = 0L,
        val sidecarPresent: Boolean = false,
        val evidence: String? = null
    ) {
        val isValid: Boolean get() = verdict == Verdict.VALID
    }

    fun verifyZip(
        file: File,
        onProgress: (Progress) -> Unit = {},
        onLog: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false }
    ): Result {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return Result(Verdict.UNREADABLE, "Файл недоступен", file.length())
        }
        val fileSize = file.length()
        val lastModifiedMs = file.lastModified()
        if (fileSize <= 0L) {
            return Result(Verdict.TRUNCATED, "ZIP-файл пуст", fileSize)
        }
        if (shouldCancel()) return cancelled(fileSize)

        onLog("=== ПРОВЕРКА ЦЕЛОСТНОСТИ ПАКЕТА ===")
        onLog("Файл: ${file.name}")
        onLog("Размер: $fileSize байт")
        onLog("Вычисление SHA-256/MD5...")

        val hashes = try {
            HashUtils.calculateHashes(file) { processed, total ->
                if (shouldCancel()) throw VerificationCancelledException()
                val percent = scaledPercent(processed, total, 0, 40)
                onProgress(Progress(Stage.HASHING, percent, "Хэширование пакета"))
            }
        } catch (_: VerificationCancelledException) {
            return cancelled(fileSize)
        } catch (e: Exception) {
            return Result(
                verdict = Verdict.UNREADABLE,
                message = "Не удалось прочитать файл для хэширования",
                fileSize = fileSize,
                evidence = e.message ?: e.javaClass.simpleName
            )
        }
        if (shouldCancel()) return cancelled(fileSize, hashes)

        onLog("SHA-256: ${hashes.sha256}")
        onLog("MD5: ${hashes.md5}")
        val sidecars = HashUtils.verifyCalculatedHashesWithSidecars(file, hashes, onLog)
        if (!sidecars.ok) {
            return Result(
                verdict = Verdict.CHECKSUM_MISMATCH,
                message = sidecars.failureMessage ?: "Контрольная сумма не совпадает",
                fileSize = fileSize,
                sha256 = hashes.sha256,
                md5 = hashes.md5,
                sidecarPresent = sidecars.anyPresent
            )
        }

        val directory = try {
            inspectCentralDirectory(file)
        } catch (e: Exception) {
            return failedFromException(fileSize, hashes, sidecars.anyPresent, e, "Не удалось прочитать ZIP directory")
        }
        if (directory.entries <= 0) {
            return Result(
                verdict = Verdict.CORRUPTED,
                message = "ZIP не содержит файлов",
                fileSize = fileSize,
                sha256 = hashes.sha256,
                md5 = hashes.md5,
                sidecarPresent = sidecars.anyPresent
            )
        }

        onLog("Полный ZIP scan: ${directory.entries} файлов")
        val buffer = ByteArray(BUFFER_SIZE)
        var scannedEntries = 0
        var scannedBytes = 0L

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(file), BUFFER_SIZE)).use { zip ->
                while (true) {
                    if (shouldCancel()) return cancelled(fileSize, hashes, scannedEntries, scannedBytes, sidecars.anyPresent)
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val crc = CRC32()
                        var entryBytes = 0L
                        while (true) {
                            if (shouldCancel()) return cancelled(fileSize, hashes, scannedEntries, scannedBytes, sidecars.anyPresent)
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            crc.update(buffer, 0, read)
                            entryBytes += read.toLong()
                            scannedBytes += read.toLong()
                            val percent = zipProgress(
                                scannedEntries = scannedEntries,
                                totalEntries = directory.entries,
                                scannedBytes = scannedBytes,
                                totalBytes = directory.totalUncompressedBytes
                            )
                            onProgress(Progress(Stage.ZIP_SCAN, percent, "Проверка ZIP: ${entry.name}"))
                        }

                        val expectedSize = entry.size
                        if (expectedSize >= 0L && entryBytes != expectedSize) {
                            return Result(
                                verdict = Verdict.TRUNCATED,
                                message = "ZIP entry имеет неполный размер: ${entry.name}",
                                fileSize = fileSize,
                                sha256 = hashes.sha256,
                                md5 = hashes.md5,
                                entriesScanned = scannedEntries,
                                uncompressedBytesScanned = scannedBytes,
                                sidecarPresent = sidecars.anyPresent,
                                evidence = "expected=$expectedSize actual=$entryBytes"
                            )
                        }
                        val expectedCrc = entry.crc
                        if (expectedCrc >= 0L && crc.value != expectedCrc) {
                            return Result(
                                verdict = Verdict.CORRUPTED,
                                message = "CRC mismatch: ${entry.name}",
                                fileSize = fileSize,
                                sha256 = hashes.sha256,
                                md5 = hashes.md5,
                                entriesScanned = scannedEntries,
                                uncompressedBytesScanned = scannedBytes,
                                sidecarPresent = sidecars.anyPresent,
                                evidence = "expected=${expectedCrc.toString(16)} actual=${crc.value.toString(16)}"
                            )
                        }
                        scannedEntries++
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return failedFromException(
                fileSize = fileSize,
                hashes = hashes,
                sidecarPresent = sidecars.anyPresent,
                error = e,
                prefix = "Полный ZIP scan завершился ошибкой",
                entriesScanned = scannedEntries,
                scannedBytes = scannedBytes
            )
        }

        if (scannedEntries != directory.entries) {
            return Result(
                verdict = Verdict.TRUNCATED,
                message = "Количество прочитанных ZIP entries не совпадает с directory",
                fileSize = fileSize,
                sha256 = hashes.sha256,
                md5 = hashes.md5,
                entriesScanned = scannedEntries,
                uncompressedBytesScanned = scannedBytes,
                sidecarPresent = sidecars.anyPresent,
                evidence = "directory=${directory.entries} scanned=$scannedEntries"
            )
        }

        onProgress(Progress(Stage.ZIP_SCAN, 100, "Целостность ZIP подтверждена"))
        onLog("✅ Полный ZIP scan завершён: $scannedEntries файлов, $scannedBytes байт распакованных данных")
        onLog("✅ PACKAGE FINGERPRINT SHA-256: ${hashes.sha256}")
        return Result(
            verdict = Verdict.VALID,
            message = "Целостность ZIP подтверждена",
            fileSize = fileSize,
            lastModifiedMs = lastModifiedMs,
            sha256 = hashes.sha256,
            md5 = hashes.md5,
            entriesScanned = scannedEntries,
            uncompressedBytesScanned = scannedBytes,
            sidecarPresent = sidecars.anyPresent
        )
    }

    private data class DirectoryInfo(
        val entries: Int,
        val totalUncompressedBytes: Long
    )

    private fun inspectCentralDirectory(file: File): DirectoryInfo {
        ZipFile(file).use { zip ->
            var entries = 0
            var totalBytes = 0L
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                if (!entry.isDirectory) {
                    entries++
                    if (entry.size > 0L && Long.MAX_VALUE - totalBytes >= entry.size) {
                        totalBytes += entry.size
                    }
                }
            }
            return DirectoryInfo(entries, totalBytes)
        }
    }

    private fun failedFromException(
        fileSize: Long,
        hashes: HashUtils.FileHashes,
        sidecarPresent: Boolean,
        error: Exception,
        prefix: String,
        entriesScanned: Int = 0,
        scannedBytes: Long = 0L
    ): Result {
        val evidence = error.message ?: error.javaClass.simpleName
        val verdict = when {
            error is EOFException -> Verdict.TRUNCATED
            evidence.contains("unexpected end", ignoreCase = true) -> Verdict.TRUNCATED
            evidence.contains("unexpected eof", ignoreCase = true) -> Verdict.TRUNCATED
            evidence.contains("end header not found", ignoreCase = true) -> Verdict.TRUNCATED
            error is ZipException -> Verdict.CORRUPTED
            error is IOException -> Verdict.UNREADABLE
            else -> Verdict.CORRUPTED
        }
        return Result(
            verdict = verdict,
            message = prefix,
            fileSize = fileSize,
            sha256 = hashes.sha256,
            md5 = hashes.md5,
            entriesScanned = entriesScanned,
            uncompressedBytesScanned = scannedBytes,
            sidecarPresent = sidecarPresent,
            evidence = evidence
        )
    }

    private fun cancelled(
        fileSize: Long,
        hashes: HashUtils.FileHashes? = null,
        entriesScanned: Int = 0,
        scannedBytes: Long = 0L,
        sidecarPresent: Boolean = false
    ): Result = Result(
        verdict = Verdict.CANCELLED,
        message = "Проверка целостности отменена",
        fileSize = fileSize,
        sha256 = hashes?.sha256,
        md5 = hashes?.md5,
        entriesScanned = entriesScanned,
        uncompressedBytesScanned = scannedBytes,
        sidecarPresent = sidecarPresent
    )

    private fun scaledPercent(processed: Long, total: Long, start: Int, end: Int): Int {
        if (total <= 0L) return start
        val ratio = (processed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        return (start + ((end - start) * ratio)).toInt().coerceIn(start, end)
    }

    private fun zipProgress(
        scannedEntries: Int,
        totalEntries: Int,
        scannedBytes: Long,
        totalBytes: Long
    ): Int {
        val ratio = if (totalBytes > 0L) {
            (scannedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
        } else if (totalEntries > 0) {
            (scannedEntries.toDouble() / totalEntries.toDouble()).coerceIn(0.0, 1.0)
        } else 0.0
        return (40 + (59 * ratio)).toInt().coerceIn(40, 99)
    }
}
