package ru.forum.adbfastboottool

/**
 * Pure policy helpers for V5.6.6 internal Fastboot DATA staging.
 *
 * Real Fastboot DATA must never stream directly from emulated/shared storage. The selected
 * source artifact is copied into app-private storage, fsynced and SHA-256 verified first.
 */
object FastbootDataStagingPolicy {
    const val DEFAULT_FREE_SPACE_RESERVE_BYTES: Long = 32L * 1024L * 1024L

    data class ArtifactRef(
        val sha256: String,
        val bytes: Long
    )

    fun parseArtifactId(artifactId: String): ArtifactRef {
        val sha256 = artifactId.substringAfter("sha256:", "").substringBefore(":bytes=")
        val bytes = artifactId.substringAfter(":bytes=", "").toLongOrNull()
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 artifact identity" }
        require(bytes != null && bytes > 0L) { "Invalid artifact byte count" }
        return ArtifactRef(sha256, bytes)
    }

    fun stagedFileName(artifactId: String): String {
        val ref = parseArtifactId(artifactId)
        return "sha256-${ref.sha256}-bytes-${ref.bytes}.ready"
    }

    fun requiredFreeBytes(
        sourceBytes: Long,
        reserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES
    ): Long {
        require(sourceBytes > 0L) { "sourceBytes must be positive" }
        require(reserveBytes >= 0L) { "reserveBytes must be non-negative" }
        return Math.addExact(sourceBytes, reserveBytes)
    }

    /** Unknown/non-positive usable space is fail-closed: staging must not start. */
    fun hasEnoughSpace(
        sourceBytes: Long,
        usableBytes: Long,
        reserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES
    ): Boolean = usableBytes > 0L && usableBytes >= requiredFreeBytes(sourceBytes, reserveBytes)
}
