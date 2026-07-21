package ru.forum.adbfastboottool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Единый источник истины для обнаружения ADB/Fastboot USB-интерфейсов.
 *
 * Важно: MainActivity и протоколы не должны повторно угадывать режим своими
 * эвристиками. Они получают выбранный здесь interfaceIndex и работают именно
 * с этим интерфейсом.
 */
object UsbDeviceInspector {

    enum class Mode(val label: String) {
        FASTBOOT("Fastboot"),
        ADB("ADB")
    }

    enum class MatchKind(val rank: Int, val autoConnectSafe: Boolean, val label: String) {
        CANONICAL(0, true, "canonical"),
        COMPAT_FASTBOOT(1, true, "compat"),
        GENERIC_FASTBOOT(2, false, "generic")
    }

    data class Candidate(
        val device: UsbDevice,
        val mode: Mode,
        val interfaceIndex: Int,
        val endpointInAddress: Int,
        val endpointOutAddress: Int,
        val matchKind: MatchKind,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int
    ) {
        val stableKey: String
            get() = buildString {
                append(device.deviceName)
                append(':').append(device.vendorId)
                append(':').append(device.productId)
                append(':').append(mode.name)
                append(':').append(interfaceIndex)
            }

        val logicalSignature: String
            get() = buildString {
                append(device.vendorId)
                append(':').append(device.productId)
                append(':').append(mode.name)
                append(':').append(interfaceClass)
                append(':').append(interfaceSubclass)
                append(':').append(interfaceProtocol)
                append(':').append(endpointInAddress)
                append(':').append(endpointOutAddress)
            }

        fun displayTitle(index: Int? = null): String {
            val prefix = index?.let { "$it. " } ?: ""
            val name = device.productName ?: device.deviceName
            val suffix = if (matchKind == MatchKind.CANONICAL) "" else " (${matchKind.label})"
            return "$prefix${mode.label}$suffix — $name"
        }

        fun displaySubtitle(): String {
            val vid = device.vendorId.toString(16).uppercase().padStart(4, '0')
            val pid = device.productId.toString(16).uppercase().padStart(4, '0')
            val inEp = "0x${endpointInAddress.toString(16).uppercase().padStart(2, '0')}"
            val outEp = "0x${endpointOutAddress.toString(16).uppercase().padStart(2, '0')}"
            return "VID:PID=$vid:$pid | interface=$interfaceIndex | " +
                "class=$interfaceClass/$interfaceSubclass/$interfaceProtocol | IN=$inEp | OUT=$outEp"
        }
    }

    fun findCandidates(device: UsbDevice, includeGenericFastboot: Boolean = true): List<Candidate> {
        val canonical = mutableListOf<Candidate>()
        val compat = mutableListOf<Candidate>()
        val generic = mutableListOf<Candidate>()

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val endpoints = bulkEndpointPair(iface) ?: continue

            when {
                isCanonicalAdb(iface) -> canonical += candidate(
                    device, Mode.ADB, i, iface, endpoints, MatchKind.CANONICAL
                )

                isCanonicalFastboot(iface) -> canonical += candidate(
                    device, Mode.FASTBOOT, i, iface, endpoints, MatchKind.CANONICAL
                )

                isAndroidFastbootCompatible(iface) -> compat += candidate(
                    device, Mode.FASTBOOT, i, iface, endpoints, MatchKind.COMPAT_FASTBOOT
                )

                includeGenericFastboot && isGenericVendorBulkPair(iface) -> generic += candidate(
                    device, Mode.FASTBOOT, i, iface, endpoints, MatchKind.GENERIC_FASTBOOT
                )
            }
        }

        // Если устройство честно объявило ADB, неизвестные vendor-интерфейсы рядом
        // не должны превращать его в Fastboot. Это типично для composite USB.
        val canonicalAdb = canonical.filter { it.mode == Mode.ADB }
        if (canonicalAdb.isNotEmpty()) return canonicalAdb

