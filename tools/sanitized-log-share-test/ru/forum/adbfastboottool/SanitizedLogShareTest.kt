package ru.forum.adbfastboottool

import java.io.File
import java.nio.file.Files

private fun assertTrue(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val root = Files.createTempDirectory("nekoflash-sanitized-share").toFile()
    try {
        val workspace = File(root, "workspace").apply { mkdirs() }
        val source = File(workspace, "logs/raw.txt").apply {
            parentFile?.mkdirs()
            writeText(
                "serialno: ABCDEF0123456789\n" +
                    "userId=123456789\n" +
                    "Log file: ${absolutePath}\n" +
                    "DeviceName: /dev/bus/usb/001/002\n"
            )
        }
        val outDir = File(root, "cache/shared-logs")
        val shared = SanitizedLogShare.create(
            source,
            outDir,
            ReportSanitizer.Scope(workspace = workspace, logFile = source, packageName = "ru.test")
        )
        val text = shared.readText()
        assertTrue(!text.contains("ABCDEF0123456789"), "serial must be removed")
        assertTrue(!text.contains("123456789"), "account id must be removed")
        assertTrue(!text.contains(source.absolutePath), "source path must be removed")
        assertTrue(text.contains(ReportSanitizer.REDACTED_SERIAL), "serial marker must remain")
        assertTrue(text.contains(ReportSanitizer.REDACTED_ACCOUNT_ID), "account marker must remain")

        shared.setLastModified(1L)
        val removed = SanitizedLogShare.cleanupExpired(outDir, nowMs = SanitizedLogShare.DEFAULT_RETENTION_MS + 2L)
        assertTrue(removed == 1 && !shared.exists(), "expired sanitized share must be removed")
        println("SanitizedLogShareTest: PASS")
    } finally {
        root.deleteRecursively()
    }
}
