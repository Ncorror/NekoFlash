package ru.forum.adbfastboottool

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic-only native USBFS DATA sender for the Fastboot diagnostic matrix.
 *
 * The backend has a single-transfer gate, tokenized cancellation, separate stall
 * and hard deadlines, strict DISCARDURB -> REAP-before-free semantics, explicit
 * poisoned-state reporting and structured transport diagnostics. A successful
 * transfer remains diagnostic evidence only and never authorizes a real
 * flash/boot/update-super mutation.
 */
object NativeUsbfsBackend {
    data class BackendState(
        val poisoned: Boolean,
        val nativeTransferActive: Boolean,
        val nativeTransferToken: Long
    )

    data class TransferResult(
        val success: Boolean,
        /** Bytes confirmed by normal completions before stop/drain began.
         * Drain completions are intentionally not counted as accepted payload bytes. */
        val bytesTransferred: Long,
        /** Bytes read from the file and successfully submitted as URBs. */
        val submittedBytes: Long,
        /** Logical stop reason errno: watchdog, cancellation, submit/read failure, etc. */
        val errnoCode: Int,
        /** Actual errno returned by a USB ioctl, or zero when no ioctl failed. */
        val kernelIoctlErrno: Int,
        /** Status of the last URB that was successfully reaped before stop. */
        val urbStatus: Int,
        /** actual_length of the last URB that was successfully reaped before stop. */
        val actualLength: Int,
        /** Number of submitted URBs still pending when shutdown/drain started. */
        val pendingUrbCountAtStop: Int,
        /** Age of the last confirmed completion when the transfer stopped. */
        val lastCompletionAgeMs: Long,
        /** DRAIN_NOT_NEEDED, DRAIN_REAPED or DRAIN_FAILED. */
        val drainState: Int,
        val drainErrno: Int,
        val backendPoisoned: Boolean,
        val elapsedMs: Long,
        val stage: Int,
        val message: String
    )

    private val loadFailure: Throwable? = runCatching {
        System.loadLibrary("nekoflash_usbfs")
    }.exceptionOrNull()

    private val nextTransferToken = AtomicLong(1L)
    private val activeTransferToken = AtomicLong(0L)

    val isAvailable: Boolean
        get() = loadFailure == null

    val unavailableReason: String
        get() = loadFailure?.let { "${it.javaClass.simpleName}: ${it.message ?: "<empty>"}" }
            ?: "available"

    val hasActiveTransfer: Boolean
        get() = activeTransferToken.get() != NO_ACTIVE_TRANSFER

    fun backendState(): BackendState {
        if (!isAvailable) return BackendState(poisoned = false, nativeTransferActive = false, nativeTransferToken = 0L)
        val raw = runCatching { nativeBackendState() }.getOrNull()
        return BackendState(
            poisoned = raw?.getOrNull(0) == 1L,
            nativeTransferActive = raw?.getOrNull(1) == 1L,
            nativeTransferToken = raw?.getOrNull(2) ?: 0L
        )
    }

    val isBackendPoisoned: Boolean
        get() = backendState().poisoned

    fun preflightError(connection: UsbDeviceConnection?, outEndpoint: UsbEndpoint?): String? {
        if (!isAvailable) return "Native USBFS library unavailable: $unavailableReason"
        val state = backendState()
        if (state.poisoned) {
            return "Native USBFS is blocked after an unproven URB drain; fully restart NekoFlash before another native transfer"
        }
        if (hasActiveTransfer || state.nativeTransferActive) {
            return "Native USBFS already has an active transfer; wait for cancellation/drain to finish"
        }
        if (connection == null) return "Native USBFS requires an open UsbDeviceConnection"
        if (outEndpoint == null) return "Native USBFS requires a Fastboot bulk OUT endpoint"
        val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        if (fd < 0) return "Native USBFS could not obtain UsbDeviceConnection file descriptor"
        return null
    }

