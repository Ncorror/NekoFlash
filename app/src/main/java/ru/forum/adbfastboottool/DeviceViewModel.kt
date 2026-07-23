package ru.forum.adbfastboottool

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DeviceViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    enum class ConnectionState { NONE, CONNECTING, FASTBOOT, ADB, ERROR }

    data class SelfTestReportArtifacts(
        val textFile: File,
        val jsonFile: File
    )

    enum class SelfTestResult { NOT_RUN, RUNNING, PASS, WARN_FAIL }

    data class SelfTestStatus(
        val result: SelfTestResult,
        val summary: String,
        val updatedAt: String? = null,
        val textReportPath: String? = null,
        val jsonReportPath: String? = null
    )

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
        val detail: String,       // строка скорости/ETA или статус
        val finished: Boolean = false,  // операция завершена (показать результат)
        val success: Boolean = false,   // успех (для обратной совместимости UI)
        val outcome: OperationOutcomeKind? = null
    )

    private val _operationProgress = MutableLiveData<OperationProgress?>(null)
    val operationProgress: LiveData<OperationProgress?> = _operationProgress

    fun postOperationProgress(progress: OperationProgress?) {
        _operationProgress.postValue(progress)
    }

    private val _selfTestStatus = MutableLiveData(
        SelfTestStatus(
            result = SelfTestResult.NOT_RUN,
            summary = "Self-test ещё не запускался"
        )
    )
    val selfTestStatus: LiveData<SelfTestStatus> = _selfTestStatus

    private val _connectionInfo    = MutableLiveData<String?>(null)
    val connectionInfo: LiveData<String?> = _connectionInfo

    private val _fastbootDiagnostics = MutableLiveData<FastbootProtocol.DeviceDiagnostics?>(null)
    val fastbootDiagnostics: LiveData<FastbootProtocol.DeviceDiagnostics?> = _fastbootDiagnostics

    private val _fastbootPartitionInventory = MutableLiveData<FastbootPartitionInventory.Snapshot?>(null)
    val fastbootPartitionInventory: LiveData<FastbootPartitionInventory.Snapshot?> = _fastbootPartitionInventory

    private val _adbPeerMode = MutableLiveData<AdbProtocol.PeerMode?>(null)
    val adbPeerMode: LiveData<AdbProtocol.PeerMode?> = _adbPeerMode

    private val _diagnosticMode = MutableLiveData(DiagnosticModePolicy.Mode.NORMAL)
    val diagnosticMode: LiveData<DiagnosticModePolicy.Mode> = _diagnosticMode

    private val _readOnlyMutationLock = MutableLiveData(false)
    val readOnlyMutationLock: LiveData<Boolean> = _readOnlyMutationLock

    private val _diagnosticReadiness = MutableLiveData<DiagnosticReadiness.Result?>(null)
    val diagnosticReadiness: LiveData<DiagnosticReadiness.Result?> = _diagnosticReadiness

    private val _transportSessionId = MutableLiveData<String?>(null)
    val transportSessionId: LiveData<String?> = _transportSessionId

    private val flashDraftLock = Any()
    private val flashDraftPreparationSequence = AtomicLong(0L)
    private val flashDraftPreparationTokens = ConcurrentHashMap<String, Long>()
    private val quickFlashConfirmationRegistry = QuickFlashConfirmationRegistry()
    private var flashDraftSnapshot: FlashOperationDraft = FlashOperationDraftPolicy.markNeedsRevalidation(
        FlashOperationDraftCodec.decode(savedStateHandle.get<ArrayList<String>>(SAVED_FLASH_QUEUE_DRAFT))
    )
    private val _flashOperationDraft = MutableLiveData(flashDraftSnapshot)
    val flashOperationDraft: LiveData<FlashOperationDraft> = _flashOperationDraft

    /** Transient execution object. It is never placed in SavedStateHandle. */
    data class FlashQueueItem(val partition: String, val file: File)

    private data class PreparedFastbootDataArtifact(
        val sourceFile: File,
        val stagedFile: File,
        val artifactId: String,
        val bytes: Long
    )

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
        val packageSha256: String?,
        val device: String?,
        val createdAtMs: Long
    )

    private fun text(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

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
    private var lastCompactMessageAtMs: Long = 0L
    private var suppressedDuplicateCount: Long = 0L
    private var lastSummaryPersistAtMs: Long = 0L
    private var workspaceRoot: File? = null
    private var lastUsbSessionSnapshot: UsbSessionSnapshot? = null
    private var connectedUsbManager: UsbManager? = null
    private var activeTransportSessionId: String? = null
    private val transportSessionSequence = AtomicLong(0L)
    @Volatile private var diagnosticModeValue: DiagnosticModePolicy.Mode = DiagnosticModePolicy.Mode.NORMAL
    @Volatile private var readOnlyMutationLockEnabled: Boolean = false
    private val adbKeyDir: File = File(application.filesDir, "adbkeys")
    private val fastbootStageDir: File = File(application.filesDir, "fastboot-stage")

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
    private val transportTransitionMutex = Mutex()
    private val transportRestartRequired = AtomicBoolean(false)
    private val viewModelCleared = AtomicBoolean(false)
    @Volatile
    private var connectedUsbTarget: UsbDeviceInspector.Candidate? = null
    @Volatile
    private var pendingUsbTargetKey: String? = null
    @Volatile
    private var connectedDeviceInfo: String? = null
    private val quarantinedUsbTargets = ConcurrentHashMap.newKeySet<String>()
    private var debugLoggingEnabled: Boolean = false
    private var operationWakeLock: PowerManager.WakeLock? = null

    // FIX #9: AtomicLong вместо обычного Long — потокобезопасно
    private val operationGeneration = AtomicLong(0L)
    private val connectionGeneration = AtomicLong(0L)
    private val processSessionId: String = UUID.randomUUID().toString()
    private val diagnosticSessionTracker = DiagnosticSessionTracker(processSessionId, buildId = BuildConfig.BUILD_ID)

    private fun currentFastbootGenerationToken(): String =
        "$processSessionId:${connectionGeneration.get()}"

    init {
        clearFastbootStaging("ViewModel init")
        DiagnosticCrashMarker.install(File(application.filesDir, "diagnostics"), BuildConfig.BUILD_ID)
        diagnosticSessionTracker.recordDiagnosticState(diagnosticModeValue, readOnlyMutationLockEnabled)
        if (flashDraftSnapshot.items.isNotEmpty()) {
            revalidateFlashQueueDraft(restoredFromSavedState = true)
        }
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
            log("⚠️ Не удалось создать папку логов: ${logsDir.absolutePath}")
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val seedInitialLines = !logFileConfigured
        val store = try {
            DiagnosticLogStore(logsDir, stamp)
        } catch (e: Exception) {
            log("⚠️ Не удалось инициализировать bounded log store: ${e.message ?: e.javaClass.simpleName}")
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
        log("Лог-файл: /sdcard/Download/NekoFlash/logs/${createdLog?.name ?: "log-$stamp.txt"}")
        log("ℹ️ Сырой USB/Fastboot trace отделён от основного журнала и автоматически ротируется.")
    }

    fun log(message: String) {
        synchronized(logLock) {
            val now = System.currentTimeMillis()
            val duplicate = message == lastCompactMessage &&
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
            appendCompactMessageLocked(message)
            lastCompactMessage = message
            lastCompactMessageAtMs = now
        }
    }

    fun clearLog() {
        synchronized(logLock) {
            flushSuppressedDuplicatesLocked()
            lines.clear()
            lastCompactMessage = null
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

    private fun appendCompactMessageLocked(message: String) {
        lines.add(message)
        while (lines.size > 600) lines.removeAt(0)
        _logLines.postValue(lines.toList())
        val classification = DiagnosticLogPolicy.classify(message)
        diagnosticSessionTracker.recordCompact(message, classification)
        appendRawToLogFile(formatLogLine(message))
        val now = System.currentTimeMillis()
        if (classification.level == DiagnosticLogPolicy.Level.ERROR || now - lastSummaryPersistAtMs >= 30_000L) {
            lastSummaryPersistAtMs = now
            persistSessionSummary()
        }
    }

    private fun flushSuppressedDuplicatesLocked() {
        if (suppressedDuplicateCount <= 0L) return
        val repeated = "↻ Предыдущая строка повторилась ещё $suppressedDuplicateCount раз(а); дубликаты свёрнуты."
        suppressedDuplicateCount = 0L
        appendCompactMessageLocked(repeated)
    }

    private fun appendRawToLogFile(text: String) {
        try {
            logStore?.appendCompact(text)
            logFile = logStore?.currentCompactFile() ?: logFile
        } catch (_: Exception) {
        }
    }

    private fun appendRawToTraceFile(text: String) {
        try {
            logStore?.appendTrace(text)
            traceLogFile = logStore?.currentTraceFile() ?: traceLogFile
        } catch (_: Exception) {
        }
    }

    private fun persistSessionSummary(): File? = try {
        val file = logStore?.writeSessionSummary(
            DiagnosticSessionTracker.toJson(diagnosticSessionTracker.snapshot())
        )
        sessionSummaryFile = file
        file
    } catch (_: Exception) {
        sessionSummaryFile
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
        quickFlashConfirmationRegistry.clear()
        val sessionId = createTransportSessionId(candidate)
        activeTransportSessionId = sessionId
        _transportSessionId.postValue(sessionId)
        val snapshot = UsbSessionSnapshot.capture(sessionId, candidate)
        lastUsbSessionSnapshot = snapshot
        diagnosticSessionTracker.recordTransportSession(sessionId, candidate.mode.name, connectedDeviceInfo)
        diagnosticSessionTracker.recordDiagnosticState(diagnosticModeValue, readOnlyMutationLockEnabled)
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
                log("⚠️ USB session snapshot не сохранён: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun connectDevice(
        usbManager: UsbManager,
        candidate: UsbDeviceInspector.Candidate,
        automatic: Boolean = false
    ) {
        val retryKey = candidate.stableKey

        if (quarantinedUsbTargets.contains(retryKey)) {
            val message =
                "USB instance quarantined after transport failure: " +
                    "stable=$retryKey logical=${candidate.logicalSignature}"
            if (candidate.mode == UsbDeviceInspector.Mode.FASTBOOT) {
                if (!automatic) {
                    log("⛔ Эта Fastboot USB-сессия уже потеряла синхронизацию. Отключите кабель/OTG, дождитесь detach и подключите устройство заново.")
                }
                logFileOnly(message)
                return
            }
            if (automatic) {
                logFileOnly(message)
                return
            }
            quarantinedUsbTargets.remove(retryKey)
        }

        if (transportRestartRequired.get()) {
            log("⛔ USB transport заблокирован после неподтверждённой очистки. Полностью перезапустите NekoFlash перед новым подключением.")
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
                    if (!shutdownCurrentTransportsSafely("новая USB generation=$generation")) {
                        if (pendingUsbTargetKey == candidate.stableKey) pendingUsbTargetKey = null
                        _connectionState.postValue(ConnectionState.ERROR)
                        return@withLock
                    }
                    if (viewModelCleared.get() || generation != connectionGeneration.get()) {
                        if (pendingUsbTargetKey == candidate.stableKey) pendingUsbTargetKey = null
                        return@withLock
                    }

                    clearFastbootStaging("новая USB generation=$generation")
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
        val retryKey = candidate.stableKey

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
            proto.readOnlyMutationLock = readOnlyMutationLockEnabled
            proto.readOnlyMutationLockReason = "Diagnostic session ${activeTransportSessionId ?: "unknown"}"
            var published = false
            try {
                val connectStartedNs = System.nanoTime()
                if (!proto.connect()) {
                    diagnosticSessionTracker.recordMilestone("fastboot.connect.failed", (System.nanoTime() - connectStartedNs) / 1_000_000L)
                    quarantinedUsbTargets.add(retryKey)
                    if (generation == connectionGeneration.get()) _connectionState.postValue(ConnectionState.ERROR)
                    return
                }
                diagnosticSessionTracker.recordMilestone("fastboot.connect", (System.nanoTime() - connectStartedNs) / 1_000_000L)
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return

                val qualifiedProduct = proto.qualifyConnection()
                if (qualifiedProduct == null) {
                    quarantinedUsbTargets.add(retryKey)
                    if (generation == connectionGeneration.get()) _connectionState.postValue(ConnectionState.ERROR)
                    log("ОШИБКА: Fastboot handshake не подтверждён. Текущая USB-сессия помещена в карантин; отключите и заново подключите кабель/OTG.")
                    return
                }
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return

                val diagnostics = proto.refreshDiagnostics(force = true, knownProduct = qualifiedProduct)
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return
                if (proto.isSessionBroken) {
                    quarantinedUsbTargets.add(retryKey)
                    _connectionState.postValue(ConnectionState.ERROR)
                    log("ОШИБКА: USB-интерфейс открылся, но корректный Fastboot-обмен не подтвердился")
                    return
                }

                applyFastbootDataTransportPreference(proto, diagnostics)
                quarantinedUsbTargets.remove(retryKey)
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
            proto.readOnlyMutationLock = readOnlyMutationLockEnabled
            proto.readOnlyMutationLockReason = "Diagnostic session ${activeTransportSessionId ?: "unknown"}"
            proto.onTransportFailure = { code, message ->
                if (!viewModelCleared.get() && generation == connectionGeneration.get()) {
                    quarantinedUsbTargets.add(retryKey)
                    diagnosticSessionTracker.recordTermination("ADB_READER_${code.name}")
                    diagnosticSessionTracker.recordMilestone("adb.reader.failure", 0L)
                    persistSessionSummary()
                    _connectionState.postValue(ConnectionState.ERROR)
                    log("⛔ ADB transport остановлен [${code.name}]: $message. Автоповтор запрещён до ручного переподключения.")
                }
            }
            var published = false
            try {
                val connectStartedNs = System.nanoTime()
                if (!proto.connect()) {
                    diagnosticSessionTracker.recordMilestone("adb.connect.failed", (System.nanoTime() - connectStartedNs) / 1_000_000L)
                    quarantinedUsbTargets.add(retryKey)
                    if (generation == connectionGeneration.get()) _connectionState.postValue(ConnectionState.ERROR)
                    return
                }
                diagnosticSessionTracker.recordMilestone("adb.connect", (System.nanoTime() - connectStartedNs) / 1_000_000L)
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return

                quarantinedUsbTargets.remove(retryKey)
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
            log("⏳ USB shutdown requested ($reason). Сначала отменяем операцию и ждём подтверждённый Native USBFS drain.")
            activeOperation?.cancel(CancellationException("Transport shutdown requested: $reason"))
            fastbootProtocol?.cancel()
            adbProtocol?.cancel()

            val clean = withTimeoutOrNull(TRANSPORT_SHUTDOWN_TIMEOUT_MS) {
                activeOperation?.join()
                awaitNativeUsbfsIdle()
            } == true

            if (!clean) {
                transportRestartRequired.set(true)
                log("⛔ Безопасное завершение USB не подтверждено за ${TRANSPORT_SHUTDOWN_TIMEOUT_MS} мс. UsbDeviceConnection не закрывается; новые подключения запрещены до полного перезапуска NekoFlash.")
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
            log("⛔ Native USBFS всё ещё сообщает активную передачу после ожидания. Закрытие USB запрещено до перезапуска приложения.")
            _connectionState.postValue(ConnectionState.ERROR)
            return false
        }

        val fastbootClosed = fastbootProtocol?.disconnect() ?: true
        if (!fastbootClosed) {
            transportRestartRequired.set(true)
            log("⛔ FastbootProtocol отказался закрывать USB до подтверждённого drain. Новые подключения запрещены до перезапуска NekoFlash.")
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
        clearFastbootStaging(reason)
        quickFlashConfirmationRegistry.clear()
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

    fun isUsbRetryQuarantined(candidate: UsbDeviceInspector.Candidate): Boolean =
        quarantinedUsbTargets.contains(candidate.stableKey)

    fun allowModeSwitchUsbRetry(candidate: UsbDeviceInspector.Candidate) {
        // selectModeSwitchCandidate is only called after a real detach and a different
        // logical USB profile appears, so this is a new transport generation.
        quarantinedUsbTargets.remove(candidate.stableKey)
    }

    fun noteUsbDetached(device: UsbDevice) {
        val prefix = "${device.deviceName}:${device.vendorId}:${device.productId}:"
        quarantinedUsbTargets.removeIf { it.startsWith(prefix) }
    }

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
        return "Режим: $mode | Устройство: $name | VID=${device.vendorId} | PID=${device.productId} | " +
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
            log(if (details.isBlank()) "Режим: $modeLabel" else "Режим: $modeLabel | $details")
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
    fun currentUsbSessionSnapshot(): UsbSessionSnapshot? = lastUsbSessionSnapshot
    fun currentDiagnosticMode(): DiagnosticModePolicy.Mode = diagnosticModeValue
    fun isReadOnlyMutationLockEnabled(): Boolean = readOnlyMutationLockEnabled
    fun currentTransportSessionId(): String? = activeTransportSessionId
    fun currentBuildId(): String = BuildConfig.BUILD_ID
    fun isDebugLoggingEnabled(): Boolean = debugLoggingEnabled

    fun cycleDiagnosticMode(): DiagnosticModePolicy.Mode {
        val next = DiagnosticModePolicy.next(diagnosticModeValue)
        setDiagnosticMode(next)
        return next
    }

    fun setDiagnosticMode(mode: DiagnosticModePolicy.Mode) {
        diagnosticModeValue = mode
        _diagnosticMode.postValue(mode)
        val state = DiagnosticModePolicy.state(mode)
        if (state.mutationLockRequired) setReadOnlyMutationLock(true, "Diagnostic mode ${mode.name}")
        if (state.rawTraceRequired && !debugLoggingEnabled) setDebugLogging(true)
        diagnosticSessionTracker.recordDiagnosticState(mode, readOnlyMutationLockEnabled)
        persistSessionSummary()
        log("=== DIAGNOSTIC MODE: ${state.userLabel} ===")
        if (state.mutationLockRequired) log("⛔ ДИАГНОСТИКА — ЗАПИСЬ В РАЗДЕЛЫ ОТКЛЮЧЕНА")
    }

    fun setReadOnlyMutationLock(enabled: Boolean, reason: String = "User diagnostic lock") {
        if (!enabled && DiagnosticModePolicy.state(diagnosticModeValue).mutationLockRequired) {
            log("⛔ READ-ONLY lock нельзя отключить в режиме ${diagnosticModeValue.name}. Сначала выберите NORMAL.")
            return
        }
        readOnlyMutationLockEnabled = enabled
        _readOnlyMutationLock.postValue(enabled)
        fastbootProtocol?.apply {
            readOnlyMutationLock = enabled
            readOnlyMutationLockReason = reason
        }
        adbProtocol?.apply {
            readOnlyMutationLock = enabled
            readOnlyMutationLockReason = reason
        }
        diagnosticSessionTracker.recordDiagnosticState(diagnosticModeValue, enabled)
        persistSessionSummary()
        log(if (enabled) "⛔ Глобальный READ-ONLY lock включён: изменяющие Fastboot-команды запрещены протоколом." else "⚠️ Глобальный READ-ONLY lock отключён.")
    }

    fun flushDiagnostics(reason: String, terminal: Boolean = false) {
        synchronized(logLock) {
            flushSuppressedDuplicatesLocked()
            if (terminal) diagnosticSessionTracker.recordTermination(reason)
            appendRawToTraceFile(
                formatLogLine("[session-flush] reason=$reason terminal=$terminal transportSession=${activeTransportSessionId ?: "none"}")
            )
            persistSessionSummary()
        }
    }

    fun runDiagnosticReadinessCheck(onComplete: (DiagnosticReadiness.Result) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val workspace = workspaceRoot
            val logs = workspace?.let { File(it, "logs") }
            val reports = workspace?.let { File(it, "reports") }
            val candidate = connectedUsbTarget
            val manager = connectedUsbManager
            val nativeState = NativeUsbfsBackend.backendState()
            val mode = when (_connectionState.value ?: ConnectionState.NONE) {
                ConnectionState.ADB -> "ADB"
                ConnectionState.FASTBOOT -> "FASTBOOT"
                ConnectionState.CONNECTING -> "CONNECTING"
                ConnectionState.ERROR -> "ERROR"
                ConnectionState.NONE -> null
            }
            val result = DiagnosticReadiness.evaluate(
                DiagnosticReadiness.Input(
                    buildId = BuildConfig.BUILD_ID,
                    workspaceReady = workspace?.exists() == true && workspace.canWrite(),
                    logsReady = logs?.exists() == true && logs.canWrite(),
                    reportsReady = reports?.exists() == true && reports.canWrite(),
                    usbPermissionGranted = if (candidate == null || manager == null) null else runCatching { manager.hasPermission(candidate.device) }.getOrNull(),
                    usbCandidatePresent = candidate != null,
                    bulkEndpointsPresent = candidate?.let { it.endpointInAddress > 0 && it.endpointOutAddress > 0 } == true,
                    protocolConnected = fastbootProtocol?.isConnected == true || adbProtocol?.isConnected == true,
                    connectionMode = mode,
                    mutationLockEnabled = readOnlyMutationLockEnabled,
                    operationActive = operationJob?.isCompleted == false,
                    transportRestartRequired = transportRestartRequired.get(),
                    nativeTransferActive = NativeUsbfsBackend.hasActiveTransfer || nativeState.nativeTransferActive,
                    freeBytes = workspace?.usableSpace
                )
            )
            _diagnosticReadiness.postValue(result)
            log("=== ПРЕДТЕСТОВАЯ ПРОВЕРКА ===")
            result.checks.forEach { check ->
                val icon = when (check.severity) {
                    DiagnosticReadiness.Severity.PASS -> "✅"
                    DiagnosticReadiness.Severity.WARNING -> "⚠️"
                    DiagnosticReadiness.Severity.BLOCKER -> "⛔"
                }
                log("$icon ${check.code}: ${check.message}${check.detail?.let { " · $it" }.orEmpty()}")
            }
            log(if (result.ready) "✅ ${result.summary()}" else "⛔ ${result.summary()}")
            onComplete(result)
        }
    }

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
                        FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY -> "legacy A-only (без A/B)"
                        FastbootPartitionInventory.SlotTopology.A_B -> "A/B"
                        FastbootPartitionInventory.SlotTopology.UNKNOWN -> "не определена"
                    }
                    val incomplete = inventory.entries.count { it.missingFields.isNotEmpty() }
                    log(
                        "ℹ️ Инвентаризация разделов: ${inventory.entries.size}, " +
                            "топология=$topology, incomplete=$incomplete, " +
                            "point-queries=${inventory.pointQueryCount}, status=${inventory.finalStatus}"
                    )
                    inventory.warnings
                        .filter { it.severity != FastbootPartitionInventory.WarningSeverity.INFO }
                        .take(4)
                        .forEach { warning -> log("⚠️ Inventory ${warning.code}: ${warning.message}") }
                } else {
                    _fastbootPartitionInventory.postValue(null)
                    if (!proto.isSessionBroken) {
                        log("⚠️ getvar:all не дал инвентаризацию; точечная Fastboot-диагностика сохранена.")
                    }
                }

                if (proto.isSessionBroken) {
                    _connectionState.postValue(ConnectionState.ERROR)
                    failOperation("Fastboot-сессия потеряла синхронизацию во время обновления данных. Переподключите устройство.")
                }
            } else {
                val message = text(R.string.error_no_fastboot)
                log(message)
                failOperation(message)
            }
        }
    }

    // ─── ВЫПОЛНЕНИЕ КОМАНД ───────────────────────────────────────────────────

    fun runSelfTest() {
        updateSelfTestStatus(SelfTestResult.RUNNING, "Self-test выполняется…")
        startOperation("Self-test", "Проверка ADB/Fastboot без записи на устройство", heavy = false) {
            try {
                val ok = performSelfTestBody()
                updateSelfTestStatus(
                    result = if (ok) SelfTestResult.PASS else SelfTestResult.WARN_FAIL,
                    summary = if (ok) "PASS: read-only проверка завершена без критических ошибок" else "WARN/FAIL: проверка завершена с предупреждениями"
                )
                if (!ok) failOperation("Self-test завершён с предупреждениями или ошибками")
            } catch (e: Exception) {
                updateSelfTestStatus(SelfTestResult.WARN_FAIL, "FAIL: ${e.message ?: e.javaClass.simpleName}")
                throw e
            }
        }
    }

    fun runSelfTestReportArtifacts(onArtifactsCreated: ((SelfTestReportArtifacts) -> Unit)? = null) {
        updateSelfTestStatus(SelfTestResult.RUNNING, "Self-test report выполняется…")
        startOperation("Self-test report", "Проверка устройства и сохранение отчёта", heavy = false) {
            try {
                val startedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val startIndex = synchronized(logLock) { lines.size }
                val ok = performSelfTestBody()
                val reportLines = synchronized(logLock) { lines.drop(startIndex).toList() }
                val artifacts = writeSelfTestReports(startedAt, ok, reportLines)
                log("✅ Self-test TXT создан: ${artifacts.textFile.absolutePath}")
                log("✅ Self-test JSON создан: ${artifacts.jsonFile.absolutePath}")
                updateSelfTestStatus(
                    result = if (ok) SelfTestResult.PASS else SelfTestResult.WARN_FAIL,
                    summary = if (ok) "PASS: отчёт создан" else "WARN/FAIL: отчёт создан, но проверка дала предупреждения",
                    textReportPath = artifacts.textFile.absolutePath,
                    jsonReportPath = artifacts.jsonFile.absolutePath
                )
                onArtifactsCreated?.invoke(artifacts)
                if (!ok) failOperation("Self-test report создан, но проверка завершилась с предупреждениями или ошибками")
            } catch (e: Exception) {
                updateSelfTestStatus(SelfTestResult.WARN_FAIL, "FAIL: ${e.message ?: e.javaClass.simpleName}")
                throw e
            }
        }
    }

    fun reportsDirectory(): File {
        val workspace = workspaceRoot ?: logFile?.parentFile?.parentFile ?: getApplication<Application>().filesDir
        return File(workspace, "reports")
    }

    private fun updateSelfTestStatus(
        result: SelfTestResult,
        summary: String,
        textReportPath: String? = null,
        jsonReportPath: String? = null
    ) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        _selfTestStatus.postValue(SelfTestStatus(result, summary, stamp, textReportPath, jsonReportPath))
    }

    private fun performSelfTestBody(): Boolean {
        log("=== SELF-TEST / SMOKE TEST ===")
        log("Версия приложения: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        log(connectedDeviceInfo ?: "USB-устройство не подключено через ADB/Fastboot")
        log("Лог-файл: ${logFile?.absolutePath ?: "не создан"}")
        log("ADB key dir: ${adbKeyDir.absolutePath}")

        val ok = when {
            fastbootProtocol?.isConnected == true -> {
                log("Режим проверки: FASTBOOT")
                val fastbootOk = fastbootProtocol?.runSelfTest() == true
                val diagnostics = fastbootProtocol?.currentDiagnostics()
                if (diagnostics != null) _fastbootDiagnostics.postValue(diagnostics)
                log(if (fastbootOk) "✅ Fastboot self-test завершён" else "⚠️ Fastboot self-test завершён с предупреждениями")
                fastbootOk
            }
            adbProtocol?.isConnected == true -> {
                log("Режим проверки: ADB")
                val adbOk = adbProtocol?.runSelfTest() == true
                log(if (adbOk) "✅ ADB self-test завершён" else "⚠️ ADB self-test завершён с предупреждениями")
                adbOk
            }
            else -> {
                log("❌ Нет активного ADB/Fastboot подключения. Подключите устройство и нажмите «Сканировать».")
                log("Проверены только локальные компоненты: лог, папка ADB-ключей, профили.")
                false
            }
        }
        log("=== SELF-TEST DONE ===")
        return ok
    }

    private fun writeSelfTestReports(startedAt: String, ok: Boolean, reportLines: List<String>): SelfTestReportArtifacts {
        val workspace = workspaceRoot ?: logFile?.parentFile?.parentFile ?: getApplication<Application>().filesDir
        val reportsDir = File(workspace, "reports")
        if (!reportsDir.exists() && !reportsDir.mkdirs()) {
            throw IllegalStateException("не удалось создать папку отчётов: ${reportsDir.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val textReport = File(reportsDir, "selftest-$stamp.txt")
        val jsonReport = File(reportsDir, "selftest-$stamp.json")
        val visibleSnapshot = synchronized(logLock) { lines.toList() }
        val sanitizerScope = ReportSanitizer.Scope(
            workspace = workspace,
            logFile = logFile,
            adbKeyDir = adbKeyDir,
            packageName = getApplication<Application>().packageName
        )
        val safeReportLines = ReportSanitizer.sanitizeLines(reportLines, sanitizerScope)
        val safeVisibleSnapshot = ReportSanitizer.sanitizeLines(visibleSnapshot, sanitizerScope)

        val text = buildString {
            appendLine("ADB Fastboot Tool — Self-test report")
            appendLine("Created: $stamp")
            appendLine("Started: $startedAt")
            appendLine("Result: ${if (ok) "PASS" else "WARN/FAIL"}")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Privacy mode: sanitized")
            appendLine("Connection state: ${_connectionState.value ?: ConnectionState.NONE}")
            appendLine("Connection: ${connectedDeviceInfo ?: "not connected"}")
            appendLine("Debug logging: $debugLoggingEnabled")
            appendLine("Log file: ${logFile?.absolutePath ?: "not created"}")
            appendLine("ADB key dir: ${adbKeyDir.absolutePath}")
            appendLine()
            appendLine("--- PARTITION INVENTORY (LAST READ-ONLY SNAPSHOT) ---")
            val inventory = _fastbootPartitionInventory.value
            if (inventory == null) {
                appendLine("Not collected. Use the explicit Refresh action in Fastboot mode.")
            } else {
                appendLine("Product: ${inventory.product ?: "unknown"}")
                appendLine("Topology: ${inventory.topology}")
                appendLine("Current slot: ${inventory.currentSlot ?: "not reported"}")
                appendLine("Concrete partitions: ${inventory.entries.size}")
                appendLine("getvar:all: ${inventory.finalStatus}; complete=${inventory.complete}")
                appendLine("Point queries: ${inventory.pointQueryCount}; unresolved=${inventory.unresolvedPointQueryCount}")
                appendLine("Warnings: ${inventory.warnings.size}")
                inventory.entries.forEach { entry ->
                    appendLine(
                        "- ${entry.name} | risk=${entry.risk} | storage=${entry.storage} | " +
                            "slot=${entry.slotBinding} | size=${entry.sizeBytes ?: "unknown"} | " +
                            "type=${entry.type ?: "unknown"} | sources=${entry.evidenceSources.joinToString("+")}"
                    )
                }
            }
            appendLine()
            appendLine("--- SELF-TEST LOG ---")
            if (reportLines.isEmpty()) {
                appendLine("No self-test lines captured.")
            } else {
                safeReportLines.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("--- FULL VISIBLE LOG SNAPSHOT ---")
            safeVisibleSnapshot.forEach { appendLine(it) }
        }

        textReport.writeText(ReportSanitizer.sanitizeText(text, sanitizerScope), Charsets.UTF_8)
        jsonReport.writeText(ReportSanitizer.sanitizeText(buildSelfTestJson(stamp, startedAt, ok, safeReportLines, safeVisibleSnapshot), sanitizerScope), Charsets.UTF_8)
        return SelfTestReportArtifacts(textReport, jsonReport)
    }

    private fun buildSelfTestJson(
        createdAt: String,
        startedAt: String,
        ok: Boolean,
        reportLines: List<String>,
        visibleSnapshot: List<String>
    ): String {
        val q = DiagnosticJson::quote
        val fastboot = fastbootProtocol?.currentDiagnostics()
        val adb = adbProtocol?.currentDiagnostics()
        val state = (_connectionState.value ?: ConnectionState.NONE).toString()

        fun fastbootJson(): String = if (fastboot == null) {
            "null"
        } else {
            buildString {
                appendLine("{")
                appendLine("      \"product\": ${q(fastboot.product)},")
                appendLine("      \"currentSlot\": ${q(fastboot.currentSlot)},")
                appendLine("      \"slotCount\": ${q(fastboot.slotCount)},")
                appendLine("      \"slotSuffix\": ${q(fastboot.slotSuffix)},")
                appendLine("      \"unlocked\": ${q(fastboot.unlocked)},")
                appendLine("      \"secure\": ${q(fastboot.secure)},")
                appendLine("      \"serialno\": ${q(fastboot.serialno)},")
                appendLine("      \"versionBootloader\": ${q(fastboot.versionBootloader)},")
                appendLine("      \"isUserspace\": ${q(fastboot.isUserspace)},")
                appendLine("      \"superPartitionName\": ${q(fastboot.superPartitionName)},")
                appendLine("      \"maxDownloadSizeRaw\": ${q(fastboot.maxDownloadSizeRaw)},")
                appendLine("      \"maxDownloadSizeBytes\": ${DiagnosticJson.number(fastboot.maxDownloadSizeBytes)},")
                appendLine("      \"maxFetchSizeRaw\": ${q(fastboot.maxFetchSizeRaw)},")
                appendLine("      \"maxFetchSizeBytes\": ${DiagnosticJson.number(fastboot.maxFetchSizeBytes)},")
                appendLine("      \"timestamp\": ${fastboot.timestamp}")
                append("    }")
            }
        }

        fun inventoryJson(): String {
            val inventory = _fastbootPartitionInventory.value ?: return "null"
            fun entryJson(entry: FastbootPartitionInventory.Entry): String = buildString {
                appendLine("{")
                appendLine("        \"name\": ${q(entry.name)},")
                appendLine("        \"baseName\": ${q(entry.baseName)},")
                appendLine("        \"risk\": ${q(entry.risk.name)},")
                appendLine("        \"storage\": ${q(entry.storage.name)},")
                appendLine("        \"slotBinding\": ${q(entry.slotBinding.name)},")
                appendLine("        \"sizeBytes\": ${DiagnosticJson.number(entry.sizeBytes)},")
                appendLine("        \"type\": ${q(entry.type)},")
                appendLine("        \"logical\": ${entry.logical?.let(DiagnosticJson::bool) ?: "null"},")
                appendLine("        \"hasSlot\": ${entry.hasSlot?.let(DiagnosticJson::bool) ?: "null"},")
                appendLine("        \"evidenceSources\": ${DiagnosticJson.stringArray(entry.evidenceSources.map { it.name }, "          ")},")
                appendLine("        \"missingFields\": ${DiagnosticJson.stringArray(entry.missingFields.map { it.name }, "          ")}")
                append("      }")
            }
            val entriesJson = if (inventory.entries.isEmpty()) {
                "[]"
            } else {
                inventory.entries.joinToString(prefix = "[\n", postfix = "\n    ]", separator = ",\n") {
                    "      ${entryJson(it).replace("\n", "\n      ").trimStart()}"
                }
            }
            return buildString {
                appendLine("{")
                appendLine("      \"product\": ${q(inventory.product)},")
                appendLine("      \"topology\": ${q(inventory.topology.name)},")
                appendLine("      \"currentSlot\": ${q(inventory.currentSlot)},")
                appendLine("      \"complete\": ${DiagnosticJson.bool(inventory.complete)},")
                appendLine("      \"finalStatus\": ${q(inventory.finalStatus)},")
                appendLine("      \"finalMessage\": ${q(inventory.finalMessage)},")
                appendLine("      \"pointQueryCount\": ${inventory.pointQueryCount},")
                appendLine("      \"unresolvedPointQueryCount\": ${inventory.unresolvedPointQueryCount},")
                appendLine("      \"duplicateMetadataCount\": ${inventory.duplicateMetadataCount},")
                appendLine("      \"warningCodes\": ${DiagnosticJson.stringArray(inventory.warnings.map { it.code }.distinct(), "        ")},")
                appendLine("      \"entries\": $entriesJson")
                append("    }")
            }
        }

        fun adbJson(): String = if (adb == null) {
            "null"
        } else {
            buildString {
                appendLine("{")
                appendLine("      \"remoteBanner\": ${q(adb.remoteBanner)},")
                appendLine("      \"peerMode\": ${q(adb.peerMode.name)},")
                appendLine("      \"features\": ${DiagnosticJson.stringArray(adb.features, "        ")},")
                appendLine("      \"supportsShellV2\": ${DiagnosticJson.bool(adb.supportsShellV2)},")
                appendLine("      \"interactiveShellActive\": ${DiagnosticJson.bool(adb.interactiveShellActive)},")
                appendLine("      \"publicKeyPath\": ${q(adb.publicKeyPath)}")
                append("    }")
            }
        }

        return buildString {
            appendLine("{")
            appendLine("  \"schema\": \"ru.forum.adbfastboottool.selftest.v3\",")
            appendLine("  \"privacyMode\": \"sanitized\",")
            appendLine("  \"createdAt\": ${q(createdAt)},")
            appendLine("  \"startedAt\": ${q(startedAt)},")
            appendLine("  \"result\": ${q(if (ok) "PASS" else "WARN_FAIL")},")
            appendLine("  \"app\": {")
            appendLine("    \"versionName\": ${q(BuildConfig.VERSION_NAME)},")
            appendLine("    \"versionCode\": ${BuildConfig.VERSION_CODE}")
            appendLine("  },")
            appendLine("  \"connection\": {")
            appendLine("    \"state\": ${q(state)},")
            appendLine("    \"info\": ${q(connectedDeviceInfo)}")
            appendLine("  },")
            appendLine("  \"debugLogging\": ${DiagnosticJson.bool(debugLoggingEnabled)},")
            appendLine("  \"paths\": {")
            appendLine("    \"logFile\": ${q(logFile?.absolutePath)},")
            appendLine("    \"adbKeyDir\": ${q(adbKeyDir.absolutePath)},")
            appendLine("  },")
            appendLine("  \"adb\": ${adbJson()},")
            appendLine("  \"fastboot\": ${fastbootJson()},")
            appendLine("  \"partitionInventory\": ${inventoryJson()},")
            appendLine("  \"selfTestLog\": ${DiagnosticJson.stringArray(reportLines, "    ")},")
            appendLine("  \"visibleLogSnapshot\": ${DiagnosticJson.stringArray(visibleSnapshot, "    ")}")
            appendLine("}")
        }
    }

    fun analyzeFirmwareFile(file: File) {
        startOperation(text(R.string.notif_file_analysis), text(R.string.notif_checking_file, file.name), heavy = false) {
            val analysis = ImageInspector.analyze(file, includeHashes = true)
            analysis.toDisplayText().lines().forEach { line -> if (line.isNotBlank()) log(line) }
        }
    }

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
                val message = "Устройство не в режиме Fastboot. Переведите его в Fastboot и подключите по OTG."
                log("❌ $message")
                failOperation(message)
            }

            log("🔍 Чтение данных устройства...")
            val product = proto.getVar("product")?.replace(Regex("\\s"), "")
            if (product.isNullOrEmpty()) {
                val message = "Не удалось прочитать product устройства"
                log("❌ $message")
                failOperation(message)
            }
            log("📱 product: $product")

            val serial = proto.currentDiagnostics()?.serialno?.trim()?.takeIf { it.isNotBlank() }
            val deviceToken = (proto.getVar("token") ?: run {
                proto.sendCommand("oem get_token")
                proto.getVar("token")
            })?.replace(Regex("\\s"), "")
            if (deviceToken.isNullOrEmpty()) {
                val message = "Не удалось прочитать token устройства"
                log("❌ $message")
                failOperation(message)
            }
            log("🔑 deviceToken получен")

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
                log("🌐 Запрос nonce у Mi сервера...")
                val nonce = client.getNonce()

                log("🌐 Проверка устройства...")
                val clearInfo = client.checkClear(product, nonce)
                if (clearInfo.notice.isNotEmpty()) log("ℹ️ ${clearInfo.notice}")
                log(if (clearInfo.clearsData) "⚠️ Разблокировка СОТРЁТ данные устройства" else "ℹ️ Данные не будут стёрты")
                postMainThread { onClearInfo(clearInfo.notice, clearInfo.clearsData) }

                log("🌐 Запрос разблокировки у Mi сервера...")
                val encryptDataHex = client.requestUnlock(product, deviceToken, nonce)
                log("✅ Сервер выдал данные разблокировки")

                val bytes = hexToBytes(encryptDataHex)
                val file = File(getApplication<Application>().filesDir, "encryptData")
                file.outputStream().use { it.write(bytes) }
                var prepared: PreparedFastbootDataArtifact? = null
                val accepted = try {
                    val activePrepared = stageFastbootDataArtifact(file)
                    prepared = activePrepared
                    val qualificationMode = proto.dataTransportMode
                    log(
                        "🛡️ Mi Unlock exact-file DATA qualification: bytes=${activePrepared.bytes}, " +
                            "mode=$qualificationMode, artifact=${activePrepared.artifactId.substringAfter("sha256:").take(16)}…"
                    )
                    val qualification = proto.runDataQualificationTestDetailed(activePrepared.stagedFile, qualificationMode)
                    currentCoroutineContext().ensureActive()
                    recordDataQualificationResult(product, qualificationMode, qualification, activePrepared.artifactId)
                    if (!qualification.success) {
                        failOperation(
                            "Mi Unlock DATA qualification FAIL [stage=${qualification.stage}, mode=$qualificationMode, " +
                                "offset=${qualification.bytesTransferred}/${qualification.bytes}]: ${qualification.message}. " +
                                if (qualification.sessionCorrupted) "Требуется новый вход в Fastboot." else ""
                        )
                    }
                    proto.stageAndOemUnlock(activePrepared.stagedFile).also { success ->
                        recordFastbootDataEvidenceFromLegacyFileOperation(proto, success, activePrepared)
                    }
                } finally {
                    prepared?.let { releaseFastbootStagedArtifact(it, "Mi Unlock finished") }
                    runCatching { file.delete() }
                }

                if (!accepted) {
                    val message = "Разблокировка не удалась на этапе устройства"
                    log("❌ $message")
                    failOperation(message)
                }

                persistPendingUnlockVerification(product, serial)
                log("✅ Команда oem unlock принята устройством.")
                log("🔎 Финальный успех будет подтверждён только после нового Fastboot-подключения и getvar:unlocked=yes.")
                verificationPending("Команда разблокировки принята. Ожидается переподключение для проверки unlocked=yes.")
            } catch (abort: OperationAbort) {
                throw abort
            } catch (e: MiUnlockClient.SessionExpiredException) {
                val message = "Mi-сессия истекла или отозвана (HTTP 401). Выполните вход в Mi-аккаунт заново."
                log("❌ $message")
                postMainThread { onAuthExpired() }
                failOperation(message)
            } catch (e: MiUnlockClient.BusinessException) {
                val message = e.message ?: "Xiaomi code ${e.code}"
                log("❌ Ошибка разблокировки: $message")
                if (e.code == 20045) {
                    log("💡 Код 20045: проверьте dataCenterZone. Текущая зона: ${auth.dataCenterZone}; выберите другую зону вручную и повторите только после проверки региона аккаунта.")
                } else {
                    log("💡 Точную причину см. выше. Проверьте официальный Mi Unlock status устройства.")
                }
                failOperation(message)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                log("❌ Ошибка разблокировки: $msg")
                val hasSpecificReason = msg.contains("Xiaomi code") ||
                    msg.contains("Xiaomi:") ||
                    msg.contains("code ")
                if (!hasSpecificReason) {
                    log("💡 Сервер не сообщил конкретную причину. Проверьте сеть, отключите VPN/Private DNS и повторите безопасный этап.")
                } else {
                    log("💡 Точную причину см. выше. Проверьте официальный Mi Unlock status устройства.")
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
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    fun runFastbootCommand(cmd: String) {
        val bootloaderTransition = FastbootMutationSafety.parseBootloaderStateTransition(cmd)
        val requiresReconnectVerification = bootloaderTransition?.expectedUnlocked != null
        // Обычные terminal-команды короткие и не требуют foreground-service.
        // Изменение lock-state — исключение: после OKAY операция остаётся VERIFY_PENDING
        // до нового Fastboot-подключения и фактического getvar:unlocked.
        startOperation(
            text(R.string.notif_fastboot_command),
            text(R.string.notif_executing, cmd),
            heavy = requiresReconnectVerification
        ) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            val expectedUnlocked = bootloaderTransition?.expectedUnlocked
            var verificationProduct: String? = null
            var verificationSerial: String? = null
            val operationLabel = when (bootloaderTransition) {
                FastbootMutationSafety.BootloaderStateTransition.UNLOCK -> "Fastboot bootloader unlock"
                FastbootMutationSafety.BootloaderStateTransition.LOCK -> "Fastboot bootloader lock"
                FastbootMutationSafety.BootloaderStateTransition.UNLOCK_CRITICAL -> "Fastboot critical unlock"
                FastbootMutationSafety.BootloaderStateTransition.LOCK_CRITICAL -> "Fastboot critical lock"
                null -> "Fastboot command"
            }

            if (expectedUnlocked != null) {
                val diagnostics = proto.refreshDiagnostics(force = false)
                verificationProduct = diagnostics.product?.trim()?.takeIf { it.isNotBlank() }
                    ?: failOperation("Нельзя менять lock-state: product устройства не подтверждён")
                verificationSerial = diagnostics.serialno?.trim()?.takeIf { it.isNotBlank() }
                clearPendingUnlockVerification()
            }

            if (!proto.sendCommand(cmd)) failOperation("Fastboot-команда завершилась ошибкой: $cmd")

            if (expectedUnlocked != null) {
                persistPendingUnlockVerification(
                    product = verificationProduct!!,
                    serial = verificationSerial,
                    expectedUnlocked = expectedUnlocked,
                    operationLabel = operationLabel
                )
                val expected = if (expectedUnlocked) "yes" else "no"
                log("✅ Команда изменения lock-state принята устройством.")
                log("🔎 Финальный успех будет подтверждён только после нового Fastboot-подключения и getvar:unlocked=$expected.")
                verificationPending("Команда принята. Ожидается переподключение для проверки unlocked=$expected.")
            }
        }
    }

    fun runFastbootDownloadAndRun(file: File, commandAfterDownload: String) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, commandAfterDownload)) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            val prepared = requireQualifiedStagedFastbootDataArtifact(proto, file)
            try {
                val success = proto.downloadAndRun(prepared.stagedFile, commandAfterDownload)
                recordFastbootDataEvidenceFromLegacyFileOperation(proto, success, prepared)
                if (!success) {
                    failOperation("Fastboot download+run завершился ошибкой: $commandAfterDownload")
                }
            } finally {
                releaseFastbootStagedArtifact(prepared, "download+run finished")
            }
        }
    }

    fun runFastbootLogicalPartitionCommand(command: String) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, command)) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            if (!proto.runLogicalPartitionCommand(command)) failOperation("Fastboot logical-команда завершилась ошибкой: $command")
        }
    }

    fun inspectFastbootLogicalPartition(partition: String) {
        startOperation(text(R.string.notif_fastboot_diagnostics), text(R.string.notif_updating_device), heavy = false) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            if (proto.inspectLogicalPartition(partition) == null) {
                failOperation("Не удалось получить сведения о logical-разделе: $partition")
            }
        }
    }

    fun runFastbootFetch(partition: String, outputFile: File) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, "fetch $partition")) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            if (!proto.fetchPartition(partition, outputFile)) failOperation("Fastboot fetch завершился ошибкой: $partition")
        }
    }

    fun runAdbService(service: String) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, service)) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.runService(service)) failOperation("ADB service завершился ошибкой: $service")
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
            if (!proto.runShellCommand(command)) failOperation("ADB shell завершился ошибкой: $label")
        }
    }

    fun isInteractiveAdbShellActive(): Boolean = adbProtocol?.hasInteractiveShell == true

    fun sendInteractiveAdbShellInput(line: String) {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.sendInteractiveShellInput(line)
        } else {
            log("❌ Интерактивный adb shell не открыт")
        }
    }

    fun interruptInteractiveAdbShell() {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.sendInteractiveShellInterrupt()
        } else {
            log("❌ Интерактивный adb shell не открыт")
        }
    }

    fun sendInteractiveAdbShellEof() {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.sendInteractiveShellEof()
        } else {
            log("❌ Интерактивный adb shell не открыт")
        }
    }

    fun stopInteractiveAdbShell() {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.stopInteractiveShell()
        } else {
            log("ℹ️ Интерактивный adb shell уже закрыт")
        }
    }

    fun runAdbPush(localFile: File, remotePath: String) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "push ${localFile.name} $remotePath")) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.isConnected) failOperation(text(R.string.error_no_adb))
            if (!proto.pushPath(localFile, remotePath)) failOperation("ADB push завершился ошибкой")
        }
    }

    fun runAdbPull(remotePath: String, localFile: File) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "pull $remotePath")) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.isConnected) failOperation(text(R.string.error_no_adb))
            if (!proto.pullFile(remotePath, localFile)) failOperation("ADB pull завершился ошибкой")
        }
    }

    fun runAdbInstall(packageFile: File, options: List<String>) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "install ${packageFile.name}")) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.isConnected) failOperation(text(R.string.error_no_adb))
            if (!proto.installPackage(packageFile, options)) failOperation("Установка APK завершилась ошибкой")
        }
    }

    fun runAdbInstallMultiple(apkFiles: List<File>, options: List<String>) {
        val names = apkFiles.joinToString(" ") { it.name }
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "install-multiple $names")) {
            val proto = adbProtocol ?: failOperation(text(R.string.error_no_adb))
            if (!proto.isConnected) failOperation(text(R.string.error_no_adb))
            if (!proto.installMultipleApks(apkFiles, options)) failOperation("install-multiple завершился ошибкой")
        }
    }

    fun runFlash(partition: String, file: File) {
        startOperation(text(R.string.notif_flash_img), text(R.string.notif_flashing_partition, file.name, partition)) {
            val proto = fastbootProtocol ?: failOperation("Нет Fastboot-соединения")
            if (!proto.isConnected) failOperation("Нет Fastboot-соединения")

            val prepared = requireQualifiedStagedFastbootDataArtifact(proto, file)
            try {
                val result = proto.flashPartitionDetailed(partition, prepared.stagedFile)
                recordFastbootDataEvidenceFromFlash(proto, result, prepared)
                if (!result.success) failOperation(formatFlashFailure(partition, result))
            } finally {
                releaseFastbootStagedArtifact(prepared, "single flash finished")
            }
        }
    }

    /**
     * Slice D execution path for the Recovery-first UI.
     *
     * A confirmation ticket is consumed exactly once. The plan, USB session,
     * image identity and concrete topology candidate are revalidated inside the
     * operation immediately before one call to flashPartitionDetailed().
     */
    fun runConfirmedQuickFlash(
        plan: QuickFlashPlan,
        sourceFile: File,
        ticket: QuickFlashMutationGate.ConfirmationTicket,
        expertModeEnabled: Boolean
    ) {
        startOperation(
            text(R.string.notif_flash_img),
            text(R.string.notif_flashing_partition, sourceFile.name, plan.partitionName)
        ) {
            val confirmationAvailable = quickFlashConfirmationRegistry.consume(ticket.confirmationId)
            val proto = fastbootProtocol ?: failOperation("Нет Fastboot-соединения")
            val canonicalFile = runCatching { sourceFile.canonicalFile }
                .getOrElse { failOperation("Quick Flash file path invalid: ${it.message ?: it.javaClass.simpleName}") }

            val artifactId = runCatching { computeFastbootArtifactId(canonicalFile) }
                .getOrElse { failOperation("Quick Flash image identity failed: ${it.message ?: it.javaClass.simpleName}") }
            val actualSha256 = artifactId.substringAfter("sha256:").substringBefore(":bytes=")
            val inventory = currentFastbootPartitionInventory()
                ?: failOperation("Quick Flash inventory отсутствует; выполните Refresh и подтвердите план заново")

            val topology = QuickFlashTopologyCandidateBuilder.buildFromInventory(
                QuickFlashTopologyCandidateBuilder.InventoryRequest(
                    inventory = inventory,
                    imageDisplayName = canonicalFile.name,
                    expertModeEnabled = expertModeEnabled,
                    manualPartitionName = plan.partitionName.takeIf { plan.target == QuickFlashTarget.MANUAL },
                    maxPointQueries = 0,
                    sessionBroken = proto.isSessionBroken
                )
            )
            val currentCandidates = topology.candidates.filter { it.target == plan.target }
            val unlocked = FastbootMutationSafety.parseFastbootBoolean(proto.currentDiagnostics()?.unlocked)
            val gate = QuickFlashMutationGate.evaluate(
                QuickFlashMutationGate.Request(
                    plan = plan,
                    ticket = ticket,
                    runtime = QuickFlashMutationGate.RuntimeEvidence(
                        currentSessionId = currentTransportSessionId(),
                        fastbootConnected = proto.isConnected,
                        sessionBroken = proto.isSessionBroken,
                        readOnlyMutationLock = readOnlyMutationLockEnabled,
                        expertModeEnabled = expertModeEnabled,
                        bootloaderUnlocked = unlocked,
                        currentImageUri = canonicalFile.toURI().toString(),
                        currentImageDisplayName = canonicalFile.name,
                        currentImageSizeBytes = canonicalFile.length(),
                        currentImageSha256 = actualSha256,
                        currentCandidates = currentCandidates,
                        confirmationAvailable = confirmationAvailable
                    )
                )
            )
            gate.warnings.forEach { log("⚠️ Quick Flash gate: $it") }
            if (!gate.allowed) {
                failOperation("Quick Flash mutation blocked: ${gate.errors.joinToString()}")
            }
            val authorization = gate.authorization
                ?: failOperation("Quick Flash mutation authorization отсутствует")

            log("=== QUICK FLASH MUTATION GATE ===")
            log("Confirmation: ${authorization.confirmationId}")
            log("Plan fingerprint: ${authorization.planFingerprint.take(16)}…")
            log("Execution fingerprint: ${authorization.executionFingerprint.take(16)}…")
            log("Authorized command count: ${authorization.commandCount}; retry=${authorization.retryAllowed}")
            log("Authorized command: fastboot ${authorization.fastbootArguments.joinToString(" ")}")

            val targetSize = proto.partitionSizeBytes(authorization.partitionName)
            if (proto.isSessionBroken) {
                failOperation("Fastboot-сессия BROKEN во время финальной проверки partition size")
            }
            if (targetSize != null && targetSize > 0L && canonicalFile.length() > targetSize) {
                failOperation(
                    "Файл ${canonicalFile.name} (${formatBytesShort(canonicalFile.length())}) больше раздела " +
                        "${authorization.partitionName} (${formatBytesShort(targetSize)})"
                )
            }

            val preparationMode = FastbootFlashPreparationPolicy.forGuidedPartition(plan.basePartition)
            var prepared: PreparedFastbootDataArtifact? = null
            try {
                val activePrepared = prepareGuidedFastbootDataArtifact(
                    proto = proto,
                    sourceFile = canonicalFile,
                    targetPartition = authorization.partitionName,
                    mode = preparationMode
                )
                prepared = activePrepared
                if (currentTransportSessionId() != plan.deviceSessionId || proto.isSessionBroken) {
                    failOperation("Quick Flash session changed after staging; flash command was not sent")
                }

                // Exactly one mutation call. No loop and no automatic retry.
                val result = proto.flashPartitionDetailed(authorization.partitionName, activePrepared.stagedFile)
                recordFastbootDataEvidenceFromFlash(proto, result, activePrepared)
                if (!result.success) {
                    failOperation(formatFlashFailure(authorization.partitionName, result))
                }
                log("✅ Quick Flash completed: exactly one flash command for ${authorization.partitionName}")
            } finally {
                prepared?.let { releaseFastbootStagedArtifact(it, "confirmed quick flash finished") }
            }
        }
    }

    /**
     * Guided flash with explicit slot intent. Unlike raw terminal flash, this path
     * resolves the concrete target from device evidence and never assumes that
     * slot-count=2 makes every partition slotted.
     */
    fun runFlashTarget(
        partition: String,
        file: File,
        requestedSlot: FastbootSlotResolver.RequestedSlot
    ) {
        val base = partition.trim().lowercase(Locale.US)
            .removeSuffix("_ab").removeSuffix("_a").removeSuffix("_b")

        startOperation(
            text(R.string.notif_flash_img),
            text(R.string.notif_flashing_partition, file.name, base)
        ) {
            val proto = fastbootProtocol ?: failOperation("Нет Fastboot-соединения")
            if (!proto.isConnected) failOperation("Нет Fastboot-соединения")

            log("=== SLOT TARGET PREFLIGHT: $base / $requestedSlot ===")
            val evidence = proto.inspectSlotEvidence(base)
            if (proto.isSessionBroken) {
                failOperation("Fastboot-сессия потеряна во время проверки slot layout. Переподключите устройство.")
            }

            val diagnostics = proto.currentDiagnostics()
            val runtimeLogical = proto.probeLogicalPartition(base)
            if (runtimeLogical != null) {
                log("Runtime logical probe: is-logical:$base=$runtimeLogical")
            } else {
                log("Runtime logical probe unavailable for $base; using conservative static fallback.")
            }
            val requiresFastbootd = runtimeLogical == true || (runtimeLogical == null && FastbootMutationSafety.likelyLogicalPartition(base))
            if (requiresFastbootd && diagnostics?.isUserspace?.equals("yes", ignoreCase = true) != true) {
                failOperation("Раздел $base подтверждён/предположен как logical dynamic partition. Перейдите в fastbootd (fastboot reboot fastboot) и повторите.")
            }

            val resolution = FastbootSlotResolver.resolve(base, requestedSlot, evidence)
            log(
                "Slot evidence: slot-count=${evidence.slotCount ?: "unknown"}, " +
                    "current=${evidence.currentSlot ?: "unknown"}, has-slot=${evidence.hasSlot ?: "unknown"}, " +
                    "base=${evidence.unsuffixedExists ?: "unknown"}, " +
                    "a=${evidence.slotAExists ?: "unknown"}, b=${evidence.slotBExists ?: "unknown"}"
            )
            log("Slot layout: ${resolution.layout}")
            log("Slot topology: ${FastbootSlotResolver.topologyLabel(resolution.topology)}")
            if (resolution.topology == FastbootSlotResolver.SlotTopology.SINGLE_SLOT) {
                log("✅ Устройство/раздел подтверждён как однослотовый: target=${resolution.targets.joinToString().ifBlank { base }}")
            }
            if (!resolution.canProceed) {
                failOperation(resolution.error ?: "Не удалось определить целевой раздел")
            }
            log("Resolved flash target(s): ${resolution.targets.joinToString()}")

            FastbootSlotResolver.validateExplicitFileSlot(file.name, resolution.targets)?.let {
                failOperation(it)
            }

            val preparationMode = FastbootFlashPreparationPolicy.forGuidedPartition(base)
            log(
                when (preparationMode) {
                    FastbootFlashPreparationPolicy.Mode.STANDARD_ONE_PASS ->
                        "🛡️ Подготовка файла: STANDARD one-pass (private staging + fsync + SHA-256, без повторной DATA qualification)."
                    FastbootFlashPreparationPolicy.Mode.STRICT_QUALIFICATION ->
                        "🛡️ Подготовка файла: STRICT qualification для расширенного/критичного раздела."
                }
            )

            var prepared: PreparedFastbootDataArtifact? = null
            try {
                resolution.targets.forEachIndexed { index, target ->
                    if (proto.isSessionBroken) {
                        failOperation("Fastboot-сессия BROKEN перед $target. Нужен новый вход в Fastboot.")
                    }
                    if (resolution.targets.size > 1) {
                        log("→ [${index + 1}/${resolution.targets.size}] прошивка $target")
                    }
                    val targetSize = proto.partitionSizeBytes(target)
                    if (targetSize != null && targetSize > 0L && file.length() > targetSize) {
                        failOperation(
                            "Файл ${file.name} (${formatBytesShort(file.length())}) больше раздела $target " +
                                "(${formatBytesShort(targetSize)}). DATA-передача не начата."
                        )
                    }
                    if (prepared == null) {
                        prepared = prepareGuidedFastbootDataArtifact(
                            proto = proto,
                            sourceFile = file,
                            targetPartition = target,
                            mode = preparationMode
                        )
                    }
                    val activePrepared = prepared ?: failOperation("Не удалось подготовить private staged-копию")
                    val result = proto.flashPartitionDetailed(target, activePrepared.stagedFile)
                    recordFastbootDataEvidenceFromFlash(proto, result, activePrepared)
                    if (!result.success) {
                        if (resolution.targets.size > 1) {
                            val untouched = resolution.targets.drop(index + 1)
                            log("❌ $target не прошит. Операция остановлена.")
                            if (untouched.isNotEmpty()) log("ℹ️ Не тронуты: ${untouched.joinToString()}")
                        }
                        failOperation(formatFlashFailure(target, result))
                    }
                }
            } finally {
                prepared?.let { releaseFastbootStagedArtifact(it, "guided slot flash finished") }
            }

            if (resolution.targets.size > 1) {
                log("✅ $base успешно прошит в подтверждённые слоты: ${resolution.targets.joinToString()}")
            }
        }
    }

    private fun formatFlashFailure(partition: String, result: FastbootProtocol.FlashResult): String {
        return buildString {
            append("Прошивка $partition провалилась")
            append(" [stage=${result.stage}, kind=${result.failureKind}]")
            if (result.message.isNotBlank()) append(": ${result.message}")
            if (result.sessionCorrupted) append(". Требуется полный повторный вход целевого устройства в Fastboot")
        }
    }

    fun currentFlashOperationDraft(): FlashOperationDraft = synchronized(flashDraftLock) {
        flashDraftSnapshot
    }

    fun addFlashQueueFile(partition: String, file: File) {
        val partitionKey = partition.trim().lowercase(Locale.US)
        val preparationToken = flashDraftPreparationSequence.incrementAndGet()
        flashDraftPreparationTokens[partitionKey] = preparationToken
        log("Очередь прошивки: вычисление SHA-256 для ${file.name}…")
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching { FlashOperationDraftPolicy.createItem(partition, file) }
            }
            if (flashDraftPreparationTokens[partitionKey] != preparationToken) {
                log("ℹ️ Устаревший результат подготовки ${file.name} отброшен: для $partitionKey уже выбран другой файл.")
                return@launch
            }
            flashDraftPreparationTokens.remove(partitionKey, preparationToken)
            prepared.onSuccess { item ->
                val next = runCatching {
                    FlashOperationDraftPolicy.upsert(currentFlashOperationDraft(), item)
                }.getOrElse { error ->
                    log("❌ Не удалось добавить файл в очередь: ${error.message ?: error.javaClass.simpleName}")
                    return@onSuccess
                }
                publishFlashOperationDraft(next, persist = true)
                log(text(R.string.flash_queue_updated_log, item.partition, item.displayName))
                log("Queue SHA-256: ${item.expectedSha256}")
            }.onFailure { error ->
                log("❌ Не удалось подготовить файл очереди: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun clearFlashQueueDraft() {
        flashDraftPreparationTokens.clear()
        publishFlashOperationDraft(
            FlashOperationDraftPolicy.clear(currentFlashOperationDraft()),
            persist = true
        )
        log(text(R.string.flash_queue_cleared_log))
    }

    fun revalidateFlashQueueDraft(restoredFromSavedState: Boolean = false) {
        val captured = currentFlashOperationDraft()
        if (captured.items.isEmpty()) return
        publishFlashOperationDraft(FlashOperationDraftPolicy.markVerifying(captured), persist = false)
        if (restoredFromSavedState) {
            log("ℹ️ Очередь восстановлена как черновик. Mutation не возобновляется; проверяются доступ, размер и SHA-256.")
        }
        viewModelScope.launch {
            val verified = withContext(Dispatchers.IO) { FlashOperationDraftPolicy.verifyAll(captured).first }
            if (currentFlashOperationDraft().revision != captured.revision) {
                log("ℹ️ Повторная проверка очереди отброшена: черновик изменился.")
                return@launch
            }
            publishFlashOperationDraft(verified, persist = true)
            val failed = verified.items.filterNot { it.isVerified }
            if (failed.isEmpty()) {
                log("✅ Восстановленная очередь повторно подтверждена. Для запуска всё равно требуется новое ручное подтверждение.")
            } else {
                failed.forEach { item ->
                    log("⛔ ${item.partition} ← ${item.displayName}: ${item.verificationDetail ?: item.verification.name}")
                }
            }
        }
    }

    /**
     * Called only from the final user-confirmation callback. The draft is checked
     * one more time immediately before creating transient [File] execution items.
     */
    fun executeFlashQueueDraftAfterConfirmation() {
        val captured = currentFlashOperationDraft()
        if (captured.items.isEmpty()) {
            log(text(R.string.flash_queue_empty_log))
            return
        }
        publishFlashOperationDraft(FlashOperationDraftPolicy.markVerifying(captured), persist = false)
        log("Очередь прошивки: финальная повторная проверка размера и SHA-256 перед mutation…")
        viewModelScope.launch {
            val (verifiedDraft, resolvedFiles) = withContext(Dispatchers.IO) {
                FlashOperationDraftPolicy.verifyAll(captured)
            }
            if (currentFlashOperationDraft().revision != captured.revision) {
                log("⛔ Запуск отменён: очередь изменилась во время финальной проверки.")
                return@launch
            }
            publishFlashOperationDraft(verifiedDraft, persist = true)
            if (!verifiedDraft.canRequestExecution || resolvedFiles.size != verifiedDraft.items.size) {
                verifiedDraft.items.filterNot { it.isVerified }.forEach { item ->
                    log("⛔ ${item.partition} ← ${item.displayName}: ${item.verificationDetail ?: item.verification.name}")
                }
                log("⛔ Очередь не запущена. Выберите повреждённые/изменённые файлы заново.")
                return@launch
            }
            val executionItems = verifiedDraft.items.zip(resolvedFiles).map { (item, file) ->
                FlashQueueItem(item.partition, file)
            }
            runFlashQueue(executionItems)
        }
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

        // FIX: очередь прошивается в рекомендованном порядке:
        // vbmeta → boot → init_boot → vendor_boot → recovery → dtbo
        val order = listOf("vbmeta", "boot", "init_boot", "vendor_boot", "recovery", "dtbo")
        val sorted = queue.sortedBy { item ->
            val idx = order.indexOf(item.partition.lowercase())
            if (idx < 0) order.size else idx
        }

        startOperation(text(R.string.notif_flash_img), "Flash queue: ${sorted.size} шт. Не отключайте кабель.") {
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
                log(text(R.string.error_no_fastboot))
                throw Exception("Нет Fastboot-соединения")
            }
            val preparedByArtifact = linkedMapOf<String, PreparedFastbootDataArtifact>()
            try {
                sorted.forEachIndexed { index, item ->
                    val stepNumber = index + 1
                    markOperationStep(stepNumber, OperationStepStatus.RUNNING, "fastboot flash ${item.partition}")
                    log("=== FLASH QUEUE ${stepNumber}/${sorted.size}: ${item.partition} ← ${item.file.name} ===")
                    val candidate = requireQualifiedStagedFastbootDataArtifact(proto, item.file)
                    val prepared = preparedByArtifact.getOrPut(candidate.artifactId) { candidate }
                    val result = proto.flashPartitionDetailed(item.partition, prepared.stagedFile)
                    recordFastbootDataEvidenceFromFlash(proto, result, prepared)
                    val diagnostics = proto.currentDiagnostics()
                    if (diagnostics != null) _fastbootDiagnostics.postValue(diagnostics)
                    markOperationStep(stepNumber, if (result.success) OperationStepStatus.OK else OperationStepStatus.FAILED, diagnosticsBrief(diagnostics))
                    if (!result.success) {
                        log("❌ Очередь остановлена на разделе ${item.partition}")
                        failOperation(formatFlashFailure(item.partition, result))
                    }
                }
                log("✅ Очередь прошивки завершена")
            } finally {
                preparedByArtifact.values.distinctBy { it.stagedFile.absolutePath }.forEach {
                    releaseFastbootStagedArtifact(it, "flash queue finished")
                }
            }
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


    private fun fastbootTransportPrefs() =
        getApplication<Application>().getSharedPreferences(FASTBOOT_TRANSPORT_PREFS, Context.MODE_PRIVATE)

    private fun legacyFastbootTransportPreferenceKey(product: String): String =
        "sync_bulk:" + product.trim().lowercase(Locale.US)

    private fun legacyFastbootDataOutcomeKey(
        product: String,
        transport: FastbootDataTransportEvidence.Transport
    ): String = "evidence:${transport.name.lowercase(Locale.US)}:${product.trim().lowercase(Locale.US)}"

    private fun fastbootDataEvidencePrefix(
        product: String,
        transport: FastbootDataTransportEvidence.Transport
    ): String = "evidence_v2:${transport.name.lowercase(Locale.US)}:${product.trim().lowercase(Locale.US)}"

    private fun readTransportEvidenceState(
        product: String,
        transport: FastbootDataTransportEvidence.Transport
    ): FastbootDataTransportEvidence.TransportState {
        val prefs = fastbootTransportPrefs()
        val prefix = fastbootDataEvidencePrefix(product, transport)
        val hasHistoryKey = "$prefix:has_history"
        if (prefs.contains(hasHistoryKey)) {
            return FastbootDataTransportEvidence.TransportState(
                hasHistory = prefs.getBoolean(hasHistoryKey, false),
                qualifiedBytes = prefs.getLong("$prefix:qualified_bytes", 0L),
                qualifiedGeneration = prefs.getString("$prefix:qualified_generation", null),
                historicalMaxProvenBytes = prefs.getLong("$prefix:historical_max_bytes", 0L),
                lastFailureRequestedBytes = prefs.getLong("$prefix:last_failure_requested_bytes", 0L),
                lastFailureOffsetBytes = prefs.getLong("$prefix:last_failure_offset_bytes", -1L),
                lastFailureGeneration = prefs.getString("$prefix:last_failure_generation", null),
                legacyRequalificationRequired = prefs.getBoolean("$prefix:legacy_requalification_required", false)
            )
        }

        val legacyRaw = prefs.getString(legacyFastbootDataOutcomeKey(product, transport), null)
        val migrated = FastbootDataTransportEvidence.migrateLegacyOutcome(legacyRaw)
        if (migrated.hasHistory) {
            persistTransportEvidenceState(product, transport, migrated)
            log(
                "ℹ️ Миграция Fastboot DATA evidence V5.6.3.x → V5.6.4: " +
                    "product=${product.trim().lowercase(Locale.US)}, transport=$transport, old=$legacyRaw. " +
                    "Старый PASS/FAIL сохранён только как история; требуется новая size-aware квалификация."
            )
        }
        return migrated
    }

    private fun persistTransportEvidenceState(
        product: String,
        transport: FastbootDataTransportEvidence.Transport,
        state: FastbootDataTransportEvidence.TransportState
    ) {
        val cleanProduct = product.trim().lowercase(Locale.US)
        val prefix = fastbootDataEvidencePrefix(cleanProduct, transport)
        fastbootTransportPrefs().edit()
            .putBoolean("$prefix:has_history", state.hasHistory)
            .putLong("$prefix:qualified_bytes", state.qualifiedBytes)
            .putString("$prefix:qualified_generation", state.qualifiedGeneration)
            .putLong("$prefix:historical_max_bytes", state.historicalMaxProvenBytes)
            .putLong("$prefix:last_failure_requested_bytes", state.lastFailureRequestedBytes)
            .putLong("$prefix:last_failure_offset_bytes", state.lastFailureOffsetBytes)
            .putString("$prefix:last_failure_generation", state.lastFailureGeneration)
            .putBoolean("$prefix:legacy_requalification_required", state.legacyRequalificationRequired)
            .remove(legacyFastbootDataOutcomeKey(cleanProduct, transport))
            .remove(legacyFastbootTransportPreferenceKey(cleanProduct))
            .apply()
    }

    private fun readFastbootDataEvidence(product: String): FastbootDataTransportEvidence.State {
        val cleanProduct = product.trim().lowercase(Locale.US)
        var state = FastbootDataTransportEvidence.State(
            async = readTransportEvidenceState(cleanProduct, FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST),
            sync = readTransportEvidenceState(cleanProduct, FastbootDataTransportEvidence.Transport.SYNC_BULK)
        )

        // V5.6.2 stored only "async failed, try sync next" as a boolean. Migrate it
        // as history only. It must never become a current-generation sync qualification.
        val prefs = fastbootTransportPrefs()
        val legacyKey = legacyFastbootTransportPreferenceKey(cleanProduct)
        if (prefs.getBoolean(legacyKey, false) && !FastbootDataTransportEvidence.hasAnyHistory(state)) {
            val migratedAsync = FastbootDataTransportEvidence.migrateLegacyOutcome("FAIL")
            state = state.copy(async = migratedAsync)
            persistTransportEvidenceState(
                cleanProduct,
                FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST,
                migratedAsync
            )
            log(
                "ℹ️ Миграция Fastboot DATA evidence для product=$cleanProduct: " +
                    "old sync flag → async failure history; current-session qualification отсутствует."
            )
        }
        return state
    }

    private fun persistFastbootDataPass(
        product: String,
        transport: FastbootDataTransportEvidence.Transport,
        bytes: Long
    ): FastbootDataTransportEvidence.State {
        val generation = currentFastbootGenerationToken()
        val current = readFastbootDataEvidence(product)
        val updated = FastbootDataTransportEvidence.recordPass(current, transport, bytes, generation)
        persistTransportEvidenceState(product, transport, FastbootDataTransportEvidence.transportState(updated, transport))
        log(
            "ℹ️ Fastboot DATA evidence PASS: product=$product, transport=$transport, " +
                "bytes=$bytes, generation=$generation, ${FastbootDataTransportEvidence.summary(updated, generation)}"
        )
        return updated
    }

    private fun persistFastbootDataFailure(
        product: String,
        transport: FastbootDataTransportEvidence.Transport,
        requestedBytes: Long,
        failureOffsetBytes: Long
    ): FastbootDataTransportEvidence.State {
        val generation = currentFastbootGenerationToken()
        val current = readFastbootDataEvidence(product)
        val updated = FastbootDataTransportEvidence.recordFailure(
            current,
            transport,
            requestedBytes = requestedBytes,
            failureOffsetBytes = failureOffsetBytes,
            generation = generation
        )
        persistTransportEvidenceState(product, transport, FastbootDataTransportEvidence.transportState(updated, transport))
        log(
            "ℹ️ Fastboot DATA evidence FAIL: product=$product, transport=$transport, " +
                "requested=$requestedBytes, offset=$failureOffsetBytes, generation=$generation, " +
                FastbootDataTransportEvidence.summary(updated, generation)
        )
        return updated
    }

    private fun protocolTransportToEvidence(
        mode: FastbootProtocol.DataTransportMode
    ): FastbootDataTransportEvidence.Transport = when (mode) {
        FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST -> FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST
        FastbootProtocol.DataTransportMode.SYNC_BULK -> FastbootDataTransportEvidence.Transport.SYNC_BULK
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_64K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_256K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_16K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_64K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_128K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_256K -> error("Native USBFS Matrix is diagnostic-only and is not persisted as mutation evidence")
    }

    private fun evidenceTransportToProtocol(
        transport: FastbootDataTransportEvidence.Transport
    ): FastbootProtocol.DataTransportMode = when (transport) {
        FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST -> FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST
        FastbootDataTransportEvidence.Transport.SYNC_BULK -> FastbootProtocol.DataTransportMode.SYNC_BULK
    }

    private fun applyFastbootDataTransportPreference(
        proto: FastbootProtocol,
        diagnostics: FastbootProtocol.DeviceDiagnostics
    ) {
        val product = diagnostics.product?.trim()?.takeIf { it.isNotBlank() } ?: return
        val generation = currentFastbootGenerationToken()
        val evidence = readFastbootDataEvidence(product)
        FastbootDataTransportEvidence.bestQualifiedTransport(evidence, generation)?.let { preferred ->
            proto.dataTransportMode = evidenceTransportToProtocol(preferred)
        }
        log(
            "ℹ️ Fastboot DATA evidence cache: product=$product, generation=$generation, " +
                FastbootDataTransportEvidence.summary(evidence, generation)
        )
        if (FastbootDataTransportEvidence.hasAnyHistory(evidence) &&
            FastbootDataTransportEvidence.bestQualifiedTransport(evidence, generation) == null
        ) {
            log("⚠️ DATA history exists, но в текущей Fastboot USB-сессии ещё нет size-aware квалификации.")
        }
    }

    private fun fastbootArtifactEvidencePrefix(
        product: String,
        transport: FastbootDataTransportEvidence.Transport,
        artifactId: String
    ): String {
        val cleanProduct = product.trim().lowercase(Locale.US)
        val safeArtifact = artifactId.substringAfter("sha256:").substringBefore(":bytes=")
        return "artifact_evidence_v1:${transport.name.lowercase(Locale.US)}:$cleanProduct:$safeArtifact"
    }

    private fun computeFastbootArtifactId(file: File): String {
        if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) {
            throw IllegalArgumentException("Файл недоступен для вычисления DATA artifact identity: ${file.absolutePath}")
        }
        val lengthBefore = file.length()
        val modifiedBefore = file.lastModified()

        log("🔐 DATA artifact identity: SHA-256 ${file.name} (${formatBytesShort(lengthBefore)})...")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }

        val lengthAfter = file.length()
        val modifiedAfter = file.lastModified()
        if (lengthBefore != lengthAfter || modifiedBefore != modifiedAfter) {
            throw IllegalStateException(
                "Файл изменился во время вычисления SHA-256: ${file.name}; " +
                    "before=$lengthBefore/$modifiedBefore after=$lengthAfter/$modifiedAfter"
            )
        }

        val sha256 = digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
        val artifactId = "sha256:$sha256:bytes=$lengthAfter"
        log("🔐 DATA artifact identity ready: ${file.name} sha256=${sha256.take(16)}… bytes=$lengthAfter")
        return artifactId
    }

    private fun stagedFastbootFileForArtifact(artifactId: String): File =
        File(fastbootStageDir, FastbootDataStagingPolicy.stagedFileName(artifactId))


    private fun cleanupFastbootStageParts() {
        if (!fastbootStageDir.exists()) return
        fastbootStageDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { runCatching { it.delete() } }
    }

    private fun clearFastbootStaging(reason: String) {
        if (!fastbootStageDir.exists()) return
        var deleted = 0
        var failed = 0
        fastbootStageDir.listFiles()?.forEach { file ->
            if (runCatching { file.delete() }.getOrDefault(false)) deleted++ else failed++
        }
        if (runCatching { fastbootStageDir.delete() }.getOrDefault(false)) {
            // recreated lazily on next qualification
        }
        if (deleted > 0 || failed > 0) {
            log("🧹 Fastboot internal staging cleanup: reason=$reason, deleted=$deleted, failed=$failed")
        }
    }

    private fun releaseFastbootStagedArtifact(prepared: PreparedFastbootDataArtifact, reason: String) {
        // Qualification evidence is meaningful only while the verified private copy
        // still exists. Invalidate it at the same lifecycle boundary as the file so
        // the next attempt asks for a fresh qualification instead of showing a stale
        // PASS followed by "staged copy missing".
        fastbootProtocol?.compatibilityProduct
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { product ->
                invalidateAllFastbootArtifactQualifications(
                    product = product,
                    artifactId = prepared.artifactId,
                    reason = "staged artifact released: $reason"
                )
            }

        val deleted = runCatching { prepared.stagedFile.delete() }.getOrDefault(false)
        log(
            "🧹 Fastboot staged artifact release: file=${prepared.sourceFile.name}, " +
                "artifact=${prepared.artifactId.substringAfter("sha256:").take(16)}…, " +
                "reason=$reason, deleted=$deleted"
        )
    }

    private suspend fun stageFastbootDataArtifact(sourceFile: File): PreparedFastbootDataArtifact {
        if (!sourceFile.exists() || !sourceFile.isFile || !sourceFile.canRead() || sourceFile.length() <= 0L) {
            throw IllegalArgumentException("Файл недоступен для internal staging: ${sourceFile.absolutePath}")
        }

        val sourceArtifactId = computeFastbootArtifactId(sourceFile)
        val sourceBytes = sourceFile.length()
        if (!fastbootStageDir.exists() && !fastbootStageDir.mkdirs()) {
            throw IllegalStateException("Не удалось создать private staging: ${fastbootStageDir.absolutePath}")
        }
        cleanupFastbootStageParts()

        val readyFile = stagedFastbootFileForArtifact(sourceArtifactId)
        if (readyFile.exists()) {
            val existingId = runCatching { computeFastbootArtifactId(readyFile) }.getOrNull()
            if (existingId == sourceArtifactId && readyFile.length() == sourceBytes) {
                log(
                    "✅ Fastboot internal staging reuse: source=${sourceFile.name}, staged=${readyFile.name}, " +
                        "bytes=$sourceBytes, artifact=${sourceArtifactId.substringAfter("sha256:").take(16)}…"
                )
                return PreparedFastbootDataArtifact(sourceFile, readyFile, sourceArtifactId, sourceBytes)
            }
            runCatching { readyFile.delete() }
        }

        val requiredFree = FastbootDataStagingPolicy.requiredFreeBytes(sourceBytes)
        val usable = fastbootStageDir.usableSpace
        if (!FastbootDataStagingPolicy.hasEnoughSpace(sourceBytes, usable)) {
            throw IllegalStateException(
                "Недостаточно внутреннего места для Fastboot staging: нужно минимум " +
                    "${formatBytesShort(requiredFree)}, доступно ${formatBytesShort(usable)}. " +
                    "Прямая DATA-передача из /sdcard запрещена."
            )
        }

        val partFile = File(fastbootStageDir, readyFile.name.removeSuffix(".ready") + ".part")
        runCatching { partFile.delete() }
        val sourceLengthBefore = sourceFile.length()
        val sourceModifiedBefore = sourceFile.lastModified()
        val buffer = ByteArray(FASTBOOT_STAGE_COPY_BUFFER_BYTES)
        var copied = 0L
        var nextProgressBytes = FASTBOOT_STAGE_PROGRESS_STEP_BYTES

        log(
            "📦 Fastboot internal staging start: source=${sourceFile.absolutePath}, " +
                "staged=${readyFile.absolutePath}, bytes=${formatBytesShort(sourceBytes)}, " +
                "artifact=${sourceArtifactId.substringAfter("sha256:").take(16)}…"
        )

        try {
            FileInputStream(sourceFile).use { rawInput ->
                BufferedInputStream(rawInput, FASTBOOT_STAGE_COPY_BUFFER_BYTES).use { input ->
                    FileOutputStream(partFile).use { rawOutput ->
                        BufferedOutputStream(rawOutput, FASTBOOT_STAGE_COPY_BUFFER_BYTES).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                copied += read.toLong()
                                if (copied >= nextProgressBytes || copied == sourceBytes) {
                                    val percent = ((copied * 100L) / sourceBytes).toInt().coerceIn(0, 100)
                                    log(
                                        "📦 Fastboot staging: $percent% " +
                                            "(${formatBytesShort(copied)}/${formatBytesShort(sourceBytes)})"
                                    )
                                    nextProgressBytes = copied + FASTBOOT_STAGE_PROGRESS_STEP_BYTES
                                }
                            }
                            output.flush()
                            rawOutput.fd.sync()
                        }
                    }
                }
            }

            val sourceLengthAfter = sourceFile.length()
            val sourceModifiedAfter = sourceFile.lastModified()
            if (sourceLengthBefore != sourceLengthAfter || sourceModifiedBefore != sourceModifiedAfter) {
                throw IllegalStateException(
                    "Исходный файл изменился во время staging: ${sourceFile.name}; " +
                        "before=$sourceLengthBefore/$sourceModifiedBefore " +
                        "after=$sourceLengthAfter/$sourceModifiedAfter"
                )
            }
            if (copied != sourceBytes || partFile.length() != sourceBytes) {
                throw IllegalStateException(
                    "Staging size mismatch: source=$sourceBytes copied=$copied staged=${partFile.length()}"
                )
            }

            val stagedArtifactId = computeFastbootArtifactId(partFile)
            if (stagedArtifactId != sourceArtifactId) {
                throw IllegalStateException(
                    "Staging SHA-256 mismatch: source=${sourceArtifactId.substringAfter("sha256:").take(16)}… " +
                        "staged=${stagedArtifactId.substringAfter("sha256:").take(16)}…"
                )
            }

            runCatching { readyFile.delete() }
            if (!partFile.renameTo(readyFile)) {
                throw IllegalStateException("Не удалось зафиксировать staged-файл: ${readyFile.absolutePath}")
            }
            if (!readyFile.exists() || readyFile.length() != sourceBytes) {
                throw IllegalStateException("Staged-файл потерян после rename: ${readyFile.absolutePath}")
            }

            log(
                "✅ Fastboot internal staging ready: source=${sourceFile.name}, staged=${readyFile.name}, " +
                    "bytes=${formatBytesShort(sourceBytes)}, SHA-256 verified"
            )
            return PreparedFastbootDataArtifact(sourceFile, readyFile, sourceArtifactId, sourceBytes)
        } catch (t: Throwable) {
            runCatching { partFile.delete() }
            runCatching { readyFile.delete() }
            throw t
        }
    }

    private fun persistFastbootArtifactPass(
        product: String,
        transport: FastbootDataTransportEvidence.Transport,
        artifactId: String,
        bytes: Long
    ) {
        val generation = currentFastbootGenerationToken()
        val prefix = fastbootArtifactEvidencePrefix(product, transport, artifactId)
        fastbootTransportPrefs().edit()
            .putString("$prefix:generation", generation)
            .putLong("$prefix:bytes", bytes)
            .putString("$prefix:artifact_id", artifactId)
            .remove("$prefix:last_failure_generation")
            .remove("$prefix:last_failure_offset")
            .apply()
        log(
            "ℹ️ Fastboot DATA artifact PASS: product=$product, transport=$transport, " +
                "bytes=$bytes, generation=$generation, artifact=${artifactId.substringAfter("sha256:").take(16)}…"
        )
    }

    private fun persistFastbootArtifactFailure(
        product: String,
        transport: FastbootDataTransportEvidence.Transport,
        artifactId: String,
        failureOffsetBytes: Long
    ) {
        val generation = currentFastbootGenerationToken()
        val prefix = fastbootArtifactEvidencePrefix(product, transport, artifactId)
        fastbootTransportPrefs().edit()
            .remove("$prefix:generation")
            .remove("$prefix:bytes")
            .putString("$prefix:artifact_id", artifactId)
            .putString("$prefix:last_failure_generation", generation)
            .putLong("$prefix:last_failure_offset", failureOffsetBytes)
            .apply()
        log(
            "ℹ️ Fastboot DATA artifact FAIL: product=$product, transport=$transport, " +
                "offset=$failureOffsetBytes, generation=$generation, artifact=${artifactId.substringAfter("sha256:").take(16)}…"
        )
    }

    private fun invalidateAllFastbootArtifactQualifications(
        product: String,
        artifactId: String,
        reason: String
    ) {
        val editor = fastbootTransportPrefs().edit()
        FastbootDataTransportEvidence.Transport.values().forEach { transport ->
            val prefix = fastbootArtifactEvidencePrefix(product, transport, artifactId)
            editor.remove("$prefix:generation")
            editor.remove("$prefix:bytes")
        }
        editor.apply()
        log(
            "⚠️ Fastboot DATA artifact qualifications invalidated: product=$product, " +
                "artifact=${artifactId.substringAfter("sha256:").take(16)}…, reason=$reason"
        )
    }

    private fun invalidateFastbootArtifactQualification(
        product: String,
        transport: FastbootDataTransportEvidence.Transport,
        artifactId: String,
        reason: String
    ) {
        val prefix = fastbootArtifactEvidencePrefix(product, transport, artifactId)
        fastbootTransportPrefs().edit()
            .remove("$prefix:generation")
            .remove("$prefix:bytes")
            .apply()
        log(
            "⚠️ Fastboot DATA artifact qualification invalidated: product=$product, transport=$transport, " +
                "artifact=${artifactId.substringAfter("sha256:").take(16)}…, reason=$reason"
        )
    }

    private fun readFastbootArtifactQualification(
        product: String,
        transport: FastbootDataTransportEvidence.Transport,
        artifactId: String
    ): FastbootDataArtifactEvidence.Qualification {
        val prefix = fastbootArtifactEvidencePrefix(product, transport, artifactId)
        val prefs = fastbootTransportPrefs()
        return FastbootDataArtifactEvidence.Qualification(
            artifactId = prefs.getString("$prefix:artifact_id", null),
            qualifiedBytes = prefs.getLong("$prefix:bytes", 0L),
            generation = prefs.getString("$prefix:generation", null)
        )
    }

    private suspend fun prepareGuidedFastbootDataArtifact(
        proto: FastbootProtocol,
        sourceFile: File,
        targetPartition: String,
        mode: FastbootFlashPreparationPolicy.Mode
    ): PreparedFastbootDataArtifact {
        return when (mode) {
            FastbootFlashPreparationPolicy.Mode.STRICT_QUALIFICATION ->
                requireQualifiedStagedFastbootDataArtifact(proto, sourceFile)

            FastbootFlashPreparationPolicy.Mode.STANDARD_ONE_PASS -> {
                val product = proto.compatibilityProduct?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "Fastboot DATA safety block: product устройства не подтверждён"
                    )
                if (proto.isSessionBroken) {
                    throw IllegalStateException(
                        "Fastboot DATA safety block: сессия BROKEN до staging"
                    )
                }

                // Native USBFS profiles are diagnostic-only. Standard real flash uses
                // the universal async transport unless the user already has the safe
                // sync fallback selected.
                if (isNativeUsbfsDiagnosticOnly(proto.dataTransportMode)) {
                    log(
                        "ℹ️ Diagnostic-only transport ${proto.dataTransportMode} заменён на " +
                            "ASYNC_USB_REQUEST перед реальной прошивкой."
                    )
                    proto.dataTransportMode = FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST
                }

                val prepared = stageFastbootDataArtifact(sourceFile)
                log(
                    "✅ STANDARD one-pass staged artifact ready: product=$product, " +
                        "target=$targetPartition, bytes=${prepared.bytes}, " +
                        "transport=${proto.dataTransportMode}, " +
                        "artifact=${prepared.artifactId.substringAfter("sha256:").take(16)}…"
                )
                prepared
            }
        }
    }

    private fun requireQualifiedStagedFastbootDataArtifact(
        proto: FastbootProtocol,
        sourceFile: File
    ): PreparedFastbootDataArtifact {
        val product = proto.compatibilityProduct?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Fastboot DATA safety block: product устройства не подтверждён")
        val requiredBytes = sourceFile.length()
        val generation = currentFastbootGenerationToken()
        val artifactId = computeFastbootArtifactId(sourceFile)
        val asyncQualification = readFastbootArtifactQualification(
            product, FastbootDataTransportEvidence.Transport.ASYNC_USB_REQUEST, artifactId
        )
        val syncQualification = readFastbootArtifactQualification(
            product, FastbootDataTransportEvidence.Transport.SYNC_BULK, artifactId
        )
        val preferred = FastbootDataArtifactEvidence.preferredTransport(
            async = asyncQualification,
            sync = syncQualification,
            requiredArtifactId = artifactId,
            requiredBytes = requiredBytes,
            generation = generation
        ) ?: throw IllegalStateException(buildString {
            append("Fastboot DATA safety block for product=$product: ")
            append("файл ${sourceFile.name} (${formatBytesShort(requiredBytes)}) не имеет успешной download-only квалификации ")
            append("именно этого SHA-256 в текущей Fastboot USB-сессии. ")
            append("Синтетический self-test того же размера не разрешает прошивку другого файла. ")
            append("Квалификация должна пройти через private internal staging. ")
            append("Запустите «Проверить размер выбранного .img», затем, не переподключая USB и не изменяя файл, повторите операцию.")
        })

        val stagedFile = stagedFastbootFileForArtifact(artifactId)
        if (!stagedFile.exists() || !stagedFile.isFile || !stagedFile.canRead() || stagedFile.length() != requiredBytes) {
            invalidateFastbootArtifactQualification(product, preferred, artifactId, "staged file missing or size mismatch")
            throw IllegalStateException(
                "Fastboot DATA safety block: квалификация есть, но проверенная private staged-копия отсутствует. " +
                    "Повторите квалификацию файла ${sourceFile.name}. Прямая передача из /sdcard запрещена."
            )
        }

        val stagedArtifactId = runCatching { computeFastbootArtifactId(stagedFile) }.getOrElse { error ->
            runCatching { stagedFile.delete() }
            invalidateFastbootArtifactQualification(product, preferred, artifactId, "staged SHA-256 read failed")
            throw IllegalStateException("Fastboot DATA safety block: staged-копия не прошла SHA-256: ${error.message}")
        }
        if (stagedArtifactId != artifactId) {
            runCatching { stagedFile.delete() }
            invalidateFastbootArtifactQualification(product, preferred, artifactId, "staged SHA-256 mismatch")
            throw IllegalStateException("Fastboot DATA safety block: staged-копия не совпадает с исходным SHA-256")
        }

        proto.dataTransportMode = evidenceTransportToProtocol(preferred)
        log(
            "✅ FILE-BOUND STAGED DATA selected: product=$product, source=${sourceFile.name}, " +
                "staged=${stagedFile.name}, required=${formatBytesShort(requiredBytes)}, transport=$preferred, " +
                "generation=$generation, artifact=${artifactId.substringAfter("sha256:").take(16)}…"
        )
        return PreparedFastbootDataArtifact(sourceFile, stagedFile, artifactId, requiredBytes)
    }

    private fun recordFastbootDataEvidenceFromFlash(
        proto: FastbootProtocol,
        result: FastbootProtocol.FlashResult,
        prepared: PreparedFastbootDataArtifact
    ) {
        val requestedBytes = prepared.bytes
        val artifactId = prepared.artifactId
        val mode = proto.lastDataTransportUsed ?: return
        val product = proto.compatibilityProduct?.trim()?.takeIf { it.isNotBlank() } ?: return
        val transport = protocolTransportToEvidence(mode)
        when {
            result.success -> {
                persistFastbootDataPass(product, transport, requestedBytes)
                persistFastbootArtifactPass(product, transport, artifactId, requestedBytes)
            }
            result.stage == FastbootProtocol.FlashStage.DATA_TRANSFER -> {
                persistFastbootDataFailure(product, transport, requestedBytes, result.dataBytesTransferred)
                persistFastbootArtifactFailure(product, transport, artifactId, result.dataBytesTransferred)
            }
            result.stage == FastbootProtocol.FlashStage.WAIT_DOWNLOAD_FINAL &&
                result.failureKind == FastbootProtocol.FlashFailureKind.TRANSPORT -> {
                persistFastbootDataFailure(product, transport, requestedBytes, result.dataBytesTransferred)
                persistFastbootArtifactFailure(product, transport, artifactId, result.dataBytesTransferred)
            }
            result.stage == FastbootProtocol.FlashStage.WAIT_DOWNLOAD_FINAL &&
                result.failureKind == FastbootProtocol.FlashFailureKind.PROTOCOL &&
                result.dataBytesTransferred >= requestedBytes -> {
                persistFastbootDataPass(product, transport, requestedBytes)
                persistFastbootArtifactPass(product, transport, artifactId, requestedBytes)
            }
            result.stage == FastbootProtocol.FlashStage.SEND_FLASH ||
                result.stage == FastbootProtocol.FlashStage.WAIT_FLASH_FINAL -> {
                persistFastbootDataPass(product, transport, requestedBytes)
                persistFastbootArtifactPass(product, transport, artifactId, requestedBytes)
            }
        }
    }

    private fun recordFastbootDataEvidenceFromLegacyFileOperation(
        proto: FastbootProtocol,
        success: Boolean,
        prepared: PreparedFastbootDataArtifact
    ) {
        val mode = proto.lastDataTransportUsed ?: return
        val product = proto.compatibilityProduct?.trim()?.takeIf { it.isNotBlank() } ?: return
        val transport = protocolTransportToEvidence(mode)
        val requestedBytes = prepared.bytes
        val artifactId = prepared.artifactId
        if (success) {
            persistFastbootDataPass(product, transport, requestedBytes)
            persistFastbootArtifactPass(product, transport, artifactId, requestedBytes)
        } else if (proto.isSessionBroken) {
            persistFastbootDataFailure(product, transport, requestedBytes, -1L)
            persistFastbootArtifactFailure(product, transport, artifactId, -1L)
        }
    }


    private fun isNativeUsbfsDiagnosticOnly(mode: FastbootProtocol.DataTransportMode): Boolean = when (mode) {
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_64K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_256K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_16K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_64K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_128K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_256K -> true
        else -> false
    }

    private fun logNativeUsbfsDiagnosticOnlyPass(mode: FastbootProtocol.DataTransportMode, label: String) {
        if (isNativeUsbfsDiagnosticOnly(mode)) {
            log("🛡️ Native USBFS Matrix PASS записан только как диагностика: $label не разрешает flash/boot/update-super.")
        }
    }

    fun runFastbootDataSelfTest(sizeBytes: Long, mode: FastbootProtocol.DataTransportMode) {
        startOperation("Fastboot DATA Self-Test", "download-only ${formatBytesShort(sizeBytes)}") {
            val proto = fastbootProtocol ?: failOperation("Нет Fastboot-соединения")
            if (!proto.isConnected) failOperation("Нет Fastboot-соединения")
            if (sizeBytes !in FastbootProtocol.SAFE_DATA_SELF_TEST_SIZES_BYTES) {
                failOperation("Неподдерживаемый размер Safe DATA Self-Test: $sizeBytes байт")
            }

            val product = proto.compatibilityProduct?.trim()?.takeIf { it.isNotBlank() }
                ?: failOperation("Не удалось определить product для DATA self-test")
            val payload = File(getApplication<Application>().cacheDir, "fastboot-data-self-test-$sizeBytes.bin")
            try {
                writeDeterministicSelfTestPayload(payload, sizeBytes)
                log(
                    "🧪 Safe DATA Self-Test: product=$product, size=${formatBytesShort(sizeBytes)}, " +
                        "mode=$mode, generation=${currentFastbootGenerationToken()}"
                )
                val result = proto.runDataSelfTestDetailed(payload, mode)
                currentCoroutineContext().ensureActive()
                recordDataQualificationResult(product, mode, result)
                if (result.success) {
                    if (isNativeUsbfsDiagnosticOnly(mode)) {
                        logNativeUsbfsDiagnosticOnlyPass(mode, "Safe DATA Self-Test")
                    } else {
                        proto.dataTransportMode = mode
                    }
                } else {
                    failOperation(
                        "Safe DATA Self-Test FAIL [stage=${result.stage}, mode=$mode, " +
                            "offset=${result.bytesTransferred}/${result.bytes}]: ${result.message}. " +
                            if (result.sessionCorrupted) "Требуется новый вход в Fastboot." else ""
                    )
                }
            } finally {
                runCatching { payload.delete() }
            }
        }
    }

    fun runFastbootAutoNativeUsbfsMatrix(
        sizeBytes: Long,
        modes: List<FastbootProtocol.DataTransportMode>
    ) {
        startOperation("Native USBFS Auto Matrix", "download-only ${formatBytesShort(sizeBytes)}") {
            val proto = fastbootProtocol ?: failOperation("Нет Fastboot-соединения")
            if (!proto.isConnected) failOperation("Нет Fastboot-соединения")
            if (sizeBytes !in FastbootProtocol.SAFE_DATA_SELF_TEST_SIZES_BYTES) {
                failOperation("Неподдерживаемый размер Auto Matrix: $sizeBytes байт")
            }
            val product = proto.compatibilityProduct?.trim()?.takeIf { it.isNotBlank() }
                ?: failOperation("Не удалось определить product для Auto Matrix")
            val nativeModes = modes.filter { isNativeUsbfsDiagnosticOnly(it) }
            if (nativeModes.isEmpty()) failOperation("Auto Matrix не получил Native USBFS профили")

            log("🧪 Native USBFS Auto Matrix: product=$product, size=${formatBytesShort(sizeBytes)}, profiles=${nativeModes.size}, generation=${currentFastbootGenerationToken()}")
            log("🛡️ Auto Matrix: download-only. flash/boot/stage/update-super/unlock не отправляются.")

            val payload = File(getApplication<Application>().cacheDir, "fastboot-auto-matrix-$sizeBytes.bin")
            val results = mutableListOf<String>()
            try {
                writeDeterministicSelfTestPayload(payload, sizeBytes)
                for ((index, mode) in nativeModes.withIndex()) {
                    log("▶️ Auto Matrix ${index + 1}/${nativeModes.size}: mode=$mode")
                    val result = proto.runDataSelfTestDetailed(payload, mode)
                    currentCoroutineContext().ensureActive()
                    recordDataQualificationResult(product, mode, result)
                    if (result.success) {
                        logNativeUsbfsDiagnosticOnlyPass(mode, "Auto Matrix ${index + 1}/${nativeModes.size}")
                        results += "${index + 1}. $mode PASS ${formatBytesShort(result.bytes)}"
                    } else {
                        results += "${index + 1}. $mode FAIL stage=${result.stage} offset=${formatBytesShort(result.bytesTransferred)}/${formatBytesShort(result.bytes)}"
                        log("📊 Auto Matrix summary before FAIL: ${results.joinToString(" | ")}")
                        failOperation(
                            "Native USBFS Auto Matrix FAIL [${index + 1}/${nativeModes.size}, mode=$mode, " +
                                "stage=${result.stage}, offset=${result.bytesTransferred}/${result.bytes}]: ${result.message}. " +
                                if (result.sessionCorrupted) "Требуется новый вход в Fastboot." else ""
                        )
                    }
                }
                log("📊 Native USBFS Auto Matrix summary: ${results.joinToString(" | ")}")
                log("✅ Native USBFS Auto Matrix PASS: все ${nativeModes.size} профилей прошли download-only.")
            } finally {
                runCatching { payload.delete() }
            }
        }
    }

    fun runFastbootImagePrefixProbe(file: File, mode: FastbootProtocol.DataTransportMode) {
        startOperation(
            "Fastboot Content Probe",
            "prefix download-only ${file.name}"
        ) {
            val proto = fastbootProtocol ?: failOperation("Нет Fastboot-соединения")
            if (!proto.isConnected) failOperation("Нет Fastboot-соединения")
            if (!isNativeUsbfsDiagnosticOnly(mode)) {
                failOperation("Content Probe разрешён только для Native USBFS diagnostic profiles")
            }
            if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) {
                failOperation("Файл недоступен для Content Probe: ${file.absolutePath}")
            }
            val product = proto.compatibilityProduct?.trim()?.takeIf { it.isNotBlank() }
                ?: failOperation("Не удалось определить product для Content Probe")
            val probeSizes = listOf(
                1L * 1024L * 1024L,
                2L * 1024L * 1024L,
                3L * 1024L * 1024L,
                4L * 1024L * 1024L,
                8L * 1024L * 1024L,
                16L * 1024L * 1024L
            ).filter { it <= file.length() }
            if (probeSizes.isEmpty()) failOperation("Файл слишком мал для Content Probe: ${file.length()} байт")

            val probeDir = File(getApplication<Application>().cacheDir, "fastboot-content-probe").apply { mkdirs() }
            val results = mutableListOf<String>()
            log("🧬 Content-Differential DATA Probe: product=$product, file=${file.name}, size=${formatBytesShort(file.length())}, mode=$mode, generation=${currentFastbootGenerationToken()}")
            log("🛡️ Content Probe: prefix-only download buffer. flash/boot/stage/update-super/unlock не отправляются.")
            try {
                for ((index, bytes) in probeSizes.withIndex()) {
                    val prefixFile = File(probeDir, "${file.name}.prefix-${bytes}.img")
                    runCatching { prefixFile.delete() }
                    copyFilePrefix(file, prefixFile, bytes)
                    val sha = sha256OfFile(prefixFile).take(16)
                    log("▶️ Content Probe ${index + 1}/${probeSizes.size}: prefix=${formatBytesShort(bytes)}, sha256=$sha…, mode=$mode")
                    val result = proto.runDataQualificationTestDetailed(prefixFile, mode)
                    currentCoroutineContext().ensureActive()
                    recordDataQualificationResult(product, mode, result, "content-prefix-sha256:$sha:bytes=$bytes")
                    if (result.success) {
                        logNativeUsbfsDiagnosticOnlyPass(mode, "Content Probe prefix ${formatBytesShort(bytes)}")
                        results += "prefix ${formatBytesShort(bytes)} PASS"
                    } else {
                        results += "prefix ${formatBytesShort(bytes)} FAIL offset=${formatBytesShort(result.bytesTransferred)}/${formatBytesShort(result.bytes)} stage=${result.stage}"
                        log("📊 Content Probe summary before FAIL: ${results.joinToString(" | ")}")
                        failOperation(
                            "Content Probe FAIL [prefix=${formatBytesShort(bytes)}, mode=$mode, " +
                                "stage=${result.stage}, offset=${result.bytesTransferred}/${result.bytes}]: ${result.message}. " +
                                if (result.sessionCorrupted) "Требуется новый вход в Fastboot." else ""
                        )
                    }
                    runCatching { prefixFile.delete() }
                }
                log("📊 Content Probe summary: ${results.joinToString(" | ")}")
                log("✅ Content Probe PASS: все prefix-срезы выбранного файла прошли download-only.")
            } finally {
                runCatching { probeDir.listFiles()?.forEach { it.delete() } }
            }
        }
    }

    private fun copyFilePrefix(source: File, dest: File, bytesToCopy: Long) {
        BufferedInputStream(FileInputStream(source)).use { input ->
            FileOutputStream(dest).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = bytesToCopy
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read.toLong()
                    }
                    output.flush()
                }
                runCatching { fileOutput.fd.sync() }
            }
        }
        if (dest.length() != bytesToCopy) {
            throw IllegalStateException("Prefix copy size mismatch: expected=$bytesToCopy actual=${dest.length()}")
        }
    }

    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun runFastbootDataFileQualification(file: File, mode: FastbootProtocol.DataTransportMode) {
        startOperation(
            "Fastboot DATA Qualification",
            "staging + download-only ${file.name} · ${formatBytesShort(file.length())}"
        ) {
            val proto = fastbootProtocol ?: failOperation("Нет Fastboot-соединения")
            if (!proto.isConnected) failOperation("Нет Fastboot-соединения")
            if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) {
                failOperation("Файл недоступен для DATA qualification: ${file.absolutePath}")
            }
            val product = proto.compatibilityProduct?.trim()?.takeIf { it.isNotBlank() }
                ?: failOperation("Не удалось определить product для DATA qualification")

            val prepared = stageFastbootDataArtifact(file)
            var keepStaged = false
            try {
                log(
                    "🧪 Internal-Staged DATA Qualification: product=$product, source=${file.name}, " +
                        "sourcePath=${file.absolutePath}, stagedPath=${prepared.stagedFile.absolutePath}, " +
                        "size=${formatBytesShort(prepared.bytes)}, mode=$mode, " +
                        "generation=${currentFastbootGenerationToken()}, " +
                        "artifact=${prepared.artifactId.substringAfter("sha256:").take(16)}…"
                )
                val result = proto.runDataQualificationTestDetailed(prepared.stagedFile, mode)
                currentCoroutineContext().ensureActive()
                recordDataQualificationResult(product, mode, result, prepared.artifactId)
                if (result.success) {
                    if (isNativeUsbfsDiagnosticOnly(mode)) {
                        logNativeUsbfsDiagnosticOnlyPass(mode, "Internal-Staged DATA Qualification ${file.name}")
                        keepStaged = false
                    } else {
                        proto.dataTransportMode = mode
                        keepStaged = true
                    }
                    log("✅ Internal-Staged DATA Qualification PASS: ${file.name} через $mode")
                } else {
                    failOperation(
                        "Internal-Staged DATA Qualification FAIL [stage=${result.stage}, mode=$mode, " +
                            "offset=${result.bytesTransferred}/${result.bytes}]: ${result.message}. " +
                            if (result.sessionCorrupted) "Требуется новый вход в Fastboot." else ""
                    )
                }
            } finally {
                if (!keepStaged) releaseFastbootStagedArtifact(prepared, "qualification failed/cancelled")
            }
        }
    }


    private fun recordDataQualificationResult(
        product: String,
        mode: FastbootProtocol.DataTransportMode,
        result: FastbootProtocol.DataSelfTestResult,
        artifactId: String? = null
    ) {
        if (isNativeUsbfsDiagnosticOnly(mode)) {
            val artifactLabel = artifactId ?: "none"
            log("ℹ️ Native USBFS Matrix diagnostic result is not persisted as mutation authorization evidence. product=$product, success=${result.success}, bytes=${result.bytes}, artifact=$artifactLabel")
            return
        }
        val transport = protocolTransportToEvidence(mode)
        if (result.success) {
            persistFastbootDataPass(product, transport, result.bytes)
            artifactId?.let { persistFastbootArtifactPass(product, transport, it, result.bytes) }
        } else if (
            result.stage == FastbootProtocol.DataSelfTestStage.DATA_TRANSFER ||
            result.stage == FastbootProtocol.DataSelfTestStage.WAIT_DOWNLOAD_FINAL
        ) {
            persistFastbootDataFailure(product, transport, result.bytes, result.bytesTransferred)
            artifactId?.let { persistFastbootArtifactFailure(product, transport, it, result.bytesTransferred) }
        }
    }

    fun runFastbootSharedStorageDataProbe(
        sizeBytes: Long,
        mode: FastbootProtocol.DataTransportMode,
        workspaceDir: File
    ) {
        startOperation("Internal-Staging DATA Probe", "shared source → private stage → download-only ${formatBytesShort(sizeBytes)}") {
            val proto = fastbootProtocol ?: failOperation("Нет Fastboot-соединения")
            if (!proto.isConnected) failOperation("Нет Fastboot-соединения")
            if (sizeBytes !in FastbootProtocol.SAFE_DATA_SELF_TEST_SIZES_BYTES) {
                failOperation("Неподдерживаемый размер internal-staging probe: $sizeBytes байт")
            }
            if (!workspaceDir.exists() && !workspaceDir.mkdirs()) {
                failOperation("Не удалось создать рабочую папку: ${workspaceDir.absolutePath}")
            }
            val product = proto.compatibilityProduct?.trim()?.takeIf { it.isNotBlank() }
                ?: failOperation("Не удалось определить product для internal-staging DATA probe")
            val payload = File(workspaceDir, "fastboot-shared-storage-probe-$sizeBytes.img")
            var prepared: PreparedFastbootDataArtifact? = null
            try {
                writeDeterministicSelfTestPayload(payload, sizeBytes)
                val activePrepared = stageFastbootDataArtifact(payload)
                prepared = activePrepared
                log(
                    "🧪 Internal-Staging DATA Probe: product=$product, source=${payload.name}, " +
                        "sourcePath=${payload.absolutePath}, stagedPath=${activePrepared.stagedFile.absolutePath}, " +
                        "size=${formatBytesShort(sizeBytes)}, mode=$mode, generation=${currentFastbootGenerationToken()}, " +
                        "artifact=${activePrepared.artifactId.substringAfter("sha256:").take(16)}…"
                )
                if (runCatching { payload.delete() }.getOrDefault(false)) {
                    log("🧪 Probe source удалён до USB DATA: доказательство, что передача идёт только из private staged-копии.")
                }
                val result = proto.runDataQualificationTestDetailed(activePrepared.stagedFile, mode)
                currentCoroutineContext().ensureActive()
                recordDataQualificationResult(product, mode, result, activePrepared.artifactId)
                if (result.success) {
                    if (isNativeUsbfsDiagnosticOnly(mode)) {
                        logNativeUsbfsDiagnosticOnlyPass(mode, "Internal-Staging DATA Probe")
                    } else {
                        proto.dataTransportMode = mode
                    }
                    log("✅ Internal-Staging DATA Probe PASS: ${activePrepared.stagedFile.name} через $mode")
                } else {
                    failOperation(
                        "Internal-Staging DATA Probe FAIL [stage=${result.stage}, mode=$mode, " +
                            "offset=${result.bytesTransferred}/${result.bytes}]: ${result.message}. " +
                            if (result.sessionCorrupted) "Требуется новый вход в Fastboot." else ""
                    )
                }
            } finally {
                runCatching { payload.delete() }
                prepared?.let { releaseFastbootStagedArtifact(it, "diagnostic probe finished") }
            }
        }
    }


    private fun writeDeterministicSelfTestPayload(file: File, sizeBytes: Long) {
        require(sizeBytes > 0L)
        val block = ByteArray(64 * 1024)
        var written = 0L
        file.outputStream().buffered().use { out ->
            while (written < sizeBytes) {
                val count = minOf(block.size.toLong(), sizeBytes - written).toInt()
                for (i in 0 until count) {
                    val absolute = written + i.toLong()
                    block[i] = ((absolute * 31L + 0x5AL) and 0xFFL).toByte()
                }
                out.write(block, 0, count)
                written += count
            }
        }
        if (file.length() != sizeBytes) {
            throw IllegalStateException("Self-test payload size mismatch: expected=$sizeBytes actual=${file.length()}")
        }
    }

    private fun unlockVerificationPrefs() =
        getApplication<Application>().getSharedPreferences(MI_UNLOCK_VERIFY_PREFS, Context.MODE_PRIVATE)

    private fun persistPendingUnlockVerification(
        product: String,
        serial: String?,
        expectedUnlocked: Boolean = true,
        operationLabel: String = "Mi Unlock"
    ) {
        unlockVerificationPrefs().edit()
            .putString(MI_UNLOCK_VERIFY_PRODUCT, product.trim())
            .putString(MI_UNLOCK_VERIFY_SERIAL, serial?.trim())
            .putBoolean(MI_UNLOCK_VERIFY_EXPECTED_UNLOCKED, expectedUnlocked)
            .putString(MI_UNLOCK_VERIFY_OPERATION_LABEL, operationLabel)
            .putLong(MI_UNLOCK_VERIFY_CREATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun readPendingUnlockVerification(): PendingUnlockVerification? {
        val prefs = unlockVerificationPrefs()
        val product = prefs.getString(MI_UNLOCK_VERIFY_PRODUCT, null)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return PendingUnlockVerification(
            product = product,
            serial = prefs.getString(MI_UNLOCK_VERIFY_SERIAL, null)?.trim()?.takeIf { it.isNotBlank() },
            expectedUnlocked = prefs.getBoolean(MI_UNLOCK_VERIFY_EXPECTED_UNLOCKED, true),
            operationLabel = prefs.getString(MI_UNLOCK_VERIFY_OPERATION_LABEL, null)
                ?.trim()?.takeIf { it.isNotBlank() } ?: "Mi Unlock",
            createdAtMs = prefs.getLong(MI_UNLOCK_VERIFY_CREATED_AT, 0L)
        )
    }

    private fun clearPendingUnlockVerification() {
        unlockVerificationPrefs().edit().clear().apply()
    }

    private fun sideloadVerificationPrefs() =
        getApplication<Application>().getSharedPreferences(SIDELOAD_VERIFY_PREFS, Context.MODE_PRIVATE)

    private fun persistPendingSideloadVerification(
        file: File,
        proto: AdbProtocol,
        integrity: PackageIntegrityVerifier.Result
    ) {
        val device = adbBannerProperty(proto.currentDiagnostics().remoteBanner, "ro.product.device")
        val saved = sideloadVerificationPrefs().edit()
            .putString(SIDELOAD_VERIFY_PACKAGE, file.name)
            .putLong(SIDELOAD_VERIFY_PACKAGE_SIZE, integrity.fileSize)
            .putString(SIDELOAD_VERIFY_PACKAGE_SHA256, integrity.sha256)
            .putString(SIDELOAD_VERIFY_DEVICE, device)
            .putLong(SIDELOAD_VERIFY_CREATED_AT, System.currentTimeMillis())
            .commit()
        if (!saved) {
            log("⚠️ Не удалось надёжно сохранить маркер проверки ADB Sideload.")
        }
    }

    private fun readPendingSideloadVerification(): PendingSideloadVerification? {
        val prefs = sideloadVerificationPrefs()
        val packageName = prefs.getString(SIDELOAD_VERIFY_PACKAGE, null)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return PendingSideloadVerification(
            packageName = packageName,
            packageSize = prefs.getLong(SIDELOAD_VERIFY_PACKAGE_SIZE, -1L),
            packageSha256 = prefs.getString(SIDELOAD_VERIFY_PACKAGE_SHA256, null)?.trim()?.takeIf { it.isNotBlank() },
            device = prefs.getString(SIDELOAD_VERIFY_DEVICE, null)?.trim()?.takeIf { it.isNotBlank() },
            createdAtMs = prefs.getLong(SIDELOAD_VERIFY_CREATED_AT, 0L)
        )
    }

    private fun clearPendingSideloadVerification() {
        sideloadVerificationPrefs().edit().clear().apply()
    }

    private fun adbBannerProperty(banner: String, key: String): String? {
        val marker = "$key="
        val start = banner.indexOf(marker)
        if (start < 0) return null
        return banner.substring(start + marker.length)
            .substringBefore(';')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun verifyPendingSideloadIfReady(proto: AdbProtocol) {
        val pending = readPendingSideloadVerification() ?: return
        if (proto.peerMode != AdbProtocol.PeerMode.RECOVERY) return

        val ageMs = System.currentTimeMillis() - pending.createdAtMs
        if (pending.createdAtMs <= 0L || ageMs < 0L || ageMs > SIDELOAD_VERIFY_TIMEOUT_MS) {
            clearPendingSideloadVerification()
            log("⚠️ Проверка результата ADB Sideload истекла. Итог установки не подтверждён приложением.")
            return
        }

        val currentDevice = adbBannerProperty(proto.currentDiagnostics().remoteBanner, "ro.product.device")
        if (pending.device != null) {
            if (currentDevice == null) {
                logFileOnly(
                    "Pending sideload verification waits for device identity: " +
                        "pending=${pending.device}, current=unknown"
                )
                return
            }
            if (!pending.device.equals(currentDevice, ignoreCase = true)) {
                logFileOnly(
                    "Pending sideload verification ignored for another device: " +
                        "pending=${pending.device}, current=$currentDevice"
                )
                return
            }
        }

        log("=== ПРОВЕРКА РЕЗУЛЬТАТА ADB SIDELOAD ===")
        log("Пакет: ${pending.packageName} (${pending.packageSize} байт)")
        pending.packageSha256?.let { log("Package SHA-256: $it") }
        val verification = proto.inspectRecoveryInstallResult()
        verification.source?.let { log("Recovery source: $it") }
        verification.evidence?.takeIf { it.isNotBlank() }?.let { log("Recovery evidence: $it") }

        when (verification.verdict) {
            RecoveryInstallVerifier.Verdict.SUCCESS -> {
                clearPendingSideloadVerification()
                log("✅ ADB Sideload подтверждён Recovery: ${verification.message}")
                _operationProgress.postValue(
                    OperationProgress(
                        title = "ADB Sideload",
                        percent = 100,
                        detail = verification.message,
                        finished = true,
                        success = true,
                        outcome = OperationOutcomeKind.SUCCESS
                    )
                )
            }
            RecoveryInstallVerifier.Verdict.FAILED -> {
                clearPendingSideloadVerification()
                val detail = buildString {
                    append(verification.message)
                    verification.evidence?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
                log("❌ ADB Sideload завершился ошибкой в Recovery: $detail")
                _operationProgress.postValue(
                    OperationProgress(
                        title = "ADB Sideload",
                        percent = 100,
                        detail = detail,
                        finished = true,
                        success = false,
                        outcome = OperationOutcomeKind.FAILED
                    )
                )
            }
            RecoveryInstallVerifier.Verdict.UNKNOWN -> {
                log("⚠️ ${verification.message}")
                log("ℹ️ Передача ZIP завершена, но приложение не нашло надёжного install result. Проверьте экран Recovery.")
                _operationProgress.postValue(
                    OperationProgress(
                        title = "ADB Sideload",
                        percent = 100,
                        detail = "Передача завершена; итог установки пока не подтверждён",
                        finished = true,
                        success = false,
                        outcome = OperationOutcomeKind.VERIFY_PENDING
                    )
                )
            }
        }
    }

    private fun verifyPendingUnlockIfReady(diagnostics: FastbootProtocol.DeviceDiagnostics) {
        val pending = readPendingUnlockVerification() ?: return
        val ageMs = System.currentTimeMillis() - pending.createdAtMs
        if (pending.createdAtMs <= 0L || ageMs < 0L || ageMs > MI_UNLOCK_VERIFY_TIMEOUT_MS) {
            clearPendingUnlockVerification()
            log("⚠️ Ожидание проверки ${pending.operationLabel} истекло. Запустите операцию заново.")
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

        val actualUnlocked = FastbootMutationSafety.parseFastbootBoolean(diagnostics.unlocked)
        val expectedText = if (pending.expectedUnlocked) "yes" else "no"
        when (actualUnlocked) {
            pending.expectedUnlocked -> {
                clearPendingUnlockVerification()
                val message = "${pending.operationLabel} подтверждён устройством: getvar:unlocked=$expectedText"
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
            null -> log("ℹ️ ${pending.operationLabel} ожидает финальную проверку: getvar:unlocked пока недоступен.")
            else -> {
                clearPendingUnlockVerification()
                val actualText = if (actualUnlocked) "yes" else "no"
                val message = "${pending.operationLabel} не подтверждён: устройство сообщает getvar:unlocked=$actualText, ожидалось $expectedText"
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
        if (proto.peerMode != AdbProtocol.PeerMode.SIDELOAD) {
            log("ОШИБКА: ADB Sideload недоступен в режиме ${proto.peerMode.name}.")
            log("💡 Откройте: Recovery → Apply update → Apply from ADB")
            return
        }

        clearPendingSideloadVerification()
        startOperation(text(R.string.notif_adb_sideload), text(R.string.notif_sideload_sending, file.name)) {
            when (val result = proto.sideloadZip(file)) {
                is AdbProtocol.SideloadResult.TransferComplete -> {
                    log("✅ Package integrity preflight: VALID")
                    result.integrity.sha256?.let { log("Package SHA-256: $it") }
                    persistPendingSideloadVerification(file, proto, result.integrity)
                    verificationPending(
                        "Передача ZIP завершена. Ожидаем возврат в Recovery для проверки фактического результата установки."
                    )
                }
                AdbProtocol.SideloadResult.Cancelled ->
                    throw OperationAbort(OperationOutcome.Cancelled("ADB Sideload отменён"))
                is AdbProtocol.SideloadResult.NotInSideloadMode ->
                    failOperation("ADB Sideload не активирован. Текущий режим: ${result.mode.name}")
                is AdbProtocol.SideloadResult.Failed ->
                    failOperation("ADB Sideload [${result.kind.name}]: ${result.message}")
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
        block: suspend OperationContext.() -> Unit
    ) {
        synchronized(operationLaunchLock) {
            if (viewModelCleared.get()) {
                log("⚠️ Новая операция отклонена: DeviceViewModel уже завершает безопасное закрытие USB.")
                return
            }
            if (transportRestartRequired.get()) {
                log("⛔ Новая операция запрещена: USB transport требует полного перезапуска NekoFlash после неподтверждённой очистки.")
                return
            }
            if (connectionJob?.isCompleted == false) {
                log("⚠️ Подключение или отключение USB ещё не завершено. Дождитесь стабильного статуса устройства.")
                return
            }
            if (operationJob?.isCompleted == false || NativeUsbfsBackend.hasActiveTransfer ||
                NativeUsbfsBackend.backendState().nativeTransferActive
            ) {
                log("⚠️ Другая USB-операция ещё активна. Сначала дождитесь её завершения или выполните безопасную отмену.")
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
                    if (fastboot?.isConnected == true && !fastboot.beginOperation()) {
                        throw OperationAbort(
                            OperationOutcome.Failed(
                                "Fastboot-сессия не готова к новой операции. Переподключите устройство."
                            )
                        )
                    }
                    context.block()
                } catch (abort: OperationAbort) {
                    outcome = abort.outcome
                } catch (_: CancellationException) {
                    outcome = OperationOutcome.Cancelled("Операция отменена")
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
                detail = "Отмена запрошена. Завершаем pending USB URB и закрываем сессию безопасно…",
                finished = false,
                success = false,
                outcome = null
            )
        )
        log("⏳ Отмена запрошена. WakeLock и foreground-service останутся активны до фактического завершения USB-очистки.")
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
        super.onCleared()
    }

    companion object {
        private const val SAVED_FLASH_QUEUE_DRAFT = "flash_queue_draft_v1"
        private const val FASTBOOT_DOWNLOAD_IMPLEMENTATION_LIMIT_BYTES = 0xFFFF_FFFFL
        private const val FASTBOOT_STAGE_COPY_BUFFER_BYTES = 1024 * 1024
        private const val FASTBOOT_STAGE_PROGRESS_STEP_BYTES = 64L * 1024L * 1024L
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        private const val TRANSPORT_SHUTDOWN_TIMEOUT_MS = 100_000L
        private const val TRANSPORT_IDLE_POLL_MS = 10L
        private const val MI_UNLOCK_VERIFY_TIMEOUT_MS = 24L * 60L * 60L * 1000L
        private const val SIDELOAD_VERIFY_TIMEOUT_MS = 60L * 60L * 1000L
        private const val MAX_OPERATION_STEPS_IN_UI = 240
        private const val MI_UNLOCK_VERIFY_PREFS = "mi_unlock_verify"
        private const val SIDELOAD_VERIFY_PREFS = "sideload_verify"
        private const val FASTBOOT_TRANSPORT_PREFS = "fastboot_transport_compat"
        private const val MI_UNLOCK_VERIFY_PRODUCT = "product"
        private const val MI_UNLOCK_VERIFY_SERIAL = "serial"
        private const val MI_UNLOCK_VERIFY_EXPECTED_UNLOCKED = "expected_unlocked"
        private const val MI_UNLOCK_VERIFY_OPERATION_LABEL = "operation_label"
        private const val MI_UNLOCK_VERIFY_CREATED_AT = "created_at"
        private const val SIDELOAD_VERIFY_PACKAGE = "package_name"
        private const val SIDELOAD_VERIFY_PACKAGE_SIZE = "package_size"
        private const val SIDELOAD_VERIFY_PACKAGE_SHA256 = "package_sha256"
        private const val SIDELOAD_VERIFY_DEVICE = "device"
        private const val SIDELOAD_VERIFY_CREATED_AT = "created_at"
    }
}
