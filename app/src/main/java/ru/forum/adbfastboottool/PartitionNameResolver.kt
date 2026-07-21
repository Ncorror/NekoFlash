package ru.forum.adbfastboottool

/**
 * Classifies a payload file name and suggests a likely target partition. Pure,
 * side-effect-free logic used only to *hint* the user — it never selects a partition
 * or authorizes a flash. The partition-selection safety gate is unchanged.
 *
 * Custom recovery images rarely follow the `recovery.img` convention: they are named
 * after the project (OrangeFox, TWRP, PBRP, SHRP, RedWolf, ...). On modern A/B devices
 * there is often no dedicated `recovery` partition at all — the recovery ramdisk lives
 * in `boot` / `vendor_boot` / `init_boot`. So for a recovery-brand image we deliberately
 * return a topology-dependent classification with candidate partitions instead of one
 * hard answer; the concrete target must come from the device (getvar:all) and an explicit
 * user choice.
 */
object PartitionNameResolver {

    enum class Kind { EXACT_PARTITION, RECOVERY_IMAGE, ARCHIVE, UNKNOWN }

    data class Suggestion(
        val kind: Kind,
        /** A confident single target, or null when it is topology-dependent/unknown. */
        val partition: String?,
        /** Possible targets (for recovery-brand images on A/B vs legacy devices). */
        val candidates: List<String> = emptyList(),
        val note: String? = null,
    )

    private val KNOWN: Set<String> = setOf(
        "boot", "init_boot", "vendor_boot", "dtbo", "recovery",
        "vbmeta", "vbmeta_system", "vbmeta_vendor",
        "super", "system", "system_ext", "vendor", "product", "odm",
        "modem", "persist", "misc", "cust", "cache", "userdata", "metadata",
        "logo", "splash", "dtb", "vendor_dlkm", "system_dlkm", "odm_dlkm",
    )

    /** Substrings that identify a custom-recovery image regardless of the rest of the name. */
    private val RECOVERY_BRANDS: List<String> = listOf(
        "orangefox", "ofox", "ofrp",
        "twrp", "teamwin",
        "pbrp", "pitchblack",
        "shrp", "skyhawk",
        "redwolf", "rwrp",
        "nebrassy", "ianmacd",
    )

    /** Candidate partitions a recovery image may target, in rough priority order. */
    private val RECOVERY_CANDIDATES = listOf("recovery", "boot", "vendor_boot", "init_boot")

    /** Backward-compatible confident-only accessor: returns a partition or null. */
    fun suggest(fileName: String): String? =
        resolve(fileName).takeIf { it.kind == Kind.EXACT_PARTITION }?.partition

    fun resolve(fileName: String): Suggestion {
        val raw = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase()

        if (raw.endsWith(".zip")) {
            // A recovery/ROM zip is installed via sideload/recovery, not `fastboot flash`.
            return Suggestion(
                kind = Kind.ARCHIVE,
                partition = null,
                note = "Это архив (.zip) — устанавливается через ADB sideload / recovery, а не fastboot flash.",
            )
        }

        // Custom recovery images are named after the project, not the partition.
        if (RECOVERY_BRANDS.any { it in raw }) {
            return Suggestion(
                kind = Kind.RECOVERY_IMAGE,
                partition = null,
                candidates = RECOVERY_CANDIDATES,
                note = "Похоже на recovery-образ. Целевой раздел зависит от устройства: " +
                    "на A/B отдельного recovery часто нет — цель обычно boot или vendor_boot. " +
                    "Выберите раздел по списку устройства и подтвердите.",
            )
        }

        // Strip content-probe artifacts and image extensions, then match a known base name.
        var name = raw.replace(Regex("""\.prefix-\d+\.img$"""), "")
        for (ext in listOf(".img", ".bin", ".mbn", ".elf", ".raw")) {
            if (name.endsWith(ext)) { name = name.dropLast(ext.length); break }
        }
        val base = name.removeSuffix("_ab").removeSuffix("_a").removeSuffix("_b")
        if (base in KNOWN) {
            return Suggestion(kind = Kind.EXACT_PARTITION, partition = base)
        }

        return Suggestion(kind = Kind.UNKNOWN, partition = null)
    }
}
