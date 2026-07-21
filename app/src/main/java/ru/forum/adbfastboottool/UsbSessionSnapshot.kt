package ru.forum.adbfastboottool

import android.hardware.usb.UsbConstants
import android.os.Build

/** Immutable USB descriptor snapshot captured before protocol handshake. */
data class UsbSessionSnapshot(
    val sessionId: String,
    val capturedAtMs: Long,
    val mode: String,
    val matchKind: String,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val productName: String?,
    val selectedInterfaceIndex: Int,
    val selectedEndpointIn: Int,
    val selectedEndpointOut: Int,
    val interfaces: List<InterfaceSnapshot>,
    val hostSdk: Int,
    val hostRelease: String,
    val hostManufacturer: String,
    val hostModel: String,
    val hostDevice: String
) {
    data class InterfaceSnapshot(
        val index: Int,
        val id: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val endpoints: List<EndpointSnapshot>
    )

    data class EndpointSnapshot(
        val index: Int,
        val address: Int,
        val direction: String,
        val type: Int,
        val maxPacketSize: Int
    )

    fun toText(): String = buildString {
        appendLine("NekoFlash USB session snapshot")
        appendLine("Session ID: $sessionId")
        appendLine("Captured: $capturedAtMs")
        appendLine("Mode: $mode / $matchKind")
        appendLine("VID:PID: ${vendorId.toString(16).uppercase().padStart(4, '0')}:${productId.toString(16).uppercase().padStart(4, '0')}")
        appendLine("Device: ${productName ?: deviceName}")
        appendLine("Selected interface: $selectedInterfaceIndex")
        appendLine("Selected endpoints: IN=0x${selectedEndpointIn.toString(16).uppercase()} OUT=0x${selectedEndpointOut.toString(16).uppercase()}")
        appendLine("Host: Android $hostRelease (SDK $hostSdk), $hostManufacturer $hostModel / $hostDevice")
        appendLine("Interfaces: ${interfaces.size}")
        interfaces.forEach { iface ->
            appendLine("- interface[${iface.index}] id=${iface.id} class=${iface.interfaceClass}/${iface.interfaceSubclass}/${iface.interfaceProtocol}")
            iface.endpoints.forEach { ep ->
                appendLine("  endpoint[${ep.index}] address=0x${ep.address.toString(16).uppercase()} direction=${ep.direction} type=${ep.type} maxPacket=${ep.maxPacketSize}")
            }
        }
    }

    fun toJson(): String {
        val q = DiagnosticJson::quote
        val interfaceJson = interfaces.joinToString(",\n") { iface ->
            val endpointJson = iface.endpoints.joinToString(",\n") { ep ->
                "        {\"index\":${ep.index},\"address\":${ep.address},\"direction\":${q(ep.direction)},\"type\":${ep.type},\"maxPacketSize\":${ep.maxPacketSize}}"
            }
            buildString {
                appendLine("    {")
                appendLine("      \"index\": ${iface.index},")
                appendLine("      \"id\": ${iface.id},")
                appendLine("      \"class\": ${iface.interfaceClass},")
                appendLine("      \"subclass\": ${iface.interfaceSubclass},")
                appendLine("      \"protocol\": ${iface.interfaceProtocol},")
                appendLine("      \"endpoints\": [")
                if (endpointJson.isNotBlank()) appendLine(endpointJson)
                append("      ]\n    }")
            }
        }
        return buildString {
            appendLine("{")
            appendLine("  \"schema\": \"ru.forum.adbfastboottool.usb-session.v1\",")
            appendLine("  \"sessionId\": ${q(sessionId)},")
            appendLine("  \"capturedAtMs\": $capturedAtMs,")
            appendLine("  \"mode\": ${q(mode)},")
            appendLine("  \"matchKind\": ${q(matchKind)},")
            appendLine("  \"vendorId\": $vendorId,")
            appendLine("  \"productId\": $productId,")
            appendLine("  \"deviceName\": ${q(deviceName)},")
            appendLine("  \"productName\": ${q(productName)},")
            appendLine("  \"selectedInterfaceIndex\": $selectedInterfaceIndex,")
            appendLine("  \"selectedEndpointIn\": $selectedEndpointIn,")
            appendLine("  \"selectedEndpointOut\": $selectedEndpointOut,")
            appendLine("  \"host\": {\"sdk\":$hostSdk,\"release\":${q(hostRelease)},\"manufacturer\":${q(hostManufacturer)},\"model\":${q(hostModel)},\"device\":${q(hostDevice)}},")
            appendLine("  \"interfaces\": [")
            if (interfaceJson.isNotBlank()) appendLine(interfaceJson)
            appendLine("  ]")
            appendLine("}")
        }
    }

    companion object {
        fun capture(sessionId: String, candidate: UsbDeviceInspector.Candidate): UsbSessionSnapshot {
            val device = candidate.device
            val interfaces = (0 until device.interfaceCount).map { index ->
                val iface = device.getInterface(index)
                InterfaceSnapshot(
                    index = index,
                    id = iface.id,
                    interfaceClass = iface.interfaceClass,
                    interfaceSubclass = iface.interfaceSubclass,
                    interfaceProtocol = iface.interfaceProtocol,
                    endpoints = (0 until iface.endpointCount).map { endpointIndex ->
                        val ep = iface.getEndpoint(endpointIndex)
                        EndpointSnapshot(
                            index = endpointIndex,
                            address = ep.address,
                            direction = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                            type = ep.type,
                            maxPacketSize = ep.maxPacketSize
                        )
                    }
                )
            }
            return UsbSessionSnapshot(
                sessionId = sessionId,
                capturedAtMs = System.currentTimeMillis(),
                mode = candidate.mode.name,
                matchKind = candidate.matchKind.name,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceName = device.deviceName,
                productName = device.productName,
                selectedInterfaceIndex = candidate.interfaceIndex,
                selectedEndpointIn = candidate.endpointInAddress,
                selectedEndpointOut = candidate.endpointOutAddress,
                interfaces = interfaces,
                hostSdk = Build.VERSION.SDK_INT,
                hostRelease = Build.VERSION.RELEASE,
                hostManufacturer = Build.MANUFACTURER,
                hostModel = Build.MODEL,
                hostDevice = Build.DEVICE
            )
        }
    }
}
