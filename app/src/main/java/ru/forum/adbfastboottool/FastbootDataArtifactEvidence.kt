package ru.forum.adbfastboottool

/**
 * File-bound authorization for real Fastboot DATA mutations.
 *
 * A transport self-test proves only that a transport can move N bytes. It must never authorize
 * a different real image of the same size. Real mutation authorization requires all of:
 * - the same artifact identity (SHA-256 + exact byte count),
 * - the same live Fastboot USB generation,
 * - a successful download-only qualification at least as large as the requested file.
 */
object FastbootDataArtifactEvidence {
    data class Qualification(
        val artifactId: String? = null,
        val qualifiedBytes: Long = 0L,
        val generation: String? = null
    )

    fun qualifies(
        qualification: Qualification,
        requiredArtifactId: String,
        requiredBytes: Long,
        generation: String
    ): Boolean = requiredArtifactId.isNotBlank() &&
        requiredBytes > 0L &&
        generation.isNotBlank() &&
        qualification.artifactId == requiredArtifactId &&
        qualification.qualifiedBytes >= requiredBytes &&
        qualification.generation == generation

    fun preferredTransport(
        async: Qualification,
        sync: Qualification,
        requiredArtifactId: String,
        requiredBytes: Long,
        generation: String
    ): FastbootDataTransportEvidence.Transport? {
        val asyncQualified = qualifies(async, requiredArtifactId, requiredBytes, generation)
        val syncQualified = qualifies(sync, requiredArtifactId, requiredBytes, generation)
        return when {
            asyncQualified -> FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST
            syncQualified -> FastbootDataTransportEvidence.Transport.SYNC_BULK
            else -> null
        }
    }
}
