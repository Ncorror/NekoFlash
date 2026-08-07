package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Pure policy for one-way ADB services.
 *
 * Android's reboot service commonly tears down the USB transport before the host
 * receives A_OKAY/A_CLSE. Once the complete A_OPEN packet was written, an
 * immediate timeout/closed transport is therefore an expected hand-off, not a
 * generic command failure. This exception is deliberately limited to reboot:*
 * and never applies to shell, sync, install or arbitrary services.
 */
object AdbServiceCompletionPolicy {
    enum class TerminalSignal {
        SERVICE_CLOSED,
        HEADER_TIMEOUT,
        TRANSPORT_CLOSED,
        TRANSPORT_FAILURE,
        EXPLICIT_PROTOCOL_FAILURE,
        USER_CANCELLED
    }

    fun normalizeRebootService(target: String?): String {
        val normalized = target.orEmpty().trim().lowercase(Locale.US)
        return when (normalized) {
            "", "system", "systems" -> "reboot:"
            else -> "reboot:$normalized"
        }
    }

    fun expectsOneWayDisconnect(service: String): Boolean =
        service.trim().lowercase(Locale.US).startsWith("reboot:")

    /**
     * Converts the reader's concrete failure into the narrow service-level
     * signal used by the one-way reboot policy. Corrupt/partial packets must
     * never be relabelled as an expected reboot disconnect.
     */
    fun terminalSignalForFailure(
        failureCode: AdbPacketDispatcher.FailureCode?
    ): TerminalSignal? = when (failureCode) {
        null,
        AdbPacketDispatcher.FailureCode.NONE -> null

        AdbPacketDispatcher.FailureCode.USB_IN_TIMEOUT_BUDGET -> TerminalSignal.HEADER_TIMEOUT
        AdbPacketDispatcher.FailureCode.USB_IN_FAILED,
        AdbPacketDispatcher.FailureCode.DEVICE_DISCONNECTED -> TerminalSignal.TRANSPORT_CLOSED

        AdbPacketDispatcher.FailureCode.INVALID_HEADER,
        AdbPacketDispatcher.FailureCode.PARTIAL_HEADER_TIMEOUT,
        AdbPacketDispatcher.FailureCode.INVALID_PAYLOAD,
        AdbPacketDispatcher.FailureCode.CHECKSUM_MISMATCH,
        AdbPacketDispatcher.FailureCode.QUEUE_OVERFLOW,
        AdbPacketDispatcher.FailureCode.DISPATCHER_STOPPED -> TerminalSignal.EXPLICIT_PROTOCOL_FAILURE
    }

    fun isExpectedCompletion(
        service: String,
        openPacketWritten: Boolean,
        signal: TerminalSignal
    ): Boolean {
        if (!openPacketWritten || !expectsOneWayDisconnect(service)) return false
        return signal == TerminalSignal.SERVICE_CLOSED ||
            signal == TerminalSignal.HEADER_TIMEOUT ||
            signal == TerminalSignal.TRANSPORT_CLOSED ||
            signal == TerminalSignal.TRANSPORT_FAILURE
    }
}
