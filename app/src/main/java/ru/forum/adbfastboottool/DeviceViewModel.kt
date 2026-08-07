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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    // FIX #9: AtomicLong вместо обычного Long — потокобезопасно
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
        val repeated = "↻ Предыдущая строка повторилась ещё $suppressedDuplicateCount раз(а); дубликаты свёрнуты."
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
                log("⚠️ USB session snapshot не сохранён: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun connectDevice(
        usbManager: UsbManager,
        candidate: UsbDeviceInspector.Candidate,
        automatic: Boolean = false
    ) {
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
                    log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: Fastboot handshake не подтверждён. Повторное подключение разрешено.")
                    return
                }
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return

                val diagnostics = proto.refreshDiagnostics(force = true, knownProduct = qualifiedProduct)
                if (viewModelCleared.get() || generation != connectionGeneration.get()) return
                if (proto.isSessionBroken) {
                    _connectionState.postValue(ConnectionState.ERROR)
                    log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: USB-интерфейс открылся, но корректный Fastboot-обмен не подтвердился")
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
                    log("⛔ ADB transport остановлен [${code.name}]: $message. Автоповтор запрещён до ручного переподключения.")
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
                val accepted = try {
                    proto.stageAndOemUnlock(file)
                } finally {
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
            if (!proto.sendCommand(cmd)) failOperation("Fastboot-команда завершилась ошибкой: $cmd")
        }
    }


    fun runFastbootDownloadAndRun(file: File, commandAfterDownload: String) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, commandAfterDownload)) {
            val proto = fastbootProtocol ?: failOperation(text(R.string.error_no_fastboot))
            if (!proto.downloadAndRun(file, commandAfterDownload)) {
                failOperation("Fastboot download+run завершился ошибкой: $commandAfterDownload")
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
            if (AdbServiceCompletionPolicy.expectsOneWayDisconnect(service)) {
                _operationProgress.postValue(
                    OperationProgress(
                        title = text(R.string.notif_adb_command),
                        percent = 100,
                        detail = "Команда перезагрузки передана. Устройство временно отключится и появится в новом режиме."
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
            val result = proto.flashPartitionDetailed(partition, file)
            if (!result.success) failOperation(formatFlashFailure(partition, result))
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
        val item = runCatching { FlashOperationDraftPolicy.createItem(partition, file) }
            .getOrElse { error ->
                log("❌ Не удалось добавить файл в очередь: ${error.message ?: error.javaClass.simpleName}")
                return
            }
        val next = runCatching { FlashOperationDraftPolicy.upsert(currentFlashOperationDraft(), item) }
            .getOrElse { error ->
                log("❌ Не удалось обновить очередь: ${error.message ?: error.javaClass.simpleName}")
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
                log("❌ ${item.partition} ← ${item.displayName}: файл недоступен")
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
                failOperation("Нет Fastboot-соединения")
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
                    log("❌ Очередь остановлена на разделе ${item.partition}")
                    failOperation(formatFlashFailure(item.partition, result))
                }
            }
            log("✅ Очередь прошивки завершена")
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
            log("⚠️ Не удалось сохранить маркер результата ADB Sideload.")
        }
    }

    private fun readPendingSideloadVerification(): PendingSideloadVerification? {
        val prefs = sideloadVerificationPrefs()
        val packageName = prefs.getString(SIDELOAD_VERIFY_PACKAGE, null)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return PendingSideloadVerification(
            packageName = packageName,
            packageSize = prefs.getLong(SIDELOAD_VERIFY_PACKAGE_SIZE, -1L),
            device = prefs.getString(SIDELOAD_VERIFY_DEVICE, null)?.trim()?.takeIf { it.isNotBlank() },
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
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun verifyPendingSideloadIfReady(proto: AdbProtocol) {
        val pending = readPendingSideloadVerification() ?: return
        if (proto.peerMode != AdbProtocol.PeerMode.RECOVERY) return

        val ageMs = System.currentTimeMillis() - pending.createdAtMs
        if (pending.createdAtMs <= 0L || ageMs < 0L || ageMs > SIDELOAD_VERIFY_TIMEOUT_MS) {
            clearPendingSideloadVerification()
            log("⚠️ Ожидание результата ADB Sideload истекло; итог установки смотрите на экране Recovery.")
            return
        }

        val currentDevice = adbBannerProperty(proto.currentDiagnostics().remoteBanner, "ro.product.device")
        if (pending.device != null) {
            if (currentDevice == null) return
            if (!pending.device.equals(currentDevice, ignoreCase = true)) return
        }

        log("=== РЕЗУЛЬТАТ ADB SIDELOAD ===")
        log("Пакет: ${pending.packageName} (${pending.packageSize} байт)")
        val verification = proto.inspectRecoveryInstallResult()
        verification.source?.let { log("Recovery source: $it") }
        verification.evidence?.takeIf { it.isNotBlank() }?.let { log("Recovery evidence: $it") }

        when (verification.verdict) {
            RecoveryInstallVerifier.Verdict.SUCCESS -> {
                clearPendingSideloadVerification()
                log("✅ Recovery сообщает успешную установку: ${verification.message}")
            }
            RecoveryInstallVerifier.Verdict.FAILED -> {
                clearPendingSideloadVerification()
                val detail = buildString {
                    append(verification.message)
                    verification.evidence?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
                log("❌ Recovery сообщает ошибку установки: $detail")
            }
            RecoveryInstallVerifier.Verdict.UNKNOWN -> {
                log("ℹ️ Передача завершена; Recovery не предоставило однозначный install result.")
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

        val actualUnlocked = FastbootValueParser.parseBoolean(diagnostics.unlocked)
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

        clearPendingSideloadVerification()
        startOperation(text(R.string.notif_adb_sideload), text(R.string.notif_sideload_sending, file.name)) {
            when (val result = proto.sideloadZip(file)) {
                AdbProtocol.SideloadResult.TransferComplete -> {
                    persistPendingSideloadVerification(file, proto)
                    verificationPending(
                        "Передача файла завершена. Recovery само определяет допустимость пакета; итог установки будет считан после возврата в Recovery, если лог доступен."
                    )
                }
                AdbProtocol.SideloadResult.Cancelled -> {
                    throw OperationAbort(OperationOutcome.Cancelled("ADB Sideload отменён"))
                }
                is AdbProtocol.SideloadResult.NotInSideloadMode -> {
                    failOperation("ADB Sideload не активирован. Текущий режим: ${result.mode.name}")
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
                    if (prepareFastbootSession && fastboot?.isConnected == true && !fastboot.beginOperation()) {
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
