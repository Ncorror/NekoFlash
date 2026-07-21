package ru.forum.adbfastboottool

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun checkTrue(value: Boolean, message: String) {
    if (!value) error(message)
}

private fun createValidZip(file: File, stored: Boolean = false) {
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        val entries = listOf(
            "META-INF/com/android/metadata" to "pre-device=vayu\npost-build=test\n".toByteArray(),
            "payload.bin" to ByteArray(256 * 1024) { (it % 251).toByte() }
        )
        for ((name, data) in entries) {
            val entry = ZipEntry(name)
            if (stored) {
                val crc = CRC32().apply { update(data) }
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                entry.crc = crc.value
            }
            zip.putNextEntry(entry)
            zip.write(data)
            zip.closeEntry()
        }
    }
}

private fun corruptFirstEntryData(file: File) {
    val bytes = file.readBytes()
    checkTrue(bytes.size > 40, "zip too small")
    val signature = 0x04034b50
    val actual = (bytes[0].toInt() and 0xff) or
        ((bytes[1].toInt() and 0xff) shl 8) or
        ((bytes[2].toInt() and 0xff) shl 16) or
        ((bytes[3].toInt() and 0xff) shl 24)
    checkTrue(actual == signature, "local header signature missing")
    val nameLen = (bytes[26].toInt() and 0xff) or ((bytes[27].toInt() and 0xff) shl 8)
    val extraLen = (bytes[28].toInt() and 0xff) or ((bytes[29].toInt() and 0xff) shl 8)
    val dataOffset = 30 + nameLen + extraLen
    checkTrue(dataOffset < bytes.size, "entry data offset invalid")
    bytes[dataOffset] = (bytes[dataOffset].toInt() xor 0x5a).toByte()
    file.writeBytes(bytes)
}

fun main() {
    val root = Files.createTempDirectory("nekoflash-package-test").toFile()
    try {
        val valid = File(root, "valid.zip")
        createValidZip(valid)
        val progress = mutableListOf<Int>()
        val validResult = PackageIntegrityVerifier.verifyZip(
            valid,
            onProgress = { progress += it.percent }
        )
        checkTrue(validResult.verdict == PackageIntegrityVerifier.Verdict.VALID, "valid zip must pass: $validResult")
        checkTrue(validResult.sha256?.length == 64, "SHA-256 must always be produced")
        checkTrue(validResult.lastModifiedMs == valid.lastModified(), "verified file snapshot must retain lastModified")
        checkTrue(validResult.entriesScanned == 2, "all entries must be scanned")
        checkTrue(progress.lastOrNull() == 100, "valid scan must reach 100%")

        val correctSidecar = File(valid.absolutePath + ".sha256")
        correctSidecar.writeText(validResult.sha256!!)
        val sidecarPass = PackageIntegrityVerifier.verifyZip(valid)
        checkTrue(sidecarPass.verdict == PackageIntegrityVerifier.Verdict.VALID, "matching sidecar must pass")
        checkTrue(sidecarPass.sidecarPresent, "matching sidecar must be reported")
        correctSidecar.delete()

        val wrongSidecar = File(valid.absolutePath + ".sha256")
        wrongSidecar.writeText("0".repeat(64))
        val mismatch = PackageIntegrityVerifier.verifyZip(valid)
        checkTrue(
            mismatch.verdict == PackageIntegrityVerifier.Verdict.CHECKSUM_MISMATCH,
            "wrong sidecar must block package: $mismatch"
        )
        wrongSidecar.delete()

        val truncated = File(root, "truncated.zip")
        createValidZip(truncated)
        val truncatedBytes = truncated.readBytes()
        truncated.writeBytes(truncatedBytes.copyOf(truncatedBytes.size - 18))
        val truncatedResult = PackageIntegrityVerifier.verifyZip(truncated)
        checkTrue(
            truncatedResult.verdict == PackageIntegrityVerifier.Verdict.TRUNCATED,
            "truncated zip must be classified as TRUNCATED: $truncatedResult"
        )

        val corrupted = File(root, "corrupted.zip")
        createValidZip(corrupted, stored = true)
        corruptFirstEntryData(corrupted)
        val corruptedResult = PackageIntegrityVerifier.verifyZip(corrupted)
        checkTrue(
            corruptedResult.verdict == PackageIntegrityVerifier.Verdict.CORRUPTED,
            "CRC mismatch must be CORRUPTED: $corruptedResult"
        )

        var cancelChecks = 0
        val cancelled = PackageIntegrityVerifier.verifyZip(
            valid,
            shouldCancel = { (++cancelChecks) > 2 }
        )
        checkTrue(cancelled.verdict == PackageIntegrityVerifier.Verdict.CANCELLED, "cancellation must stop integrity preflight")

        val emptyZip = File(root, "empty.zip")
        ZipOutputStream(emptyZip.outputStream()).use { }
        val emptyResult = PackageIntegrityVerifier.verifyZip(emptyZip)
        checkTrue(emptyResult.verdict == PackageIntegrityVerifier.Verdict.CORRUPTED, "empty ZIP must be CORRUPTED")

        val missing = File(root, "missing.zip")
        val missingResult = PackageIntegrityVerifier.verifyZip(missing)
        checkTrue(
            missingResult.verdict == PackageIntegrityVerifier.Verdict.UNREADABLE,
            "missing package must be UNREADABLE"
        )

        val copyTarget = File(root, "copied.bin")
        val sourceBytes = ByteArray(512 * 1024 + 17) { (it * 31).toByte() }
        val copyResult = HashUtils.copyToFileVerified(
            input = ByteArrayInputStream(sourceBytes),
            target = copyTarget,
            expectedSize = sourceBytes.size.toLong()
        )
        checkTrue(copyResult.ok, "verified import copy must pass: $copyResult")
        checkTrue(copyResult.sourceSha256 == copyResult.destinationSha256, "source/destination hashes must match")
        checkTrue(copyTarget.readBytes().contentEquals(sourceBytes), "copied data mismatch")

        val wrongSizeTarget = File(root, "wrong-size.bin")
        val wrongSize = HashUtils.copyToFileVerified(
            input = ByteArrayInputStream(sourceBytes),
            target = wrongSizeTarget,
            expectedSize = sourceBytes.size.toLong() + 1L
        )
        checkTrue(!wrongSize.ok, "wrong source size must fail")

        println("PACKAGE INTEGRITY TESTS: OK")
    } finally {
        root.deleteRecursively()
    }
}
