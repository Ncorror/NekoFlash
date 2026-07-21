package ru.forum.adbfastboottool

/**
 * Size-aware and USB-session-aware evidence for Fastboot DATA transports.
 *
 * Core rule:
 * - a small successful download-only test never authorizes a larger mutation;
 * - a qualification is valid only for the same live Fastboot USB generation;
 * - any DATA transport failure invalidates that transport for the current generation;
 * - historical results are retained for diagnostics, but never substitute for a current
 *   same-size-or-larger qualification.
 *
 * Devices with absolutely no evidence history keep the legacy behaviour. As soon as a
 * transport result is recorded (or legacy PASS/FAIL is migrated), real DATA mutation is
 * gated by a current-generation qualification of at least the requested byte count.
 */
object FastbootDataTransportEvidence {
    enum class Transport { ASYNC_USB_REQUEST, SYNC_BULK }

    data class TransportState(
        val hasHistory: Boolean = false,
        val qualifiedBytes: Long = 0L,
        val qualifiedGeneration: String? = null,
        val historicalMaxProvenBytes: Long = 0L,
        val lastFailureRequestedBytes: Long = 0L,
        val lastFailureOffsetBytes: Long = -1L,
        val lastFailureGeneration: String? = null,
        val legacyRequalificationRequired: Boolean = false
    )

    data class State(
        val async: TransportState = TransportState(),
        val sync: TransportState = TransportState()
    )

    fun transportState(state: State, transport: Transport): TransportState = when (transport) {
        Transport.ASYNC_USB_REQUEST -> state.async
        Transport.SYNC_BULK -> state.sync
    }

    private fun withTransportState(state: State, transport: Transport, value: TransportState): State = when (transport) {
        Transport.ASYNC_USB_REQUEST -> state.copy(async = value)
        Transport.SYNC_BULK -> state.copy(sync = value)
    }

    fun recordPass(
        state: State,
        transport: Transport,
        bytes: Long,
        generation: String
    ): State {
        require(bytes > 0L)
        require(generation.isNotBlank())
        val current = transportState(state, transport)
        val sameGeneration = current.qualifiedGeneration == generation
        val qualified = if (sameGeneration) maxOf(current.qualifiedBytes, bytes) else bytes
        return withTransportState(
            state,
            transport,
            current.copy(
                hasHistory = true,
                qualifiedBytes = qualified,
                qualifiedGeneration = generation,
                historicalMaxProvenBytes = maxOf(current.historicalMaxProvenBytes, bytes),
                legacyRequalificationRequired = false
            )
        )
    }

    fun recordFailure(
        state: State,
        transport: Transport,
        requestedBytes: Long,
        failureOffsetBytes: Long,
        generation: String
    ): State {
        require(requestedBytes >= 0L)
        require(failureOffsetBytes >= -1L)
        require(generation.isNotBlank())
        val current = transportState(state, transport)
        return withTransportState(
            state,
            transport,
            current.copy(
                hasHistory = true,
                qualifiedBytes = 0L,
                qualifiedGeneration = null,
                lastFailureRequestedBytes = requestedBytes,
                lastFailureOffsetBytes = failureOffsetBytes,
                lastFailureGeneration = generation,
                legacyRequalificationRequired = false
            )
        )
    }

    /** Conservative migration: an old PASS/FAIL is history only, never a current qualification. */
    fun migrateLegacyOutcome(raw: String?): TransportState = when (raw?.trim()?.uppercase()) {
        "PASS" -> TransportState(hasHistory = true, legacyRequalificationRequired = true)
        "FAIL" -> TransportState(
            hasHistory = true,
            lastFailureRequestedBytes = 0L,
            lastFailureOffsetBytes = -1L
        )
        else -> TransportState()
    }

    fun hasAnyHistory(state: State): Boolean = state.async.hasHistory || state.sync.hasHistory

    fun qualifies(
        transportState: TransportState,
        requiredBytes: Long,
        generation: String
    ): Boolean = requiredBytes > 0L &&
        generation.isNotBlank() &&
        transportState.qualifiedGeneration == generation &&
        transportState.qualifiedBytes >= requiredBytes

