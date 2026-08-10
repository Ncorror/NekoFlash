package ru.forum.adbfastboottool

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifecycle-владелец USB-сессии и пользовательских операций NekoFlash.
 *
 * ViewModel сериализует подключение ADB/Fastboot, хранит наблюдаемое состояние экранов
 * и публикует прогресс. Mutation-команды передаются напрямую реальному protocol-слою;
 * ViewModel не вводит отдельные host-side разрешения поверх bootloader/ADB.
 */
class DeviceViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    enum class ConnectionState { NONE, CONNECTING, FASTBOOT, ADB, ERROR }

    enum class OperationStepStatus { PENDING, RUNNING, OK, FAILED, SKIPPED, INFO }

    enum class OperationOutcomeKind { SUCCESS, FAILED, CANCELLED, VERIFY_PENDING }

    sealed class OperationOutcome {
        object Success : OperationOutcome()
        data class Failed(val message: String) : OperationOutcome()
        data class Cancelled(val message: String) : OperationOutcome()
        data class VerifyPending(val message: String) : OperationOutcome()
    }

    private class OperationAbort(val outcome: OperationOutcome) :
        RuntimeException(null, null, false, false)

    private class OperationContext {
        fun failOperation(message: String): Nothing =
            throw OperationAbort(OperationOutcome.Failed(message))

        fun verificationPending(message: String): Nothing =
            throw OperationAbort(OperationOutcome.VerifyPending(message))
    }

    data class OperationStep(
        val index: Int,
        val total: Int,
        val title: String,
        val subtitle: String? = null,
        val status: OperationStepStatus = OperationStepStatus.PENDING
    )

    private val _connectionState   = MutableLiveData(ConnectionState.NONE)
    val connectionState: LiveData<ConnectionState> = _connectionState

    private val _operationActive   = MutableLiveData(false)
    val operationActive: LiveData<Boolean> = _operationActive

    private val _operationSteps = MutableLiveData<List<OperationStep>>(emptyList())
    val operationSteps: LiveData<List<OperationStep>> = _operationSteps

    /**
     * Прогресс активной операции записи (для полноэкранного диалога прошивки).
     * null — операция без явного прогресса или не идёт.
     */
    data class OperationProgress(
        val title: String,        // что прошивается, напр. "flash recovery_a"
        val percent: Int,         // 0..100, -1 = неопределённый (busy)
        val detail: String,       // linesа скорости/ETA или статус
        val finished: Boolean = false,  // operation завершена (показать результат)
        val success: Boolean = false,   // успех (для обратной совместимости UI)
        val outcome: OperationOutcomeKind? = null
    )

    private val _operationProgress = MutableLiveData<OperationProgress?>(null)
    val operationProgress: LiveData<OperationProgress?> = _operationProgress

    fun postOperationProgress(progress: OperationProgress?) {
        _operationProgress.postValue(progress)
    }

    private val _connectionInfo    = MutableLiveData<String?>(null)
    val connectionInfo: LiveData<String?> = _connectionInfo

    private val _fastbootDiagnostics = MutableLiveData<FastbootProtocol.DeviceDiagnostics?>(null)
    val fastbootDiagnostics: LiveData<FastbootProtocol.DeviceDiagnostics?> = _fastbootDiagnostics

    private val _fastbootPartitionInventory = MutableLiveData<FastbootPartitionInventory.Snapshot?>(null)
    val fastbootPartitionInventory: LiveData<FastbootPartitionInventory.Snapshot?> = _fastbootPartitionInventory

    private val _adbPeerMode = MutableLiveData<AdbProtocol.PeerMode?>(null)
    val adbPeerMode: LiveData<AdbProtocol.PeerMode?> = _adbPeerMode

    private val _transportSessionId = MutableLiveData<String?>(null)
    val transportSessionId: LiveData<String?> = _transportSessionId

    private val flashDraftLock = Any()
    private var flashDraftSnapshot: FlashOperationDraft =
        FlashOperationDraftCodec.decode(savedStateHandle.get<ArrayList<String>>(SAVED_FLASH_QUEUE_DRAFT))
    private val _flashOperationDraft = MutableLiveData(flashDraftSnapshot)
    val flashOperationDraft: LiveData<FlashOperationDraft> = _flashOperationDraft

    /** Transient execution object. It is never placed in SavedStateHandle. */
    data class FlashQueueItem(val partition: String, val file: File)

    private data class PendingUnlockVerification(
        val product: String,
        val serial: String?,
        val expectedUnlocked: Boolean,
        val operationLabel: String,
        val createdAtMs: Long
    )

    private data class PendingSideloadVerification(
        val packageName: String,
        val packageSize: Long,
        val device: String?,
        val createdAtMs: Long
    )

    private fun text(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private fun String?.trimToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private val initialLines = listOf(
        text(R.string.system_terminal_ready),
        text(R.string.security_full_terminal_active)
    )

    private val _logLines = MutableLiveData(initialLines)
    val logLines: LiveData<List<String>> = _logLines

    private val lines    = initialLines.toMutableList()
    private val logLock  = Any()
    private val operationStepLock = Any()
    private var operationStepSnapshot: List<OperationStep> = emptyList()
    private var logFile: File? = null
    private var traceLogFile: File? = null
    private var logStore: DiagnosticLogStore? = null
    private var sessionSummaryFile: File? = null
    private var logFileConfigured = false
    private var configuredWorkspacePath: String? = null
    private var lastCompactMessage: String? = null
    private var lastCompactDeclaredLevel: DiagnosticLogPolicy.Level? = null
    private var lastCompactMessageAtMs: Long = 0L
    private var suppressedDuplicateCount: Long = 0L
    private var lastSummaryPersistAtMs: Long = 0L
    private var workspaceRoot: File? = null
    private var lastUsbSessionSnapshot: UsbSessionSnapshot? = null
    private var connectedUsbManager: UsbManager? = null
    private var activeTransportSessionId: String? = null
    private val transportSessionSequence = AtomicLong(0L)
    private val adbKeyDir: File = File(application.filesDir, "adbkeys")

    @Volatile
    var fastbootProtocol: FastbootProtocol? = null
        private set
    @Volatile
    var adbProtocol: AdbProtocol? = null
        private set

    @Volatile
    private var connectionJob: Job? = null
    @Volatile
    private var operationJob: Job? = null
    private val operationLaunchLock = Any()
    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transportTransitionMutex = Mutex()
    private val transportRestartRequired = AtomicBoolean(false)
    private val viewModelCleared = AtomicBoolean(false)
    @Volatile
    private var connectedUsbTarget: UsbDeviceInspector.Candidate? = null
    @Volatile
    private var pendingUsbTargetKey: String? = null
    @Volatile
    private var connectedDeviceInfo: String? = null
    private var debugLoggingEnabled: Boolean = false
    private var operationWakeLock: PowerManager.WakeLock? = null

    private val operationGeneration = AtomicLong(0L)
    private val connectionGeneration = AtomicLong(0L)
    private val processSessionId: String = UUID.randomUUID().toString()
    private val diagnosticSessionTracker = DiagnosticSessionTracker(processSessionId, buildId = BuildConfig.BUILD_ID)


    init {
        DiagnosticCrashMarker.install(File(application.filesDir, "diagnostics"), BuildConfig.BUILD_ID)
    }

    // ─── ЛОГИРОВАНИЕ ─────────────────────────────────────────────────────────

    fun configureLogDirectory(workspacePath: File) {
        workspaceRoot = workspacePath

        val workspaceKey = runCatching { workspacePath.canonicalPath }.getOrElse { workspacePath.absolutePath }
        if (logFileConfigured && configuredWorkspacePath == workspaceKey && logFile != null) {
            // Activity recreation must not start a second log file or replay the old terminal history.
            return
        }

        val logsDir = File(workspacePath, "logs")
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            log("⚠️ Could not create logs folder: ${logsDir.absolutePath}")
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val seedInitialLines = !logFileConfigured
        val store = try {
            DiagnosticLogStore(logsDir, stamp)
        } catch (e: Exception) {
            log("⚠️ Could not initialize bounded log store: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        logStore = store
        configuredWorkspacePath = workspaceKey
        logFileConfigured = true
        appendRawToLogFile(
            "# NekoFlash compact log\n# Created: $stamp\n" +
                "# Session: $processSessionId\n# Build: ${BuildConfig.BUILD_ID}\n# Raw protocol traffic is stored in trace-*.txt\n\n"
        )
        appendRawToTraceFile(
            "# NekoFlash protocol trace\n# Created: $stamp\n" +
                "# Session: $processSessionId\n# Build: ${BuildConfig.BUILD_ID}\n# This file may contain high-volume USB/Fastboot timing details.\n\n"
        )
        if (seedInitialLines) {
            synchronized(logLock) { lines.forEach { appendRawToLogFile(formatLogLine(it)) } }
        }
        val createdLog = store.currentCompactFile()
        logFile = createdLog
        traceLogFile = store.currentTraceFile()
        persistSessionSummary()
        log("Log file: /sdcard/Download/NekoFlash/logs/${createdLog?.name ?: "log-$stamp.txt"}")
        log("ℹ️ Raw USB/Fastboot trace is separated from the main log and rotates automatically.")
    }

    fun log(message: String) {
        logInternal(message, declaredLevel = null)
    }

    fun log(level: DiagnosticLogPolicy.Level, message: String) {
        logInternal(message, declaredLevel = level)
    }

    private fun logInternal(message: String, declaredLevel: DiagnosticLogPolicy.Level?) {
        synchronized(logLock) {
            val now = System.currentTimeMillis()
            val duplicate = message == lastCompactMessage &&
                declaredLevel == lastCompactDeclaredLevel &&
                now - lastCompactMessageAtMs <= DiagnosticLogPolicy.duplicateWindowMs()
            if (duplicate) {
                suppressedDuplicateCount += 1L
                diagnosticSessionTracker.recordDuplicateSuppressed()
                lastCompactMessageAtMs = now
                return
            }
            flushSuppressedDuplicatesLocked()
            if (message.trim().startsWith("💡")) {
                lines.removeAll { it.trim().startsWith("💡") }
            }
            appendCompactMessageLocked(message, declaredLevel)
            lastCompactMessage = message
            lastCompactDeclaredLevel = declaredLevel
            lastCompactMessageAtMs = now
        }
    }

    fun clearLog() {
        synchronized(logLock) {
            flushSuppressedDuplicatesLocked()
            lines.clear()
            lastCompactMessage = null
            lastCompactDeclaredLevel = null
            suppressedDuplicateCount = 0L
        }
        log(text(R.string.log_cleared))
    }

    /**
     * Raw protocol/timing trace, separated from the compact user log.
     * Trace segments are bounded and rotated independently.
     */
    fun logFileOnly(message: String) {
        synchronized(logLock) {
            diagnosticSessionTracker.recordTrace()
            appendRawToTraceFile(formatLogLine(message))
        }
    }

    private fun appendCompactMessageLocked(
        message: String,
        declaredLevel: DiagnosticLogPolicy.Level? = null
    ) {
        lines.add(message)
        while (lines.size > 600) lines.removeAt(0)
        _logLines.postValue(lines.toList())
        val classification = DiagnosticLogPolicy.classify(message, declaredLevel)
        diagnosticSessionTracker.recordCompact(message, classification)
        appendRawToLogFile(formatLogLine(message))
        val now = System.currentTimeMillis()
        if (classification.level == DiagnosticLogPolicy.Level.ERROR ||
            now - lastSummaryPersistAtMs >= 30_000L
        ) {
            lastSummaryPersistAtMs = now
            persistSessionSummary()
        }
    }

    private fun flushSuppressedDuplicatesLocked() {
        if (suppressedDuplicateCount <= 0L) return
        val repeated = "↻ Previous line repeated $suppressedDuplicateCount time(s); duplicates collapsed."
        suppressedDuplicateCount = 0L
        appendCompactMessageLocked(repeated, DiagnosticLogPolicy.Level.INFO)
    }

    private fun appendRawToLogFile(text: String) {
        try {
            logStore?.appendCompact(text)
            logFile = logStore?.currentCompactFile() ?: logFile
        } catch (error: Exception) {
            android.util.Log.w("NekoFlash", "Unable to append compact diagnostic log", error)
        }
    }

    private fun appendRawToTraceFile(text: String) {
        try {
            logStore?.appendTrace(text)
            traceLogFile = logStore?.currentTraceFile() ?: traceLogFile
        } catch (error: Exception) {
            android.util.Log.w("NekoFlash", "Unable to append protocol trace", error)
        }
    }

    private fun persistSessionSummary(): File? = try {
        val file = logStore?.writeSessionSummary(
            DiagnosticSessionTracker.toJson(diagnosticSessionTracker.snapshot())
        )
        sessionSummaryFile = file
        file
    } catch (error: Exception) {
        android.util.Log.w("NekoFlash", "Unable to persist diagnostic session summary", error)
        sessionSummaryFile
    }

    fun flushDiagnostics(reason: String, terminal: Boolean = false) {
        synchronized(logLock) {
            flushSuppressedDuplicatesLocked()
            if (terminal) diagnosticSessionTracker.recordTermination(reason)
            appendRawToTraceFile(
                formatLogLine(
                    "[session-flush] reason=$reason terminal=$terminal " +
                        "transportSession=${activeTransportSessionId ?: "none"}"
                )
            )
            persistSessionSummary()
        }
    }

    private fun formatLogLine(message: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "[$stamp] $message\n"
    }

    // ─── ПОДКЛЮЧЕНИЕ ─────────────────────────────────────────────────────────

    private fun createTransportSessionId(candidate: UsbDeviceInspector.Candidate): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val sequence = transportSessionSequence.incrementAndGet().toString().padStart(3, '0')
        val vid = candidate.device.vendorId.toString(16).uppercase(Locale.US).padStart(4, '0')
        val pid = candidate.device.productId.toString(16).uppercase(Locale.US).padStart(4, '0')
        return "${stamp}_${candidate.mode.name}_${vid}-${pid}_$sequence"
    }

    private fun captureUsbSession(candidate: UsbDeviceInspector.Candidate, usbManager: UsbManager) {
        connectedUsbManager = usbManager
        val sessionId = createTransportSessionId(candidate)
        activeTransportSessionId = sessionId
        _transportSessionId.postValue(sessionId)
        val snapshot = UsbSessionSnapshot.capture(sessionId, candidate)
        lastUsbSessionSnapshot = snapshot
        diagnosticSessionTracker.recordTransportSession(sessionId, candidate.mode.name, connectedDeviceInfo)
        persistSessionSummary()
        log("=== USB SESSION: $sessionId ===")
        log("USB: ${candidate.displaySubtitle()}")
        logFileOnly(snapshot.toText())
        val workspace = workspaceRoot
        if (workspace != null) {
            runCatching {
                val dir = File(workspace, "reports/usb-sessions")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "usb-session-$sessionId.txt").writeText(snapshot.toText(), Charsets.UTF_8)
                File(dir, "usb-session-$sessionId.json").writeText(snapshot.toJson(), Charsets.UTF_8)
            }.onFailure { error ->
                log("⚠️ USB session snapshot was not saved: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun connectDevice(
        usbManager: UsbManager,
        candidate: UsbDeviceInspector.Candidate,
        automatic: Boolean = false
    ) {
        if (transportRestartRequired.get()) {
            log("⛔ USB transport is locked after unconfirmed cleanup. Fully restart NekoFlash before a new connection.")
            _connectionState.postValue(ConnectionState.ERROR)
            return
        }

        val current = connectedUsbTarget
        val state = _connectionState.value ?: ConnectionState.NONE
        if ((current?.stableKey == candidate.stableKey || pendingUsbTargetKey == candidate.stableKey) &&
            state in setOf(ConnectionState.CONNECTING, ConnectionState.ADB, ConnectionState.FASTBOOT)
        ) {
            logFileOnly("Duplicate USB connect ignored: ${candidate.stableKey} state=$state")
            return
        }

        // Publish the transition Job under the same lock used by startOperation.
        // CoroutineStart.LAZY removes the window where a new USB operation could
        // start after a transition was requested but before connectionJob became visible.
        synchronized(operationLaunchLock) {
            if (viewModelCleared.get()) return
            val generation = connectionGeneration.incrementAndGet()
            pendingUsbTargetKey = candidate.stableKey
            _fastbootDiagnostics.postValue(null)
            _fastbootPartitionInventory.postValue(null)
            _adbPeerMode.postValue(null)
            _connectionState.postValue(ConnectionState.CONNECTING)

            val transitionJob = transportScope.launch(start = CoroutineStart.LAZY) {
                transportTransitionMutex.withLock {
                    if (viewModelCleared.get() || generation != connectionGeneration.get()) {
                        if (pendingUsbTargetKey == candidate.stableKey) pendingUsbTargetKey = null
                        return@withLock
                    }
                    if (!shutdownCurrentTransportsSafely("new USB generation=$generation")) {
                        if (pendingUsbTargetKey == candidate.stableKey) pendingUsbTargetKey = null
                        _connectionState.postValue(ConnectionState.ERROR)
                        return@withLock
                    }
                    if (viewModelCleared.get() || generation != connectionGeneration.get()) {
                        if (pendingUsbTargetKey == candidate.stableKey) pendingUsbTargetKey = null
                        return@withLock
                    }

                    pendingUsbTargetKey = null
                    connectedUsbTarget = candidate
                    connectedDeviceInfo = buildDeviceInfo(candidate)
                    captureUsbSession(candidate, usbManager)
                    _connectionInfo.postValue(connectedDeviceInfo)
                    _fastbootDiagnostics.postValue(null)
                    _fastbootPartitionInventory.postValue(null)
                    _adbPeerMode.postValue(null)
                    _connectionState.postValue(ConnectionState.CONNECTING)
                    connectCandidateLocked(usbManager, candidate, generation)
                }
            }
            connectionJob = transitionJob
            transitionJob.start()
        }
    }

    private suspend fun connectCandidateLocked(
        usbManager: UsbManager,
        candidate: UsbDeviceInspector.Candidate,
        generation: Long
    ) {
        val device = candidate.device

        if (candidate.mode == UsbDeviceInspector.Mode.FASTBOOT) {
            val proto = FastbootProtocol(
                usbManager = usbManager,
                device = device,
                onLog = { msg -> log(msg) },
                onLogVerbose = { msg -> logFileOnly(msg) },
                onProgress = { percent, detail ->
                    val currentProgress = _operationProgress.value
                    _operationProgress.postValue(
                        OperationProgress(
                            title = currentProgress?.title ?: text(R.string.flash_progress_writing),
                            percent = percent,
                            detail = detail
                        )
                    )
                },
                preferredInterfaceIndex = candidate.interfaceIndex
            )
            proto.debugLogging = debugLoggingEnabled
            var published = false
            try {
                val connectStartedNs = System.nanoTime()
                if (!proto.connect()) {
                    diagnosticSessionTracker.recordMilestone("fastboot.connect.failed", (System.nanoTime() - connectStartedNs) / 1_000_000L)
                    if (generation == connectionGeneration.get()) _connectionState.postValue(ConnectionState.ERROR)
                    return
                }
                diagnosticSessionTracker.recordMilestone("fastboot.connect", (System.nanoTime() - connectStartedNs) / 1_000_000L)
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return

                val qualifiedProduct = proto.qualifyConnection()
                if (qualifiedProduct == null) {
                    if (generation == connectionGeneration.get()) _connectionState.postValue(ConnectionState.ERROR)
                    log(DiagnosticLogPolicy.Level.ERROR, "ERROR: Fastboot handshake was not confirmed. Reconnection is allowed.")
                    return
                }
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return

                val diagnostics = proto.refreshDiagnostics(force = true, knownProduct = qualifiedProduct)
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return
                if (proto.isSessionBroken) {
                    _connectionState.postValue(ConnectionState.ERROR)
                    log(DiagnosticLogPolicy.Level.ERROR, "ERROR: USB interface opened, but a valid Fastboot exchange was not confirmed")
                    return
                }

                fastbootProtocol = proto
                published = true
                _adbPeerMode.postValue(null)
                _connectionState.postValue(ConnectionState.FASTBOOT)
                diagnosticSessionTracker.recordConnection("FASTBOOT", connectedDeviceInfo)
                persistSessionSummary()
                logConnectionStatus()
                _fastbootDiagnostics.postValue(diagnostics)
                verifyPendingUnlockIfReady(diagnostics)
            } finally {
                if (!published || generation != connectionGeneration.get() || viewModelCleared.get()) {
                    if (fastbootProtocol === proto) fastbootProtocol = null
                    proto.disconnect()
                }
            }
        } else {
            val proto = AdbProtocol(
                usbManager = usbManager,
                device = device,
                keyDirectory = adbKeyDir,
                onLog = { msg -> log(msg) },
                onProgress = { percent, detail ->
                    val currentProgress = _operationProgress.value
                    _operationProgress.postValue(
                        OperationProgress(
                            title = currentProgress?.title ?: text(R.string.flash_progress_writing),
                            percent = percent,
                            detail = detail
                        )
                    )
                },
                preferredInterfaceIndex = candidate.interfaceIndex
            )
            proto.onTransportFailure = { code, message ->
                if (!viewModelCleared.get() && generation == connectionGeneration.get()) {
                    diagnosticSessionTracker.recordTermination("ADB_READER_${code.name}")
                    diagnosticSessionTracker.recordMilestone("adb.reader.failure", 0L)
                    persistSessionSummary()
                    _connectionState.postValue(ConnectionState.ERROR)
                    log("⛔ ADB transport stopped [${code.name}]: $message. Auto-retry is forbidden until manual reconnection.")
                }
            }
            var published = false
            try {
                val connectStartedNs = System.nanoTime()
                if (!proto.connect()) {
                    diagnosticSessionTracker.recordMilestone("adb.connect.failed", (System.nanoTime() - connectStartedNs) / 1_000_000L)
                    if (generation == connectionGeneration.get()) _connectionState.postValue(ConnectionState.ERROR)
                    return
                }
                diagnosticSessionTracker.recordMilestone("adb.connect", (System.nanoTime() - connectStartedNs) / 1_000_000L)
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return

                adbProtocol = proto
                published = true
                _fastbootDiagnostics.postValue(null)
                _fastbootPartitionInventory.postValue(null)
                _adbPeerMode.postValue(proto.peerMode)
                verifyPendingSideloadIfReady(proto)
                _connectionState.postValue(ConnectionState.ADB)
                diagnosticSessionTracker.recordConnection("ADB", connectedDeviceInfo)
                persistSessionSummary()
                logConnectionStatus()
            } finally {
                if (!published || generation != connectionGeneration.get() || viewModelCleared.get()) {
                    if (adbProtocol === proto) adbProtocol = null
                    proto.disconnect()
                }
            }
        }
    }

    private suspend fun awaitNativeUsbfsIdle(): Boolean {
        while (true) {
            val state = NativeUsbfsBackend.backendState()
            if (!NativeUsbfsBackend.hasActiveTransfer && !state.nativeTransferActive) return true
            delay(TRANSPORT_IDLE_POLL_MS)
        }
    }

    /**
     * Two-phase transport shutdown. The Java UsbDeviceConnection is never
     * released while a blocking native USBFS call may still own submitted URBs.
     */
    private suspend fun shutdownCurrentTransportsSafely(reason: String): Boolean {
        val activeOperation = operationJob
        val nativeStateBefore = NativeUsbfsBackend.backendState()
        // isActive becomes false as soon as a Job enters Cancelling, while its
        // finally block or a blocking JNI call may still be running. Only
        // isCompleted proves that the operation no longer owns the transport.
        val needsDrain = activeOperation?.isCompleted == false ||
            NativeUsbfsBackend.hasActiveTransfer || nativeStateBefore.nativeTransferActive

        if (needsDrain) {
            log("⏳ USB shutdown requested ($reason). First cancelling the operation and waiting for confirmed Native USBFS drain.")
            activeOperation?.cancel(CancellationException("Transport shutdown requested: $reason"))
            fastbootProtocol?.cancel()
            adbProtocol?.cancel()

            val clean = withTimeoutOrNull(TRANSPORT_SHUTDOWN_TIMEOUT_MS) {
                activeOperation?.join()
                awaitNativeUsbfsIdle()
            } == true

            if (!clean) {
                transportRestartRequired.set(true)
                log("⛔ Safe USB shutdown was not confirmed within ${TRANSPORT_SHUTDOWN_TIMEOUT_MS} ms. UsbDeviceConnection will not be closed; new connections are blocked until NekoFlash is fully restarted.")
                _connectionState.postValue(ConnectionState.ERROR)
                return false
            }
        }

        val nativeStateAfter = NativeUsbfsBackend.backendState()
        if (!UsbTransportShutdownPolicy.canCloseUsb(
                kotlinTransferActive = NativeUsbfsBackend.hasActiveTransfer,
                nativeTransferActive = nativeStateAfter.nativeTransferActive
            )
        ) {
            transportRestartRequired.set(true)
            log("⛔ Native USBFS still reports an active transfer after waiting. USB close is blocked until the app is restarted.")
            _connectionState.postValue(ConnectionState.ERROR)
            return false
        }

        val fastbootClosed = fastbootProtocol?.disconnect() ?: true
        if (!fastbootClosed) {
            transportRestartRequired.set(true)
            log("⛔ FastbootProtocol refused to close USB before confirmed drain. New connections are blocked until NekoFlash is restarted.")
            _connectionState.postValue(ConnectionState.ERROR)
            return false
        }
        adbProtocol?.disconnect()
        fastbootProtocol = null
        adbProtocol = null
        operationJob = null
        operationGeneration.incrementAndGet()

        // At this point any blocking operation has returned and its finally block
        // already had a chance to release these resources. The calls below only
        // clear stale state left by an interrupted non-native operation.
        releaseOperationWakeLock(logRelease = false)
        FlashOperationService.stop(getApplication())
        _operationActive.postValue(false)
        return true
    }

    private fun publishDisconnectedState(reason: String) {
        if (activeTransportSessionId != null) {
            diagnosticSessionTracker.recordTermination(reason)
            flushDiagnostics("DISCONNECT:$reason", terminal = false)
        }
        pendingUsbTargetKey = null
        connectedUsbTarget = null
        connectedUsbManager = null
        connectedDeviceInfo = null
        activeTransportSessionId = null
        _transportSessionId.postValue(null)
        _connectionInfo.postValue(null)
        _fastbootDiagnostics.postValue(null)
        _fastbootPartitionInventory.postValue(null)
        _adbPeerMode.postValue(null)
        _operationActive.postValue(false)
        _connectionState.postValue(ConnectionState.NONE)
        diagnosticSessionTracker.recordConnection("NONE", reason)
        persistSessionSummary()
    }

    fun currentUsbMode(): UsbDeviceInspector.Mode? = connectedUsbTarget?.mode
    fun currentUsbLogicalSignature(): String? = connectedUsbTarget?.logicalSignature
    fun currentUsbVendorId(): Int? = connectedUsbTarget?.device?.vendorId

    fun isCurrentUsbDevice(device: UsbDevice): Boolean {
        val current = connectedUsbTarget?.device ?: return false
        return current.deviceName == device.deviceName ||
            (current.deviceId == device.deviceId &&
                current.vendorId == device.vendorId &&
                current.productId == device.productId)
    }

    private fun buildDeviceInfo(candidate: UsbDeviceInspector.Candidate): String {
        val device = candidate.device
        val mode = candidate.mode.name
        val name = device.productName ?: device.deviceName
        return "Mode: $mode | Device: $name | VID=${device.vendorId} | PID=${device.productId} | " +
            "interface=${candidate.interfaceIndex} | match=${candidate.matchKind.label}"
    }

    private fun setOperationSteps(steps: List<OperationStep>) {
        val safeSteps = steps.take(MAX_OPERATION_STEPS_IN_UI)
        synchronized(operationStepLock) { operationStepSnapshot = safeSteps }
        _operationSteps.postValue(safeSteps)
    }

    private fun markOperationStep(index: Int, status: OperationStepStatus, subtitle: String? = null) {
        val updated = synchronized(operationStepLock) {
            operationStepSnapshot.map { step ->
                if (step.index == index) step.copy(status = status, subtitle = subtitle ?: step.subtitle) else step
            }.also { operationStepSnapshot = it }
        }
        _operationSteps.postValue(updated)
    }

    fun logConnectionStatus() {
        log(text(R.string.connection_status_header))

        val displayMode = when {
            fastbootProtocol?.isConnected == true -> {
                val fastbootd = when (fastbootProtocol?.currentDiagnostics()?.isUserspace?.trim()?.lowercase(Locale.ROOT)) {
                    "yes" -> true
                    "no" -> false
                    else -> null
                }
                ConnectionModeUiPolicy.resolve(ConnectionModeUiPolicy.Transport.FASTBOOT, fastbootd = fastbootd)
            }
            adbProtocol?.isConnected == true -> {
                val adbMode = when (adbProtocol?.peerMode) {
                    AdbProtocol.PeerMode.DEVICE -> ConnectionModeUiPolicy.AdbMode.SYSTEM
                    AdbProtocol.PeerMode.RECOVERY -> ConnectionModeUiPolicy.AdbMode.RECOVERY
                    AdbProtocol.PeerMode.SIDELOAD -> ConnectionModeUiPolicy.AdbMode.SIDELOAD
                    AdbProtocol.PeerMode.UNKNOWN, null -> ConnectionModeUiPolicy.AdbMode.UNKNOWN
                }
                ConnectionModeUiPolicy.resolve(ConnectionModeUiPolicy.Transport.ADB, adbMode = adbMode)
            }
            else -> when (_connectionState.value ?: ConnectionState.NONE) {
                ConnectionState.NONE -> ConnectionModeUiPolicy.DisplayMode.NO_DEVICE
                ConnectionState.CONNECTING -> ConnectionModeUiPolicy.DisplayMode.CONNECTING
                ConnectionState.FASTBOOT -> ConnectionModeUiPolicy.DisplayMode.FASTBOOT_UNKNOWN
                ConnectionState.ADB -> ConnectionModeUiPolicy.DisplayMode.ADB_UNKNOWN
                ConnectionState.ERROR -> ConnectionModeUiPolicy.DisplayMode.ERROR
            }
        }

        val modeLabel = ConnectionModeUiPolicy.logLabel(displayMode)
        log(text(R.string.state_label, modeLabel))

        val deviceInfo = connectedDeviceInfo
        if (deviceInfo == null) {
            log(text(R.string.device_not_connected))
        } else {
            val details = deviceInfo.substringAfter(" | ", missingDelimiterValue = "")
            log(if (details.isBlank()) "Mode: $modeLabel" else "Mode: $modeLabel | $details")
        }

        adbProtocol?.takeIf { it.isConnected }?.let { log("ADB peer mode: ${it.peerMode.name}") }
        log(text(R.string.log_auto_saved, logFile?.absolutePath ?: text(R.string.log_folder_not_ready)))
    }

    fun currentLogFile(): File? = logStore?.currentCompactFile() ?: logFile
    fun currentLogFiles(): List<File> = logStore?.compactFiles().orEmpty()
    fun currentTraceLogFiles(): List<File> = logStore?.traceFiles().orEmpty()
    fun currentSessionSummaryFile(): File? = synchronized(logLock) {
        flushSuppressedDuplicatesLocked()
        persistSessionSummary()
    }
    fun currentDiagnosticSessionSummary(): DiagnosticSessionTracker.Snapshot = synchronized(logLock) {
        flushSuppressedDuplicatesLocked()
        diagnosticSessionTracker.snapshot()
    }
    fun logSnapshot(): List<String> = synchronized(logLock) {
        flushSuppressedDuplicatesLocked()
        lines.toList()
    }
    fun currentConnectionInfo(): String? = connectedDeviceInfo
    fun currentFastbootDiagnostics(): FastbootProtocol.DeviceDiagnostics? = fastbootProtocol?.currentDiagnostics()
    fun currentFastbootPartitionInventory(): FastbootPartitionInventory.Snapshot? =
        _fastbootPartitionInventory.value
    fun currentAdbDiagnostics(): AdbProtocol.DeviceDiagnostics? = adbProtocol?.currentDiagnostics()
    fun currentTransportSessionId(): String? = activeTransportSessionId
    fun currentBuildId(): String = BuildConfig.BUILD_ID

    fun setDebugLogging(enabled: Boolean) {
        debugLoggingEnabled = enabled
        fastbootProtocol?.debugLogging = enabled
        log(if (enabled) text(R.string.debug_enabled) else text(R.string.debug_disabled))
    }

    fun refreshFastbootDiagnostics() {
        startOperation(text(R.string.notif_fastboot_diagnostics), text(R.string.notif_updating_device), heavy = false) {
            val proto = fastbootProtocol
            if (proto?.isConnected == true) {
                val diagnostics = proto.refreshDiagnostics(force = true)
                _fastbootDiagnostics.postValue(diagnostics)

                // getvar:all is intentionally tied to a manual refresh only. It is
                // not executed during initial connection because some bootloaders
                // (notably onyx) have an unstable first Fastboot response over OTG.
                val inventoryStartedNs = System.nanoTime()
                val inventory = if (!proto.isSessionBroken) {
                    proto.collectPartitionInventory(diagnostics)
                } else {
                    null
                }
                diagnosticSessionTracker.recordMilestone(
                    "fastboot.partition-inventory",
                    (System.nanoTime() - inventoryStartedNs) / 1_000_000L
                )
                if (inventory != null) {
                    _fastbootPartitionInventory.postValue(inventory)
                    val topology = when (inventory.topology) {
                        FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY -> "legacy A-only (without A/B)"
                        FastbootPartitionInventory.SlotTopology.A_B -> "A/B"
                        FastbootPartitionInventory.SlotTopology.UNKNOWN -> "unknown"
                    }
                    val incomplete = inventory.entries.count { it.missingFields.isNotEmpty() }
                    log(
                        "ℹ️ Partition inventory: ${inventory.entries.size}, " +
                            "topology=$topology, incomplete=$incomplete, " +
                            "point-queries=${inventory.pointQueryCount}, status=${inventory.finalStatus}"
                    )
                    inventory.warnings
                        .filter { it.severity != FastbootPartitionInventory.WarningSeverity.INFO }
                        .take(4)
                        .forEach { warning -> log("⚠️ Inventory ${warning.code}: ${warning.message}") }
                } else {
                    _fastbootPartitionInventory.postValue(null)
                    if (!proto.isSessionBroken) {
                        log("⚠️ getvar:all did not provide inventory; point Fastboot diagnostics were saved.")
                    }
                }

                if (proto.isSessionBroken) {
                    _connectionState.postValue(ConnectionState.ERROR)
                    failOperation("Fastboot session lost synchronization while refreshing data. Reconnect the device.")
                }
            } else {
                val message = text(R.string.error_no_fastboot)
                log(message)
                failOperation(message)
            }
        }
    }

    // ─── ВЫПОЛНЕНИЕ КОМАНД ───────────────────────────────────────────────────

    /**
     * Полный процесс разблокировки загрузчика Xiaomi (официальный Mi Unlock).
     * Требует: устройство в fastboot + авторизованный Mi-аккаунт (auth).
     * Шаги: чтение product+token с устройства → nonce → clear → ahaUnlock →
     * staging encryptData + oem unlock.
     */
    fun runMiUnlock(
        auth: MiAccountClient.AuthResult,
        onClearInfo: (String, Boolean) -> Unit,
        onAuthExpired: () -> Unit = {}
    ) {
        startOperation(text(R.string.notif_fastboot_command), "Mi Unlock", heavy = true) {
            val proto = fastbootProtocol
            if (proto?.isConnected != true) {
                val message = "Device is not in Fastboot mode. Put it into Fastboot and connect it over OTG."
                log("❌ $message")
                failOperation(message)
            }

            log("🔍 Reading device data...")
            val product = proto.getVar("product")?.replace(Regex("\\s"), "")
            if (product.isNullOrEmpty()) {
                val message = "Could not read device product"
                log("❌ $message")
                failOperation(message)
            }
            log("📱 product: $product")

            val serial = proto.currentDiagnostics()?.serialno?.trim()?.takeIf { it.isNotBlank() }
            val deviceToken = proto.readXiaomiUnlockToken()
            if (deviceToken.isNullOrEmpty()) {
                val message = "Could not read device token"
                log("❌ $message")
                failOperation(message)
            }
            log("🔑 deviceToken received (${deviceToken.length} characters)")

            // Новая явная попытка отменяет только старый незавершённый verify-marker.
            clearPendingUnlockVerification()

            try {
                val client = MiUnlockClient(
                    host = auth.host,
                    ssecurity = auth.ssecurity,
                    serviceCookies = auth.serviceCookies,
                    userId = auth.userId,
                    deviceId = auth.deviceId
                )
                log("🌐 Mi Unlock transport: migate-auth + signed-query v3 (${BuildConfig.VERSION_NAME})")
                log("🌐 Requesting nonce from the Mi server...")
                val nonce = client.getNonce()

                log("🌐 Checking device...")
                val clearInfo = client.checkClear(product, nonce)
                if (clearInfo.notice.isNotEmpty()) log("ℹ️ ${clearInfo.notice}")
                log(if (clearInfo.clearsData) "⚠️ Unlocking WILL ERASE device data" else "ℹ️ Data will not be erased")
                postMainThread { onClearInfo(clearInfo.notice, clearInfo.clearsData) }

                log("🌐 Requesting unlock from the Mi server...")
                val encryptDataHex = client.requestUnlock(product, deviceToken, nonce)
                log("✅ Server returned unlock data")

                val bytes = hexToBytes(encryptDataHex)
                val file = File(getApplication<Application>().filesDir, "encryptData")
                file.outputStream().use { it.write(bytes) }
                val accepted = try {
                    proto.stageAndOemUnlock(file)
                } finally {
                    runCatching { file.delete() }
                }

                if (!accepted) {
                    val message = "Unlock failed at the device stage"
                    log("❌ $message")
                    failOperation(message)
                }

                persistPendingUnlockVerification(product, serial)
                log("✅ The oem unlock command was accepted by the device.")
                log("🔎 Final success will be confirmed only after a new Fastboot connection and getvar:unlocked=yes.")
                verificationPending("Unlock command accepted. Waiting for reconnect to verify unlocked=yes.")
            } catch (abort: OperationAbort) {
                throw abort
            } catch (e: MiUnlockClient.SessionExpiredException) {
                val message = "Mi session expired or was revoked (HTTP 401). Sign in to Mi Account again."
                log("❌ $message")
                postMainThread { onAuthExpired() }
                failOperation(message)
            } catch (e: MiUnlockClient.BusinessException) {
                val message = e.message ?: "Xiaomi code ${e.code}"
                log("❌ Unlock error: $message")
                if (e.code == 20045) {
                    log("💡 Code 20045: check dataCenterZone. Current zone: ${auth.dataCenterZone}; choose another zone manually and retry only after checking the account region.")
                } else {
                    log("💡 See the exact reason above. Check the official Mi Unlock device status.")
                }
                failOperation(message)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                log("❌ Unlock error: $msg")
                val hasSpecificReason = msg.contains("Xiaomi code") ||
                    msg.contains("Xiaomi:") ||
                    msg.contains("code ")
                if (!hasSpecificReason) {
                    log("💡 Server did not provide a specific reason. Check the network, disable VPN/Private DNS, and repeat the safe step.")
                } else {
                    log("💡 See the exact reason above. Check the official Mi Unlock device status.")
                }
                failOperation(msg)
            }
        }
    }


    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.isNotEmpty()) { "Hex payload is empty" }
        require(clean.length % 2 == 0) { "Hex must have even length" }
        return ByteArray(clean.length / 2) { index ->
            val high = Character.digit(clean[index * 2], 16)
            val low = Character.digit(clean[index * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "Invalid hex payload at byte $index" }
            ((high shl 4) + low).toByte()
        }
    }

    private fun postMainThread(block: () -> Unit) {
        mainHandler.post(block)
    }

    fun runFastbootCommand(cmd: String, heavy: Boolean = true) {
        startOperation(
            text(R.string.notif_fastboot_command),
            text(R.string.notif_executing, cmd),
            heavy = heavy
        ) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            if (!proto.isConnected) failOperation(text(R.string.error_no_fastboot))
            val ok = if (heavy) proto.sendCommand(cmd) else proto.runTerminalCommand(cmd)
            if (!ok) failOperation("Fastboot command failed: $cmd")
        }
    }


    fun runFastbootDownloadAndRun(file: File, commandAfterDownload: String) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, commandAfterDownload)) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            if (!proto.downloadAndRun(file, commandAfterDownload)) {
                failOperation("Fastboot download+run failed: $commandAfterDownload")
            }
        }
    }


    fun runFastbootLogicalPartitionCommand(command: String) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, command)) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            if (!proto.runLogicalPartitionCommand(command)) failOperation("Fastboot logical command failed: $command")
        }
    }

    fun inspectFastbootLogicalPartition(partition: String) {
        startOperation(text(R.string.notif_fastboot_diagnostics), text(R.string.notif_updating_device), heavy = false) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            if (proto.inspectLogicalPartition(partition) == null) {
                failOperation("Could not get logical partition info: $partition")
            }
        }
    }

    fun runFastbootFetch(partition: String, outputFile: File, slot: String? = null) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, "fetch $partition")) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            val targets = proto.resolveSlotPartitionTargets(partition, slot)
                ?: failOperation("Could not apply Fastboot slot for $partition")
            if (targets.size > 1) failOperation("Fastboot fetch for multiple slots requires separate output files")
            val target = targets.firstOrNull() ?: failOperation("Fastboot fetch: no suitable partition for $partition")
            if (!proto.fetchPartition(target, outputFile)) failOperation("Fastboot fetch failed: $target")
        }
    }

    fun runFastbootPartitionCommand(wirePrefix: String, partition: String, slot: String? = null) {
        val label = "$wirePrefix $partition" + slot?.let { " --slot=$it" }.orEmpty()
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, label), heavy = false) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            if (!proto.isConnected) failOperation(text(R.string.error_no_fastboot))
            val targets = proto.resolveSlotPartitionTargets(partition, slot)
                ?: failOperation("Could not apply Fastboot slot for $partition")
            targets.forEach { target ->
                val wire = "$wirePrefix:$target"
                if (!proto.sendCommand(wire)) failOperation("Fastboot $wirePrefix failed: $target")
            }
        }
    }

    fun runAdbService(service: String) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, service)) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.runService(service)) failOperation("ADB service failed: $service")
            if (AdbServiceCompletionPolicy.expectsOneWayDisconnect(service)) {
                _operationProgress.postValue(
                    OperationProgress(
                        title = text(R.string.notif_adb_command),
                        percent = 100,
                        detail = "Reboot command sent. The device will temporarily disconnect and appear in the new mode."
                    )
                )
            }
        }
    }

    fun runAdbShell(command: String) {
        val interactive = command.isBlank()
        val label = if (interactive) "interactive shell" else "shell $command"
        // ВАЖНО: интерактивный shell — долгоживущий канал, в который пользователь
        // вводит команды через нижнюю строку. Ему НЕ нужен блокирующий полноэкранный
        // прогресс-диалог (heavy=true): диалог перекрывал ввод и после закрытия
        // shell (exit/CLOSED) продолжал висеть, а его «Отмена» не убирала его.
        // Запускаем как лёгкую операцию — терминал остаётся доступен, выход через
        // exit / adb shell-stop / кнопку Стоп. Разовая команда (adb shell <cmd>) —
        // по-прежнему heavy (короткая, ждём результат).
        startOperation(
            text(R.string.notif_adb_command),
            text(R.string.notif_executing, label),
            heavy = !interactive
        ) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.isConnected) failOperation(text(R.string.error_no_adb))
            if (!proto.runShellCommand(command)) failOperation("ADB shell failed: $label")
        }
    }

    fun isInteractiveAdbShellActive(): Boolean = adbProtocol?.hasInteractiveShell == true

    fun sendInteractiveAdbShellInput(line: String) {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.sendInteractiveShellInput(line)
        } else {
            log("❌ Interactive adb shell is not open")
        }
    }

    fun interruptInteractiveAdbShell() {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.sendInteractiveShellInterrupt()
        } else {
            log("❌ Interactive adb shell is not open")
        }
    }

    fun sendInteractiveAdbShellEof() {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.sendInteractiveShellEof()
        } else {
            log("❌ Interactive adb shell is not open")
        }
    }

    fun stopInteractiveAdbShell() {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.stopInteractiveShell()
        } else {
            log("ℹ️ Interactive adb shell is already closed")
        }
    }

    fun runAdbPush(localFile: File, remotePath: String) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "push ${localFile.name} $remotePath")) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.isConnected) failOperation(text(R.string.error_no_adb))
            if (!proto.pushPath(localFile, remotePath)) failOperation("ADB push failed")
        }
    }

    fun runAdbPull(remotePath: String, localFile: File) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "pull $remotePath")) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.isConnected) failOperation(text(R.string.error_no_adb))
            if (!proto.pullFile(remotePath, localFile)) failOperation("ADB pull failed")
        }
    }

    fun runAdbInstall(packageFile: File, options: List<String>) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "install ${packageFile.name}")) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.isConnected) failOperation(text(R.string.error_no_adb))
            if (!proto.installPackage(packageFile, options)) failOperation("APK installation failed")
        }
    }

    fun runAdbInstallMultiple(apkFiles: List<File>, options: List<String>) {
        val names = apkFiles.joinToString(" ") { it.name }
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "install-multiple $names")) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.isConnected) failOperation(text(R.string.error_no_adb))
            if (!proto.installMultipleApks(apkFiles, options)) failOperation("install-multiple failed")
        }
    }

    fun runFlash(partition: String, file: File, slot: String? = null) {
        val label = partition + slot?.let { " --slot=$it" }.orEmpty()
        startOperation(text(R.string.notif_flash_img), text(R.string.notif_flashing_partition, file.name, label)) {
            val proto = fastbootProtocol ?: failOperation("No Fastboot connection")
            if (!proto.isConnected) failOperation("No Fastboot connection")
            val targets = proto.resolveSlotPartitionTargets(partition, slot)
                ?: failOperation("Could not apply Fastboot slot for $partition")
            targets.forEach { target ->
                val result = proto.flashPartitionDetailed(target, file)
                if (!result.success) failOperation(formatFlashFailure(target, result))
            }
        }
    }


    private fun formatFlashFailure(partition: String, result: FastbootProtocol.FlashResult): String {
        return buildString {
            append("Flashing $partition failed")
            append(" [stage=${result.stage}, kind=${result.failureKind}]")
            if (result.message.isNotBlank()) append(": ${result.message}")
            if (result.sessionCorrupted) append(". A full target-device re-entry into Fastboot is required")
        }
    }

    fun currentFlashOperationDraft(): FlashOperationDraft = synchronized(flashDraftLock) {
        flashDraftSnapshot
    }

    fun addFlashQueueFile(partition: String, file: File) {
        val item = runCatching { FlashOperationDraftPolicy.createItem(partition, file) }
            .getOrElse { error ->
                log("❌ Could not add file to the queue: ${error.message ?: error.javaClass.simpleName}")
                return
            }
        val next = runCatching { FlashOperationDraftPolicy.upsert(currentFlashOperationDraft(), item) }
            .getOrElse { error ->
                log("❌ Could not update queue: ${error.message ?: error.javaClass.simpleName}")
                return
            }
        publishFlashOperationDraft(next, persist = true)
        log(text(R.string.flash_queue_updated_log, item.partition, item.displayName))
    }

    fun clearFlashQueueDraft() {
        publishFlashOperationDraft(
            FlashOperationDraftPolicy.clear(currentFlashOperationDraft()),
            persist = true
        )
        log(text(R.string.flash_queue_cleared_log))
    }

    fun executeFlashQueueDraft() {
        val draft = currentFlashOperationDraft()
        if (draft.items.isEmpty()) {
            log(text(R.string.flash_queue_empty_log))
            return
        }
        val executionItems = ArrayList<FlashQueueItem>(draft.items.size)
        for (item in draft.items) {
            val file = FlashOperationDraftPolicy.resolve(item)
            if (file == null) {
                log("❌ ${item.partition} ← ${item.displayName}: file is unavailable")
                return
            }
            executionItems += FlashQueueItem(item.partition, file)
        }
        runFlashQueue(executionItems)
    }

    private fun publishFlashOperationDraft(draft: FlashOperationDraft, persist: Boolean) {
        synchronized(flashDraftLock) { flashDraftSnapshot = draft }
        if (persist) {
            savedStateHandle[SAVED_FLASH_QUEUE_DRAFT] = FlashOperationDraftCodec.encode(draft)
        }
        _flashOperationDraft.value = draft
    }

    private fun runFlashQueue(items: List<FlashQueueItem>) {
        val queue = items.filter { it.partition.isNotBlank() }
        if (queue.isEmpty()) { log(text(R.string.flash_queue_empty_log)); return }

        val order = listOf("vbmeta", "boot", "init_boot", "vendor_boot", "recovery", "dtbo")
        val sorted = queue.sortedBy { item ->
            val idx = order.indexOf(item.partition.lowercase())
            if (idx < 0) order.size else idx
        }

        startOperation(text(R.string.notif_flash_img), "Flash queue: ${sorted.size} item(s) Do not disconnect the cable.") {
            setOperationSteps(sorted.mapIndexed { index, item ->
                OperationStep(
                    index = index + 1,
                    total = sorted.size,
                    title = "flash ${item.partition} ← ${item.file.name}",
                    subtitle = formatBytesShort(item.file.length()),
                    status = OperationStepStatus.PENDING
                )
            })
            val proto = fastbootProtocol
            if (proto?.isConnected != true) {
                markOperationStep(1, OperationStepStatus.FAILED, text(R.string.error_no_fastboot))
                failOperation("No Fastboot connection")
            }
            sorted.forEachIndexed { index, item ->
                val stepNumber = index + 1
                markOperationStep(stepNumber, OperationStepStatus.RUNNING, "fastboot flash ${item.partition}")
                log("=== FLASH QUEUE ${stepNumber}/${sorted.size}: ${item.partition} ← ${item.file.name} ===")
                val result = proto.flashPartitionDetailed(item.partition, item.file)
                val diagnostics = proto.currentDiagnostics()
                if (diagnostics != null) _fastbootDiagnostics.postValue(diagnostics)
                markOperationStep(
                    stepNumber,
                    if (result.success) OperationStepStatus.OK else OperationStepStatus.FAILED,
                    diagnosticsBrief(diagnostics)
                )
                if (!result.success) {
                    log("❌ Queue stopped at partition ${item.partition}")
                    failOperation(formatFlashFailure(item.partition, result))
                }
            }
            log("✅ Flash queue completed")
        }
    }




    private fun formatBytesShort(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    private fun diagnosticsBrief(diagnostics: FastbootProtocol.DeviceDiagnostics?): String {
        if (diagnostics == null) return "none"
        val parts = mutableListOf<String>()
        diagnostics.product?.takeIf { it.isNotBlank() }?.let { parts += "product=$it" }
        diagnostics.currentSlot?.takeIf { it.isNotBlank() }?.let { parts += "slot=$it" }
        diagnostics.unlocked?.takeIf { it.isNotBlank() }?.let { parts += "unlocked=$it" }
        diagnostics.secure?.takeIf { it.isNotBlank() }?.let { parts += "secure=$it" }
        diagnostics.antiRollback?.takeIf { it.isNotBlank() }?.let { parts += "anti=$it" }
        diagnostics.isUserspace?.takeIf { it.isNotBlank() }?.let { parts += "is-userspace=$it" }
        diagnostics.superPartitionName?.takeIf { it.isNotBlank() }?.let { parts += "super=$it" }
        return if (parts.isEmpty()) "empty" else parts.joinToString(", ")
    }


    private fun unlockVerificationPrefs() =
        getApplication<Application>().getSharedPreferences(MI_UNLOCK_VERIFY_PREFS, Context.MODE_PRIVATE)

    private fun persistPendingUnlockVerification(
        product: String,
        serial: String?,
        expectedUnlocked: Boolean = true,
        operationLabel: String = "Mi Unlock"
    ) {
        unlockVerificationPrefs().edit {
            putString(MI_UNLOCK_VERIFY_PRODUCT, product.trim())
            putString(MI_UNLOCK_VERIFY_SERIAL, serial?.trim())
            putBoolean(MI_UNLOCK_VERIFY_EXPECTED_UNLOCKED, expectedUnlocked)
            putString(MI_UNLOCK_VERIFY_OPERATION_LABEL, operationLabel)
            putLong(MI_UNLOCK_VERIFY_CREATED_AT, System.currentTimeMillis())
        }
    }

    private fun readPendingUnlockVerification(): PendingUnlockVerification? {
        val prefs = unlockVerificationPrefs()
        val product = prefs.getString(MI_UNLOCK_VERIFY_PRODUCT, null).trimToNull() ?: return null
        return PendingUnlockVerification(
            product = product,
            serial = prefs.getString(MI_UNLOCK_VERIFY_SERIAL, null).trimToNull(),
            expectedUnlocked = prefs.getBoolean(MI_UNLOCK_VERIFY_EXPECTED_UNLOCKED, true),
            operationLabel = prefs.getString(MI_UNLOCK_VERIFY_OPERATION_LABEL, null).trimToNull() ?: "Mi Unlock",
            createdAtMs = prefs.getLong(MI_UNLOCK_VERIFY_CREATED_AT, 0L)
        )
    }

    private fun clearPendingUnlockVerification() {
        unlockVerificationPrefs().edit { clear() }
    }

    private fun sideloadVerificationPrefs() =
        getApplication<Application>().getSharedPreferences(SIDELOAD_VERIFY_PREFS, Context.MODE_PRIVATE)

    private fun persistPendingSideloadVerification(file: File, proto: AdbProtocol) {
        val device = adbBannerProperty(proto.currentDiagnostics().remoteBanner, "ro.product.device")
        @Suppress("UseKtx")
        val saved = sideloadVerificationPrefs()
            .edit()
            .putString(SIDELOAD_VERIFY_PACKAGE, file.name)
            .putLong(SIDELOAD_VERIFY_PACKAGE_SIZE, file.length())
            .putString(SIDELOAD_VERIFY_DEVICE, device)
            .putLong(SIDELOAD_VERIFY_CREATED_AT, System.currentTimeMillis())
            .commit()
        if (!saved) {
            log("⚠️ Could not save ADB Sideload result marker.")
        }
    }

    private fun readPendingSideloadVerification(): PendingSideloadVerification? {
        val prefs = sideloadVerificationPrefs()
        val packageName = prefs.getString(SIDELOAD_VERIFY_PACKAGE, null).trimToNull() ?: return null
        return PendingSideloadVerification(
            packageName = packageName,
            packageSize = prefs.getLong(SIDELOAD_VERIFY_PACKAGE_SIZE, -1L),
            device = prefs.getString(SIDELOAD_VERIFY_DEVICE, null).trimToNull(),
            createdAtMs = prefs.getLong(SIDELOAD_VERIFY_CREATED_AT, 0L)
        )
    }

    private fun clearPendingSideloadVerification() {
        sideloadVerificationPrefs().edit { clear() }
    }

    private fun adbBannerProperty(banner: String, key: String): String? {
        val marker = "$key="
        val start = banner.indexOf(marker)
        if (start < 0) return null
        return banner.substring(start + marker.length)
            .substringBefore(';')
            .trimToNull()
    }

    private fun verifyPendingSideloadIfReady(proto: AdbProtocol) {
        val pending = readPendingSideloadVerification() ?: return
        if (proto.peerMode != AdbProtocol.PeerMode.RECOVERY) return

        val ageMs = System.currentTimeMillis() - pending.createdAtMs
        if (pending.createdAtMs <= 0L || ageMs < 0L || ageMs > SIDELOAD_VERIFY_TIMEOUT_MS) {
            clearPendingSideloadVerification()
            log("⚠️ ADB Sideload result wait timed out; check the install result on the Recovery screen.")
            return
        }

        val currentDevice = adbBannerProperty(proto.currentDiagnostics().remoteBanner, "ro.product.device")
        if (pending.device != null) {
            if (currentDevice == null) return
            if (!pending.device.equals(currentDevice, ignoreCase = true)) return
        }

        log("=== ADB SIDELOAD RESULT ===")
        log("Package: ${pending.packageName} (${pending.packageSize} bytes)")
        val verification = proto.inspectRecoveryInstallResult()
        verification.source?.let { log("Recovery source: $it") }
        verification.evidence?.takeIf { it.isNotBlank() }?.let { log("Recovery evidence: $it") }

        when (verification.verdict) {
            RecoveryInstallVerifier.Verdict.SUCCESS -> {
                clearPendingSideloadVerification()
                log("✅ Recovery reports successful installation: ${verification.message}")
            }
            RecoveryInstallVerifier.Verdict.FAILED -> {
                clearPendingSideloadVerification()
                val detail = buildString {
                    append(verification.message)
                    verification.evidence?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
                log("❌ Recovery reports an install error: $detail")
            }
            RecoveryInstallVerifier.Verdict.UNKNOWN -> {
                log("ℹ️ Transfer completed; Recovery did not provide a clear install result.")
            }
        }
    }

    private fun verifyPendingUnlockIfReady(diagnostics: FastbootProtocol.DeviceDiagnostics) {
        val pending = readPendingUnlockVerification() ?: return
        val ageMs = System.currentTimeMillis() - pending.createdAtMs
        if (pending.createdAtMs <= 0L || ageMs < 0L || ageMs > MI_UNLOCK_VERIFY_TIMEOUT_MS) {
            clearPendingUnlockVerification()
            log("⚠️ Verification wait ${pending.operationLabel} timed out. Start the operation again.")
            return
        }

        val currentProduct = diagnostics.product?.trim()
        if (!currentProduct.equals(pending.product, ignoreCase = true)) {
            logFileOnly(
                "Pending ${pending.operationLabel} verification ignored for another product: " +
                    "pending=${pending.product}, current=${currentProduct ?: "unknown"}"
            )
            return
        }

        val currentSerial = diagnostics.serialno?.trim()?.takeIf { it.isNotBlank() }
        if (pending.serial != null) {
            if (currentSerial == null) {
                logFileOnly(
                    "Pending ${pending.operationLabel} verification waits for serial identity: " +
                        "pending=${pending.serial}, current=unknown"
                )
                return
            }
            if (!pending.serial.equals(currentSerial, ignoreCase = true)) {
                logFileOnly(
                    "Pending ${pending.operationLabel} verification ignored for another serial: " +
                        "pending=${pending.serial}, current=$currentSerial"
                )
                return
            }
        }

        val actualUnlocked = FastbootValueParser.parseBoolean(diagnostics.unlocked)
        val expectedText = if (pending.expectedUnlocked) "yes" else "no"
        when (actualUnlocked) {
            pending.expectedUnlocked -> {
                clearPendingUnlockVerification()
                val message = "${pending.operationLabel} confirmed by device: getvar:unlocked=$expectedText"
                log("🎉 $message")
                _operationProgress.postValue(
                    OperationProgress(
                        title = pending.operationLabel,
                        percent = 100,
                        detail = message,
                        finished = true,
                        success = true,
                        outcome = OperationOutcomeKind.SUCCESS
                    )
                )
            }
            null -> log("ℹ️ ${pending.operationLabel} is waiting for final verification: getvar:unlocked is not available yet.")
            else -> {
                clearPendingUnlockVerification()
                val actualText = if (actualUnlocked) "yes" else "no"
                val message = "${pending.operationLabel} not confirmed: device reports getvar:unlocked=$actualText, expected $expectedText"
                log("❌ $message")
                _operationProgress.postValue(
                    OperationProgress(
                        title = pending.operationLabel,
                        percent = 0,
                        detail = message,
                        finished = true,
                        success = false,
                        outcome = OperationOutcomeKind.FAILED
                    )
                )
            }
        }
    }

    fun runSideload(file: File) {
        val proto = adbProtocol
        if (proto?.isConnected != true) {
            log(text(R.string.error_no_adb))
            return
        }

        clearPendingSideloadVerification()
        startOperation(text(R.string.notif_adb_sideload), text(R.string.notif_sideload_sending, file.name)) {
            when (val result = proto.sideloadZip(file)) {
                AdbProtocol.SideloadResult.TransferComplete -> {
                    persistPendingSideloadVerification(file, proto)
                    verificationPending(
                        "File transfer completed. Recovery decides whether the package is valid; the install result will be read after returning to Recovery if a log is available."
                    )
                }
                is AdbProtocol.SideloadResult.TransferClosedBeforeDoneDone -> {
                    persistPendingSideloadVerification(file, proto)
                    verificationPending(
                        "Recovery closed ADB before DONEDONE after ≈${result.percent}% transfer. This is not treated as a transport error; check the final install result on the Recovery screen or after returning to Recovery."
                    )
                }
                AdbProtocol.SideloadResult.Cancelled -> {
                    throw OperationAbort(OperationOutcome.Cancelled("ADB Sideload cancelled"))
                }
                is AdbProtocol.SideloadResult.NotInSideloadMode -> {
                    failOperation("ADB Sideload is not active. Current mode: ${result.mode.name}")
                }
                is AdbProtocol.SideloadResult.Failed -> {
                    failOperation("ADB Sideload [${result.kind.name}]: ${result.message}")
                }
            }
        }
    }

    /**
     * Запускает ровно одну долгую USB-операцию. Новая операция не вытесняет
     * активную: сначала текущая должна завершиться либо пройти безопасную отмену
     * с подтверждённым возвратом native USBFS вызова.
     */
    private fun startOperation(
        title: String,
        text: String,
        heavy: Boolean = true,
        prepareFastbootSession: Boolean = true,
        block: suspend OperationContext.() -> Unit
    ) {
        synchronized(operationLaunchLock) {
            if (viewModelCleared.get()) {
                log("⚠️ New operation rejected: DeviceViewModel is already finishing safe USB shutdown.")
                return
            }
            if (transportRestartRequired.get()) {
                log("⛔ New operation is blocked: USB transport requires a full NekoFlash restart after unconfirmed cleanup.")
                return
            }
            if (connectionJob?.isCompleted == false) {
                log("⚠️ USB connect or disconnect is still in progress. Wait for a stable device status.")
                return
            }
            if (operationJob?.isCompleted == false || NativeUsbfsBackend.hasActiveTransfer ||
                NativeUsbfsBackend.backendState().nativeTransferActive
            ) {
                log("⚠️ Another USB operation is still active. Wait for it to finish first or perform a safe cancel.")
                return
            }

            val gen = operationGeneration.incrementAndGet()

            if (heavy) {
                releaseOperationWakeLock(logRelease = false)
                acquireOperationWakeLock()
                FlashOperationService.start(getApplication(), title, text)
                _operationProgress.postValue(OperationProgress(title = title, percent = -1, detail = text))
            }
            _operationActive.postValue(true)
            diagnosticSessionTracker.recordOperationStarted(title)
            persistSessionSummary()

            val newJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val context = OperationContext()
                var outcome: OperationOutcome = OperationOutcome.Success
                try {
                    val fastboot = fastbootProtocol
                    if (prepareFastbootSession && fastboot?.isConnected == true && !fastboot.beginOperation()) {
                        throw OperationAbort(
                            OperationOutcome.Failed(
                                "Fastboot session is not ready for a new operation. Reconnect the device."
                            )
                        )
                    }
                    context.block()
                } catch (abort: OperationAbort) {
                    outcome = abort.outcome
                } catch (_: CancellationException) {
                    outcome = OperationOutcome.Cancelled("Operation cancelled")
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    outcome = OperationOutcome.Failed(message)
                    log(text(R.string.operation_error, message))
                } finally {
                    if (gen == operationGeneration.get()) {
                        _operationActive.postValue(false)
                        if (heavy) {
                            releaseOperationWakeLock(logRelease = true)
                            FlashOperationService.stop(getApplication())

                            val finishedProgress = _operationProgress.value
                            val outcomeKind = when (outcome) {
                                OperationOutcome.Success -> OperationOutcomeKind.SUCCESS
                                is OperationOutcome.Failed -> OperationOutcomeKind.FAILED
                                is OperationOutcome.Cancelled -> OperationOutcomeKind.CANCELLED
                                is OperationOutcome.VerifyPending -> OperationOutcomeKind.VERIFY_PENDING
                            }
                            val outcomeDetail = when (outcome) {
                                OperationOutcome.Success -> finishedProgress?.detail.orEmpty()
                                is OperationOutcome.Failed -> outcome.message
                                is OperationOutcome.Cancelled -> outcome.message
                                is OperationOutcome.VerifyPending -> outcome.message
                            }
                            val successful = outcome === OperationOutcome.Success
                            val previousPercent = finishedProgress?.percent ?: 0
                            _operationProgress.postValue(
                                OperationProgress(
                                    title = finishedProgress?.title ?: title,
                                    percent = if (successful) 100 else previousPercent.coerceAtLeast(0),
                                    detail = outcomeDetail,
                                    finished = true,
                                    success = successful,
                                    outcome = outcomeKind
                                )
                            )
                        }
                        val summaryOutcome = when (outcome) {
                            OperationOutcome.Success -> "SUCCESS"
                            is OperationOutcome.Failed -> "FAILED"
                            is OperationOutcome.Cancelled -> "CANCELLED"
                            is OperationOutcome.VerifyPending -> "VERIFY_PENDING"
                        }
                        diagnosticSessionTracker.recordOperationFinished(title, summaryOutcome)
                        persistSessionSummary()
                        operationJob = null
                    }
                }
            }
            operationJob = newJob
            newJob.start()
        }
    }

    private fun acquireOperationWakeLock() {
        try {
            val wl = getApplication<Application>()
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NekoFlash:FlashOperation")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }
            operationWakeLock = wl
            log(text(R.string.wake_lock_acquired))
        } catch (e: Exception) {
            operationWakeLock = null
            log(text(R.string.wake_lock_error, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun releaseOperationWakeLock(logRelease: Boolean) {
        val wl = operationWakeLock ?: return
        operationWakeLock = null
        try {
            if (wl.isHeld) wl.release()
            if (logRelease) log(text(R.string.wake_lock_released))
        } catch (e: Exception) {
            if (logRelease) log(text(R.string.wake_lock_release_error, e.message ?: e.javaClass.simpleName))
        }
    }

    fun cancelActiveOperation() {
        val job = operationJob
        if (job == null || job.isCompleted) {
            log(text(R.string.operation_cancelled))
            return
        }

        // Do not publish a false "cancelled" state yet. Native USBFS is a
        // blocking JNI call and must first DISCARD + REAP every pending URB.
        // The operation's finally block releases the WakeLock/FGS only after
        // the native call has actually returned.
        // Publish coroutine cancellation first, then wake the blocking USB
        // transport. This closes the narrow race where native could return
        // before the Job was marked cancelled.
        job.cancel(CancellationException("Operation cancellation requested"))
        fastbootProtocol?.cancel()
        adbProtocol?.cancel()
        _operationProgress.postValue(
            _operationProgress.value?.copy(
                detail = "Cancellation requested. Finishing pending USB URB and closing the session safely…",
                finished = false,
                success = false,
                outcome = null
            )
        )
        log("⏳ Cancel requested. WakeLock and foreground service will remain active until USB cleanup actually finishes.")
    }

    // ─── ОТКЛЮЧЕНИЕ ──────────────────────────────────────────────────────────

    fun disconnectCurrent() {
        synchronized(operationLaunchLock) {
            val generation = connectionGeneration.incrementAndGet()
            val transitionJob = transportScope.launch(start = CoroutineStart.LAZY) {
                transportTransitionMutex.withLock {
                    val clean = shutdownCurrentTransportsSafely("disconnect generation=$generation")
                    if (clean) publishDisconnectedState("disconnect")
                }
            }
            connectionJob = transitionJob
            transitionJob.start()
        }
    }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        flushDiagnostics("VIEWMODEL_CLEARED", terminal = true)
        viewModelCleared.set(true)
        synchronized(operationLaunchLock) {
            val generation = connectionGeneration.incrementAndGet()
            val transitionJob = transportScope.launch(start = CoroutineStart.LAZY) {
                transportTransitionMutex.withLock {
                    val clean = shutdownCurrentTransportsSafely("ViewModel cleared generation=$generation")
                    if (clean) publishDisconnectedState("ViewModel cleared")
                }
                transportScope.cancel()
            }
            connectionJob = transitionJob
            transitionJob.start()
        }
    }

    companion object {
        private const val SAVED_FLASH_QUEUE_DRAFT = "flash_queue_draft_v1"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        private const val TRANSPORT_SHUTDOWN_TIMEOUT_MS = 100_000L
        private const val TRANSPORT_IDLE_POLL_MS = 10L
        private const val MI_UNLOCK_VERIFY_TIMEOUT_MS = 24L * 60L * 60L * 1000L
        private const val SIDELOAD_VERIFY_TIMEOUT_MS = 60L * 60L * 1000L
        private const val MAX_OPERATION_STEPS_IN_UI = 240
        private const val MI_UNLOCK_VERIFY_PREFS = "mi_unlock_verify"
        private const val SIDELOAD_VERIFY_PREFS = "sideload_verify"
        private const val MI_UNLOCK_VERIFY_PRODUCT = "product"
        private const val MI_UNLOCK_VERIFY_SERIAL = "serial"
        private const val MI_UNLOCK_VERIFY_EXPECTED_UNLOCKED = "expected_unlocked"
        private const val MI_UNLOCK_VERIFY_OPERATION_LABEL = "operation_label"
        private const val MI_UNLOCK_VERIFY_CREATED_AT = "created_at"
        private const val SIDELOAD_VERIFY_PACKAGE = "package_name"
        private const val SIDELOAD_VERIFY_PACKAGE_SIZE = "package_size"
        private const val SIDELOAD_VERIFY_DEVICE = "device"
        private const val SIDELOAD_VERIFY_CREATED_AT = "created_at"
    }
}
