package ru.forum.adbfastboottool

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Лёгкий анализатор файлов прошивки. Он не модифицирует образ и не пытается
 * распаковывать payload.bin; цель — показать пользователю тип файла, размер,
 * контрольные суммы и подсказку, какой раздел выбирать осторожно.
 */
object ImageInspector {
    private const val MAX_HEADER_BYTES = 4096

    /**
     * Тег языка из настроек приложения ("", "ru", "en" — см. MainActivity.PREF_LANGUAGE_TAG).
     * Пустая строка = "Системный", тогда используется реальная локаль устройства.
     * Устанавливается MainActivity при старте и при смене языка в настройках —
     * без этого ImageInspector мог не совпадать с остальным UI, если пользователь
     * вручную выбрал язык приложения отличный от системного.
     */
    var languageOverrideTag: String = ""

    private fun ru(): Boolean {
        val override = languageOverrideTag
        return if (override.isNotBlank()) override == "ru" else Locale.getDefault().language == "ru"
    }
    private fun tr(ru: String, en: String): String = if (ru()) ru else en

    data class Analysis(
        val fileName: String,
        val absolutePath: String,
        val sizeBytes: Long,
        val kind: String,
        val magic: String?,
        val suggestedPartitions: List<String>,
        val warnings: List<String>,
        val details: List<String>,
        val sha256: String? = null,
        val md5: String? = null,
        val sidecarResults: List<String> = emptyList()
    ) {
        fun toDisplayText(): String {
            val lines = mutableListOf<String>()
            lines.add("${tr("Файл", "File")}: $fileName")
            lines.add("${tr("Путь", "Path")}: $absolutePath")
            lines.add("${tr("Размер", "Size")}: ${formatSize(sizeBytes)} ($sizeBytes ${tr("байт", "bytes")})")
            lines.add("${tr("Тип", "Type")}: $kind")
            if (!magic.isNullOrBlank()) lines.add("${tr("Сигнатура", "Signature")}: $magic")
            if (suggestedPartitions.isNotEmpty()) lines.add("${tr("Подсказка раздела", "Suggested partition")}: ${suggestedPartitions.joinToString(", ")}")
            if (sha256 != null) lines.add("SHA-256: $sha256")
            if (md5 != null) lines.add("MD5: $md5")
            if (sidecarResults.isNotEmpty()) {
                lines.add("")
                lines.add(tr("Sidecar-хэши:", "Sidecar hashes:"))
                sidecarResults.forEach { lines.add("- $it") }
            }
            if (details.isNotEmpty()) {
                lines.add("")
                lines.add(tr("Детали:", "Details:"))
                details.forEach { lines.add("- $it") }
            }
            if (warnings.isNotEmpty()) {
                lines.add("")
                lines.add(tr("Предупреждения:", "Warnings:"))
                warnings.forEach { lines.add("- $it") }
            }
            return lines.joinToString("\n")
        }
    }

    fun analyze(file: File, includeHashes: Boolean = true): Analysis {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return Analysis(
                fileName = file.name,
                absolutePath = file.absolutePath,
                sizeBytes = 0L,
                kind = tr("файл недоступен", "file unavailable"),
                magic = null,
                suggestedPartitions = emptyList(),
                warnings = listOf(tr("Файл не существует или не читается", "File does not exist or is not readable")),
                details = emptyList()
            )
        }

        val header = readHeader(file)
        val lowerName = file.name.lowercase(Locale.US)
        val baseWarnings = mutableListOf<String>()
        val details = mutableListOf<String>()
        val suggested = mutableListOf<String>()
        var kind = tr("неизвестный файл", "unknown file")
        var magic: String? = hexMagic(header)

