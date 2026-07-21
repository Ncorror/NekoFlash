package ru.forum.adbfastboottool

import java.io.File
import java.net.URI
import java.util.Base64
import java.util.Locale

/**
 * Persistable, mutation-free description of one queued flash image.
 *
 * The draft deliberately stores a URI and immutable identity metadata instead of a
 * raw [File]. A [File] is resolved only for a fresh validation or an explicitly
 * confirmed execution attempt.
 */
data class FlashQueueDraftItem(
    val partition: String,
    val sourceUri: String,
    val displayName: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val addedAtEpochMs: Long,
    val verification: FlashQueueVerification = FlashQueueVerification.NEEDS_REVALIDATION,
    val verificationDetail: String? = null
) {
    val isVerified: Boolean get() = verification == FlashQueueVerification.VERIFIED
}

enum class FlashQueueVerification {
    NEEDS_REVALIDATION,
    VERIFYING,
    VERIFIED,
    INVALID_URI,
    MISSING,
    NOT_A_FILE,
    UNREADABLE,
    SIZE_CHANGED,
    SHA256_CHANGED,
    ERROR
}

/**
 * Only draft data is retained. There is intentionally no persisted "running",
 * "confirmed" or "resume mutation" flag.
 */
data class FlashOperationDraft(
    val items: List<FlashQueueDraftItem> = emptyList(),
    val revision: Long = 0L
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val canRequestExecution: Boolean get() = items.isNotEmpty() && items.all { it.isVerified }
}

data class FlashQueueVerificationResult(
    val item: FlashQueueDraftItem,
    val resolvedFile: File? = null
)

object FlashOperationDraftPolicy {
    const val MAX_QUEUE_ITEMS = 32
    private const val MAX_URI_LENGTH = 4096
    private const val MAX_DISPLAY_NAME_LENGTH = 256
    private val PARTITION_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    fun createItem(
        partition: String,
        file: File,
        addedAtEpochMs: Long = System.currentTimeMillis()
    ): FlashQueueDraftItem {
        val normalizedPartition = normalizePartition(partition)
            ?: throw IllegalArgumentException("Invalid partition name")
        val canonical = file.canonicalFile
        require(canonical.exists()) { "Source file does not exist" }
        require(canonical.isFile) { "Source is not a file" }
        require(canonical.canRead()) { "Source file is not readable" }
        require(canonical.length() > 0L) { "Source file is empty" }

        val sha256 = HashUtils.calculateSha256(canonical).lowercase(Locale.US)
        return FlashQueueDraftItem(
            partition = normalizedPartition,
            sourceUri = canonical.toURI().toASCIIString(),
            displayName = canonical.name.take(MAX_DISPLAY_NAME_LENGTH),
            expectedSizeBytes = canonical.length(),
            expectedSha256 = sha256,
            addedAtEpochMs = addedAtEpochMs,
            verification = FlashQueueVerification.VERIFIED,
            verificationDetail = "Размер и SHA-256 подтверждены"
        )
    }

    fun upsert(draft: FlashOperationDraft, item: FlashQueueDraftItem): FlashOperationDraft {
        require(isPersistable(item)) { "Draft item is not persistable" }
        val next = LinkedHashMap<String, FlashQueueDraftItem>()
        draft.items.forEach { existing ->
            if (existing.partition != item.partition) next[existing.partition] = existing
        }
        next[item.partition] = item
        require(next.size <= MAX_QUEUE_ITEMS) { "Flash queue is too large" }
        return FlashOperationDraft(next.values.toList(), draft.revision + 1L)
    }

    fun clear(draft: FlashOperationDraft): FlashOperationDraft =
        FlashOperationDraft(emptyList(), draft.revision + 1L)

    fun markNeedsRevalidation(draft: FlashOperationDraft): FlashOperationDraft =
        draft.copy(
            items = draft.items.map {
                it.copy(
                    verification = FlashQueueVerification.NEEDS_REVALIDATION,
                    verificationDetail = "Требуется повторная проверка доступа, размера и SHA-256"
                )
            }
        )

    fun markVerifying(draft: FlashOperationDraft): FlashOperationDraft =
        draft.copy(
            items = draft.items.map {
                it.copy(
                    verification = FlashQueueVerification.VERIFYING,
                    verificationDetail = "Повторная проверка файла"
                )
            }
        )

