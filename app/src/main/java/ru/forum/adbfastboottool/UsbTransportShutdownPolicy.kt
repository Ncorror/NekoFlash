package ru.forum.adbfastboottool

/**
 * Pure fail-closed predicate shared by the transport owner and FastbootProtocol.
 * Closing UsbDeviceConnection while either side still reports an active native
 * transfer would invalidate memory/file-descriptor lifetime assumptions.
 */
object UsbTransportShutdownPolicy {
    fun canCloseUsb(
        kotlinTransferActive: Boolean,
        nativeTransferActive: Boolean
    ): Boolean = !kotlinTransferActive && !nativeTransferActive
}
