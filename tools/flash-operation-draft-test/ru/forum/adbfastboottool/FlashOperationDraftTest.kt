package ru.forum.adbfastboottool

import java.io.File
import java.nio.file.Files

fun main() {
        val root = Files.createTempDirectory("nekoflash-draft-").toFile()
        try {
            val boot = File(root, "boot.img").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
            val recovery = File(root, "recovery.img").apply { writeBytes(ByteArray(2048) { (it % 127).toByte() }) }

            val bootItem = FlashOperationDraftPolicy.createItem("BOOT", boot, addedAtEpochMs = 10L)
            check(bootItem.partition == "boot")
            check(bootItem.sourceUri.startsWith("file:"))
            check(bootItem.expectedSizeBytes == 4096L)
            check(bootItem.expectedSha256.length == 64)
            check(bootItem.isVerified)

            var draft = FlashOperationDraftPolicy.upsert(FlashOperationDraft(), bootItem)
            draft = FlashOperationDraftPolicy.upsert(
                draft,
                FlashOperationDraftPolicy.createItem("recovery", recovery, addedAtEpochMs = 20L)
            )
            check(draft.items.map { it.partition } == listOf("boot", "recovery"))
            check(draft.canRequestExecution)

            val encoded = FlashOperationDraftCodec.encode(draft)
            val restored = FlashOperationDraftCodec.decode(encoded)
            check(restored.items.size == 2)
            check(restored.items.all { it.verification == FlashQueueVerification.NEEDS_REVALIDATION })
            check(!restored.canRequestExecution)
            check(restored.items[0].expectedSha256 == bootItem.expectedSha256)

            val (reverified, files) = FlashOperationDraftPolicy.verifyAll(restored)
            check(reverified.canRequestExecution)
            check(files.map { it.canonicalFile } == listOf(boot.canonicalFile, recovery.canonicalFile))

            // Same-size content changes must be caught by SHA-256, not only length.
            boot.writeBytes(ByteArray(4096) { ((it + 1) % 251).toByte() })
            val hashChanged = FlashOperationDraftPolicy.verify(restored.items.first())
            check(hashChanged.item.verification == FlashQueueVerification.SHA256_CHANGED)
            check(hashChanged.resolvedFile == null)

            // Recreate the original item, then verify size-change handling separately.
            val freshBoot = FlashOperationDraftPolicy.createItem("boot", boot, addedAtEpochMs = 30L)
            boot.appendBytes(byteArrayOf(1, 2, 3))
            val sizeChanged = FlashOperationDraftPolicy.verify(freshBoot)
            check(sizeChanged.item.verification == FlashQueueVerification.SIZE_CHANGED)

            val missingFile = File(root, "missing.img").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val missingItem = FlashOperationDraftPolicy.createItem("dtbo", missingFile)
            check(missingFile.delete())
            check(FlashOperationDraftPolicy.verify(missingItem).item.verification == FlashQueueVerification.MISSING)

            val invalidUriItem = bootItem.copy(sourceUri = "content://example/not-supported")
            check(FlashOperationDraftPolicy.verify(invalidUriItem).item.verification == FlashQueueVerification.INVALID_URI)

            // A replacement is explicit and changes the draft revision; it never carries an execution flag.
            val replacement = FlashOperationDraftPolicy.createItem("recovery", boot)
            val replaced = FlashOperationDraftPolicy.upsert(reverified, replacement)
            check(replaced.items.size == 2)
            check(replaced.items.last().partition == "recovery")
            check(replaced.revision == reverified.revision + 1L)

            // Malformed rows are dropped fail-closed.
            val malformed = FlashOperationDraftCodec.decode(listOf("1|broken"))
            check(malformed.items.isEmpty())

            val cleared = FlashOperationDraftPolicy.clear(replaced)
            check(cleared.isEmpty)
            check(!cleared.canRequestExecution)

            println("FlashOperationDraftTest: PASS")
        } finally {
            root.deleteRecursively()
        }
    }