    fun transferBulkOutUrb(
        connection: UsbDeviceConnection,
        outEndpoint: UsbEndpoint,
        payloadFile: File,
        blockBytes: Int,
        pipelineDepth: Int,
        stallTimeoutMs: Int,
        hardTimeoutMs: Int
    ): TransferResult {
        val preflight = preflightError(connection, outEndpoint)
        if (preflight != null) return failedPreflight(preflight)
        if (!payloadFile.exists() || !payloadFile.isFile || !payloadFile.canRead()) {
            return failedPreflight("Payload file is not readable: ${payloadFile.absolutePath}")
        }
        if (stallTimeoutMs <= 0 || hardTimeoutMs <= 0 || hardTimeoutMs < stallTimeoutMs) {
            return failedPreflight(
                "Invalid Native USBFS deadlines: stall=${stallTimeoutMs}ms hard=${hardTimeoutMs}ms"
            )
        }

        val token = nextToken()
        if (!activeTransferToken.compareAndSet(NO_ACTIVE_TRANSFER, token)) {
            return failedPreflight(
                message = "Native USBFS transfer rejected: another transfer is still active or draining",
                errno = ERRNO_EBUSY
            )
        }

        val raw = try {
            nativeBulkOutUrb(
                connection.fileDescriptor,
                outEndpoint.address,
                payloadFile.absolutePath,
                payloadFile.length(),
                blockBytes,
                pipelineDepth,
                stallTimeoutMs,
                hardTimeoutMs,
                token
            )
        } catch (error: Throwable) {
            return TransferResult(
                success = false,
                bytesTransferred = 0L,
                submittedBytes = 0L,
                errnoCode = 0,
                kernelIoctlErrno = 0,
                urbStatus = 0,
                actualLength = 0,
                pendingUrbCountAtStop = 0,
                lastCompletionAgeMs = 0L,
                drainState = DRAIN_NOT_NEEDED,
                drainErrno = 0,
                backendPoisoned = isBackendPoisoned,
                elapsedMs = 0L,
                stage = STAGE_JNI,
                message = "Native USBFS JNI error: ${error.javaClass.name}: ${error.message ?: "<empty>"}"
            )
        } finally {
            activeTransferToken.compareAndSet(token, NO_ACTIVE_TRANSFER)
        }

        val success = raw.getOrNull(IDX_SUCCESS) == 1L
        val confirmed = raw.getOrNull(IDX_CONFIRMED_BYTES) ?: 0L
        val errnoCode = (raw.getOrNull(IDX_STOP_ERRNO) ?: 0L).toInt()
        val urbStatus = (raw.getOrNull(IDX_LAST_STATUS) ?: 0L).toInt()
        val actual = (raw.getOrNull(IDX_LAST_ACTUAL) ?: 0L).toInt()
        val elapsedMs = raw.getOrNull(IDX_ELAPSED_MS) ?: 0L
        val stage = (raw.getOrNull(IDX_STAGE) ?: STAGE_UNKNOWN.toLong()).toInt()
        val submitted = raw.getOrNull(IDX_SUBMITTED_BYTES) ?: confirmed
        val pendingAtStop = (raw.getOrNull(IDX_PENDING_AT_STOP) ?: 0L).toInt()
        val lastCompletionAgeMs = raw.getOrNull(IDX_LAST_COMPLETION_AGE_MS) ?: 0L
        val drainState = (raw.getOrNull(IDX_DRAIN_STATE) ?: DRAIN_NOT_NEEDED.toLong()).toInt()
        val drainErrno = (raw.getOrNull(IDX_DRAIN_ERRNO) ?: 0L).toInt()
        val poisoned = raw.getOrNull(IDX_BACKEND_POISONED) == 1L || backendState().poisoned
        val kernelIoctlErrno = (raw.getOrNull(IDX_KERNEL_IOCTL_ERRNO) ?: 0L).toInt()

        val structured =
            "stage=${stageLabel(stage)}, confirmedBeforeStop=${formatBytes(confirmed)}, submitted=${formatBytes(submitted)}, " +
                "stopErrno=$errnoCode, kernelIoctlErrno=$kernelIoctlErrno, " +
                "lastCompletedUrbStatus=$urbStatus, lastCompletedUrbActual=$actual, " +
                "pendingAtStop=$pendingAtStop, lastCompletionAgeMs=$lastCompletionAgeMs, " +
                "drain=${drainLabel(drainState)}, drainErrno=$drainErrno, backendPoisoned=$poisoned, " +
                "elapsed=${formatDuration(elapsedMs)}"
        val message = if (success) {
            "Native USBFS URB transfer OK: $structured"
        } else {
            "Native USBFS URB transfer FAIL: $structured"
        }
        return TransferResult(
            success = success,
            bytesTransferred = confirmed,
            submittedBytes = submitted,
            errnoCode = errnoCode,
            kernelIoctlErrno = kernelIoctlErrno,
            urbStatus = urbStatus,
            actualLength = actual,
            pendingUrbCountAtStop = pendingAtStop,
            lastCompletionAgeMs = lastCompletionAgeMs,
            drainState = drainState,
            drainErrno = drainErrno,
            backendPoisoned = poisoned,
            elapsedMs = elapsedMs,
            stage = stage,
            message = message
        )
    }

    /**
     * Requests cancellation of the currently blocking native transfer.
     * Completion is asynchronous: callers must keep the operation marked active
     * until transferBulkOutUrb() returns after DISCARDURB + REAP.
     */
    fun cancelActiveTransfer(): Boolean {
        val token = activeTransferToken.get()
        if (token == NO_ACTIVE_TRANSFER || !isAvailable) return false
        return runCatching { nativeCancelTransfer(token) }.getOrDefault(false)
    }