    fun verify(item: FlashQueueDraftItem): FlashQueueVerificationResult {
        if (!isPersistable(item)) {
            return failed(item, FlashQueueVerification.INVALID_URI, "Метаданные черновика повреждены")
        }
        val file = try {
            val uri = URI(item.sourceUri)
            if (!uri.scheme.equals("file", ignoreCase = true) || uri.authority != null) {
                return failed(item, FlashQueueVerification.INVALID_URI, "Сейчас поддерживается только локальный file URI")
            }
            File(uri).canonicalFile
        } catch (e: Exception) {
            return failed(item, FlashQueueVerification.INVALID_URI, e.message ?: "Некорректный URI")
        }

        if (!file.exists()) return failed(item, FlashQueueVerification.MISSING, "Файл больше не существует")
        if (!file.isFile) return failed(item, FlashQueueVerification.NOT_A_FILE, "URI больше не указывает на файл")
        if (!file.canRead()) return failed(item, FlashQueueVerification.UNREADABLE, "Файл недоступен для чтения")
        if (file.length() != item.expectedSizeBytes) {
            return failed(
                item,
                FlashQueueVerification.SIZE_CHANGED,
                "Размер изменился: ожидалось ${item.expectedSizeBytes}, получено ${file.length()}"
            )
        }

        val actualSha256 = try {
            HashUtils.calculateSha256(file).lowercase(Locale.US)
        } catch (e: Exception) {
            return failed(item, FlashQueueVerification.ERROR, e.message ?: e.javaClass.simpleName)
        }
        if (!actualSha256.equals(item.expectedSha256, ignoreCase = true)) {
            return failed(item, FlashQueueVerification.SHA256_CHANGED, "SHA-256 файла изменился")
        }

        return FlashQueueVerificationResult(
            item = item.copy(
                verification = FlashQueueVerification.VERIFIED,
                verificationDetail = "Размер и SHA-256 повторно подтверждены"
            ),
            resolvedFile = file
        )
    }

    fun verifyAll(draft: FlashOperationDraft): Pair<FlashOperationDraft, List<File>> {
        val results = draft.items.map(::verify)
        val verifiedDraft = draft.copy(items = results.map { it.item })
        val files = if (results.all { it.item.isVerified && it.resolvedFile != null }) {
            results.map { requireNotNull(it.resolvedFile) }
        } else {
            emptyList()
        }
        return verifiedDraft to files
    }

    fun isPersistable(item: FlashQueueDraftItem): Boolean {
        return normalizePartition(item.partition) == item.partition &&
            item.sourceUri.isNotBlank() && item.sourceUri.length <= MAX_URI_LENGTH &&
            item.displayName.isNotBlank() && item.displayName.length <= MAX_DISPLAY_NAME_LENGTH &&
            item.expectedSizeBytes > 0L &&
            SHA256_PATTERN.matches(item.expectedSha256) &&
            item.addedAtEpochMs >= 0L
    }

    private fun normalizePartition(value: String): String? {
        val normalized = value.trim().lowercase(Locale.US)
        return normalized.takeIf { PARTITION_PATTERN.matches(it) }
    }

    private fun failed(
        item: FlashQueueDraftItem,
        verification: FlashQueueVerification,
        detail: String
    ): FlashQueueVerificationResult = FlashQueueVerificationResult(
        item = item.copy(verification = verification, verificationDetail = detail),
        resolvedFile = null
    )
}

/** Bundle/SavedStateHandle-safe codec: only an ArrayList<String> is persisted. */
object FlashOperationDraftCodec {
    private const val SCHEMA = "1"
    private const val SEPARATOR = '|'
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(draft: FlashOperationDraft): ArrayList<String> = ArrayList(
        draft.items
            .filter(FlashOperationDraftPolicy::isPersistable)
            .take(FlashOperationDraftPolicy.MAX_QUEUE_ITEMS)
            .map { item ->
                listOf(
                    SCHEMA,
                    encodeField(item.partition),
                    encodeField(item.sourceUri),
                    encodeField(item.displayName),
                    item.expectedSizeBytes.toString(),
                    item.expectedSha256.lowercase(Locale.US),
                    item.addedAtEpochMs.toString()
                ).joinToString(SEPARATOR.toString())
            }
    )

    fun decode(encoded: List<String>?): FlashOperationDraft {
        if (encoded.isNullOrEmpty()) return FlashOperationDraft()
        val items = LinkedHashMap<String, FlashQueueDraftItem>()
        encoded.take(FlashOperationDraftPolicy.MAX_QUEUE_ITEMS).forEach { row ->
            val parts = row.split(SEPARATOR)
            if (parts.size != 7 || parts[0] != SCHEMA) return@forEach
            val item = runCatching {
                FlashQueueDraftItem(
                    partition = decodeField(parts[1]),
                    sourceUri = decodeField(parts[2]),
                    displayName = decodeField(parts[3]),
                    expectedSizeBytes = parts[4].toLong(),
                    expectedSha256 = parts[5].lowercase(Locale.US),
                    addedAtEpochMs = parts[6].toLong(),
                    verification = FlashQueueVerification.NEEDS_REVALIDATION,
                    verificationDetail = "Восстановлено из SavedStateHandle; требуется повторная проверка"
                )
            }.getOrNull() ?: return@forEach
            if (FlashOperationDraftPolicy.isPersistable(item)) items[item.partition] = item
        }
        return FlashOperationDraft(items.values.toList())
    }

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String =
        decoder.decode(value).toString(Charsets.UTF_8)
}