        when {
            startsWithAscii(header, "ANDROID!") -> {
                kind = "Android boot image"
                magic = "ANDROID!"
                parseBootHeader(header, details)
                suggested += when {
                    lowerName.contains("init_boot") -> listOf("init_boot")
                    lowerName.contains("vendor_boot") -> listOf("vendor_boot")
                    lowerName.contains("recovery") -> listOf("recovery")
                    else -> listOf("boot", "recovery")
                }
                baseWarnings += tr("boot/recovery/init_boot образы похожи по сигнатуре; выбирайте раздел по имени файла и инструкции к устройству.", "boot/recovery/init_boot images can share the same signature; choose the partition by file name and device instructions.")
            }
            startsWithAscii(header, "VNDRBOOT") -> {
                kind = "Android vendor_boot image"
                magic = "VNDRBOOT"
                parseVendorBootHeader(header, details)
                suggested += "vendor_boot"
            }
            startsWithAscii(header, "AVB0") -> {
                kind = "Android Verified Boot vbmeta image"
                magic = "AVB0"
                parseVbmetaHeader(header, details)
                suggested += when {
                    lowerName.contains("system") -> "vbmeta_system"
                    lowerName.contains("vendor") -> "vbmeta_vendor"
                    else -> "vbmeta"
                }
                baseWarnings += tr("vbmeta влияет на AVB/verity. В этой версии приложение только анализирует vbmeta и не включает unsafe-команды отключения проверки.", "vbmeta affects AVB/verity. This version only inspects vbmeta and does not enable unsafe verification-disabling commands.")
            }
            isAndroidSparse(header) -> {
                kind = "Android sparse image"
                magic = "sparse 0xED26FF3A"
                parseSparseHeader(header, details)
                suggested += guessSparsePartition(lowerName)
                baseWarnings += tr("Sparse-образ может быть system/vendor/product/super. Эта утилита не прошивает dynamic partitions и super.img.", "A sparse image can be system/vendor/product/super. This utility does not flash dynamic partitions or super.img.")
            }
            isDtbo(header, lowerName) -> {
                kind = "DTBO image"
                magic = if (header.size >= 4) hexMagic(header) else null
                suggested += "dtbo"
                baseWarnings += tr("DTBO должен соответствовать модели и версии ядра устройства.", "DTBO must match the device model and kernel version.")
            }
            lowerName.endsWith(".zip") || isZip(header) -> {
                kind = "ZIP / update package"
                magic = "PK"
                inspectZip(file, details, baseWarnings)
                suggested += "ADB Sideload"
            }
            lowerName.endsWith(".img") -> {
                kind = tr("raw .img без известной сигнатуры", "raw .img without a known signature")
                suggested += guessRawPartition(lowerName)
                baseWarnings += tr("Сигнатура не распознана. Не прошивайте файл без точной инструкции для вашей модели.", "Signature not recognized. Do not flash this file without exact instructions for your model.")
            }
        }

        // FIX #6: единственный проход через файл для SHA-256 + MD5
        val (sha256, md5, sidecars) = if (includeHashes) {
            val h = HashUtils.calculateHashes(file)
            Triple(h.sha256, h.md5, inspectSidecars(file, h.sha256, h.md5))
        } else {
            Triple(null, null, emptyList<String>())
        }

