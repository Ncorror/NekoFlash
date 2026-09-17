package io.github.ncorror.nekoflash.transport.usb.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.util.UUID

/**
 * Evidence-only Android USB owner for the first Phase 2 slice.
 *
 * It deliberately stops after descriptor capture and a reversible open/claim/release probe.
 * It does not send ADB/Fastboot bytes and does not classify vendors as supported/unsupported.
 */
class UsbEvidenceProbe(
    context: Context,
    private val diagnostics: DiagnosticSink,
    private val monotonicNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val permissionAction = "${appContext.packageName}.USB_EVIDENCE_PERMISSION.${UUID.randomUUID()}"
    private var receiverRegistered = false
    private var permissionListener: ((UsbPermissionResult) -> Unit)? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return
            val device = intent.usbDeviceExtra()
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            emit(
                code = if (granted) "permission_granted" else "permission_denied",
                fields = mapOf(
                    "deviceName" to (device?.deviceName ?: "<missing>"),
                    "deviceId" to (device?.deviceId?.toString() ?: "<missing>"),
                ),
            )
            permissionListener?.invoke(UsbPermissionResult(device?.deviceName, granted))
        }
    }

    fun start(onPermissionResult: ((UsbPermissionResult) -> Unit)? = null) {
        permissionListener = onPermissionResult
        if (receiverRegistered) return
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerLegacyPermissionReceiver(filter)
        }
        receiverRegistered = true
        emit("probe_started")
    }

    fun scan(): List<UsbDeviceEvidence> {
        val devices = usbManager.deviceList.values
            .sortedWith(compareBy<UsbDevice>({ it.vendorId }, { it.productId }, { it.deviceId }))
            .map { device -> AndroidUsbEvidenceMapper.map(device, usbManager.hasPermission(device)) }

        emit("scan_completed", mapOf("deviceCount" to devices.size.toString()))
        devices.forEach(::emitDeviceEvidence)
        return devices
    }

    fun requestPermission(deviceName: String): UsbPermissionRequestResult {
        val device = currentDevice(deviceName)
            ?: return UsbPermissionRequestResult.DeviceMissing(deviceName).also {
                emit("permission_device_missing", mapOf("deviceName" to deviceName))
            }
        if (usbManager.hasPermission(device)) {
            emit("permission_already_granted", mapOf("deviceName" to deviceName))
            return UsbPermissionRequestResult.AlreadyGranted(deviceName)
        }

        return runCatching {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val intent = Intent(permissionAction).setPackage(appContext.packageName)
            val pendingIntent = PendingIntent.getBroadcast(appContext, device.deviceId, intent, flags)
            usbManager.requestPermission(device, pendingIntent)
            emit(
                "permission_requested",
                mapOf(
                    "deviceName" to deviceName,
                    "deviceId" to device.deviceId.toString(),
                    "vidPid" to "%04X:%04X".format(device.vendorId, device.productId),
                ),
            )
            UsbPermissionRequestResult.Requested(deviceName)
        }.getOrElse { error ->
            emit(
                "permission_request_failed",
                mapOf("deviceName" to deviceName, "error" to error.javaClass.simpleName),
            )
            UsbPermissionRequestResult.Failed(deviceName, error.javaClass.simpleName)
        }
    }

    fun probeOpenAndClaim(deviceName: String): UsbOpenClaimResult {
        val device = currentDevice(deviceName)
            ?: return UsbOpenClaimResult.DeviceMissing(deviceName).also {
                emit("open_device_missing", mapOf("deviceName" to deviceName))
            }
        if (!usbManager.hasPermission(device)) {
            emit("open_permission_required", mapOf("deviceName" to deviceName))
            return UsbOpenClaimResult.PermissionRequired(deviceName)
        }

        val evidence = AndroidUsbEvidenceMapper.map(device, permissionGranted = true)
        val candidateIndexes = evidence.bulkPairInterfaceIndexes
        if (candidateIndexes.isEmpty()) {
            emit("claim_no_bulk_pair", baseFields(evidence))
            return UsbOpenClaimResult.NoBulkPair(deviceName)
        }

        val connection = try {
            usbManager.openDevice(device)
        } catch (error: SecurityException) {
            emit("open_failed", baseFields(evidence) + ("error" to error.javaClass.simpleName))
            return UsbOpenClaimResult.OpenFailed(deviceName, error.javaClass.simpleName)
        }
        if (connection == null) {
            emit("open_failed", baseFields(evidence) + ("error" to "null_connection"))
            return UsbOpenClaimResult.OpenFailed(deviceName, "null_connection")
        }

        emit("open_succeeded", baseFields(evidence))
        try {
            for (interfaceIndex in candidateIndexes) {
                val usbInterface = device.getInterface(interfaceIndex)
                val claimed = runCatching { connection.claimInterface(usbInterface, false) }
                    .getOrElse { error ->
                        emit(
                            "claim_exception",
                            baseFields(evidence) + mapOf(
                                "interfaceIndex" to interfaceIndex.toString(),
                                "interfaceId" to usbInterface.id.toString(),
                                "error" to error.javaClass.simpleName,
                            ),
                        )
                        false
                    }
                emit(
                    "claim_result",
                    baseFields(evidence) + mapOf(
                        "interfaceIndex" to interfaceIndex.toString(),
                        "interfaceId" to usbInterface.id.toString(),
                        "interfaceClass" to usbInterface.interfaceClass.toString(),
                        "interfaceSubclass" to usbInterface.interfaceSubclass.toString(),
                        "interfaceProtocol" to usbInterface.interfaceProtocol.toString(),
                        "claimed" to claimed.toString(),
                        "force" to "false",
                    ),
                )
                if (claimed) {
                    val released = runCatching { connection.releaseInterface(usbInterface) }.getOrDefault(false)
                    emit(
                        "release_result",
                        baseFields(evidence) + mapOf(
                            "interfaceIndex" to interfaceIndex.toString(),
                            "released" to released.toString(),
                        ),
                    )
                    return UsbOpenClaimResult.ClaimSucceeded(deviceName, interfaceIndex, usbInterface.id)
                }
            }
            return UsbOpenClaimResult.ClaimFailed(deviceName, candidateIndexes)
        } finally {
            connection.close()
            emit("connection_closed", baseFields(evidence))
        }
    }

    override fun close() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
            receiverRegistered = false
        }
        permissionListener = null
        emit("probe_stopped")
    }

    private fun currentDevice(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

    private fun emitDeviceEvidence(device: UsbDeviceEvidence) {
        emit("device_seen", baseFields(device) + mapOf(
            "deviceClass" to device.deviceClass.toString(),
            "deviceSubclass" to device.deviceSubclass.toString(),
            "deviceProtocol" to device.deviceProtocol.toString(),
            "permissionGranted" to device.permissionGranted.toString(),
            "interfaceCount" to device.interfaces.size.toString(),
            "bulkPairInterfaces" to device.bulkPairInterfaceIndexes.joinToString(","),
        ))
        device.interfaces.forEach { usbInterface ->
            emit(
                "interface_seen",
                baseFields(device) + mapOf(
                    "interfaceIndex" to usbInterface.index.toString(),
                    "interfaceId" to usbInterface.id.toString(),
                    "interfaceClass" to usbInterface.interfaceClass.toString(),
                    "interfaceSubclass" to usbInterface.interfaceSubclass.toString(),
                    "interfaceProtocol" to usbInterface.interfaceProtocol.toString(),
                    "bulkIn" to usbInterface.hasBulkIn.toString(),
                    "bulkOut" to usbInterface.hasBulkOut.toString(),
                    "endpoints" to usbInterface.endpoints.joinToString(";") { endpoint ->
                        "0x%02X:%s:%s:mps=%d".format(
                            endpoint.address,
                            endpoint.direction,
                            endpoint.transferType,
                            endpoint.maxPacketSize,
                        )
                    },
                ),
            )
        }
    }

    private fun baseFields(device: UsbDeviceEvidence): Map<String, String> = mapOf(
        "deviceName" to device.deviceName,
        "deviceId" to device.deviceId.toString(),
        "vidPid" to device.vidPid(),
    )

    private fun emit(code: String, fields: Map<String, String> = emptyMap()) {
        diagnostics.emit(
            DiagnosticEvent(
                monotonicNanos = monotonicNanos(),
                category = "usb_evidence",
                code = code,
                fields = fields,
            ),
        )
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Suppress("DEPRECATION")
    private fun registerLegacyPermissionReceiver(filter: IntentFilter) {
        appContext.registerReceiver(permissionReceiver, filter)
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