        val canonicalFastboot = canonical.filter { it.mode == Mode.FASTBOOT }
        if (canonicalFastboot.isNotEmpty()) return canonicalFastboot

        if (compat.isNotEmpty()) return compat
        return generic
    }

    /** Автоподключение принимает только канонический/проверенный compat-кандидат. */
    fun selectPrimaryCandidate(device: UsbDevice, allowGenericFastboot: Boolean = false): Candidate? {
        val candidates = findCandidates(device, includeGenericFastboot = allowGenericFastboot)
        return candidates
            .filter { allowGenericFastboot || it.matchKind.autoConnectSafe }
            .minWithOrNull(
                compareBy<Candidate> { it.matchKind.rank }
                    .thenBy { if (it.mode == Mode.ADB) 0 else 1 }
                    .thenBy { it.interfaceIndex }
            )
    }

    fun findAllCandidates(
        devices: Collection<UsbDevice>,
        includeGenericFastboot: Boolean = true
    ): List<Candidate> {
        return devices
            .flatMap { findCandidates(it, includeGenericFastboot) }
            .distinctBy { it.stableKey }
            .sortedWith(
                compareBy<Candidate> { it.matchKind.rank }
                    .thenBy { it.mode.name }
                    .thenBy { it.device.productName ?: it.device.deviceName }
                    .thenBy { it.interfaceIndex }
            )
    }

    /** Только кандидаты, которые можно подключать автоматически без угадывания протокола. */
    fun findSafeCandidates(devices: Collection<UsbDevice>): List<Candidate> =
        findAllCandidates(devices, includeGenericFastboot = false)
            .filter { it.matchKind.autoConnectSafe }

    /**
     * Для re-enumeration после смены режима выбираем только НОВЫЙ логический USB-профиль.
     * Тот же ADB/Fastboot профиль намеренно игнорируется: это не даёт превратить
     * один неудачный handshake в бесконечный attach -> connect -> detach цикл.
     */
    fun selectModeSwitchCandidate(
        devices: Collection<UsbDevice>,
        previousLogicalSignature: String?,
        previousVendorId: Int? = null
    ): Candidate? {
        val changed = findSafeCandidates(devices)
            .filter { previousLogicalSignature == null || it.logicalSignature != previousLogicalSignature }
            .filter { previousVendorId == null || it.device.vendorId == previousVendorId }
        return changed.singleOrNull()
    }

    fun summarizeDevice(device: UsbDevice): String {
        val vid = device.vendorId.toString(16).uppercase().padStart(4, '0')
        val pid = device.productId.toString(16).uppercase().padStart(4, '0')
        val name = device.productName ?: device.deviceName
        val lines = mutableListOf<String>()
        lines += "Device: $name"
        lines += "VID:PID: $vid:$pid"
        lines += "DeviceName: ${device.deviceName}"
        lines += "Interfaces: ${device.interfaceCount}"
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val detected = detectInterface(device, i, includeGenericFastboot = true)
            val mode = detected?.let { "${it.mode.label}/${it.matchKind.label}" } ?: "unknown"
            lines += "  Interface $i: class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} " +
                "protocol=${iface.interfaceProtocol} mode=$mode endpoints=${iface.endpointCount}"
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                val direction = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "bulk" else "type=${ep.type}"
                lines += "    Endpoint $e: address=0x${ep.address.toString(16).uppercase().padStart(2, '0')} " +
                    "direction=$direction $type maxPacket=${ep.maxPacketSize}"
            }
        }
        return lines.joinToString("\n")
    }

    fun summarizeCandidates(candidates: List<Candidate>): String {
        if (candidates.isEmpty()) return "Совместимые ADB/Fastboot USB-устройства не найдены."
        return candidates.mapIndexed { index, candidate ->
            candidate.displayTitle(index + 1) + "\n" + candidate.displaySubtitle()
        }.joinToString("\n\n")
    }

    fun isCandidateInterface(candidate: Candidate): Boolean =
        rebindCandidate(candidate.device, candidate) != null

    /**
     * Перевязывает ранее выбранный интерфейс к актуальному UsbDevice-объекту.
     * Android обычно возвращает тот же дескриптор после USB permission, но OEM
     * host-стеки могут выдать новый объект для того же deviceName. Transport
     * всегда должен открываться через объект, пришедший в последнем broadcast.
     */
    fun rebindCandidate(device: UsbDevice, previous: Candidate): Candidate? {
        if (device.deviceName != previous.device.deviceName) return null
        val includeGeneric = previous.matchKind == MatchKind.GENERIC_FASTBOOT

        val sameIndex = detectInterface(device, previous.interfaceIndex, includeGeneric)
        if (sameIndex != null &&
            sameIndex.mode == previous.mode &&
            sameIndex.matchKind == previous.matchKind
        ) {
            return sameIndex
        }

        val candidates = findCandidates(device, includeGenericFastboot = includeGeneric)
        return candidates.firstOrNull {
            it.mode == previous.mode && it.logicalSignature == previous.logicalSignature
        } ?: candidates.singleOrNull {
            it.mode == previous.mode && it.matchKind == previous.matchKind
        }
    }

    private fun detectInterface(
        device: UsbDevice,
        interfaceIndex: Int,
        includeGenericFastboot: Boolean
    ): Candidate? {
        if (interfaceIndex !in 0 until device.interfaceCount) return null
        val iface = device.getInterface(interfaceIndex)
        val endpoints = bulkEndpointPair(iface) ?: return null
        val match = when {
            isCanonicalAdb(iface) -> Mode.ADB to MatchKind.CANONICAL
            isCanonicalFastboot(iface) -> Mode.FASTBOOT to MatchKind.CANONICAL
            isAndroidFastbootCompatible(iface) -> Mode.FASTBOOT to MatchKind.COMPAT_FASTBOOT
            includeGenericFastboot && isGenericVendorBulkPair(iface) -> Mode.FASTBOOT to MatchKind.GENERIC_FASTBOOT
            else -> return null
        }
        return candidate(device, match.first, interfaceIndex, iface, endpoints, match.second)
    }

    private fun candidate(
        device: UsbDevice,
        mode: Mode,
        interfaceIndex: Int,
        iface: UsbInterface,
        endpoints: Pair<UsbEndpoint, UsbEndpoint>,
        matchKind: MatchKind
    ) = Candidate(
        device = device,
        mode = mode,
        interfaceIndex = interfaceIndex,
        endpointInAddress = endpoints.first.address,
        endpointOutAddress = endpoints.second.address,
        matchKind = matchKind,
        interfaceClass = iface.interfaceClass,
        interfaceSubclass = iface.interfaceSubclass,
        interfaceProtocol = iface.interfaceProtocol
    )

    private fun isCanonicalAdb(iface: UsbInterface): Boolean =
        iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            iface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            iface.interfaceProtocol == ADB_PROTOCOL

    private fun isCanonicalFastboot(iface: UsbInterface): Boolean =
        iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            iface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            iface.interfaceProtocol == FASTBOOT_PROTOCOL

    private fun isAndroidFastbootCompatible(iface: UsbInterface): Boolean =
        iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            iface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            iface.interfaceProtocol != ADB_PROTOCOL &&
            iface.interfaceProtocol != FASTBOOT_PROTOCOL

    private fun isGenericVendorBulkPair(iface: UsbInterface): Boolean =
        iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            iface.interfaceProtocol != ADB_PROTOCOL

    private fun bulkEndpointPair(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN && input == null) input = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT && output == null) output = ep
        }
        return if (input != null && output != null) input to output else null
    }

    private const val ANDROID_USB_SUBCLASS = 0x42
    private const val ADB_PROTOCOL = 0x01
    private const val FASTBOOT_PROTOCOL = 0x03
}