        return Analysis(
            fileName = file.name,
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            kind = kind,
            magic = magic,
            suggestedPartitions = suggested.distinct().filter { it.isNotBlank() },
            warnings = baseWarnings.distinct(),
            details = details.distinct(),
            sha256 = sha256,
            md5 = md5,
            sidecarResults = sidecars
        )
    }

    fun isSupportedFirmwareFile(file: File): Boolean {
        if (!file.isFile || !file.canRead()) return false
        val lower = file.name.lowercase(Locale.US)
        return lower.endsWith(".img") || lower.endsWith(".zip") || lower.endsWith(".bin") ||
            lower.endsWith(".sha256") || lower.endsWith(".md5")
    }

    private fun readHeader(file: File): ByteArray {
        val size = minOf(file.length(), MAX_HEADER_BYTES.toLong()).toInt()
        if (size <= 0) return ByteArray(0)
        val buffer = ByteArray(size)
        RandomAccessFile(file, "r").use { raf -> raf.readFully(buffer) }
        return buffer
    }

    private fun startsWithAscii(header: ByteArray, value: String): Boolean {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        if (header.size < bytes.size) return false
        for (i in bytes.indices) if (header[i] != bytes[i]) return false
        return true
    }

    private fun isZip(header: ByteArray): Boolean =
        header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()

    private fun isAndroidSparse(header: ByteArray): Boolean =
        header.size >= 4 && u32le(header, 0) == 0xED26FF3AL

    private fun isDtbo(header: ByteArray, lowerName: String): Boolean {
        if (lowerName.contains("dtbo")) return true
        if (header.size < 4) return false
        val be = u32be(header, 0)
        val le = u32le(header, 0)
        return be == 0xD7B7AB1EL || le == 0xD7B7AB1EL
    }

    private fun parseBootHeader(header: ByteArray, details: MutableList<String>) {
        if (header.size < 48) return
        val kernelSize = u32le(header, 8)
        val ramdiskSize = u32le(header, 16)
        val secondSize = u32le(header, 24)
        val pageSizeOrReserved = u32le(header, 36)
        val headerVersion = u32le(header, 40)
        val osVersion = u32le(header, 44)
        details += "kernel_size: $kernelSize"
        details += "ramdisk_size: $ramdiskSize"
        details += "second_size: $secondSize"
        details += "page_size/reserved: $pageSizeOrReserved"
        details += "header_version: $headerVersion"
        details += "os_version raw: 0x${osVersion.toString(16)}"
        val name = asciiZ(header, 48, 16)
        if (name.isNotBlank()) details += "name: $name"
    }

    private fun parseVendorBootHeader(header: ByteArray, details: MutableList<String>) {
        if (header.size < 32) return
        details += "header_version: ${u32le(header, 8)}"
        details += "page_size: ${u32le(header, 12)}"
        details += "kernel_addr: 0x${u32le(header, 16).toString(16)}"
        details += "ramdisk_addr: 0x${u32le(header, 20).toString(16)}"
        details += "vendor_ramdisk_size: ${u32le(header, 24)}"
    }

    private fun parseVbmetaHeader(header: ByteArray, details: MutableList<String>) {
        if (header.size < 256) return
        details += "required_libavb_version: ${u32be(header, 4)}.${u32be(header, 8)}"
        details += "authentication_block_size: ${u64be(header, 12)}"
        details += "auxiliary_block_size: ${u64be(header, 20)}"
        details += "algorithm_type: ${u32be(header, 28)}"
        details += "rollback_index: ${u64be(header, 88)}"
        details += "flags: ${u32be(header, 120)}"
        val release = asciiZ(header, 124, 48)
        if (release.isNotBlank()) details += "release_string: $release"
    }

    private fun parseSparseHeader(header: ByteArray, details: MutableList<String>) {
        if (header.size < 28) return
        details += "major_version: ${u16le(header, 4)}"
        details += "minor_version: ${u16le(header, 6)}"
        details += "file_header_size: ${u16le(header, 8)}"
        details += "chunk_header_size: ${u16le(header, 10)}"
        details += "block_size: ${u32le(header, 12)}"
        details += "total_blocks: ${u32le(header, 16)}"
        details += "total_chunks: ${u32le(header, 20)}"
    }

    private fun inspectZip(file: File, details: MutableList<String>, warnings: MutableList<String>) {
        try {
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.take(500).toList()
                details += "entries_sample: ${names.size}"
                when {
                    names.any { it == "payload.bin" } -> {
                        details += tr("payload.bin: найден — A/B OTA пакет", "payload.bin: found — A/B OTA package")
                        warnings += tr("payload.bin внутри ZIP не распаковывается этой утилитой. Используйте ADB Sideload через recovery, если пакет для этого предназначен.", "payload.bin inside ZIP is not unpacked by this utility. Use ADB Sideload through recovery if the package is intended for it.")
                    }
                    names.any { it.startsWith("META-INF/com/google/android/") } -> {
                        details += tr("META-INF updater-script/binary: найден — recovery ZIP", "META-INF updater-script/binary: found — recovery ZIP")
                    }
                    else -> warnings += tr("ZIP не похож на стандартный recovery/OTA пакет. Проверьте источник файла.", "ZIP does not look like a standard recovery/OTA package. Check the file source.")
                }
                val androidInfo = names.firstOrNull { it.endsWith("android-info.txt", ignoreCase = true) }
                if (androidInfo != null) details += "android-info.txt: $androidInfo"
            }
        } catch (e: Exception) {
            warnings += "${tr("ZIP не удалось прочитать", "Could not read ZIP")}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun inspectSidecars(file: File, sha256: String?, md5: String?): List<String> {
        val results = mutableListOf<String>()
        if (sha256 != null) results += inspectSidecar(file, "sha256", 64, sha256)
        if (md5 != null) results += inspectSidecar(file, "md5", 32, md5)
        return results
    }

    private fun inspectSidecar(file: File, extension: String, hashLength: Int, actual: String): String {
        val sidecar = findSidecar(file, extension)
            ?: return tr(".$extension не найден — сравнение пропущено", ".$extension not found — comparison skipped")
        val expected = Regex("[A-Fa-f0-9]{$hashLength}").find(sidecar.readText())?.value
            ?: return "${sidecar.name}: ${tr("не удалось прочитать хэш", "could not read hash")}"
        return if (actual.equals(expected, ignoreCase = true)) {
            "${sidecar.name}: ${tr("совпадает", "matches")}"
        } else {
            "${sidecar.name}: ${tr("НЕ СОВПАДАЕТ", "MISMATCH")}; expected=$expected actual=$actual"
        }
    }

    private fun findSidecar(file: File, extension: String): File? {
        val parent = file.parentFile ?: return null
        val candidates = listOf(
            File(file.absolutePath + ".$extension"),
            File(parent, file.nameWithoutExtension + ".$extension")
        )
        return candidates.distinctBy { it.absolutePath }.firstOrNull { it.exists() && it.isFile && it.canRead() }
    }

    private fun guessSparsePartition(lowerName: String): List<String> = when {
        lowerName.contains("super") -> listOf("super — ${tr("не поддерживается", "not supported")}")
        lowerName.contains("system") -> listOf("system — ${tr("не поддерживается", "not supported")}")
        lowerName.contains("vendor") -> listOf("vendor — ${tr("не поддерживается", "not supported")}")
        lowerName.contains("product") -> listOf("product — ${tr("не поддерживается", "not supported")}")
        else -> listOf(tr("неизвестно", "unknown"))
    }

    private fun guessRawPartition(lowerName: String): List<String> = when {
        lowerName.contains("init_boot") -> listOf("init_boot")
        lowerName.contains("vendor_boot") -> listOf("vendor_boot")
        lowerName.contains("recovery") -> listOf("recovery")
        lowerName.contains("boot") -> listOf("boot")
        lowerName.contains("dtbo") -> listOf("dtbo")
        lowerName.contains("vbmeta") -> listOf("vbmeta — ${tr("только анализ в этой версии", "inspection only in this version")}")
        else -> emptyList()
    }

    private fun hexMagic(header: ByteArray): String? {
        if (header.isEmpty()) return null
        return header.take(minOf(8, header.size)).joinToString(" ") { "%02X".format(it) }
    }

    private fun asciiZ(buffer: ByteArray, offset: Int, maxLength: Int): String {
        if (offset >= buffer.size) return ""
        val end = minOf(buffer.size, offset + maxLength)
        val slice = buffer.copyOfRange(offset, end)
        return slice.takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.US_ASCII)
            .trim()
    }

    private fun u16le(b: ByteArray, offset: Int): Long {
        if (offset + 2 > b.size) return 0L
        return ByteBuffer.wrap(b, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt().and(0xFFFF).toLong()
    }

    private fun u32le(b: ByteArray, offset: Int): Long {
        if (offset + 4 > b.size) return 0L
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong().and(0xFFFF_FFFFL)
    }

    private fun u32be(b: ByteArray, offset: Int): Long {
        if (offset + 4 > b.size) return 0L
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong().and(0xFFFF_FFFFL)
    }

    private fun u64be(b: ByteArray, offset: Int): Long {
        if (offset + 8 > b.size) return 0L
        return ByteBuffer.wrap(b, offset, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes.toDouble() / 1024.0 / 1024.0
        return "%.2f MB".format(Locale.US, mb)
    }
}
