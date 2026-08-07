package ru.forum.adbfastboottool

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal best-effort crash marker. It never replaces the platform crash handler. */
object DiagnosticCrashMarker {
    private val installed = AtomicBoolean(false)

    fun install(directory: File, buildId: String) {
        if (!installed.compareAndSet(false, true)) return
        if (!directory.exists()) directory.mkdirs()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val marker = File(directory, "last-crash-marker.txt")
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                marker.writeText(
                    buildString {
                        appendLine("NekoFlash crash marker")
                        appendLine("Created: $stamp")
                        appendLine("Build: $buildId")
                        appendLine("Thread: ${thread.name}")
                        appendLine("Exception: ${throwable.javaClass.name}")
                        appendLine("Message: ${throwable.message ?: "none"}")
                        appendLine("Top stack:")
                        throwable.stackTrace.take(24).forEach { appendLine("- $it") }
                    },
                    Charsets.UTF_8
                )
            }
            if (previous != null) previous.uncaughtException(thread, throwable) else System.exit(10)
        }
    }
}
