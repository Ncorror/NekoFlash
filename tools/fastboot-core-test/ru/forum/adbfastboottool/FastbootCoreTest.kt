package ru.forum.adbfastboottool

import android.hardware.usb.*
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private fun diagnosticsResponses() = arrayOf(
    "OKAYonyx", "OKAYb", "OKAY2", "FAILnot found", "OKAYyes", "OKAYyes",
    "OKAYserial", "OKAY", "OKAY1", "OKAYno", "FAILnot found", "OKAYnone", "OKAY805306368", "FAILnot found"
)

private fun newProtocol(logs: MutableList<String>): FastbootProtocol {
    val p = FastbootProtocol(UsbManager(), UsbDevice(), logs::add, logs::add)
    check(p.connect())
    check(p.beginOperation())
    UsbScript.enqueue(*diagnosticsResponses())
    val d = p.refreshDiagnostics(force = true)
    check(d.product == "onyx")
    check(d.slotCount == "2")
    return p
}

private fun dataReply(size: Long): String = "DATA%08x".format(size)

fun main() {
    run {
        check(UsbTransportShutdownPolicy.canCloseUsb(false, false))
        check(!UsbTransportShutdownPolicy.canCloseUsb(true, false))
        check(!UsbTransportShutdownPolicy.canCloseUsb(false, true))
        check(!UsbTransportShutdownPolicy.canCloseUsb(true, true))
    }
    val small = File.createTempFile("nekoflash-test-small", ".img")
    small.writeBytes(ByteArray(32 * 1024) { (it and 0xff).toByte() })
    val large = File.createTempFile("nekoflash-test-large", ".img")
    large.writeBytes(ByteArray(600 * 1024) { (it and 0xff).toByte() })
    val self64 = File.createTempFile("nekoflash-data-self-test-64k", ".bin")
    self64.writeBytes(ByteArray(64 * 1024) { ((it * 31 + 0x5a) and 0xff).toByte() })
    val exact6MiB = File.createTempFile("nekoflash-data-qualification-6m", ".img")
    RandomAccessFile(exact6MiB, "rw").use { it.setLength(6L * 1024L * 1024L) }

    // 1) Successful modern UsbRequest DATA flash.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", dataReply(small.length()), "OKAY", "OKAY")
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(r.success) { r }
        check(p.currentSessionState == FastbootProtocol.SessionState.IDLE)
        check(UsbScript.commands.any { it == "flash:recovery_a" })
        check(UsbScript.asyncRequestSizes == listOf(32 * 1024)) { UsbScript.asyncRequestSizes }
        check(UsbScript.requestWaitTimeouts.all { it == 30_000L })
    }


    // 2) Positive short completion is continued from the confirmed ByteBuffer position.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", dataReply(small.length()), "OKAY", "OKAY")
        UsbScript.dataOutResults.add(10 * 1024)
        UsbScript.dataOutResults.add(22 * 1024)
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(r.success) { r }
        check(UsbScript.asyncRequestSizes == listOf(32 * 1024, 22 * 1024)) { UsbScript.asyncRequestSizes }
        check(UsbScript.dataBytesAccepted == small.length())
    }

    // 3) Protocol FAIL is clean: session remains reusable.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", "FAILpartition missing")
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(!r.success && r.failureKind == FastbootProtocol.FlashFailureKind.PROTOCOL) { r }
        check(!r.sessionCorrupted)
        check(p.currentSessionState == FastbootProtocol.SessionState.IDLE)
        check(p.beginOperation())
    }

    // 4) Async DATA failure breaks the session, closes USB, and blocks all later commands.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", dataReply(large.length()))
        UsbScript.dataOutResults.add(256 * 1024)
        UsbScript.dataOutResults.add(-1)
        val r = p.flashPartitionDetailed("recovery_a", large)
        check(!r.success && r.failureKind == FastbootProtocol.FlashFailureKind.TRANSPORT) { r }
        check(r.dataBytesTransferred == 256L * 1024L) { r }
        check(r.sessionCorrupted)
        check(p.currentSessionState == FastbootProtocol.SessionState.BROKEN)
        check(!p.isConnected) { "BROKEN session kept USB connection open" }
        val before = UsbScript.commands.size
        check(!p.beginOperation())
        check(p.getVar("product") == null)
        check(UsbScript.commands.size == before) { "A command was sent after BROKEN" }
        check(UsbScript.asyncRequestSizes.take(2) == listOf(256 * 1024, 256 * 1024)) { UsbScript.asyncRequestSizes }
    }

    // 5) A short/failed command write is not retried; the session is broken immediately.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>()
        val p = FastbootProtocol(UsbManager(), UsbDevice(), logs::add, logs::add)
        check(p.connect()); check(p.beginOperation())
        UsbScript.commandOutResults.add(-1)
        val before = UsbScript.commands.size
        check(p.getVar("product") == null)
        check(p.currentSessionState == FastbootProtocol.SessionState.BROKEN)
        check(!p.isConnected)
        check(UsbScript.commands.size == before + 1) { "Command write was retried" }
    }

    // 6) A transient failed bulk IN read is retried without resending the getvar command.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val commandCountBefore = UsbScript.commands.size
        UsbScript.enqueueReadFailure()
        UsbScript.enqueue("OKAYonyx")
        check(p.getVar("product", timeout = 2_500) == "onyx")
        check(p.currentSessionState == FastbootProtocol.SessionState.IDLE)
        val newCommands = UsbScript.commands.drop(commandCountBefore)
        check(newCommands == listOf("getvar:product")) { newCommands }
    }

    // 7) Repeated failed IN reads are bounded, break the session, and still never resend getvar.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val commandCountBefore = UsbScript.commands.size
        repeat(3) { UsbScript.enqueueReadFailure() }
        check(p.getVar("product", timeout = 5_000) == null)
        check(p.currentSessionState == FastbootProtocol.SessionState.BROKEN)
        val newCommands = UsbScript.commands.drop(commandCountBefore)
        check(newCommands == listOf("getvar:product")) { newCommands }
    }

    // 7b) V5.8.10 onyx handshake regression: more than GETVAR_MAX_FAILED_READS empty
    //     reads followed by a valid response inside the patience window must NOT break
    //     the session. This is the exact onyx symptom from the 2026-07-14 logs, where
    //     the first IN-response arrived ~200-400 ms after several immediate empty reads.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val commandCountBefore = UsbScript.commands.size
        repeat(6) { UsbScript.enqueueReadFailure() }   // > 3, but ~600 ms < 1500 ms patience
        UsbScript.enqueue("OKAYonyx")
        check(p.getVar("product", timeout = 7_000) == "onyx")
        check(p.currentSessionState == FastbootProtocol.SessionState.IDLE)
        val newCommands = UsbScript.commands.drop(commandCountBefore)
        check(newCommands == listOf("getvar:product")) { newCommands }
    }

    // 8) Android 8.x keeps the 16 KiB compatibility block size.
    run {
        Build.VERSION.SDK_INT = 26
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", dataReply(small.length()), "OKAY", "OKAY")
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(r.success) { r }
        check(UsbScript.asyncRequestSizes == listOf(16 * 1024, 16 * 1024)) { UsbScript.asyncRequestSizes }
    }


    // 9) Async failure on the first DATA block never falls back inline and does not claim sync PASS.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", dataReply(small.length()))
        UsbScript.dataOutResults.add(-1)
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(!r.success && r.sessionCorrupted) { r }
        check(p.lastDataTransportUsed == FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST)
        check(UsbScript.syncDataBytesAccepted == 0L) { "Inline sync fallback sent data in an ambiguous DATA session" }
        check(p.currentSessionState == FastbootProtocol.SessionState.BROKEN)
        check(logs.none { it.contains("следующая попытка будет использовать sync", ignoreCase = true) })
    }

    // 10) A fresh session can start in SYNC_BULK mode and handles positive partial writes.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        p.dataTransportMode = FastbootProtocol.DataTransportMode.SYNC_BULK
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", dataReply(small.length()), "OKAY", "OKAY")
        UsbScript.syncDataOutResults.add(4096)
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(r.success) { r }
        check(UsbScript.syncDataBytesAccepted == small.length()) {
            "sync bytes=${UsbScript.syncDataBytesAccepted}, expected=${small.length()}"
        }
        check(UsbScript.asyncRequestSizes.isEmpty()) { "SYNC_BULK unexpectedly used UsbRequest" }
    }


    // 10b) V5.8.11 Fix A: a real (non-diagnostic) DATA payload must fail closed on a
    //      single-URB transport — it wedges the onyx OUT endpoint and reference fastboot
    //      has no single-URB path. No bytes may be transferred.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        p.dataTransportMode = FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_256K
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", dataReply(small.length()))
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(!r.success && r.sessionCorrupted) { r }
        check(p.currentSessionState == FastbootProtocol.SessionState.BROKEN)
        check(UsbScript.dataBytesAccepted == 0L) { "single-URB real DATA must not transfer bytes" }
        check(logs.any { it.contains("single-URB", ignoreCase = true) }) { logs.takeLast(10) }
    }

    // 10c) Every Native USBFS profile remains diagnostic-only, including pipelined
    //      profiles. Real mutations must use UsbRequest or the sync bulk fallback.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        p.dataTransportMode = FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_256K
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", dataReply(small.length()))
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(!r.success && r.sessionCorrupted) { r }
        check(p.currentSessionState == FastbootProtocol.SessionState.BROKEN)
        check(UsbScript.dataBytesAccepted == 0L) { "Native USBFS real DATA must not transfer bytes" }
        check(logs.any { it.contains("diagnostic-only Native USBFS", ignoreCase = true) })
    }

    // 11) Safe DATA self-test sends download + payload + final OKAY, but no mutation command.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val before = UsbScript.commands.size
        UsbScript.enqueue(dataReply(self64.length()), "OKAY")
        val r = p.runDataSelfTestDetailed(self64, FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST)
        check(r.success) { r }
        val commands = UsbScript.commands.drop(before)
        check(commands == listOf("download:00010000")) { commands }
        check(commands.none { it.startsWith("flash:") || it == "boot" || it == "stage" || it.startsWith("update-super:") })
        check(UsbScript.dataBytesAccepted == self64.length())
        check(r.bytesTransferred == self64.length()) { r }
        check(p.currentSessionState == FastbootProtocol.SessionState.IDLE)
        check(logs.any { it.contains("Ни одна команда записи раздела не отправлялась") })
    }

    // 12) Cancel after DATA but before final OKAY quarantines and closes the session.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue(dataReply(self64.length()))
        val resultRef = AtomicReference<FastbootProtocol.DataSelfTestResult?>()
        val worker = thread(name = "fastboot-cancel-after-data") {
            resultRef.set(
                p.runDataSelfTestDetailed(
                    self64,
                    FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST
                )
            )
        }
        // Deterministically wait until the worker has left the DATA transfer loop and is
        // parked in the final-OKAY wait (AWAITING_DATA_FINAL). Cancelling merely when all
        // bytes are accepted is racy: on some schedulers the cancel is caught one iteration
        // earlier, still inside the DATA loop ("во время DATA-фазы"), instead of the
        // final-OKAY wait this case is meant to exercise. No final OKAY is enqueued, so the
        // worker stays in AWAITING_DATA_FINAL until we cancel.
        val deadline = System.currentTimeMillis() + 5_000L
        while (p.currentSessionState != FastbootProtocol.SessionState.AWAITING_DATA_FINAL
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L)
        }
        check(UsbScript.dataBytesAccepted == self64.length()) {
            "DATA did not complete before cancellation: ${UsbScript.dataBytesAccepted}/${self64.length()}"
        }
        check(p.currentSessionState == FastbootProtocol.SessionState.AWAITING_DATA_FINAL) {
            "worker did not reach the final-OKAY wait: ${p.currentSessionState}"
        }
        p.cancel()
        worker.join(5_000L)
        check(!worker.isAlive) { "cancel-after-DATA test did not terminate" }
        val r = checkNotNull(resultRef.get())
        check(!r.success && r.sessionCorrupted) { r }
        check(p.currentSessionState == FastbootProtocol.SessionState.BROKEN)
        check(!p.isConnected) { "cancel-after-DATA kept USB open" }
        check(logs.any { it.contains("отменён во время ожидания финального OKAY после DATA") }) { logs.takeLast(20) }
    }

    // 13) Self-test rejects a bootloader DATA size mismatch before payload transfer.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val before = UsbScript.commands.size
        UsbScript.enqueue("DATA00020000")
        val r = p.runDataSelfTestDetailed(self64, FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST)
        check(!r.success && r.stage == FastbootProtocol.DataSelfTestStage.WAIT_DATA) { r }
        check(r.sessionCorrupted)
        check(UsbScript.commands.drop(before) == listOf("download:00010000"))
        check(UsbScript.dataBytesAccepted == 0L)
    }

    // 14) Self-test rejects malformed/unparseable DATA size before payload transfer.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val before = UsbScript.commands.size
        UsbScript.enqueue("DATAnot-hex!")
        val r = p.runDataSelfTestDetailed(self64, FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST)
        check(!r.success && r.stage == FastbootProtocol.DataSelfTestStage.WAIT_DATA) { r }
        check(r.sessionCorrupted)
        check(UsbScript.commands.drop(before) == listOf("download:00010000"))
        check(UsbScript.dataBytesAccepted == 0L)
        check(r.message.contains("invalid:")) { r.message }
    }

    // 15) Safe DATA self-test can explicitly exercise sync bulkTransfer without sending flash.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val before = UsbScript.commands.size
        UsbScript.enqueue(dataReply(self64.length()), "OKAY")
        val r = p.runDataSelfTestDetailed(self64, FastbootProtocol.DataTransportMode.SYNC_BULK)
        check(r.success) { r }
        val commands = UsbScript.commands.drop(before)
        check(commands == listOf("download:00010000")) { commands }
        check(UsbScript.syncDataBytesAccepted == self64.length())
        check(UsbScript.asyncRequestSizes.isEmpty())
    }

    // 16) Unsupported self-test size is rejected before any wire command.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val before = UsbScript.commands.size
        val r = p.runDataSelfTestDetailed(small, FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST)
        check(!r.success && r.stage == FastbootProtocol.DataSelfTestStage.VALIDATION) { r }
        check(UsbScript.commands.size == before)
    }

    // 17) Exact-size qualification accepts a non-preset file size and still sends download only.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val before = UsbScript.commands.size
        UsbScript.enqueue(dataReply(exact6MiB.length()), "OKAY")
        val r = p.runDataQualificationTestDetailed(exact6MiB, FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST)
        check(r.success && r.bytes == exact6MiB.length() && r.bytesTransferred == exact6MiB.length()) { r }
        val commands = UsbScript.commands.drop(before)
        check(commands == listOf("download:00600000")) { commands }
        check(commands.none { it.startsWith("flash:") || it == "boot" || it == "stage" || it.startsWith("update-super:") })
    }

    // 18) Size-aware evidence: 4 MiB PASS never authorizes 100 MiB and never crosses USB generations.
    run {
        val genA = "process-a:7"
        val genB = "process-a:8"
        val fourMiB = 4L * 1024L * 1024L
        val hundredMiB = 100L * 1024L * 1024L
        val halfMiB = 512L * 1024L
        var state = FastbootDataTransportEvidence.State()

        // Completely untouched products preserve legacy behavior.
        check(FastbootDataTransportEvidence.realMutationBlockReason(state, hundredMiB, genA) == null)

        state = FastbootDataTransportEvidence.recordPass(
            state,
            FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST,
            fourMiB,
            genA
        )
        check(FastbootDataTransportEvidence.preferredTransport(state, fourMiB, genA) == FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST)
        check(FastbootDataTransportEvidence.preferredTransport(state, hundredMiB, genA) == null)
        check(FastbootDataTransportEvidence.realMutationBlockReason(state, hundredMiB, genA)?.contains("100 MiB") == true)
        check(FastbootDataTransportEvidence.preferredTransport(state, fourMiB, genB) == null)

        state = FastbootDataTransportEvidence.recordFailure(
            state,
            FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST,
            hundredMiB,
            halfMiB,
            genA
        )
        check(state.async.qualifiedBytes == 0L)
        check(state.async.historicalMaxProvenBytes == fourMiB)
        check(state.async.lastFailureRequestedBytes == hundredMiB)
        check(state.async.lastFailureOffsetBytes == halfMiB)

        state = FastbootDataTransportEvidence.recordPass(
            state,
            FastbootDataTransportEvidence.Transport.SYNC_BULK,
            hundredMiB,
            genA
        )
        check(FastbootDataTransportEvidence.preferredTransport(state, hundredMiB, genA) == FastbootDataTransportEvidence.Transport.SYNC_BULK)
        check(FastbootDataTransportEvidence.realMutationBlockReason(state, hundredMiB, genA) == null)

        val migrated = FastbootDataTransportEvidence.State(
            async = FastbootDataTransportEvidence.migrateLegacyOutcome("PASS")
        )
        check(migrated.async.legacyRequalificationRequired)
        check(FastbootDataTransportEvidence.preferredTransport(migrated, fourMiB, genA) == null)
        check(FastbootDataTransportEvidence.realMutationBlockReason(migrated, fourMiB, genA) != null)
    }

    // 19) File-bound DATA evidence rejects same-size different files and cross-session reuse.
    run {
        val genA = "process-a:41"
        val genB = "process-a:42"
        val size = 100L * 1024L * 1024L
        val recoveryId = "sha256:aaaaaaaa:bytes=$size"
        val syntheticId = "sha256:bbbbbbbb:bytes=$size"
        val async = FastbootDataArtifactEvidence.Qualification(
            artifactId = syntheticId,
            qualifiedBytes = size,
            generation = genA
        )
        val sync = FastbootDataArtifactEvidence.Qualification()

        check(!FastbootDataArtifactEvidence.qualifies(async, recoveryId, size, genA))
        check(FastbootDataArtifactEvidence.qualifies(async, syntheticId, size, genA))
        check(!FastbootDataArtifactEvidence.qualifies(async, syntheticId, size, genB))
        check(FastbootDataArtifactEvidence.preferredTransport(async, sync, recoveryId, size, genA) == null)
        check(
            FastbootDataArtifactEvidence.preferredTransport(async, sync, syntheticId, size, genA) ==
                FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST
        )
    }

    // 20) Monotonic timing diagnostics record failed reads and command turnaround.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueueReadFailure()
        UsbScript.enqueue("OKAYonyx")
        check(p.getVar("product", timeout = 2_500) == "onyx")
        check(logs.any { it.contains("[fastboot-timing] phase=in-empty") }) { logs.takeLast(20) }
        UsbScript.enqueue("OKAYb")
        check(p.getVar("current-slot") == "b")
        check(logs.any { it.contains("[fastboot-timing] phase=turnaround") && it.contains("getvar:current-slot") }) { logs.takeLast(30) }
    }

    // 20) Active Virtual A/B merge blocks set_active before the wire command is sent.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val before = UsbScript.commands.size
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYmerging", "OKAYyes", "OKAYno", "OKAY7")
        check(!p.sendCommand("set_active:a"))
        check(UsbScript.commands.none { it == "set_active:a" }) { UsbScript.commands.drop(before) }
        check(logs.any { it.contains("snapshot merge active", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 19) A target slot marked unbootable is blocked before set_active.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", "OKAYno", "OKAYyes", "OKAY0")
        check(!p.sendCommand("set_active:a"))
        check(UsbScript.commands.none { it == "set_active:a" })
        check(logs.any { it.contains("unbootable=yes", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 20) set_active succeeds only after current-slot verifies the requested slot.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", "OKAYyes", "OKAYno", "OKAY7", "OKAY", "OKAYa")
        check(p.sendCommand("set_active:a"))
        check(UsbScript.commands.any { it == "set_active:a" })
        check(logs.any { it.contains("set_active подтверждён", ignoreCase = true) })
    }

    // 21) OKAY without current-slot confirmation is not green success.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", "OKAYyes", "OKAYno", "OKAY7", "OKAY", "OKAYb")
        check(!p.sendCommand("set_active:a"))
        check(logs.any { it.contains("set_active не подтверждён", ignoreCase = true) })
    }


    // 22) A fresh product mismatch blocks flash before download.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val before = UsbScript.commands.size
        UsbScript.enqueue("OKAYdifferent-device")
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(!r.success && r.stage == FastbootProtocol.FlashStage.VALIDATION) { r }
        check(UsbScript.commands.none { it.startsWith("download:") }) { UsbScript.commands.drop(before) }
        check(logs.any { it.contains("Mutation identity mismatch") }) { logs.takeLast(20) }
    }

    // 23) A known logical partition cannot enter DATA phase outside fastbootd.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone")
        val r = p.flashPartitionDetailed("system_a", small)
        check(!r.success && r.stage == FastbootProtocol.FlashStage.VALIDATION) { r }
        check(UsbScript.commands.none { it.startsWith("download:") })
        check(logs.any { it.contains("requires fastbootd/userspace", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 24) An active snapshot merge blocks ordinary flash before DATA.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYmerging")
        val r = p.flashPartitionDetailed("recovery_a", small)
        check(!r.success && r.stage == FastbootProtocol.FlashStage.VALIDATION) { r }
        check(UsbScript.commands.none { it.startsWith("download:") })
        check(logs.any { it.contains("snapshot merge active", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 25) snapshot-update cancel is allowed only from SNAPSHOTTED and must verify NONE afterwards.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYsnapshotted", "OKAY", "OKAYnone")
        check(p.sendCommand("snapshot-update:cancel"))
        check(UsbScript.commands.any { it == "snapshot-update:cancel" }) { UsbScript.commands }
        check(logs.any { it.contains("Snapshot control подтверждён", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 26) snapshot cancellation is blocked while merge is active, before the control command.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYmerging")
        check(!p.sendCommand("snapshot-update:cancel"))
        check(UsbScript.commands.none { it == "snapshot-update:cancel" })
    }

    // 27) snapshot merge completion request is accepted only from MERGING and verifies NONE.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYmerging", "OKAY", "OKAYnone")
        check(p.sendCommand("snapshot-update:merge"))
        check(UsbScript.commands.any { it == "snapshot-update:merge" })
        check(logs.any { it.contains("snapshot-update-status=NONE", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 28) bootloader relock is blocked before any lock command reaches the wire.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone")
        check(!p.sendCommand("flashing lock"))
        check(UsbScript.commands.none { it == "flashing lock" })
        check(logs.any { it.contains("relock is blocked", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 29) ordinary unlock receives fresh identity/snapshot preflight before the wire command.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone", "OKAY")
        check(p.sendCommand("flashing unlock"))
        val unlockIndex = UsbScript.commands.indexOf("flashing unlock")
        check(unlockIndex >= 0)
        check(UsbScript.commands.subList(0, unlockIndex).takeLast(3) == listOf(
            "getvar:product", "getvar:serialno", "getvar:snapshot-update-status"
        )) { UsbScript.commands }
    }

    // 30) wipe-super is a host-side helper and is never sent as a raw device command.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        val before = UsbScript.commands.size
        check(!p.sendCommand("wipe-super"))
        check(UsbScript.commands.size == before)
        check(logs.any { it.contains("host-side helper", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 31) Mi Unlock staging also obeys mutation preflight and cannot enter DATA during merge.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYmerging")
        check(!p.stageAndOemUnlock(small))
        check(UsbScript.commands.none { it.startsWith("download:") }) { UsbScript.commands }
        check(logs.any { it.contains("stopped before DATA", ignoreCase = true) || it.contains("остановлен до DATA", ignoreCase = true) }) {
            logs.takeLast(20)
        }
    }

    // 32) Opaque destructive OEM control receives central snapshot preflight and is blocked during merge.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYmerging")
        check(!p.sendCommand("oem factory_reset"))
        check(UsbScript.commands.none { it == "oem factory_reset" }) { UsbScript.commands }
        check(logs.any { it.contains("snapshot merge active", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 33) GSI control is blocked outside confirmed fastbootd before the wire command.
    run {
        Build.VERSION.SDK_INT = 34
        UsbScript.reset(); val logs = mutableListOf<String>(); val p = newProtocol(logs)
        UsbScript.enqueue("OKAYonyx", "OKAYserial", "OKAYnone")
        check(!p.sendCommand("gsi:wipe"))
        check(UsbScript.commands.none { it == "gsi:wipe" }) { UsbScript.commands }
        check(logs.any { it.contains("requires confirmed fastbootd/userspace", ignoreCase = true) }) { logs.takeLast(20) }
    }

    // 34) V5.6.6 staging identity is content-bound and free-space policy is fail-closed.
    run {
        val a = "sha256:${"a".repeat(64)}:bytes=104857600"
        val b = "sha256:${"b".repeat(64)}:bytes=104857600"
        check(FastbootDataStagingPolicy.stagedFileName(a) != FastbootDataStagingPolicy.stagedFileName(b)) {
            "same-size different artifacts must never share a staged filename"
        }
        check(FastbootDataStagingPolicy.stagedFileName(a).endsWith("-bytes-104857600.ready"))
        val required = FastbootDataStagingPolicy.requiredFreeBytes(100L * 1024L * 1024L)
        check(required == 132L * 1024L * 1024L) { required }
        check(FastbootDataStagingPolicy.hasEnoughSpace(100L * 1024L * 1024L, required))
        check(!FastbootDataStagingPolicy.hasEnoughSpace(100L * 1024L * 1024L, required - 1L))
        check(!FastbootDataStagingPolicy.hasEnoughSpace(100L * 1024L * 1024L, 0L))
    }

    // 35) PartitionNameResolver classifies filenames but never invents a hard target.
    run {
        // exact base names -> confident single partition
        check(PartitionNameResolver.suggest("recovery.img") == "recovery")
        check(PartitionNameResolver.suggest("boot_a.img") == "boot")
        check(PartitionNameResolver.suggest("vbmeta_system.img") == "vbmeta_system")
        check(PartitionNameResolver.suggest("recovery.img.prefix-1048576.img") == "recovery")
        check(PartitionNameResolver.resolve("init_boot.bin").kind == PartitionNameResolver.Kind.EXACT_PARTITION)

        // custom-recovery brands -> topology-dependent, candidates include boot/vendor_boot,
        // and NO single confident partition (must not blindly say "recovery" on A/B)
        for (fn in listOf(
            "OrangeFox-alioth-stable@R11.1_5_2.img",
            "twrp-3.7.0_12-0-onyx.img",
            "PBRP-alioth-4.0-20240324-0051.img",
            "SHRP_onyx_v3.1.img",
            "orangefox11.3_48294.img",
        )) {
            val r = PartitionNameResolver.resolve(fn)
            check(r.kind == PartitionNameResolver.Kind.RECOVERY_IMAGE) { fn }
            check(r.partition == null) { "recovery-brand must not pin a single partition: $fn" }
            check(r.candidates.contains("boot") && r.candidates.contains("vendor_boot")) { fn }
            check(PartitionNameResolver.suggest(fn) == null) { fn }
        }

        // archives are sideload/recovery installs, not fastboot flash
        check(PartitionNameResolver.resolve("OrangeFox-alioth-R11.1.zip").kind == PartitionNameResolver.Kind.ARCHIVE)
        check(PartitionNameResolver.resolve("some-rom.zip").kind == PartitionNameResolver.Kind.ARCHIVE)

        // unknown / ambiguous names are not guessed
        check(PartitionNameResolver.resolve("payload.bin").kind == PartitionNameResolver.Kind.UNKNOWN)
        check(PartitionNameResolver.suggest("payload.bin") == null)
        check(PartitionNameResolver.resolve("").kind == PartitionNameResolver.Kind.UNKNOWN)
    }

    println("FASTBOOT CORE TESTS: OK")
    small.delete()
    large.delete()
    self64.delete()
    exact6MiB.delete()
}