    fun preferredTransport(
        state: State,
        requiredBytes: Long,
        generation: String
    ): Transport? {
        val asyncQualified = qualifies(state.async, requiredBytes, generation)
        val syncQualified = qualifies(state.sync, requiredBytes, generation)
        return when {
            asyncQualified && syncQualified -> {
                if (state.sync.qualifiedBytes > state.async.qualifiedBytes) Transport.SYNC_BULK
                else Transport.ASYNC_USB_REQUEST
            }
            asyncQualified -> Transport.ASYNC_USB_REQUEST
            syncQualified -> Transport.SYNC_BULK
            else -> null
        }
    }

    /** Best currently qualified transport for defaults; authorization still checks requested bytes. */
    fun bestQualifiedTransport(state: State, generation: String): Transport? {
        val asyncBytes = if (state.async.qualifiedGeneration == generation) state.async.qualifiedBytes else 0L
        val syncBytes = if (state.sync.qualifiedGeneration == generation) state.sync.qualifiedBytes else 0L
        return when {
            asyncBytes <= 0L && syncBytes <= 0L -> null
            syncBytes > asyncBytes -> Transport.SYNC_BULK
            else -> Transport.ASYNC_USB_REQUEST
        }
    }

    fun realMutationBlockReason(
        state: State,
        requiredBytes: Long,
        generation: String
    ): String? {
        if (requiredBytes <= 0L) {
            return "Неизвестен размер Fastboot DATA-операции. Реальная мутация заблокирована до точного определения размера."
        }
        if (!hasAnyHistory(state)) return null // preserve legacy behaviour for completely untouched products
        if (preferredTransport(state, requiredBytes, generation) != null) return null

        val required = formatBytes(requiredBytes)
        val asyncCurrent = currentQualifiedLabel(state.async, generation)
        val syncCurrent = currentQualifiedLabel(state.sync, generation)
        val failureHint = buildList {
            failureLabel("UsbRequest", state.async)?.let(::add)
            failureLabel("sync bulkTransfer", state.sync)?.let(::add)
        }.joinToString("; ")

        return buildString {
            append("Для DATA-операции размером $required нет квалифицированного транспорта в текущей Fastboot USB-сессии. ")
            append("Текущая квалификация: UsbRequest=$asyncCurrent, sync bulkTransfer=$syncCurrent. ")
            append("Выполните безопасный download-only тест выбранного файла того же размера (или больше) и затем, не переподключая USB, повторите операцию.")
            if (failureHint.isNotBlank()) append(" Последние сбои: $failureHint.")
        }
    }

    fun summary(state: State, generation: String? = null): String =
        "async=${transportSummary(state.async, generation)}, sync=${transportSummary(state.sync, generation)}"

    private fun currentQualifiedLabel(state: TransportState, generation: String): String =
        if (state.qualifiedGeneration == generation && state.qualifiedBytes > 0L) formatBytes(state.qualifiedBytes) else "нет"

    private fun failureLabel(name: String, state: TransportState): String? {
        if (!state.hasHistory || state.lastFailureRequestedBytes <= 0L) return null
        val offset = if (state.lastFailureOffsetBytes >= 0L) formatBytes(state.lastFailureOffsetBytes) else "unknown"
        return "$name requested=${formatBytes(state.lastFailureRequestedBytes)}, offset=$offset"
    }

    private fun transportSummary(state: TransportState, generation: String?): String {
        val qualified = when {
            state.qualifiedBytes <= 0L -> "none"
            generation != null && state.qualifiedGeneration == generation -> "${formatBytes(state.qualifiedBytes)}@current"
            else -> "${formatBytes(state.qualifiedBytes)}@other-session"
        }
        val history = if (state.historicalMaxProvenBytes > 0L) formatBytes(state.historicalMaxProvenBytes) else "none"
        val failure = if (state.lastFailureRequestedBytes > 0L) {
            val offset = if (state.lastFailureOffsetBytes >= 0L) formatBytes(state.lastFailureOffsetBytes) else "unknown"
            "${formatBytes(state.lastFailureRequestedBytes)}@$offset"
        } else "none"
        val legacy = if (state.legacyRequalificationRequired) ",legacy-requal=true" else ""
        return "qualified=$qualified,historicalMax=$history,lastFail=$failure$legacy"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (value >= 100.0 || value % 1.0 == 0.0) "%.0f %s".format(java.util.Locale.US, value, units[unitIndex])
        else "%.2f %s".format(java.util.Locale.US, value, units[unitIndex])
    }
}