    private fun failedPreflight(message: String, errno: Int = 0): TransferResult = TransferResult(
        success = false,
        bytesTransferred = 0L,
        submittedBytes = 0L,
        errnoCode = errno,
        kernelIoctlErrno = 0,
        urbStatus = 0,
        actualLength = 0,
        pendingUrbCountAtStop = 0,
        lastCompletionAgeMs = 0L,
        drainState = DRAIN_NOT_NEEDED,
        drainErrno = 0,
        backendPoisoned = isBackendPoisoned,
        elapsedMs = 0L,
        stage = STAGE_PREFLIGHT,
        message = message
    )

    private fun nextToken(): Long {
        while (true) {
            val current = nextTransferToken.getAndIncrement()
            if (current > 0L) return current
            nextTransferToken.compareAndSet(current + 1L, 1L)
        }
    }

    private external fun nativeBulkOutUrb(
        fd: Int,
        endpointAddress: Int,
        payloadPath: String,
        totalBytes: Long,
        blockBytes: Int,
        pipelineDepth: Int,
        stallTimeoutMs: Int,
        hardTimeoutMs: Int,
        transferToken: Long
    ): LongArray

    private external fun nativeCancelTransfer(transferToken: Long): Boolean
    private external fun nativeBackendState(): LongArray
    private external fun nativeUsbfsResetDevice(fd: Int): Int

    /**
     * Issues USBDEVFS_RESET on the device fd to clear a wedged bulk endpoint, mirroring
     * upstream fastboot's LinuxUsbTransport::Reset(). Returns 0 on success, a positive
     * errno on ioctl failure, or -1 when the fd is unavailable. On success the given
     * [connection] is invalidated and the device must be re-enumerated by the caller.
     */
    fun resetUsbDevice(connection: UsbDeviceConnection): Int {
        val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        if (fd < 0) return -1
        return runCatching { nativeUsbfsResetDevice(fd) }.getOrDefault(-1)
    }

    fun stageLabel(stage: Int): String = when (stage) {
        STAGE_PREFLIGHT -> "preflight"
        STAGE_OPEN -> "open"
        STAGE_READ -> "read"
        STAGE_SUBMIT -> "submit_urb"
        STAGE_REAP -> "reap_urb"
        STAGE_TIMEOUT -> "stall_timeout"
        STAGE_STATUS -> "urb_status"
        STAGE_LENGTH -> "length_mismatch"
        STAGE_JNI -> "jni"
        STAGE_DONE -> "done"
        STAGE_CANCELLED -> "cancelled"
        STAGE_HARD_TIMEOUT -> "hard_timeout"
        STAGE_DRAIN -> "drain_failed_backend_poisoned"
        else -> "unknown"
    }

    fun drainLabel(state: Int): String = when (state) {
        DRAIN_NOT_NEEDED -> "not_needed"
        DRAIN_REAPED -> "reaped"
        DRAIN_FAILED -> "failed"
        else -> "unknown"
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 0L -> "unknown"
        ms >= 1000L -> String.format(Locale.US, "%.3fs", ms / 1000.0)
        else -> "${ms}ms"
    }

    const val STAGE_PREFLIGHT = 1
    const val STAGE_OPEN = 2
    const val STAGE_READ = 3
    const val STAGE_SUBMIT = 4
    const val STAGE_REAP = 5
    const val STAGE_TIMEOUT = 6
    const val STAGE_STATUS = 7
    const val STAGE_LENGTH = 8
    const val STAGE_JNI = 9
    const val STAGE_DONE = 10
    const val STAGE_CANCELLED = 11
    const val STAGE_HARD_TIMEOUT = 12
    const val STAGE_DRAIN = 13
    const val STAGE_UNKNOWN = 99

    const val DRAIN_NOT_NEEDED = 0
    const val DRAIN_REAPED = 1
    const val DRAIN_FAILED = 2

    private const val IDX_SUCCESS = 0
    private const val IDX_CONFIRMED_BYTES = 1
    private const val IDX_STOP_ERRNO = 2
    private const val IDX_LAST_STATUS = 3
    private const val IDX_LAST_ACTUAL = 4
    private const val IDX_ELAPSED_MS = 5
    private const val IDX_STAGE = 6
    private const val IDX_SUBMITTED_BYTES = 7
    private const val IDX_PENDING_AT_STOP = 8
    private const val IDX_LAST_COMPLETION_AGE_MS = 9
    private const val IDX_DRAIN_STATE = 10
    private const val IDX_DRAIN_ERRNO = 11
    private const val IDX_BACKEND_POISONED = 12
    private const val IDX_KERNEL_IOCTL_ERRNO = 13

    private const val NO_ACTIVE_TRANSFER = 0L
    private const val ERRNO_EBUSY = 16
}
