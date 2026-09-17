package io.github.ncorror.nekoflash.diagnostics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticBundle
import java.io.File

object EvidenceBundleIo {
    fun writeDocument(
        context: Context,
        uri: Uri,
        snapshot: EvidenceBundleFactory.Snapshot,
    ) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            DiagnosticBundle.write(output, snapshot.sections, snapshot.exportedAt)
        } ?: error("Content resolver returned no output stream")
    }

    fun writeShareCache(
        context: Context,
        snapshot: EvidenceBundleFactory.Snapshot,
    ): Uri {
        val directory = File(context.cacheDir, "evidence").apply { mkdirs() }
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("NekoFlash-evidence-") && it.name.endsWith(".zip") }
            .forEach { it.delete() }

        val file = File(directory, DiagnosticBundle.suggestedFileName(snapshot.exportedAt))
        file.outputStream().buffered().use { output ->
            DiagnosticBundle.write(output, snapshot.sections, snapshot.exportedAt)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.evidence-files",
            file,
        )
    }
}
