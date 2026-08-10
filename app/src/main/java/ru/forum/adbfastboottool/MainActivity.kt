package ru.forum.adbfastboottool

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Главный UI-контейнер NekoFlash.
 *
 * Activity связывает вкладки приложения с единственным [DeviceViewModel], но не владеет
 * USB-транспортом и не выполняет mutation напрямую. Долгоживущие подключения и операции
 * остаются во ViewModel/transport layer. UI выбирает действие и передаёт его в реальный
 * ADB/Fastboot transport без тестовых и host-side authorization прослоек.
 */
class MainActivity : AppCompatActivity() {

    private enum class MiAuthExchangeState { IDLE, LOADING, SUCCESS, ERROR }

    private lateinit var usbManager: UsbManager
    private lateinit var rvConsoleOutput: RecyclerView
    private lateinit var consoleLogAdapter: ConsoleLogAdapter
    private lateinit var etCommand: EditText
    private lateinit var consoleDockController: ConsoleDockController
    private lateinit var tvStatus: TextView
    private var tvOtgStatus: TextView? = null
    private lateinit var tvOperationCenterStatus: TextView
    private lateinit var tvOperationCenterLastEvent: TextView
    private lateinit var tvOperationStepQueue: TextView
    private var flashProgressPanel: View? = null
    private var flashProgressBar: android.widget.ProgressBar? = null
    private var flashProgressPercent: TextView? = null
    private var flashProgressDetail: TextView? = null
    private var flashProgressTitleTv: TextView? = null
    private var flashProgressButton: Button? = null
    private var flashProgressWarning: TextView? = null
    private lateinit var viewModel: DeviceViewModel
    private var viewModelReady: Boolean = false

    private val actionUsbPermission: String by lazy { "$packageName.USB_PERMISSION" }
    private val folderName = "NekoFlash"
    private lateinit var workspacePath: File
    private lateinit var importFileLauncher: ActivityResultLauncher<Intent>
    private lateinit var miLoginLauncher: ActivityResultLauncher<Intent>
    private var miAuth: MiAccountClient.AuthResult? = null
    private var miAuthExchangeJob: Job? = null
    private var miAuthExchangeState: MiAuthExchangeState = MiAuthExchangeState.IDLE

    // Управление вкладками вынесено в TabController (декомпозиция MainActivity).
    // by lazy — чтобы не обращаться к this в инициализаторе поля (leaking this).
    private val tabController by lazy { TabController(this) }
    // Совместимость: остальной код читает currentTab/selectedWindow как раньше.
    private val currentTab: String get() = tabController.commandContext
    private val selectedWindow: String get() = tabController.selectedWindow

    private var restoringWindowState = false
    private var overlayProtectionLogged = false
    private var redirectingToWelcome = false

    private data class PendingUsbConnect(
        val candidate: UsbDeviceInspector.Candidate,
        val automatic: Boolean
    )

    private val usbPermissionHandler = Handler(Looper.getMainLooper())
    private val usbPermissionTimeouts = mutableMapOf<Int, Runnable>()
    private val pendingUsbCandidates = mutableMapOf<Int, PendingUsbConnect>()

    private val modeSwitchHandler = Handler(Looper.getMainLooper())
    private val deviceOverviewHandler = Handler(Looper.getMainLooper())
    private val shortDeviceOverviewRefresh = Runnable {
        if (!isFinishing && !isDestroyed) updateDeviceOverview()
    }
    private val finalDeviceOverviewRefresh = Runnable {
        if (!isFinishing && !isDestroyed) updateDeviceOverview()
    }
    private var modeSwitchPreviousSignature: String? = null
    private var modeSwitchPreviousVendorId: Int? = null
    private var modeSwitchAttemptsRemaining = 0
    private var startupUsbDiscoveryDone = false
    private val startupUsbDiscoveryRunnable = Runnable { discoverAlreadyConnectedDevice() }
    private val modeSwitchRunnable = object : Runnable {
        override fun run() {
            if (modeSwitchAttemptsRemaining <= 0) return

            val candidate = UsbDeviceInspector.selectModeSwitchCandidate(
                usbManager.deviceList.values,
                modeSwitchPreviousSignature,
                modeSwitchPreviousVendorId
            )
            if (candidate != null) {
                modeSwitchAttemptsRemaining = 0
                // selectModeSwitchCandidate уже гарантировал другой логический профиль.
                viewModel.log(
                    "USB re-enumeration: найден новый режим ${candidate.mode.label} " +
                        "(interface=${candidate.interfaceIndex})"
                )
                requestUsbAccess(candidate, automatic = true)
                return
            }

            modeSwitchAttemptsRemaining -= 1
            if (modeSwitchAttemptsRemaining > 0) {
                modeSwitchHandler.postDelayed(this, MODE_SWITCH_SCAN_INTERVAL_MS)
            }
        }
    }

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private sealed class TerminalAction {
        data object LocalStatus : TerminalAction()
        data object OpenReportsFolder : TerminalAction()
        data class RawFastboot(val command: String) : TerminalAction()
        data class FastbootFlash(val partition: String, val file: File, val slot: String? = null) : TerminalAction()
        data class FastbootPartitionCommand(val wirePrefix: String, val partition: String, val slot: String? = null) : TerminalAction()
        data class FastbootDownloadAndRun(val file: File, val commandAfterDownload: String) : TerminalAction()
        data class FastbootLogicalInfo(val partition: String) : TerminalAction()
        data class FastbootFetch(val partition: String, val outputFile: File, val slot: String? = null) : TerminalAction()
        data class AdbService(val service: String) : TerminalAction()
        data class AdbShell(val command: String) : TerminalAction()
        data class AdbPush(val localFile: File, val remotePath: String) : TerminalAction()
        data class AdbPull(val remotePath: String, val localFile: File) : TerminalAction()
        data class AdbInstall(val packageFile: File, val options: List<String>) : TerminalAction()
        data class AdbInstallMultiple(val apkFiles: List<File>, val options: List<String>) : TerminalAction()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> handleUsbPermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleUsbDetached(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedLanguage()
        super.onCreate(savedInstanceState)

        // Never trust exported Intent extras as an onboarding bypass. This
        // check also covers ACTION_USB_DEVICE_ATTACHED, activity recreation,
        // and a storage permission revoked after onboarding.
        if (!OnboardingGate.canEnterMain(this)) {
            redirectToWelcome(intent)
            return
        }
        setContentView(R.layout.activity_main)

        rvConsoleOutput = findViewById(R.id.rvConsoleOutput)
        consoleLogAdapter = ConsoleLogAdapter()
        rvConsoleOutput.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = consoleLogAdapter
            itemAnimator = null
        }
        etCommand = findViewById(R.id.etCommand)
        consoleDockController = ConsoleDockController(this, rvConsoleOutput).also { it.initialize() }
        flashProgressPanel = findViewById(R.id.flashProgressPanel)
        flashProgressTitleTv = findViewById(R.id.flashProgressTitle)
        flashProgressPercent = findViewById(R.id.flashProgressPercent)
        flashProgressBar = findViewById(R.id.flashProgressBar)
        flashProgressDetail = findViewById(R.id.flashProgressDetail)
        flashProgressWarning = findViewById(R.id.flashProgressWarning)
        flashProgressButton = findViewById(R.id.flashProgressAction)
        findViewById<View>(R.id.btnConsoleLogs).setOnClickListener { showLogsMenu() }
        tvStatus = findViewById(R.id.tvStatus)
        tvOtgStatus = findViewById(R.id.tvOtgStatus)
        updateOtgStatus()
        tvOperationCenterStatus = findViewById(R.id.tvOperationCenterStatus)
        tvOperationCenterLastEvent = findViewById(R.id.tvOperationCenterLastEvent)
        tvOperationStepQueue = findViewById(R.id.tvOperationStepQueue)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        viewModel = ViewModelProvider(this)[DeviceViewModel::class.java]
        viewModelReady = true
        enableOverlayProtection()

        viewModel.logLines.observe(this) { lines ->
            renderLog(lines)
            updateOperationCenter(lines)
        }

        viewModel.connectionState.observe(this) {
            refreshConnectionStatusLabel()
            updateDeviceOverview()
            if (selectedWindow == "unlock") buildUnlockPage()
        }

        viewModel.connectionInfo.observe(this) { updateDeviceOverview() }
        viewModel.fastbootDiagnostics.observe(this) {
            // Диагностика приходит после connectionState — переобновим точный режим.
            refreshConnectionStatusLabel()
            updateDeviceOverview()
            if (selectedWindow == "unlock") buildUnlockPage()
        }
        viewModel.fastbootPartitionInventory.observe(this) {
            updateDeviceOverview()
        }
        viewModel.adbPeerMode.observe(this) {
            // ADB transport один, но peer mode различается: system/recovery/sideload.
            refreshConnectionStatusLabel()
            updateDeviceOverview()
        }
        viewModel.transportSessionId.observe(this) { updateDeviceOverview() }
        viewModel.flashOperationDraft.observe(this) { draft -> updateFlashQueueUi(draft) }

        viewModel.operationActive.observe(this) { active ->
            if (active) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Авто-снижение яркости на время записи: экономит энергию
                // и снижает нагрев/троттлинг при долгой прошивке.
                applyReducedBrightness()
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                restoreBrightness()
            }
            updateDeviceOverview()
            updateOperationCenter(viewModel.logSnapshot())
        }

        viewModel.operationSteps.observe(this) { steps -> renderOperationSteps(steps) }
        viewModel.operationProgress.observe(this) { progress -> renderFlashProgressDialog(progress) }

        registerUsbReceiver()
        registerImportLauncher()
        registerMiLoginLauncher()
        setupButtons()
        buildSettingsPage()
        restoreWindowState(savedInstanceState)
        updateFlashQueueUi()
        updateDeviceOverview()
        checkPermissions()
        logBatteryOptimizationState()
        viewModel.log(getString(R.string.log_init_v20))
        val scanAfterWelcome = intent.getBooleanExtra(
            WelcomeActivity.EXTRA_STARTUP_SCAN_AFTER_WELCOME,
            false
        )
        val attachHandled = handleAutoUsbIntent(intent)
        if (!attachHandled) {
            if (scanAfterWelcome) {
                intent.removeExtra(WelcomeActivity.EXTRA_STARTUP_SCAN_AFTER_WELCOME)
                viewModel.log("USB attach продолжен после обязательного welcome-gate; выполняется безопасный startup-scan.")
            }
            scheduleStartupUsbDiscovery()
        }
    }

    private fun redirectToWelcome(sourceIntent: Intent?) {
        if (redirectingToWelcome || isFinishing || isDestroyed) return
        redirectingToWelcome = true
        val launchedFromUsbAttach = sourceIntent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        startActivity(Intent(this, WelcomeActivity::class.java).apply {
            putExtra(WelcomeActivity.EXTRA_PENDING_USB_ATTACH, launchedFromUsbAttach)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!OnboardingGate.canEnterMain(this)) {
            redirectToWelcome(intent)
            return
        }
        val scanAfterWelcome = intent.getBooleanExtra(
            WelcomeActivity.EXTRA_STARTUP_SCAN_AFTER_WELCOME,
            false
        )
        val attachHandled = handleAutoUsbIntent(intent)
        if (!attachHandled && scanAfterWelcome) {
            intent.removeExtra(WelcomeActivity.EXTRA_STARTUP_SCAN_AFTER_WELCOME)
            viewModel.log("USB attach продолжен после обязательного welcome-gate; выполняется безопасный startup-scan.")
            scheduleStartupUsbDiscovery()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_WINDOW, selectedWindow)
        super.onSaveInstanceState(outState)
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnScan).setOnClickListener { updateOtgStatus(); scanForDevices() }
        // Импорт файла из угла блока прошивки (в контексте Fastboot).
        findViewById<View>(R.id.btnBlockImportFastboot).setOnClickListener { startImportFilePicker() }
        findViewById<View>(R.id.btnBlockImportQueue).setOnClickListener { startImportFilePicker() }
        // Режим перезагрузки в блоке прошивки — то же меню, что было на главной.
        findViewById<View>(R.id.btnFlashRebootMode).setOnClickListener { showRebootMenu() }
        findViewById<Button>(R.id.btnHomeRefreshData).setOnClickListener { refreshDeviceDataFromUi() }
        findViewById<Button>(R.id.btnOperationCenterConsole).setOnClickListener {
            openConsole(requestCommandFocus = false)
        }
        findViewById<View>(R.id.btnReportsMenu).setOnClickListener { showReportsMenu() }
        findViewById<Button>(R.id.btnOperationCenterCancel).setOnClickListener { viewModel.cancelActiveOperation() }
        findViewById<Button>(R.id.btnQueueBoot).setOnClickListener { chooseFlashQueueFile("boot") }
        findViewById<Button>(R.id.btnQueueInitBoot).setOnClickListener { chooseFlashQueueFile("init_boot") }
        findViewById<Button>(R.id.btnQueueVendorBoot).setOnClickListener { chooseFlashQueueFile("vendor_boot") }
        findViewById<Button>(R.id.btnQueueRecovery).setOnClickListener { chooseFlashQueueFile("recovery") }
        findViewById<Button>(R.id.btnQueueDtbo).setOnClickListener { chooseFlashQueueFile("dtbo") }
        findViewById<Button>(R.id.btnQueueClear).setOnClickListener { clearFlashQueue() }
        findViewById<View>(R.id.btnQueueStart).setOnClickListener { confirmFlashQueue() }

        findViewById<View>(R.id.btnFlashRecovery).setOnClickListener {
            startDirectFlash("recovery")
        }
        findViewById<View>(R.id.btnFlashBoot).setOnClickListener {
            startDirectFlash("boot")
        }
        findViewById<View>(R.id.btnFlashInitBoot).setOnClickListener {
            startDirectFlash("init_boot")
        }
        findViewById<View>(R.id.btnFlashVendorBoot).setOnClickListener {
            startDirectFlash("vendor_boot")
        }
        findViewById<View>(R.id.btnFlashDtbo).setOnClickListener {
            startDirectFlash("dtbo")
        }
        findViewById<View>(R.id.btnFlashVbmeta).setOnClickListener {
            startDirectFlash("vbmeta")
        }
        findViewById<View>(R.id.btnFlashManual).setOnClickListener {
            showManualQuickFlashTargetDialog()
        }
        viewModel.log(getString(R.string.quick_flash_legacy_queue_hidden))

        // Единое меню Reboot (BottomSheet) — собирает все варианты перезагрузки.
        findViewById<Button>(R.id.btnAdbSideload).setOnClickListener {
            showFileSelector { file -> viewModel.runSideload(file) }
        }
        findViewById<View>(R.id.btnSideloadImport).setOnClickListener { startImportFilePicker() }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            viewModel.cancelActiveOperation()
        }

        findViewById<Button>(R.id.btnHistoryUp).setOnClickListener { navigateHistory(-1) }
        findViewById<Button>(R.id.btnHistoryDown).setOnClickListener { navigateHistory(1) }

        findViewById<Button>(R.id.btnSend).setOnClickListener { handleCommandInput() }
        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                handleCommandInput()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.tabHome).setOnClickListener { switchTab("home") }

        // Кнопка «Назад»: если мы не на главном экране — возвращаемся на него,
        // а не закрываем приложение. На главном — стандартный выход.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = tabController.selectedWindow
                viewModel.log("← Back pressed (current tab: $current)")
                when {
                    // Console owns IME and sheet-state navigation before pages.
                    consoleDockController.handleBack() -> Unit
                    // Не на главной — возвращаемся на главный экран.
                    current != "home" -> switchTab("home")
                    // На главной — сворачиваем в фон (не убивая процесс).
                    else -> moveTaskToBack(true)
                }
            }
        })
        findViewById<Button>(R.id.tabFastboot).setOnClickListener { switchTab("fastboot") }
        findViewById<Button>(R.id.tabAdb).setOnClickListener { switchTab("adb") }
        findViewById<Button>(R.id.tabSettings).setOnClickListener { switchTab("settings") }
        findViewById<Button>(R.id.tabUnlock).setOnClickListener {
            switchTab("unlock")
            buildUnlockPage()
        }
    }

    // ─── USB ─────────────────────────────────────────────────────────────────

    private fun registerUsbReceiver() {
        val filter = IntentFilter(actionUsbPermission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun handleUsbPermissionResult(intent: Intent) {
        synchronized(this@MainActivity) {
            val device = intent.parcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

            device?.let { cancelUsbPermissionTimeout(it.deviceId) }

            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                device?.let { takePendingUsbConnect(it) }
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: Доступ к USB отклонён пользователем")
                return
            }
            if (device == null) {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: USB-устройство не передано системой")
                return
            }

            viewModel.log("Доступ к USB разрешён. Анализ интерфейсов...")
            val pending = takePendingUsbConnect(device)
            analyzeAndConnectDevice(device, pending)
        }
    }

    private fun handleUsbDetached(intent: Intent) {
        val device = intent.parcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device == null) {
            viewModel.log("USB-устройство отключено: неизвестно")
            updateOtgStatus()
            return
        }

        pendingUsbCandidates.remove(device.deviceId)
        val isCurrent = viewModel.isCurrentUsbDevice(device)
        val wasFastboot = isCurrent && viewModel.currentUsbMode() == UsbDeviceInspector.Mode.FASTBOOT
        if (isCurrent) {
            val previousSignature = viewModel.currentUsbLogicalSignature()
                ?: UsbDeviceInspector.selectPrimaryCandidate(device, allowGenericFastboot = true)?.logicalSignature
            val previousVendorId = viewModel.currentUsbVendorId() ?: device.vendorId
            if (wasFastboot) {
                viewModel.log(
                    DiagnosticLogPolicy.Level.INFO,
                    "ℹ️ Fastboot detach подтверждён: текущая USB generation закрывается fail-closed. " +
                        "Перед следующей командой подключите устройство заново."
                )
            } else {
                viewModel.log("USB-устройство отключено: ${device.productName ?: device.deviceName}")
            }
            viewModel.disconnectCurrent()
            startModeSwitchWatch(previousSignature, previousVendorId)
        } else {
            viewModel.logFileOnly(
                "USB detach ignored for unrelated device: ${device.deviceName} " +
                    "VID=${device.vendorId} PID=${device.productId}"
            )
        }
        updateOtgStatus()
    }

    private fun handleAutoUsbIntent(intent: Intent?): Boolean {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        if (intent.getBooleanExtra(EXTRA_USB_INTENT_CONSUMED, false)) {
            // Activity могла быть пересоздана с тем же Intent. Сам Intent повторно
            // не обрабатываем, но возвращаем false, чтобы onCreate выполнил
            // одноразовое перечисление уже подключённых USB-устройств.
            return false
        }

        val device = intent.parcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        intent.putExtra(EXTRA_USB_INTENT_CONSUMED, true)

        if (device == null) {
            viewModel.log("⚠️ USB attach: система не передала устройство")
            return true
        }

        cancelStartupUsbDiscovery()
        stopModeSwitchWatch()
        val candidate = UsbDeviceInspector.selectPrimaryCandidate(device, allowGenericFastboot = true)
        if (candidate == null) {
            viewModel.log("⚠️ USB-устройство подключено, но bulk-интерфейс ADB/Fastboot не найден")
            viewModel.logFileOnly(UsbDeviceInspector.summarizeDevice(device))
            return true
        }
        requestUsbAccess(candidate, automatic = true)
        updateOtgStatus()
        return true
    }

    private fun requestUsbAccess(
        candidate: UsbDeviceInspector.Candidate,
        automatic: Boolean
    ) {
        val device = candidate.device
        pendingUsbCandidates[device.deviceId] = PendingUsbConnect(candidate, automatic)
        viewModel.log(
            "Запрос доступа к устройству: ${device.productName ?: "Неизвестно"} " +
                "(VID=${device.vendorId}, PID=${device.productId}, mode=${candidate.mode.label}, " +
                "interface=${candidate.interfaceIndex}, match=${candidate.matchKind.label})"
        )

        if (usbManager.hasPermission(device)) {
            viewModel.log("USB-доступ уже разрешён")
            pendingUsbCandidates.remove(device.deviceId)
            connectCandidate(candidate, automatic)
            return
        }

        val permissionIntent = Intent(actionUsbPermission).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(this, device.deviceId, permissionIntent, flags)
        scheduleUsbPermissionTimeout(device)
        usbManager.requestPermission(device, pi)
    }

    private fun analyzeAndConnectDevice(
        device: UsbDevice,
        pending: PendingUsbConnect? = null
    ) {
        val candidate = pending?.candidate
            ?.let { UsbDeviceInspector.rebindCandidate(device, it) }
            ?: UsbDeviceInspector.selectPrimaryCandidate(device, allowGenericFastboot = true)

        if (candidate == null) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: Устройство не распознано как ADB/Fastboot")
            viewModel.logFileOnly(UsbDeviceInspector.summarizeDevice(device))
            return
        }
        connectCandidate(candidate, pending?.automatic ?: true)
    }

    private fun connectCandidate(
        candidate: UsbDeviceInspector.Candidate,
        automatic: Boolean
    ) {
        when (candidate.mode) {
            UsbDeviceInspector.Mode.ADB -> viewModel.log("Режим: ADB")
            UsbDeviceInspector.Mode.FASTBOOT -> viewModel.log("Режим: FASTBOOT")
        }
        if (candidate.matchKind != UsbDeviceInspector.MatchKind.CANONICAL) {
            viewModel.log(
                "ℹ️ Используется ${candidate.matchKind.label} USB-интерфейс: " +
                    "class=${candidate.interfaceClass}, subclass=${candidate.interfaceSubclass}, " +
                    "protocol=${candidate.interfaceProtocol}, interface=${candidate.interfaceIndex}"
            )
        }
        viewModel.connectDevice(usbManager, candidate, automatic = automatic)
    }

    private fun scheduleUsbPermissionTimeout(device: UsbDevice) {
        cancelUsbPermissionTimeout(device.deviceId)
        val timeout = Runnable {
            usbPermissionTimeouts.remove(device.deviceId)
            pendingUsbCandidates.remove(device.deviceId)
            if (!usbManager.hasPermission(device)) {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: нет ответа на запрос USB-доступа за 30 секунд. Переподключите OTG-кабель и нажмите «Поиск» ещё раз.")
            }
        }
        usbPermissionTimeouts[device.deviceId] = timeout
        usbPermissionHandler.postDelayed(timeout, USB_PERMISSION_TIMEOUT_MS)
    }

    private fun cancelUsbPermissionTimeout(deviceId: Int) {
        val timeout = usbPermissionTimeouts.remove(deviceId) ?: return
        usbPermissionHandler.removeCallbacks(timeout)
    }

    private fun takePendingUsbConnect(device: UsbDevice): PendingUsbConnect? {
        pendingUsbCandidates.remove(device.deviceId)?.let { return it }
        val matchingEntry = pendingUsbCandidates.entries.firstOrNull {
            it.value.candidate.device.deviceName == device.deviceName
        } ?: return null
        pendingUsbCandidates.remove(matchingEntry.key)
        return matchingEntry.value
    }

    private fun scheduleStartupUsbDiscovery() {
        if (startupUsbDiscoveryDone) return
        startupUsbDiscoveryDone = true
        modeSwitchHandler.postDelayed(startupUsbDiscoveryRunnable, STARTUP_USB_SCAN_DELAY_MS)
    }

    private fun cancelStartupUsbDiscovery() {
        modeSwitchHandler.removeCallbacks(startupUsbDiscoveryRunnable)
    }

    private fun discoverAlreadyConnectedDevice() {
        val state = viewModel.connectionState.value ?: DeviceViewModel.ConnectionState.NONE
        if (state !in setOf(DeviceViewModel.ConnectionState.NONE, DeviceViewModel.ConnectionState.ERROR)) return

        val candidates = UsbDeviceInspector.findAutoConnectCandidates(usbManager.deviceList.values)
        val candidate = candidates.singleOrNull() ?: return
        viewModel.log(
            "USB startup-scan: найдено ${candidate.mode.label} устройство " +
                "(interface=${candidate.interfaceIndex})"
        )
        requestUsbAccess(candidate, automatic = true)
    }

    private fun startModeSwitchWatch(previousLogicalSignature: String?, previousVendorId: Int?) {
        cancelStartupUsbDiscovery()
        stopModeSwitchWatch()
        modeSwitchPreviousSignature = previousLogicalSignature
        modeSwitchPreviousVendorId = previousVendorId
        modeSwitchAttemptsRemaining = MODE_SWITCH_SCAN_ATTEMPTS
        modeSwitchHandler.postDelayed(modeSwitchRunnable, MODE_SWITCH_SCAN_INTERVAL_MS)
    }

    private fun stopModeSwitchWatch() {
        modeSwitchAttemptsRemaining = 0
        modeSwitchPreviousSignature = null
        modeSwitchPreviousVendorId = null
        modeSwitchHandler.removeCallbacks(modeSwitchRunnable)
    }

    private fun scanForDevices() {
        cancelStartupUsbDiscovery()
        stopModeSwitchWatch()
        val candidates = UsbDeviceInspector.findAllCandidates(
            usbManager.deviceList.values,
            includeGenericFastboot = true
        )
        when {
            candidates.isEmpty() -> {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: совместимые ADB/Fastboot USB-устройства не найдены")
                logUsbInventoryForTroubleshooting()
            }
            candidates.size == 1 -> {
                val candidate = candidates.first()
                viewModel.log("Найдено устройство: ${candidate.displayTitle()} | ${candidate.displaySubtitle()}")
                connectManualCandidate(candidate)
            }
            else -> showUsbDeviceChooser(candidates)
        }
    }

    private fun showUsbDeviceChooser(candidates: List<UsbDeviceInspector.Candidate>) {
        val items = candidates.mapIndexed { index, candidate ->
            candidate.displayTitle(index + 1) + "\n" + candidate.displaySubtitle()
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_usb_choose_title))
            .setItems(items) { _, which ->
                val selected = candidates[which]
                viewModel.log("Выбрано: ${selected.displayTitle()} | ${selected.displaySubtitle()}")
                connectManualCandidate(selected)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun connectManualCandidate(candidate: UsbDeviceInspector.Candidate) {
        stopModeSwitchWatch()
        if (candidate.matchKind != UsbDeviceInspector.MatchKind.GENERIC_FASTBOOT) {
            requestUsbAccess(candidate, automatic = false)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.usb_generic_fastboot_title))
            .setMessage(
                getString(
                    R.string.usb_generic_fastboot_message,
                    candidate.displaySubtitle()
                )
            )
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                viewModel.log("⚠️ Generic Fastboot-кандидат выбран пользователем вручную")
                requestUsbAccess(candidate, automatic = false)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun logUsbInventoryForTroubleshooting() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            viewModel.log("USB-инвентарь: Android не видит ни одного USB-устройства. Проверьте OTG и data-кабель.")
            return
        }
        devices.forEach { device ->
            viewModel.log("USB найден, но не ADB/Fastboot: ${device.productName ?: device.deviceName} VID=${device.vendorId} PID=${device.productId} interfaces=${device.interfaceCount}")
            viewModel.logFileOnly(UsbDeviceInspector.summarizeDevice(device))
        }
    }

    // ─── КОМАНДЫ ─────────────────────────────────────────────────────────────

    private fun handleCommandInput() {
        val raw = etCommand.text.toString().trim()
        if (raw.isEmpty()) return
        etCommand.text.clear()
        addToHistory(raw)

        val rawLower = raw.lowercase(Locale.US)
        if (isOpenReportsCommand(rawLower)) {
            viewModel.log("> $raw")
            openReportsFolder()
            return
        }

        val (type, cmd) = when {
            raw.startsWith("fastboot ", ignoreCase = true) -> "fastboot" to raw.substringAfter(" ").trim()
            raw.startsWith("adb ", ignoreCase = true) -> "adb" to raw.substringAfter(" ").trim()
            else -> currentTab to raw
        }

        if (viewModel.isInteractiveAdbShellActive()) {
            handleInteractiveAdbShellInput(raw, type, cmd)
            return
        }

        when (type) {
            "fastboot" -> handleFastbootTerminalCommand(cmd)
            "adb" -> handleAdbTerminalCommand(cmd)
            else -> {
                viewModel.log("> $raw")
                viewModel.log("⚠️ Не выбран контекст ADB/Fastboot. Введите префикс adb или fastboot.")
            }
        }
    }

    private fun handleInteractiveAdbShellInput(raw: String, type: String, cmd: String) {
        val cleanRaw = raw.trim()
        val cleanCmd = cmd.trim()
        val lowerRaw = cleanRaw.lowercase(Locale.US)
        val lowerCmd = cleanCmd.lowercase(Locale.US)

        val stopRequested = lowerRaw == ":close" ||
            lowerRaw == ":exit" ||
            lowerRaw == "adb shell-stop" ||
            lowerRaw == "adb shell-exit" ||
            (type == "adb" && (lowerCmd == "shell-stop" || lowerCmd == "shell-exit"))

        if (stopRequested) {
            viewModel.log("> $cleanRaw")
            viewModel.stopInteractiveAdbShell()
            return
        }

        val interruptRequested = lowerRaw == ":ctrl-c" ||
            lowerRaw == ":sigint" ||
            lowerRaw == ":interrupt" ||
            lowerRaw == "adb shell-ctrl-c" ||
            lowerRaw == "adb shell-interrupt" ||
            (type == "adb" && (lowerCmd == "shell-ctrl-c" || lowerCmd == "shell-interrupt"))

        if (interruptRequested) {
            viewModel.log("> $cleanRaw")
            viewModel.interruptInteractiveAdbShell()
            return
        }

        val eofRequested = lowerRaw == ":ctrl-d" ||
            lowerRaw == ":eof" ||
            lowerRaw == "adb shell-ctrl-d" ||
            lowerRaw == "adb shell-eof" ||
            (type == "adb" && (lowerCmd == "shell-ctrl-d" || lowerCmd == "shell-eof"))

        if (eofRequested) {
            viewModel.log("> $cleanRaw")
            viewModel.sendInteractiveAdbShellEof()
            return
        }

        val shellLine = when {
            type == "adb" && lowerCmd == "shell" -> ""
            type == "adb" && lowerCmd.startsWith("shell ") -> cleanCmd.substringAfterWord("shell").trimStart()
            type == "adb" -> cleanCmd
            else -> cleanRaw
        }

        if (shellLine.isBlank()) {
            viewModel.log("ℹ️ Интерактивный adb shell уже открыт. Введите команду или adb shell-stop для выхода.")
            return
        }

        viewModel.sendInteractiveAdbShellInput(shellLine)
    }

    private fun handleFastbootTerminalCommand(cmd: String) {
        viewModel.log("> fastboot $cmd")
        when (val action = parseFastbootCommand(cmd)) {
            null -> return
            TerminalAction.LocalStatus -> viewModel.logConnectionStatus()
            TerminalAction.OpenReportsFolder -> openReportsFolder()
            is TerminalAction.RawFastboot -> {
                val hostOp = action.command.substringBefore(' ').substringBefore(':')
                if (!hostOp.matches(Regex("^-{0,2}[A-Za-z0-9][A-Za-z0-9._-]*$"))) {
                    viewModel.log("❌ Некорректная Fastboot-команда: $hostOp")
                } else if (viewModel.fastbootProtocol?.isConnected != true) {
                    viewModel.log(
                        DiagnosticLogPolicy.Level.ERROR,
                        "ОШИБКА: Fastboot-устройство не подключено. Команда не отправлена."
                    )
                } else {
                    // Raw/OEM passthrough остаётся разрешённым; terminal FAIL/OKAY
                    // показывается в Console без блокирующего progress-dialog.
                    viewModel.runFastbootCommand(action.command, heavy = false)
                }
            }
            is TerminalAction.FastbootFlash -> viewModel.runFlash(action.partition, action.file, action.slot)
            is TerminalAction.FastbootPartitionCommand -> viewModel.runFastbootPartitionCommand(action.wirePrefix, action.partition, action.slot)
            is TerminalAction.FastbootDownloadAndRun -> viewModel.runFastbootDownloadAndRun(action.file, action.commandAfterDownload)
            is TerminalAction.FastbootLogicalInfo -> viewModel.inspectFastbootLogicalPartition(action.partition)
            is TerminalAction.FastbootFetch -> viewModel.runFastbootFetch(action.partition, action.outputFile, action.slot)
            is TerminalAction.AdbService,
            is TerminalAction.AdbShell,
            is TerminalAction.AdbPush,
            is TerminalAction.AdbPull,
            is TerminalAction.AdbInstall,
            is TerminalAction.AdbInstallMultiple -> Unit
        }
    }

    private fun handleAdbTerminalCommand(cmd: String) {
        viewModel.log("> adb $cmd")
        when (val action = parseAdbCommand(cmd)) {
            null -> return
            TerminalAction.LocalStatus -> viewModel.logConnectionStatus()
            TerminalAction.OpenReportsFolder -> openReportsFolder()
            is TerminalAction.AdbService -> {
                if (viewModel.adbProtocol?.isConnected != true) {
                    viewModel.log(
                        DiagnosticLogPolicy.Level.ERROR,
                        "ОШИБКА: ADB-устройство не подключено. Команда не отправлена."
                    )
                } else {
                    viewModel.runAdbService(action.service)
                }
            }
            is TerminalAction.AdbShell -> {
                if (viewModel.adbProtocol?.isConnected != true) {
                    viewModel.log(
                        DiagnosticLogPolicy.Level.ERROR,
                        "ОШИБКА: ADB-устройство не подключено. Команда не отправлена."
                    )
                } else {
                    viewModel.runAdbShell(action.command)
                }
            }
            is TerminalAction.AdbPush -> viewModel.runAdbPush(action.localFile, action.remotePath)
            is TerminalAction.AdbPull -> viewModel.runAdbPull(action.remotePath, action.localFile)
            is TerminalAction.AdbInstall -> viewModel.runAdbInstall(action.packageFile, action.options)
            is TerminalAction.AdbInstallMultiple -> viewModel.runAdbInstallMultiple(action.apkFiles, action.options)
            is TerminalAction.RawFastboot,
            is TerminalAction.FastbootFlash,
            is TerminalAction.FastbootPartitionCommand,
            is TerminalAction.FastbootDownloadAndRun,
            is TerminalAction.FastbootLogicalInfo,
            is TerminalAction.FastbootFetch -> Unit
        }
    }

    private fun invalidTerminalFormat(format: String): TerminalAction? {
        viewModel.log("❌ Формат: $format")
        return null
    }

    private data class FastbootTerminalOptions(
        val tokens: List<String>,
        val slot: String? = null,
        val setActive: String? = null
    )

    private fun normalizeFastbootSlot(raw: String): String? {
        val slot = raw.trim().removePrefix("_").lowercase(Locale.US)
        if (slot.isBlank()) {
            viewModel.log("❌ Пустое значение --slot")
            return null
        }
        if (slot == "all" || slot == "other" || (slot.length == 1 && slot[0] in 'a'..'z')) {
            return slot
        }
        viewModel.log("❌ Некорректный slot: $raw. Используйте a, b, all или other.")
        return null
    }

    private fun parseFastbootTerminalOptions(tokens: List<String>): FastbootTerminalOptions? {
        val commandTokens = mutableListOf<String>()
        var slot: String? = null
        var setActive: String? = null
        var i = 0

        fun nextOptionValue(option: String): String? {
            val value = tokens.getOrNull(i + 1)
            if (value == null) {
                viewModel.log("❌ $option требует значение")
                return null
            }
            i += 1
            return value
        }

        while (i < tokens.size) {
            val token = tokens[i]
            val lower = token.lowercase(Locale.US)
            when {
                lower == "--slot" -> {
                    val value = nextOptionValue("--slot") ?: return null
                    slot = normalizeFastbootSlot(value) ?: return null
                }
                lower.startsWith("--slot=") -> {
                    slot = normalizeFastbootSlot(token.substringAfter('=')) ?: return null
                }
                lower == "--set-active" -> {
                    val value = nextOptionValue("--set-active") ?: return null
                    setActive = normalizeFastbootSlot(value)?.takeUnless { it == "all" || it == "other" } ?: return null
                }
                lower.startsWith("--set-active=") -> {
                    setActive = normalizeFastbootSlot(token.substringAfter('='))?.takeUnless { it == "all" || it == "other" } ?: return null
                }
                lower == "-a" -> {
                    val value = nextOptionValue("-a") ?: return null
                    setActive = normalizeFastbootSlot(value)?.takeUnless { it == "all" || it == "other" } ?: return null
                }
                lower == "--disable-verity" || lower == "--disable-verification" -> {
                    viewModel.log(
                        "⚠️ $token — host-side правка vbmeta из desktop-fastboot. " +
                            "NekoFlash не патчит vbmeta на лету; прошейте уже подготовленный образ."
                    )
                    return null
                }
                lower == "--skip-reboot" || lower == "--skip-secondary" || lower == "--force" || lower == "--verbose" || lower == "-v" -> {
                    viewModel.log("ℹ️ Опция $token обработчиком терминала не используется для одиночной USB-команды.")
                }
                token == "-S" || lower == "--sparse-limit" -> {
                    nextOptionValue(token) ?: return null
                    viewModel.log("ℹ️ Sparse splitting (-S) не требуется: NekoFlash передаёт выбранный образ напрямую.")
                }
                token.startsWith("-S") && token.length > 2 -> {
                    viewModel.log("ℹ️ Sparse splitting (-S) не требуется: NekoFlash передаёт выбранный образ напрямую.")
                }
                lower == "-s" -> {
                    nextOptionValue("-s") ?: return null
                    viewModel.log("ℹ️ -s SERIAL не используется: NekoFlash работает с выбранным OTG-устройством.")
                }
                else -> commandTokens += token
            }
            i += 1
        }

        return FastbootTerminalOptions(commandTokens, slot, setActive)
    }

    private fun parseFastbootCommand(cmd: String): TerminalAction? {
        val clean = cmd.trim()
        if (clean.isBlank()) return null
        val rawTokens = tokenizeCommandLine(clean)
        if (rawTokens.isEmpty()) return null

        val parsedOptions = parseFastbootTerminalOptions(rawTokens) ?: return null
        if (parsedOptions.setActive != null && parsedOptions.tokens.isEmpty()) {
            return TerminalAction.RawFastboot("set_active:${parsedOptions.setActive}")
        }

        val tokens = parsedOptions.tokens
        if (tokens.isEmpty()) return null
        val op = tokens[0].lowercase(Locale.US)

        if (warnIfBatchOrShellSyntax(op, clean)) return null

        return when (op) {
            "status", "devices" -> TerminalAction.LocalStatus
            "reports", "open-reports", "report-folder", "reports-folder" -> TerminalAction.OpenReportsFolder

            "-w", "--wipe" -> TerminalAction.RawFastboot("erase:userdata")

            "flash" -> {
                if (tokens.size < 3) return invalidTerminalFormat("fastboot [--slot=<a|b|all|other>] flash <partition> <file.img>")
                val partition = tokens[1]
                val file = resolveTerminalFile(tokens[2]) ?: return null
                TerminalAction.FastbootFlash(partition, file, parsedOptions.slot)
            }

            "boot" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot boot <file.img>")
                val file = resolveTerminalFile(tokens[1]) ?: return null
                TerminalAction.FastbootDownloadAndRun(file, "boot")
            }

            "getvar" -> {
                val variable = tokens.drop(1).joinToString(" ").ifBlank { "all" }
                TerminalAction.RawFastboot("getvar:$variable")
            }

            "is-logical", "logical-info" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot $op <partition>")
                TerminalAction.FastbootLogicalInfo(tokens[1])
            }

            "create-logical-partition" -> {
                if (tokens.size < 3) return invalidTerminalFormat("fastboot create-logical-partition <partition> <size>")
                val partition = tokens[1]
                val size = parseFastbootSizeArgument(tokens[2]) ?: return null
                val wire = "create-logical-partition:$partition:$size"
                TerminalAction.RawFastboot(wire)
            }

            "delete-logical-partition" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot delete-logical-partition <partition>")
                val partition = tokens[1]
                val wire = "delete-logical-partition:$partition"
                TerminalAction.RawFastboot(wire)
            }

            "resize-logical-partition" -> {
                if (tokens.size < 3) return invalidTerminalFormat("fastboot resize-logical-partition <partition> <size>")
                val partition = tokens[1]
                val size = parseFastbootSizeArgument(tokens[2]) ?: return null
                val wire = "resize-logical-partition:$partition:$size"
                TerminalAction.RawFastboot(wire)
            }

            "update-super" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot update-super <super.img> [wipe] [superPartition]")
                val file = resolveTerminalFile(tokens[1]) ?: return null
                val wipe = tokens.drop(2).any { it.equals("wipe", ignoreCase = true) || it.equals("--wipe", ignoreCase = true) }
                val explicitSuper = tokens.drop(2).firstOrNull { !it.equals("wipe", ignoreCase = true) && !it.equals("--wipe", ignoreCase = true) }
                val superName = explicitSuper ?: viewModel.currentFastbootDiagnostics()?.superPartitionName ?: "super"
                val wire = "update-super:$superName" + if (wipe) ":wipe" else ""
                TerminalAction.FastbootDownloadAndRun(file, wire)
            }

            "gsi" -> {
                val sub = tokens.getOrNull(1)?.lowercase(Locale.US)
                when (sub) {
                    "status" -> TerminalAction.RawFastboot("gsi:status")
                    "wipe", "disable" -> TerminalAction.RawFastboot("gsi:$sub")
                    else -> invalidTerminalFormat("fastboot gsi <wipe|disable|status>")
                }
            }

            "wipe-super" -> TerminalAction.RawFastboot(clean)

            "snapshot-update" -> {
                val action = tokens.getOrNull(1)?.lowercase(Locale.US)
                when (action) {
                    null -> TerminalAction.RawFastboot("snapshot-update")
                    "cancel" -> TerminalAction.RawFastboot("snapshot-update:cancel")
                    "merge" -> TerminalAction.RawFastboot("snapshot-update:merge")
                    else -> invalidTerminalFormat("fastboot snapshot-update [cancel|merge]")
                }
            }

            "fetch" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot [--slot=<a|b|other>] fetch <partition> [out.img]")
                if (parsedOptions.slot == "all") {
                    viewModel.log("⚠️ fastboot fetch --slot=all не используется: один output-файл не должен смешивать оба слота. Укажите --slot=a или --slot=b.")
                    return null
                }
                val partition = tokens[1]
                val defaultName = parsedOptions.slot?.let { "$partition-$it-fetch.img" } ?: "$partition-fetch.img"
                val output = resolveTerminalOutputFile(tokens.getOrNull(2).orEmpty(), defaultName) ?: return null
                TerminalAction.FastbootFetch(partition, output, parsedOptions.slot)
            }

            "erase" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot [--slot=<a|b|all|other>] erase <partition>")
                TerminalAction.FastbootPartitionCommand("erase", tokens[1], parsedOptions.slot)
            }

            "format" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot [--slot=<a|b|all|other>] format <partition>")
                TerminalAction.FastbootPartitionCommand("format", tokens[1], parsedOptions.slot)
            }

            "set_active", "set-active" -> {
                val slot = tokens.getOrNull(1)?.let { normalizeFastbootSlot(it) }
                    ?: parsedOptions.setActive
                    ?: return invalidTerminalFormat("fastboot set_active <a|b>")
                if (slot == "all" || slot == "other") {
                    viewModel.log("❌ set_active принимает конкретный слот: a, b, ...")
                    return null
                }
                TerminalAction.RawFastboot("set_active:$slot")
            }

            "reboot" -> {
                val target = tokens.getOrNull(1)?.lowercase(Locale.US)
                val command = when (target) {
                    null, "system" -> "reboot"
                    "bootloader" -> "reboot-bootloader"
                    "recovery" -> "reboot-recovery"
                    "fastboot" -> "reboot-fastboot"
                    else -> clean
                }
                TerminalAction.RawFastboot(command)
            }

            "flashing" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot flashing <unlock|lock|unlock_critical|lock_critical|get_unlock_ability>")
                TerminalAction.RawFastboot(clean)
            }

            "oem" -> TerminalAction.RawFastboot(clean)

            "update", "flashall" -> {
                viewModel.log("⚠️ fastboot $op требует пакетной логики desktop-fastboot и здесь не эмулируется. Используйте отдельные flash-команды или ADB Sideload.")
                null
            }

            else -> TerminalAction.RawFastboot(clean)
        }
    }

    private fun parseAdbCommand(cmd: String): TerminalAction? {
        val clean = cmd.trim()
        if (clean.isBlank()) return null
        val tokens = tokenizeCommandLine(clean)
        if (tokens.isEmpty()) return null
        val op = tokens[0].lowercase(Locale.US)

        if (warnIfBatchOrShellSyntax(op, clean, isAdbTab = true)) return null

        return when (op) {
            "status", "devices", "get-state" -> TerminalAction.LocalStatus
            "reports", "open-reports", "report-folder", "reports-folder" -> TerminalAction.OpenReportsFolder

            "shell" -> {
                val shellCommand = clean.substringAfterWord("shell").trim()
                TerminalAction.AdbShell(shellCommand)
            }

            "exec" -> {
                val execCommand = clean.substringAfterWord("exec").trim()
                if (execCommand.isBlank()) {
                    invalidTerminalFormat("adb exec <command>")
                } else {
                    TerminalAction.AdbService("exec:$execCommand")
                }
            }

            "reboot" -> {
                val target = tokens.getOrNull(1)
                TerminalAction.AdbService(AdbServiceCompletionPolicy.normalizeRebootService(target))
            }

            "root", "unroot", "remount", "disable-verity", "enable-verity", "usb" ->
                TerminalAction.AdbService("$op:")

            "tcpip" -> {
                val port = tokens.getOrNull(1) ?: "5555"
                TerminalAction.AdbService("tcpip:$port")
            }

            "raw", "service" -> {
                val service = clean.substringAfterWord(op).trim()
                if (service.isBlank()) {
                    invalidTerminalFormat("adb $op <service>, например adb raw shell:getprop")
                } else {
                    TerminalAction.AdbService(service)
                }
            }

            "logcat" -> TerminalAction.AdbShell(clean)
            "getprop", "setprop", "pm", "am", "cmd", "settings", "wm", "input", "svc", "dumpsys", "cat", "ls", "cd", "pwd", "id", "su", "sh" ->
                TerminalAction.AdbShell(clean)

            "push" -> {
                if (tokens.size < 3) {
                    invalidTerminalFormat("adb push <local-file> <remote-path>")
                } else {
                    val localPath = resolveTerminalInputPath(tokens[1]) ?: return null
                    val remoteArg = tokens[2]
                    val remotePath = if (localPath.isFile && remoteArg.endsWith("/")) remoteArg + localPath.name else remoteArg
                    TerminalAction.AdbPush(localPath, remotePath)
                }
            }

            "pull" -> {
                if (tokens.size < 2) {
                    invalidTerminalFormat("adb pull <remote-path> [local-file]")
                } else {
                    val remotePath = tokens[1]
                    val defaultName = remotePath.substringAfterLast('/').ifBlank { "adb-pull.bin" }
                    val localFile = resolveTerminalOutputFile(tokens.getOrNull(2).orEmpty(), defaultName) ?: return null
                    TerminalAction.AdbPull(remotePath, localFile)
                }
            }

            "install" -> {
                if (tokens.size < 2) {
                    invalidTerminalFormat("adb install [-r] [-d] [-g] <local.apk|local.apks|local.xapk>")
                } else {
                    val packageToken = tokens.last()
                    val packageFile = resolveTerminalFile(packageToken) ?: return null
                    val lowerName = packageFile.name.lowercase(Locale.US)
                    if (!(lowerName.endsWith(".apk") || lowerName.endsWith(".apks") || lowerName.endsWith(".xapk"))) {
                        viewModel.log("⚠️ Файл не похож на APK/APKS/XAPK: ${packageFile.name}")
                    }
                    val options = tokens.drop(1).dropLast(1)
                    TerminalAction.AdbInstall(packageFile, options)
                }
            }

            "install-multiple" -> parseAdbInstallMultiple(tokens)

            "sync" -> {
                viewModel.log("ℹ️ adb sync каталогов пока не реализован. Используйте adb push/adb pull для отдельных файлов.")
                null
            }

            "sideload" -> {
                viewModel.log("ℹ️ Для sideload используйте кнопку ADB Sideload: она передаёт ZIP через sideload-host с прогрессом.")
                null
            }

            else -> TerminalAction.AdbShell(clean)
        }
    }


    private fun parseAdbInstallMultiple(tokens: List<String>): TerminalAction? {
        if (tokens.size < 3) return invalidTerminalFormat("adb install-multiple [-r] [-d] [-g] <base.apk> <split1.apk> [split2.apk...]")

        val options = mutableListOf<String>()
        val files = mutableListOf<File>()
        tokens.drop(1).forEach { token ->
            if (token.lowercase(Locale.US).endsWith(".apk")) {
                val file = resolveTerminalFile(token) ?: return null
                files.add(file)
            } else {
                options.add(token)
            }
        }

        if (files.size < 2) {
            viewModel.log("❌ install-multiple требует минимум 2 APK: base.apk и один или несколько split/config APK")
            viewModel.log("💡 Пример: adb install-multiple -r base.apk split_config.arm64_v8a.apk split_config.xxhdpi.apk")
            return null
        }

        val hasBaseLikeFile = files.any { it.name.equals("base.apk", ignoreCase = true) || it.name.startsWith("base-", ignoreCase = true) }
        if (!hasBaseLikeFile) {
            viewModel.log("⚠️ Среди файлов не видно base.apk. Если base APK отсутствует, установка split APK обычно завершится ошибкой.")
        }

        return TerminalAction.AdbInstallMultiple(files, options)
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Распознаёт строки, скопированные целиком из .bat/.sh flash-скрипта, а не
     * реальные fastboot/adb-команды. Такие строки нельзя слать на устройство
     * как есть — это управляющий синтаксис ПК-оболочки (echo, метки, циклы,
     * условия, комментарии), у которого просто нет аналога в wire-протоколе.
     * Вместо непонятного отказа устройства показываем понятную подсказку.
     */
    private fun warnIfBatchOrShellSyntax(op: String, clean: String, isAdbTab: Boolean = false): Boolean {
        val isBatchOrShell = op == "@echo" || op == "echo" ||
            op.startsWith(":") || // метка батника, например :label
            op == "rem" || clean.startsWith("::") || clean.startsWith("#") ||
            op == "pause" || op == "cls" || op == "goto" ||
            op == "if" || op == "for" || op == "exit" || op == "set" ||
            op == "printf" || op == "read" ||
            // "cd" — легитимный шорткат в ADB-вкладке (adb shell cd), но в
            // fastboot-вкладке такой команды нет вообще — там это точно батник.
            (op == "cd" && !isAdbTab)
        if (!isBatchOrShell) return false
        viewModel.log(getString(R.string.terminal_batch_syntax_hint, clean))
        return true
    }

    private fun parseFastbootSizeArgument(raw: String): Long? {
        val token = raw.trim().lowercase(Locale.US)
        if (token.isBlank()) {
            viewModel.log("❌ Не указан размер")
            return null
        }
        val multiplier = when {
            token.endsWith("k") || token.endsWith("kb") -> 1024L
            token.endsWith("m") || token.endsWith("mb") -> 1024L * 1024L
            token.endsWith("g") || token.endsWith("gb") -> 1024L * 1024L * 1024L
            else -> 1L
        }
        val numberPart = token.removeSuffix("kb").removeSuffix("mb").removeSuffix("gb").removeSuffix("k").removeSuffix("m").removeSuffix("g")
        val value = try {
            if (numberPart.startsWith("0x")) numberPart.removePrefix("0x").toLong(16) else numberPart.toLong()
        } catch (_: NumberFormatException) {
            viewModel.log("❌ Некорректный размер: $raw. Используйте байты, 512M, 2G или 0x...")
            return null
        }
        val bytes = try {
            Math.multiplyExact(value, multiplier)
        } catch (_: ArithmeticException) {
            viewModel.log("❌ Размер слишком большой: $raw")
            return null
        }
        if (bytes <= 0L) {
            viewModel.log("❌ Размер должен быть больше нуля")
            return null
        }
        return bytes
    }

    private fun resolveTerminalInputPath(pathText: String): File? {
        val rawPath = pathText.trim().trim('"', '\'')
        if (rawPath.isBlank()) {
            viewModel.log("❌ Не указан локальный путь")
            return null
        }

        val file = when {
            // Любой абсолютный путь (/sdcard/..., /storage/..., и т.п.) — берём как есть.
            rawPath.startsWith("/") -> File(rawPath)
            else -> {
                if (!ensureWorkspaceReady()) return null
                File(workspacePath, rawPath)
            }
        }

        return if (file.exists() && file.canRead()) {
            file
        } else {
            viewModel.log("❌ Локальный путь не найден или недоступен: ${file.absolutePath}")
            viewModel.log("💡 Для относительного пути положите файл/папку в /sdcard/Download/$folderName или импортируйте файл кнопкой «Импорт».")
            null
        }
    }

    private fun resolveTerminalFile(pathText: String): File? {
        val file = resolveTerminalInputPath(pathText) ?: return null
        return if (file.isFile) {
            file
        } else {
            viewModel.log("❌ Ожидался файл, но указан каталог: ${file.absolutePath}")
            null
        }
    }

    private fun resolveTerminalOutputFile(pathText: String, defaultName: String): File? {
        if (!ensureWorkspaceReady()) return null
        val rawPath = pathText.trim().trim('"', '\'')

        val candidate = if (rawPath.isBlank()) {
            File(workspacePath, defaultName)
        } else {
            val base = if (rawPath.startsWith("/")) File(rawPath) else File(workspacePath, rawPath)
            when {
                rawPath.endsWith("/") -> File(base, defaultName)
                base.exists() && base.isDirectory -> File(base, defaultName)
                else -> base
            }
        }

        val parent = candidate.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            viewModel.log("❌ Не удалось создать папку для файла: ${parent.absolutePath}")
            return null
        }
        return candidate
    }

    private fun tokenizeCommandLine(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        input.forEach { ch ->
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                }
                ch == '\\' -> escaping = true
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun String.substringAfterWord(word: String): String {
        if (!startsWith(word, ignoreCase = true)) return this
        return drop(word.length)
    }

    private fun addToHistory(command: String) {
        if (commandHistory.lastOrNull() != command) {
            commandHistory.add(command)
            if (commandHistory.size > 50) commandHistory.removeAt(0)
        }
        historyIndex = commandHistory.size
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        historyIndex = (historyIndex + direction).coerceIn(0, commandHistory.size)
        etCommand.setText(if (historyIndex == commandHistory.size) "" else commandHistory[historyIndex])
        etCommand.setSelection(etCommand.text.length)
    }

    // ─── РАЗРЕШЕНИЯ И ФАЙЛЫ ──────────────────────────────────────────────────

    private fun registerImportLauncher() {
        importFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                viewModel.log("Импорт файла отменён")
                return@registerForActivityResult
            }
            val uri = result.data?.data
            if (uri == null) {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: системный выбор файла не вернул URI")
                return@registerForActivityResult
            }
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { error ->
                // Some document providers grant only transient access. The copy
                // below is still valid for this activity result.
                viewModel.logFileOnly(
                    "Persistable import permission unavailable: ${error.javaClass.simpleName}",
                )
            }
            importFirmwareFile(uri)
        }
    }

    private fun registerMiLoginLauncher() {
        miLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            if (activityResult.resultCode != Activity.RESULT_OK) {
                val reason = activityResult.data?.getStringExtra(MiLoginActivity.EXTRA_LOGIN_ERROR)?.trim().orEmpty()
                if (reason.isBlank()) {
                    viewModel.log("Вход в Mi-аккаунт отменён пользователем")
                } else {
                    viewModel.log("❌ Вход в Mi-аккаунт не завершён: $reason")
                }
                return@registerForActivityResult
            }
            val passToken = activityResult.data?.getStringExtra(MiLoginActivity.EXTRA_PASS_TOKEN)
            val deviceId = activityResult.data?.getStringExtra(MiLoginActivity.EXTRA_DEVICE_ID)
            val userId = activityResult.data?.getStringExtra(MiLoginActivity.EXTRA_USER_ID)
            if (passToken.isNullOrEmpty() || deviceId.isNullOrEmpty() || userId.isNullOrEmpty()) {
                viewModel.log("❌ Вход в Mi-аккаунт: не получены данные авторизации")
                return@registerForActivityResult
            }
            viewModel.log("🔑 Вход выполнен. Получение unlockApi-сессии...")
            miAuthExchangeJob?.cancel()
            miAuthExchangeState = MiAuthExchangeState.LOADING
            miAuthExchangeJob = lifecycleScope.launch {
                val exchangeResult = withContext(Dispatchers.IO) {
                    runCatching { MiAccountClient.exchangeToken(passToken, userId, deviceId) }
                }
                exchangeResult.onSuccess { auth ->
                    if (isFinishing || isDestroyed) return@onSuccess
                    miAuth = auth
                    miAuthExchangeState = MiAuthExchangeState.SUCCESS
                    viewModel.log("✅ Mi-аккаунт авторизован. Регион: ${auth.region}, dataCenterZone: ${auth.dataCenterZone} (${auth.zoneSource})")
                    viewModel.log("🔐 unlockApi cookies: ${auth.serviceCookieNames.joinToString(", ")}")
                    buildUnlockPage()
                }.onFailure { error ->
                    if (isFinishing || isDestroyed) return@onFailure
                    miAuthExchangeState = MiAuthExchangeState.ERROR
                    viewModel.log("❌ Ошибка получения токена: ${error.message ?: error.javaClass.simpleName}")
                    viewModel.log("💡 Если используете VPN — отключите его и попробуйте снова.")
                }
            }
        }
    }

    private fun startMiLogin() {
        viewModel.log("🔑 Открыта официальная страница входа Xiaomi для unlockApi")
        miLoginLauncher.launch(Intent(this, MiLoginActivity::class.java))
    }

    private fun startImportFilePicker() {
        if (!ensureWorkspaceReady()) return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed", "application/vnd.android.package-archive", "text/plain", "*/*")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            importFileLauncher.launch(intent)
        } catch (e: Exception) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: не удалось открыть системный выбор файла: ${e.message}")
        }
    }

    private fun ensureWorkspaceReady(): Boolean {
        if (::workspacePath.isInitialized && workspacePath.exists()) return true
        viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: рабочая папка ещё не готова. Выдайте доступ ко всем файлам и повторите.")
        checkPermissions()
        return false
    }

    private fun importFirmwareFile(uri: Uri) {
        if (!ensureWorkspaceReady()) return

        val displayName = sanitizeImportedFileName(queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}")
        val target = uniqueTargetFile(displayName)
        val expectedSize = queryFileSize(uri)
        viewModel.log("Импорт файла: $displayName → /sdcard/Download/$folderName/${target.name}")
        expectedSize?.let { viewModel.log("Ожидаемый размер источника: $it байт") }

        lifecycleScope.launch(Dispatchers.IO) {
            target.parentFile?.listFiles()
                ?.filter { it.name.startsWith(".${target.name}.part-") }
                ?.forEach { stale -> runCatching { stale.delete() } }
            val temp = File(target.parentFile, ".${target.name}.part-${System.currentTimeMillis()}")
            try {
                val copied = contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().buffered(1024 * 1024).use { output ->
                        input.buffered(1024 * 1024).copyTo(output, 1024 * 1024)
                    }
                } ?: throw IllegalStateException("не удалось открыть входной поток")
                if (expectedSize != null && expectedSize >= 0L && copied != expectedSize) {
                    throw IllegalStateException("размер источника изменился: ожидалось $expectedSize, скопировано $copied")
                }
                if (copied <= 0L || temp.length() != copied) {
                    throw IllegalStateException("импортирован пустой или неполный файл")
                }
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("не удалось подготовить конечный путь")
                }
                if (!temp.renameTo(target)) {
                    throw IllegalStateException("не удалось атомарно завершить импорт")
                }
                viewModel.log("✅ Файл импортирован: /sdcard/Download/$folderName/${target.name} (${formatFileSize(target.length())})")
            } catch (e: Exception) {
                temp.delete()
                target.delete()
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: не удалось импортировать файл: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }


    private fun queryFileSize(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0).takeIf { it >= 0L }
            } else {
                null
            }
        }
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sanitizeImportedFileName(name: String): String {
        val safe = name.trim()
            .replace(Regex("[\\/:*?\"<>|\r\n]+"), "_")
            .replace(Regex("\\s+"), "_")
            .take(160)
        return safe.ifBlank { "imported-${System.currentTimeMillis()}" }
    }

    private fun uniqueTargetFile(fileName: String): File {
        var candidate = File(workspacePath, fileName)
        if (!candidate.exists()) return candidate

        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(workspacePath, "$base-$index$ext")
            index++
        }
        return candidate
    }

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes.toDouble() / 1024.0 / 1024.0
        return "%.2f MB".format(Locale.US, mb)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                viewModel.log("⚠️ Требуется доступ ко всем файлам для чтения /sdcard/Download/$folderName.")
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:$packageName".toUri()
                    })
                } catch (e: Exception) {
                    // Многоуровневый фолбэк для прошивок без точечного экрана.
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e2: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = "package:$packageName".toUri()
                            })
                        } catch (e3: Exception) {
                            Toast.makeText(
                                this,
                                getString(R.string.perm_open_settings_manually),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } else if (!::workspacePath.isInitialized) {
                initWorkspace()
            }
        } else {
            if (!PermissionGate.hasStorage(this)) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    100
                )
            } else if (!::workspacePath.isInitialized) {
                initWorkspace()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initWorkspace()
                } else {
                    viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: Нет прав на чтение памяти")
                }
            }
            101 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    viewModel.log("Разрешение на уведомления выдано")
                } else {
                    viewModel.log("⚠️ Уведомления отключены. ForegroundService всё равно будет запущен, но Android может скрыть уведомление.")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!OnboardingGate.canEnterMain(this)) {
            redirectToWelcome(intent)
            return
        }
        enableOverlayProtection()
        updateOtgStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager() && !::workspacePath.isInitialized) {
                initWorkspace()
            }
        }
    }

    private fun initWorkspace() {
        // Рабочая папка теперь в системной папке «Загрузки»: /sdcard/Download/NekoFlash
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        workspacePath = File(downloadsDir, folderName)
        if (!workspacePath.exists() && !workspacePath.mkdirs()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: Не удалось создать папку ${workspaceDisplayPath()}")
            return
        }
        viewModel.log("Рабочая папка: ${workspaceDisplayPath()}")
        viewModel.configureLogDirectory(workspacePath)
        updateDeviceOverview()
    }

    /** Человекочитаемый путь рабочей папки для логов и диалогов. */
    @Suppress("SdCardPath")
    private fun workspaceDisplayPath(): String = "/sdcard/Download/$folderName"

    private fun showFileSelector(onFileSelected: (File) -> Unit) {
        if (!::workspacePath.isInitialized || !workspacePath.exists()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: Папка не инициализирована. Выдайте разрешения.")
            return
        }
        val files = workspacePath
            .listFiles()
            ?.filter { it.isFile && it.canRead() && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?.toTypedArray()

        if (files.isNullOrEmpty()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: Нет читаемых файлов в папке $folderName. Нажмите «Импорт», чтобы добавить файл через системный выбор.")
            return
        }
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_file_choose_title))
                .setItems(files.map { "📄 ${it.name}" }.toTypedArray()) { _, which ->
                    onFileSelected(files[which])
                }
                .setNegativeButton(getString(R.string.cancel_upper), null)
                .show()
        }
    }


    /**
     * Единое меню перезагрузки (BottomSheet). Собирает все варианты reboot
     * в одну панель вместо разбросанных кнопок. Вызывает существующую логику —
     * скрытые кнопки btnReboot* остаются обработчиками той же команды.
     */
    private fun showRebootMenu() {
        if (viewModel.fastbootProtocol?.isConnected != true) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: Нет Fastboot-соединения для перезагрузки")
            return
        }
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val items = listOf(
            "🔄  " + getString(R.string.layout_reboot_system) to "reboot",
            "⚙\uFE0F  " + getString(R.string.layout_reboot_bootloader) to "reboot-bootloader",
            "🛠\uFE0F  " + getString(R.string.layout_reboot_recovery) to "reboot-recovery",
            "⚡  " + getString(R.string.layout_reboot_fastbootd) to "reboot:fastboot"
        )
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(0, dp(8), 0, dp(16))
        }
        // Заголовок
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.layout_reboot_menu)
            setTextColor("#E9782B".toColorInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        })
        items.forEach { (label, cmd) ->
            container.addView(android.widget.TextView(this).apply {
                text = label
                setTextColor("#F3F6FA".toColorInt())
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(24), dp(16), dp(24), dp(16))
                isClickable = true
                setOnClickListener {
                    viewModel.runFastbootCommand(cmd)
                    openConsole(requestCommandFocus = false)
                    dialog.dismiss()
                }
            })
        }
        dialog.setContentView(container)
        dialog.show()
    }

    /**
     * Единое меню отчётов и логов (BottomSheet). Собирает 5 разбросанных
     * функций логов и отчётов в одну панель без скрытой дублирующей страницы.
     */
    private fun showReportsMenu() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val items = listOf(
            "СКОПИРОВАТЬ КРАТКИЙ ИТОГ" to { copyDiagnosticSummary() },
            getString(R.string.reports_open_folder) to { openReportsFolder() },
            getString(R.string.reports_log_actions) to { showLogsMenu() }
        )
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(0, dp(8), 0, dp(16))
        }
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.reports_sheet_title)
            setTextColor("#E9782B".toColorInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        })
        items.forEach { (label, action) ->
            container.addView(android.widget.TextView(this).apply {
                text = label
                setTextColor("#F3F6FA".toColorInt())
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(24), dp(16), dp(24), dp(16))
                isClickable = true
                setOnClickListener {
                    action()
                    dialog.dismiss()
                }
            })
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showLogsMenu() {
        if (!ensureWorkspaceReady()) return
        val compact = viewModel.currentLogFile()
        val trace = viewModel.currentTraceLogFiles().lastOrNull()
        val summaryFile = viewModel.currentSessionSummaryFile()
        val snapshot = viewModel.currentDiagnosticSessionSummary()
        val logsDir = File(workspacePath, "logs")
        val entries = LogMenuPolicy.entries(logsDir.listFiles())
        val activeNames = activeLogFileNames(summaryFile)
        val oldCount = entries.count { LogMenuPolicy.canDelete(it, activeNames) }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.logs_sheet_title)
            setTextColor("#E9782B".toColorInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
        root.addView(TextView(this).apply {
            text = buildString {
                append("Build: ").append(viewModel.currentBuildId()).append('\n')
                append("Операции: ").append(snapshot.operationsSucceeded).append('/')
                    .append(snapshot.operationsStarted)
                append("  •  ⚠ ").append(snapshot.warningCount)
                append("  •  ✕ ").append(snapshot.errorCount)
                append("Файлы: ").append(entries.size)
                append("  •  старые: ").append(oldCount)
            }
            setTextColor("#AAB6C5".toColorInt())
            textSize = 11.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor("#182330".toColorInt())
        })

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.logs_current_summary) to { showCurrentSessionSummaryDialog() }
        if (compact?.isFile == true) {
            actions += getString(R.string.logs_current_compact) to {
                showLogFileActions(LogMenuPolicy.Entry(compact, LogMenuPolicy.Kind.COMPACT))
            }
        }
        if (trace?.isFile == true) {
            actions += getString(R.string.logs_current_trace) to {
                showLogFileActions(LogMenuPolicy.Entry(trace, LogMenuPolicy.Kind.TRACE))
            }
        }
        if (summaryFile?.isFile == true) {
            actions += getString(R.string.logs_current_json) to {
                showLogFileActions(LogMenuPolicy.Entry(summaryFile, LogMenuPolicy.Kind.SESSION_SUMMARY))
            }
        }
        actions += "${getString(R.string.logs_history)} (${entries.size})" to { showLogHistoryDialog() }
        actions += getString(R.string.logs_open_folder) to { openLogsFolder() }
        actions += getString(R.string.logs_clear_console) to { viewModel.clearLog() }
        if (oldCount > 0) {
            actions += "${getString(R.string.logs_delete_old)} ($oldCount)" to { confirmDeleteOldLogs() }
        }

        actions.forEach { (label, action) ->
            root.addView(TextView(this).apply {
                text = label
                setTextColor("#F3F6FA".toColorInt())
                textSize = 14.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(12), dp(15), dp(12), dp(15))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
        }
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showCurrentSessionSummaryDialog() {
        val snapshot = viewModel.currentDiagnosticSessionSummary()
        val text = buildString {
            appendLine("Build: ${snapshot.buildId}")
            appendLine("Session: ${snapshot.sessionId}")
            appendLine("Transport session: ${snapshot.activeTransportSessionId ?: "none"}")
            appendLine()
            appendLine("Messages: info=${snapshot.infoCount}, success=${snapshot.successCount}, warnings=${snapshot.warningCount}, errors=${snapshot.errorCount}")
            appendLine("Operations: started=${snapshot.operationsStarted}, success=${snapshot.operationsSucceeded}, failed=${snapshot.operationsFailed}, cancelled=${snapshot.operationsCancelled}, verify-pending=${snapshot.operationsVerificationPending}")
            appendLine("Last operation: ${snapshot.lastOperation ?: "none"}")
            appendLine("Last outcome: ${snapshot.lastOperationOutcome ?: "none"}")
            appendLine("Connection: ${snapshot.lastConnectionMode ?: "none"}")
            snapshot.lastWarning?.let { appendLine("Last warning: $it") }
            snapshot.lastError?.let { appendLine("Last error: $it") }
        }.trim()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logs_current_summary))
            .setMessage(text)
            .setPositiveButton(getString(R.string.copy_upper)) { _, _ ->
                copyTextToClipboard("NekoFlash session summary", text, "Итог сессии скопирован")
            }
            .setNeutralButton(getString(R.string.logs_current_json)) { _, _ ->
                viewModel.currentSessionSummaryFile()?.let {
                    showLogFileActions(LogMenuPolicy.Entry(it, LogMenuPolicy.Kind.SESSION_SUMMARY))
                }
            }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun showLogHistoryDialog() {
        if (!ensureWorkspaceReady()) return
        val logsDir = File(workspacePath, "logs")
        val entries = LogMenuPolicy.entries(logsDir.listFiles())
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.logs_no_files), Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(dp(12), dp(8), dp(12), dp(20))
        }
        list.addView(TextView(this).apply {
            text = getString(R.string.logs_history_count, getString(R.string.logs_history), entries.size)
            setTextColor("#E9782B".toColorInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(10), dp(8), dp(12))
        })
        entries.forEach { entry ->
            list.addView(TextView(this).apply {
                text = buildString {
                    append(LogMenuPolicy.kindLabel(entry.kind)).append("  ")
                    append(entry.file.name).append('\n')
                    append(formatLogFileSize(entry.file.length())).append("  •  ")
                    append(java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT,
                        java.text.DateFormat.SHORT
                    ).format(java.util.Date(entry.file.lastModified())))
                }
                setTextColor("#F3F6FA".toColorInt())
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(12), dp(13), dp(12), dp(13))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    showLogFileActions(entry)
                }
            })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun showLogFileActions(entry: LogMenuPolicy.Entry) {
        if (!entry.file.isFile) {
            Toast.makeText(this, getString(R.string.logs_no_files), Toast.LENGTH_SHORT).show()
            return
        }
        val activeNames = activeLogFileNames(viewModel.currentSessionSummaryFile())
        val canDelete = LogMenuPolicy.canDelete(entry, activeNames)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        root.addView(TextView(this).apply {
            text = getString(
                R.string.logs_entry_summary,
                LogMenuPolicy.kindLabel(entry.kind),
                entry.file.name,
                formatLogFileSize(entry.file.length())
            )
            setTextColor("#E9782B".toColorInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(4), dp(8), dp(4), dp(12))
        })
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.logs_preview) to { showLogPreview(entry.file) }
        actions += getString(R.string.logs_share_sanitized) to { shareLogFile(entry.file) }
        actions += getString(R.string.logs_copy_path) to {
            copyTextToClipboard("NekoFlash log", entry.file.absolutePath, getString(R.string.logs_path_copied))
        }
        if (canDelete) {
            actions += getString(R.string.logs_delete_file) to { confirmDeleteLogFile(entry) }
        } else {
            actions += getString(R.string.logs_active_protected) to {
                Toast.makeText(this, getString(R.string.logs_active_protected), Toast.LENGTH_SHORT).show()
            }
        }
        actions.forEach { (label, action) ->
            root.addView(TextView(this).apply {
                text = label
                setTextColor("#F3F6FA".toColorInt())
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(10), dp(15), dp(10), dp(15))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
        }
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showLogPreview(file: File) {
        lifecycleScope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching { readLogTail(file) }.getOrElse { "Ошибка чтения: ${it.message ?: it.javaClass.simpleName}" }
            }
            val textView = TextView(this@MainActivity).apply {
                text = getString(
                    R.string.logs_preview_content,
                    body,
                    getString(R.string.logs_preview_tail_note)
                )
                setTextColor("#D7DEE8".toColorInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(18), dp(14), dp(18), dp(14))
            }
            val scroll = ScrollView(this@MainActivity).apply { addView(textView) }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(file.name)
                .setView(scroll)
                .setPositiveButton(getString(R.string.logs_share_sanitized)) { _, _ -> shareLogFile(file) }
                .setNegativeButton(getString(R.string.close_upper), null)
                .show()
        }
    }

    private fun readLogTail(file: File, maxBytes: Int = 128 * 1024): String {
        val length = file.length().coerceAtLeast(0L)
        val start = (length - maxBytes).coerceAtLeast(0L)
        val size = (length - start).coerceAtMost(maxBytes.toLong()).toInt()
        val bytes = ByteArray(size)
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            if (size > 0) input.readFully(bytes)
        }
        return (if (start > 0L) "…\n" else "") + bytes.toString(Charsets.UTF_8)
    }

    @Suppress("SdCardPath")
    private fun openLogsFolder() {
        if (!ensureWorkspaceReady()) return
        val logsDir = File(workspacePath, "logs")
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: не удалось создать папку logs: ${logsDir.absolutePath}")
            return
        }
        val documentId = "primary:Download/$folderName/logs"
        val treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        try {
            viewModel.log(getString(R.string.logs_folder_opened, "/sdcard/Download/$folderName/logs"))
            startActivity(intent)
        } catch (e: Exception) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: не удалось открыть DocumentsUI для logs: ${e.message ?: e.javaClass.simpleName}")
            copyTextToClipboard("NekoFlash logs", logsDir.absolutePath, getString(R.string.logs_path_copied))
        }
    }

    private fun activeLogFileNames(summaryFile: File?): Set<String> = buildSet {
        viewModel.currentLogFiles().forEach { add(it.name) }
        viewModel.currentTraceLogFiles().forEach { add(it.name) }
        summaryFile?.let { add(it.name) }
    }

    private fun confirmDeleteOldLogs() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logs_delete_old_title))
            .setMessage(getString(R.string.logs_delete_old_message))
            .setPositiveButton(getString(R.string.delete_upper)) { _, _ -> deleteOldLogs() }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun deleteOldLogs() {
        if (!ensureWorkspaceReady()) return
        val summaryFile = viewModel.currentSessionSummaryFile()
        val activeNames = activeLogFileNames(summaryFile)
        val entries = LogMenuPolicy.entries(File(workspacePath, "logs").listFiles())
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                entries.count { entry ->
                    LogMenuPolicy.canDelete(entry, activeNames) && runCatching { entry.file.delete() }.getOrDefault(false)
                }
            }
            viewModel.log(getString(R.string.logs_deleted_count, deleted))
            Toast.makeText(this@MainActivity, getString(R.string.logs_deleted_count, deleted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteLogFile(entry: LogMenuPolicy.Entry) {
        val activeNames = activeLogFileNames(viewModel.currentSessionSummaryFile())
        if (!LogMenuPolicy.canDelete(entry, activeNames)) {
            Toast.makeText(this, getString(R.string.logs_active_protected), Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logs_delete_file))
            .setMessage(entry.file.name)
            .setPositiveButton(getString(R.string.delete_upper)) { _, _ ->
                val deleted = runCatching { entry.file.delete() }.getOrDefault(false)
                viewModel.log(if (deleted) "Удалён старый журнал: ${entry.file.name}" else "⚠️ Не удалось удалить журнал: ${entry.file.name}")
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun formatLogFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * Action-first Mi Unlock page: account state, Fastboot precondition and unlock action.
     */
    private fun runMiUnlockFromUi(auth: MiAccountClient.AuthResult) {
        openConsole(requestCommandFocus = false)
        viewModel.runMiUnlock(
            auth = auth,
            onClearInfo = { _, _ -> },
            onAuthExpired = {
                miAuth = null
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                buildUnlockPage()
                Toast.makeText(this, "Mi-сессия истекла. Войдите снова.", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun isBootloaderUnlocked(): Boolean =
        viewModel.fastbootDiagnostics.value?.unlocked?.trim()?.equals("yes", ignoreCase = true) == true

    private fun isFastbootConnected(): Boolean =
        viewModel.connectionState.value == DeviceViewModel.ConnectionState.FASTBOOT

    private fun unlockStatusSummary(): String {
        val d = viewModel.fastbootDiagnostics.value
        return buildString {
            append("product: ")
            append(d?.product?.takeIf { it.isNotBlank() } ?: "unknown")
            append(" • slot: ")
            append(d?.currentSlot?.takeIf { it.isNotBlank() } ?: "unknown")
            append(" • unlocked: ")
            append(d?.unlocked?.takeIf { it.isNotBlank() } ?: "unknown")
            append(" • secure: ")
            append(d?.secure?.takeIf { it.isNotBlank() } ?: "unknown")
        }
    }

    private fun buildUnlockPage() {
        val container = findViewById<android.widget.LinearLayout>(R.id.unlockContainer)
        container.removeAllViews()

        fun title(text: String, color: String = "#E9782B") = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(color.toColorInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(16), dp(6), dp(8))
            letterSpacing = 0.08f
        }
        fun card(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor("#121A24".toColorInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), "#324052".toColorInt())
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        fun body(text: String, color: String = "#AEB8C5") = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(color.toColorInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }

        val unlocked = isBootloaderUnlocked()
        val fastbootReady = isFastbootConnected()
        val operationActive = viewModel.operationActive.value == true

        container.addView(title("🔓 РАЗБЛОКИРОВКА ЗАГРУЗЧИКА", "#E9782B"))

        container.addView(card().apply {
            addView(body("Mi Unlock Xiaomi • Fastboot через USB/OTG", "#F3F6FA"))
            addView(body("⚠️ Разблокировка стирает все данные устройства.", "#F2B766"))
        })

        container.addView(title("ВХОД В MI-АККАУНТ"))
        val auth = miAuth
        if (auth == null) {
            container.addView(card().apply {
                addView(body("Войдите в свой Mi-аккаунт (официальная страница Xiaomi), чтобы продолжить разблокировку.", "#F3F6FA"))
                addView(android.widget.Button(this@MainActivity).apply {
                    text = getString(R.string.mi_unlock_sign_in_button)
                    isAllCaps = false
                    setTextColor("#080D13".toColorInt())
                    setBackgroundColor("#E98B49".toColorInt())
                    setOnClickListener { startMiLogin() }
                })
            })
        } else {
            container.addView(card().apply {
                addView(body("✅ Авторизован. ID: ${auth.userId}", "#69C779"))
                addView(body("Регион: ${auth.region} • dataCenterZone: ${auth.dataCenterZone} (${auth.zoneSource})", "#AEB8C5"))
                addView(android.widget.Button(this@MainActivity).apply {
                    text = getString(R.string.mi_unlock_change_zone_button)
                    isAllCaps = false
                    setTextColor("#F3F6FA".toColorInt())
                    setBackgroundColor("#192431".toColorInt())
                    setOnClickListener {
                        val zoneItems = MiAccountClient.dataCenterZones().toTypedArray()
                        val checked = zoneItems.indexOf(auth.dataCenterZone).coerceAtLeast(0)
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("dataCenterZone")
                            .setSingleChoiceItems(zoneItems, checked) { dialog, which ->
                                val updated = MiAccountClient.withDataCenterZone(auth, zoneItems[which]).copy(zoneSource = "manual")
                                miAuth = updated
                                viewModel.log("🌍 dataCenterZone изменён вручную: ${updated.dataCenterZone}")
                                dialog.dismiss()
                                buildUnlockPage()
                            }
                            .setNegativeButton(getString(R.string.cancel_upper), null)
                            .show()
                    }
                })
                addView(android.widget.Button(this@MainActivity).apply {
                    text = "Выйти / сменить аккаунт"
                    isAllCaps = false
                    setTextColor("#F3F6FA".toColorInt())
                    setBackgroundColor("#192431".toColorInt())
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Выйти из Mi-аккаунта?")
                            .setMessage("ID: ${auth.userId} (регион ${auth.region}) будет отключён от NekoFlash. Для повторной разблокировки понадобится войти снова.")
                            .setNegativeButton(getString(R.string.cancel_upper), null)
                            .setPositiveButton("Выйти") { _, _ ->
                                miAuth = null
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                buildUnlockPage()
                            }
                            .show()
                    }
                })
            })

            container.addView(title("СТАТУС ЗАГРУЗЧИКА", if (unlocked) "#69C779" else "#E9782B"))
            container.addView(card().apply {
                if (unlocked) {
                    addView(body("✅ Загрузчик разблокирован", "#69C779"))
                    addView(body(unlockStatusSummary(), "#AEB8C5"))
                    addView(body("Кнопка разблокировки скрыта: повторный unlock не требуется.", "#F3F6FA"))
                } else {
                    addView(body("Устройство должно быть в режиме Fastboot и подключено по OTG. Убедитесь, что аккаунт одобрен для разблокировки.", "#F3F6FA"))
                    addView(body(unlockStatusSummary(), if (fastbootReady) "#AEB8C5" else "#F2B766"))
                    addView(body("⚠️ Все данные устройства будут стёрты.", "#F2B766"))
                    addView(android.widget.Button(this@MainActivity).apply {
                        val canRunUnlock = fastbootReady && !operationActive
                        text = when {
                            operationActive -> "Операция выполняется…"
                            !fastbootReady -> "Подключите устройство в Fastboot"
                            else -> "🔓 Разблокировать загрузчик"
                        }
                        isAllCaps = false
                        isEnabled = canRunUnlock
                        alpha = if (canRunUnlock) 1.0f else 0.55f
                        setTextColor("#080D13".toColorInt())
                        setBackgroundColor(if (canRunUnlock) "#E9782B".toColorInt() else "#5D6570".toColorInt())
                        setOnClickListener {
                            if (canRunUnlock) runMiUnlockFromUi(auth)
                        }
                    })
                }
            })
        }
    }

    private fun buildSettingsPage() {
        val container = findViewById<android.widget.LinearLayout>(R.id.settingsContainer)
        container.removeAllViews()

        fun sectionTitle(text: String) = android.widget.TextView(this).apply {
            this.text = text
            setTextColor("#E9782B".toColorInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(18), dp(6), dp(8))
            letterSpacing = 0.1f
        }
        fun card(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor("#121A24".toColorInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), "#324052".toColorInt())
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun row(text: String, sub: String? = null, onClick: () -> Unit) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(20), dp(14), dp(20), dp(14))
            addView(android.widget.TextView(this@MainActivity).apply {
                this.text = text
                setTextColor("#F3F6FA".toColorInt())
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            if (sub != null) addView(android.widget.TextView(this@MainActivity).apply {
                this.text = sub
                setTextColor("#AEB8C5".toColorInt())
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            setOnClickListener { onClick() }
        }

        // ── Система ──
        container.addView(sectionTitle(getString(R.string.settings_section_system)))
        val sysCard = card()
        sysCard.addView(row(getString(R.string.settings_open_language)) { showLanguageDialog() })
        sysCard.addView(row(getString(R.string.settings_open_permissions)) { showPermissionsDialog() })
        sysCard.addView(row(getString(R.string.settings_open_battery)) { showBatteryOptimizationDialog() })
        container.addView(sysCard)

        // ── Сервис ──
        container.addView(sectionTitle(getString(R.string.settings_section_service)))
        val svcCard = card()
        svcCard.addView(row(getString(R.string.settings_clear_workspace),
            getString(R.string.settings_clear_workspace_sub)) { confirmClearWorkspace() })
        svcCard.addView(row(getString(R.string.settings_about),
            getString(R.string.settings_about_sub, appVersionName())) { showAboutDialog() })
        container.addView(svcCard)
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
    } catch (e: Exception) { "—" }

    private fun confirmClearWorkspace() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_clear_workspace))
            .setMessage(getString(R.string.settings_clear_workspace_confirm, workspacePath.absolutePath))
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .setPositiveButton(getString(R.string.settings_clear_workspace_do)) { _, _ ->
                var count = 0
                try {
                    workspacePath.listFiles()?.forEach { if (it.isFile && it.delete()) count++ }
                } catch (error: Exception) {
                    android.util.Log.w("NekoFlash", "Unable to enumerate or delete workspace files", error)
                    viewModel.log("⚠️ Рабочая папка очищена частично: ${error.javaClass.simpleName}")
                }
                viewModel.log(resources.getQuantityString(R.plurals.settings_clear_workspace_done, count, count))
            }
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_about))
            .setMessage(getString(R.string.settings_about_body, appVersionName()))
            .setPositiveButton(getString(R.string.close_upper), null)
            .show()
    }

    private data class QuickFlashSlotTargetInfo(
        val hasSlots: Boolean?,
        val currentSlot: String?,
        val slotCount: Int
    )

    private fun startDirectFlash(partition: String) {
        chooseQuickFlashSlotTarget(partition) { slot ->
            showFileSelector { file ->
                viewModel.runFlash(partition, file, slot)
            }
        }
    }

    private fun chooseQuickFlashSlotTarget(partition: String, onSlotChosen: (String?) -> Unit) {
        val normalized = partition.trim().lowercase(Locale.US)
        if (normalized.isBlank() || quickFlashPartitionAlreadySuffixed(normalized)) {
            onSlotChosen(null)
            return
        }

        val proto = viewModel.fastbootProtocol
        if (proto?.isConnected != true) {
            // Keep the existing offline flow: allow file selection, then runFlash will
            // report the real Fastboot connection error.
            onSlotChosen(null)
            return
        }

        val snapshotInfo = quickFlashSlotInfoFromSnapshot(normalized)
        when (snapshotInfo.hasSlots) {
            true -> showQuickFlashSlotDialog(normalized, snapshotInfo, onSlotChosen)
            false -> onSlotChosen(null)
            null -> probeQuickFlashSlotTarget(normalized, snapshotInfo, onSlotChosen)
        }
    }

    private fun quickFlashPartitionAlreadySuffixed(partition: String): Boolean {
        val name = partition.substringBefore(':').lowercase(Locale.US)
        return name.endsWith("_a") || name.endsWith("_b")
    }

    private fun normalizeQuickFlashSlot(raw: String?): String? =
        raw?.trim()
            ?.removePrefix("_")
            ?.lowercase(Locale.US)
            ?.takeIf { it.length == 1 && it[0] in 'a'..'z' }

    private fun quickFlashBaseName(partition: String): String =
        FastbootPartitionInventory.baseName(partition.substringBefore(':').trim().lowercase(Locale.US))

    private fun quickFlashSlotInfoFromSnapshot(partition: String): QuickFlashSlotTargetInfo {
        val base = quickFlashBaseName(partition)
        val inventory = viewModel.currentFastbootPartitionInventory()
        val diagnostics = viewModel.currentFastbootDiagnostics()
        val currentSlot = normalizeQuickFlashSlot(inventory?.currentSlot)
            ?: normalizeQuickFlashSlot(diagnostics?.currentSlot)
        val slotCount = diagnostics?.slotCount?.trim()?.toIntOrNull()
            ?: inventory?.variables?.get("slot-count")?.trim()?.toIntOrNull()
            ?: 0

        if (inventory?.topology == FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY) {
            return QuickFlashSlotTargetInfo(hasSlots = false, currentSlot = currentSlot, slotCount = slotCount)
        }

        val familyHasSlot = inventory?.slotFamilies
            ?.entries
            ?.firstOrNull { it.key.equals(base, ignoreCase = true) }
            ?.value
        val entryHasSlot = inventory?.entries
            ?.firstOrNull {
                it.name.equals(base, ignoreCase = true) ||
                    it.baseName.equals(base, ignoreCase = true)
            }
            ?.hasSlot
        val hasSlots = familyHasSlot ?: entryHasSlot
        return QuickFlashSlotTargetInfo(
            hasSlots = hasSlots,
            currentSlot = currentSlot,
            slotCount = slotCount
        )
    }

    private fun probeQuickFlashSlotTarget(
        partition: String,
        snapshotInfo: QuickFlashSlotTargetInfo,
        onSlotChosen: (String?) -> Unit
    ) {
        val base = quickFlashBaseName(partition)
        lifecycleScope.launch(Dispatchers.IO) {
            val proto = viewModel.fastbootProtocol
            val probedHasSlots = proto
                ?.takeIf { it.isConnected }
                ?.let {
                    runCatching {
                        it.getVar("has-slot:$base")?.trim()?.equals("yes", ignoreCase = true)
                    }.getOrNull()
                }
            val probedCurrentSlot = proto
                ?.takeIf { it.isConnected }
                ?.let { runCatching { normalizeQuickFlashSlot(it.getVar("current-slot")) }.getOrNull() }
            val probedSlotCount = proto
                ?.takeIf { it.isConnected }
                ?.let { runCatching { it.getVar("slot-count")?.trim()?.toIntOrNull() }.getOrNull() }
                ?: 0

            withContext(Dispatchers.Main) {
                val info = QuickFlashSlotTargetInfo(
                    hasSlots = probedHasSlots,
                    currentSlot = probedCurrentSlot ?: snapshotInfo.currentSlot,
                    slotCount = if (probedSlotCount > 0) probedSlotCount else snapshotInfo.slotCount
                )
                if (info.hasSlots == true) {
                    showQuickFlashSlotDialog(partition, info, onSlotChosen)
                } else {
                    onSlotChosen(null)
                }
            }
        }
    }

    private fun showQuickFlashSlotDialog(
        partition: String,
        info: QuickFlashSlotTargetInfo,
        onSlotChosen: (String?) -> Unit
    ) {
        val current = info.currentSlot
        val currentTarget = current?.let { "${quickFlashBaseName(partition)}_$it" } ?: "${quickFlashBaseName(partition)}_<current>"
        val lastSlot = if (info.slotCount > 0) {
            ('a'.code + (info.slotCount - 1).coerceAtLeast(0)).toChar()
        } else {
            'b'
        }
        val allLabel = if (info.slotCount > 1) {
            "Все слоты a..$lastSlot (--slot=all)"
        } else {
            "Все слоты (--slot=all)"
        }
        val labels = arrayOf(
            "Активный слот ${current ?: "current"} → $currentTarget",
            allLabel
        )
        // Do not mix AlertDialog.setMessage() with setItems() here: on some
        // Android/Material theme combinations the message view can consume the
        // dialog content area and leave the slot choices invisible. Keep the
        // explanatory text and the two target choices in one explicit custom view.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        content.addView(TextView(this).apply {
            text = "Раздел сообщает has-slot=yes. Выберите цель прошивки перед выбором файла."
            setTextColor("#AEB8C5".toColorInt())
            textSize = 16f
            setPadding(0, dp(4), 0, dp(16))
        })
        val activeSlotButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = labels[0]
            isAllCaps = false
            minHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val allSlotsButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = labels[1]
            isAllCaps = false
            minHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8)
        }
        content.addView(activeSlotButton, buttonParams)
        content.addView(allSlotsButton, LinearLayout.LayoutParams(buttonParams))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Куда прошить $partition")
            .setView(content)
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .create()

        activeSlotButton.setOnClickListener {
            dialog.dismiss()
            onSlotChosen(current)
        }
        allSlotsButton.setOnClickListener {
            dialog.dismiss()
            onSlotChosen("all")
        }
        dialog.show()
    }

    private fun showManualQuickFlashTargetDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.quick_flash_manual_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.quick_flash_manual_title))
            .setView(input)
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                val partition = input.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
                if (!PARTITION_NAME_PATTERN.matches(partition)) {
                    viewModel.log("❌ Некорректное имя Fastboot-раздела: $partition")
                    return@setPositiveButton
                }
                startDirectFlash(partition)
            }
            .show()
    }

    private fun chooseFlashQueueFile(partition: String) {
        showFileSelector { file ->
            viewModel.addFlashQueueFile(partition, file)
            val guessed = guessPartitionFromFileName(file.name)
            if (guessed != null && guessed != partition) {
                viewModel.log(getString(R.string.flash_queue_filename_warning, file.name, guessed, partition))
            }
            switchTab("fastboot")
        }
    }

    private fun clearFlashQueue() {
        viewModel.clearFlashQueueDraft()
    }

    private fun updateFlashQueueUi(
        draft: FlashOperationDraft = viewModel.currentFlashOperationDraft()
    ) {
        val text = if (draft.isEmpty) {
            getString(R.string.flash_queue_empty)
        } else {
            draft.items.joinToString("\n") { item ->
                val guessed = guessPartitionFromFileName(item.displayName)
                val warning = if (guessed != null && guessed != item.partition) "  ⚠ looks like $guessed" else ""
                "✓ ${item.partition} ← ${item.displayName} (${formatFileSize(item.expectedSizeBytes)})$warning"
            }
        }
        findViewById<TextView>(R.id.tvFlashQueueSummary).text = text

        // A selected tile reflects the lifecycle-owned draft, not an Activity field.
        updateQueueTileState(R.id.btnQueueBoot, "boot", R.string.layout_add_boot, draft)
        updateQueueTileState(R.id.btnQueueInitBoot, "init_boot", R.string.layout_add_init_boot, draft)
        updateQueueTileState(R.id.btnQueueRecovery, "recovery", R.string.layout_add_recovery, draft)
        updateQueueTileState(R.id.btnQueueVendorBoot, "vendor_boot", R.string.layout_add_vendor_boot, draft)
        updateQueueTileState(R.id.btnQueueDtbo, "dtbo", R.string.layout_add_dtbo, draft)
    }

    private fun updateQueueTileState(
        buttonId: Int,
        partition: String,
        emptyLabelRes: Int,
        draft: FlashOperationDraft
    ) {
        val button = findViewById<MaterialButton>(buttonId)
        val added = draft.items.any { it.partition == partition }
        button.isSelected = added
        button.text = if (added) {
            getString(R.string.layout_queue_added, partition)
        } else {
            getString(emptyLabelRes)
        }
    }

    private fun confirmFlashQueue() {
        val draft = viewModel.currentFlashOperationDraft()
        if (draft.isEmpty) {
            viewModel.log(getString(R.string.flash_queue_empty_log))
            return
        }
        if (viewModel.fastbootProtocol?.isConnected != true) {
            viewModel.log(getString(R.string.error_no_fastboot))
            return
        }
        viewModel.executeFlashQueueDraft()
    }

    private fun guessPartitionFromFileName(fileName: String): String? =
        PartitionNameResolver.suggest(fileName)

    private fun refreshDeviceDataFromUi() {
        viewModel.refreshFastbootDiagnostics()
        deviceOverviewHandler.removeCallbacks(shortDeviceOverviewRefresh)
        deviceOverviewHandler.removeCallbacks(finalDeviceOverviewRefresh)
        deviceOverviewHandler.postDelayed(shortDeviceOverviewRefresh, 800L)
        deviceOverviewHandler.postDelayed(finalDeviceOverviewRefresh, 2500L)
    }



    private fun isOpenReportsCommand(rawLower: String): Boolean {
        return rawLower == "reports" ||
            rawLower == "open reports" ||
            rawLower == "open-reports" ||
            rawLower == "reports open" ||
            rawLower == "report folder" ||
            rawLower == "reports folder" ||
            rawLower == "adb reports" ||
            rawLower == "fastboot reports"
    }

    private fun copyDiagnosticSummary() {
        val inventory = viewModel.currentFastbootPartitionInventory()
        val fastboot = viewModel.currentFastbootDiagnostics()
        val adb = viewModel.currentAdbDiagnostics()
        val state = viewModel.connectionState.value ?: DeviceViewModel.ConnectionState.NONE
        val text = buildString {
            appendLine("NekoFlash: ${viewModel.currentBuildId()}")
            appendLine("Session ID: ${viewModel.currentTransportSessionId() ?: "none"}")
            appendLine("Mode: $state")
            appendLine("Connection: ${viewModel.currentConnectionInfo() ?: "none"}")
            appendLine("Fastboot session: ${fastboot?.sessionState ?: "none"}")
            appendLine("Fastboot broken reason: ${fastboot?.brokenReasonCode ?: "none"}")
            appendLine("ADB peer: ${adb?.peerMode ?: "none"}")
            appendLine("ADB dispatcher: running=${adb?.dispatcherRunning ?: false}, queue=${adb?.queuedPackets ?: 0}, packets=${adb?.packetsRead ?: 0}, failures=${adb?.readerFailures ?: 0}")
            appendLine("Topology: ${inventory?.topology ?: "unknown"}")
            appendLine("Inventory: ${inventory?.entries?.size ?: 0} partitions, warnings=${inventory?.warnings?.size ?: 0}")
        }.trim()
        copyTextToClipboard("NekoFlash diagnostic summary", text, "Краткий итог скопирован")
    }

    private fun openWorkspaceFolder() {
        if (!ensureWorkspaceReady()) return

        val documentId = "primary:Download/$folderName"
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            documentId
        )
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

        try {
            viewModel.log(getString(R.string.home_workspace_opening, workspaceDisplayPath()))
            startActivity(intent)
        } catch (e: Exception) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: не удалось открыть рабочую папку: ${e.message ?: e.javaClass.simpleName}")
            copyTextToClipboard(
                "NekoFlash workspace",
                workspaceDisplayPath(),
                getString(R.string.home_workspace_open_failed)
            )
        }
    }

    private fun openReportsFolder() {
        if (!ensureWorkspaceReady()) return
        val reportsDir = File(workspacePath, "reports")
        if (!reportsDir.exists() && !reportsDir.mkdirs()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: не удалось создать папку reports: ${reportsDir.absolutePath}")
            return
        }

        val documentId = "primary:Download/$folderName/reports"
        val treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

        try {
            viewModel.log("Открываю папку отчётов: /sdcard/Download/$folderName/reports")
            startActivity(intent)
        } catch (e: Exception) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: не удалось открыть DocumentsUI для reports: ${e.message ?: e.javaClass.simpleName}")
            copyTextToClipboard("ADB Fastboot reports", reportsDir.absolutePath, "Путь к reports скопирован")
        }
    }

    private fun shareGenericFile(file: File, mimeType: String, subject: String): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, getString(R.string.share_file_text, file.name))
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_file_chooser)))
            true
        } catch (e: Exception) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: не удалось отправить файл: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun shareLogFile(file: File) {
        lifecycleScope.launch {
            val sanitized = try {
                withContext(Dispatchers.IO) {
                    SanitizedLogShare.create(
                        source = file,
                        outputDir = File(cacheDir, "shared-logs"),
                        scope = ReportSanitizer.Scope(
                            workspace = workspacePath,
                            logFile = file,
                            packageName = packageName
                        )
                    )
                }
            } catch (e: Exception) {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ОШИБКА: не удалось подготовить очищенную копию лога: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }

            viewModel.log("🔒 Для отправки создана временная очищенная копия лога.")
            if (shareGenericFile(sanitized, "text/plain", "NekoFlash sanitized log")) {
                // ACTION_SEND does not report when the receiving app has finished reading.
                // Keep the cache file briefly, then remove it; stale files are also
                // cleaned on every subsequent share.
                Handler(Looper.getMainLooper()).postDelayed(
                    { sanitized.delete() },
                    15L * 60L * 1000L
                )
            } else {
                sanitized.delete()
            }
        }
    }

    private fun copyTextToClipboard(label: String, text: String, logMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        viewModel.log(logMessage)
    }


    private fun enableOverlayProtection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setHideOverlayWindows(true)
                if (!overlayProtectionLogged) {
                    viewModel.log(getString(R.string.overlay_protection_enabled))
                    overlayProtectionLogged = true
                }
            } catch (e: Exception) {
                if (!overlayProtectionLogged) {
                    viewModel.log(getString(R.string.overlay_protection_error, e.message ?: e.javaClass.simpleName))
                    overlayProtectionLogged = true
                }
            }
        } else if (!overlayProtectionLogged) {
            viewModel.log(getString(R.string.overlay_protection_unsupported))
            overlayProtectionLogged = true
        }
    }

    private fun showPermissionsDialog() {
        val notifications = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            getString(R.string.permission_status_not_required)
        } else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }

        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) getString(R.string.permission_status_granted) else getString(R.string.permission_status_not_granted)
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) getString(R.string.permission_status_granted) else getString(R.string.permission_status_not_granted)
        }

        val powerManager = getSystemService(PowerManager::class.java)
        val battery = if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_optional)
        }

        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getString(R.string.permission_status_enabled)
        } else {
            getString(R.string.permission_status_not_supported)
        }

        val message = getString(
            R.string.permissions_dialog_message,
            storage,
            notifications,
            battery,
            overlay
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_permissions_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok_understood_upper), null)
            .setNeutralButton(getString(R.string.open_app_settings_upper)) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = "package:$packageName".toUri()
                    })
                } catch (e: Exception) {
                    viewModel.log(getString(R.string.app_settings_open_error, e.message ?: e.javaClass.simpleName))
                }
            }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun logBatteryOptimizationState() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            viewModel.log("⚠️ Для долгой прошивки рекомендуется отключить оптимизацию батареи: кнопка «Батарея».")
        }
    }

    private fun showBatteryOptimizationDialog() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            viewModel.log(getString(R.string.battery_optimization_already_disabled))
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_battery_title))
            .setMessage(getString(R.string.battery_optimization_message))
            .setPositiveButton(getString(R.string.open_upper)) { _, _ -> requestDisableBatteryOptimization() }
            .setNegativeButton(getString(R.string.later_upper), null)
            .show()
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestDisableBatteryOptimization() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            })
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                viewModel.log(getString(R.string.battery_optimization_open_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }


    private fun applySavedLanguage() {
        val tag = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE_TAG, "") ?: ""
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun showLanguageDialog() {
        val options = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_russian),
            getString(R.string.language_english)
        )
        val tags = arrayOf("", "ru", "en")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTag = prefs.getString(PREF_LANGUAGE_TAG, "") ?: ""
        val checked = tags.indexOf(currentTag).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.language_dialog_title))
            .setMessage(getString(R.string.language_dialog_message))
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val selectedTag = tags[which]
                prefs.edit { putString(PREF_LANGUAGE_TAG, selectedTag) }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedTag))
                dialog.dismiss()
                viewModel.log(getString(R.string.language_changed))
                recreate()
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    // ─── UI ──────────────────────────────────────────────────────────────────


    private fun formatDeviceBoolean(value: String): String = when (value.trim().lowercase(Locale.US)) {
        "yes", "true", "1" -> getString(R.string.device_bool_yes)
        "no", "false", "0" -> getString(R.string.device_bool_no)
        "", "unknown" -> getString(R.string.device_bool_unknown)
        else -> value
    }

    @Suppress("SdCardPath")
    private fun updateDeviceOverview() {
        val diagnostics = viewModel.currentFastbootDiagnostics()
        val inventory = viewModel.currentFastbootPartitionInventory()
        val connectionInfo = viewModel.currentConnectionInfo()
        val modeText = connectionStatusPresentation().first.removePrefix("● ")

        val product = diagnostics?.product
            ?: inventory?.product
            ?: extractConnectionField(connectionInfo, "Устройство")
            ?: "—"
        val slot = when (inventory?.topology) {
            FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY -> "без A/B (legacy A-only)"
            FastbootPartitionInventory.SlotTopology.A_B -> diagnostics?.currentSlot ?: "A/B, текущий не определён"
            FastbootPartitionInventory.SlotTopology.UNKNOWN, null -> diagnostics?.currentSlot ?: "—"
        }
        val unlocked = diagnostics?.unlocked?.let(::formatDeviceBoolean) ?: "—"
        val maxDownload = diagnostics?.maxDownloadSizeRaw?.let { raw ->
            val bytes = diagnostics.maxDownloadSizeBytes
            if (bytes != null && bytes > 0L) "$raw / ${formatFileSize(bytes)}" else raw
        } ?: "—"

        val serialno = diagnostics?.serialno?.let { " | Serial: $it" } ?: ""
        val slotExtra = buildString {
            if (inventory?.topology != FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY) {
                diagnostics?.slotCount?.let { append(" | Слотов: $it") }
                diagnostics?.slotSuffix?.let { append(" | Суффикс: $it") }
            }
        }
        val slotDisplay = if (slotExtra.isNotBlank()) "$slot$slotExtra" else slot
        val vbl = diagnostics?.versionBootloader?.let { " | BL: $it" } ?: ""

        findViewById<TextView>(R.id.tvDeviceModeValue).text =
            getString(R.string.device_mode_value, modeText)
        findViewById<TextView>(R.id.tvDeviceProductValue).text =
            getString(R.string.device_product_value, product, serialno, vbl)
        findViewById<TextView>(R.id.tvDeviceSlotValue).text =
            getString(R.string.device_slot_value, slotDisplay)
        findViewById<TextView>(R.id.tvDeviceUnlockedValue).text =
            getString(R.string.device_bootloader_value, unlocked)
        val maxFetch = diagnostics?.maxFetchSizeRaw?.let { raw ->
            val bytes = diagnostics.maxFetchSizeBytes
            if (bytes != null && bytes > 0L) "$raw / ${formatFileSize(bytes)}" else raw
        }
        val superPart = diagnostics?.superPartitionName?.let { " | super: $it" } ?: ""
        val inventoryPart = inventory?.let { snapshot ->
            val normal = snapshot.entries.count { it.risk == FastbootPartitionInventory.RiskTier.NORMAL }
            val advanced = snapshot.entries.count { it.risk == FastbootPartitionInventory.RiskTier.ADVANCED }
            val critical = snapshot.entries.count { it.risk == FastbootPartitionInventory.RiskTier.CRITICAL }
            val logical = snapshot.entries.count { it.storage == FastbootPartitionInventory.StorageKind.LOGICAL }
            val physical = snapshot.entries.count { it.storage == FastbootPartitionInventory.StorageKind.PHYSICAL }
            val incomplete = snapshot.entries.count { it.missingFields.isNotEmpty() }
            " | Разделов: ${snapshot.entries.size} " +
                "(обычные $normal / расширенные $advanced / критичные $critical; " +
                "physical $physical / logical $logical; неполные $incomplete)"
        } ?: ""
        findViewById<TextView>(R.id.tvDeviceMaxDownloadValue).text = getString(
            R.string.device_max_download_value,
            maxDownload,
            maxFetch?.let { " | fetch: $it" } ?: "",
            superPart,
            inventoryPart
        )
        val session = viewModel.currentTransportSessionId() ?: "—"
        val transportInfo = connectionInfo
            ?.split(" | ")
            ?.drop(1)
            ?.joinToString(" • ")
            ?.ifBlank { null }
            ?: "—"
        findViewById<TextView>(R.id.tvDeviceWorkspaceValue).text = getString(
            R.string.device_transport_value,
            transportInfo,
            session
        )
    }


    private fun showPartitionInventoryDialog() {
        val snapshot = viewModel.currentFastbootPartitionInventory()
        if (snapshot == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.partition_inventory_title)
                .setMessage(R.string.partition_inventory_empty)
                .setPositiveButton(R.string.partition_inventory_close, null)
                .show()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), 0)
        }
        val summary = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(6), dp(8), dp(8))
            text = buildPartitionInventorySummary(snapshot)
        }
        val readOnly = TextView(this).apply {
            setTextColor(getColor(R.color.status_info))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), 0, dp(8), dp(8))
            setText(R.string.partition_inventory_read_only_note)
        }
        val search = EditText(this).apply {
            hint = getString(R.string.partition_inventory_filter_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val filterLabels = listOf(
            getString(R.string.partition_inventory_filter_all),
            getString(R.string.partition_inventory_filter_normal),
            getString(R.string.partition_inventory_filter_advanced),
            getString(R.string.partition_inventory_filter_critical),
            getString(R.string.partition_inventory_filter_logical),
            getString(R.string.partition_inventory_filter_physical)
        )
        val filter = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                filterLabels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val importantWarnings = snapshot.warnings
            .filter { it.severity != FastbootPartitionInventory.WarningSeverity.INFO }
            .take(6)
        val warningText = TextView(this).apply {
            setTextColor(getColor(R.color.log_warning))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(6))
            visibility = if (importantWarnings.isEmpty()) View.GONE else View.VISIBLE
            text = importantWarnings.joinToString(separator = "\n") { warning -> "⚠ ${warning.message}" }
        }
        val results = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(12))
        }
        val resultsScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                results,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(summary)
        root.addView(readOnly)
        root.addView(
            search,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), 0, dp(8), dp(6)) }
        )
        root.addView(
            filter,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), 0, dp(8), 0) }
        )
        root.addView(warningText)
        root.addView(
            resultsScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(dp(360), (resources.displayMetrics.heightPixels * 0.48f).toInt())
            ).apply { setMargins(dp(8), 0, dp(8), 0) }
        )

        fun render() {
            val selectedRisk = when (filter.selectedItemPosition) {
                1 -> FastbootPartitionInventory.RiskTier.NORMAL
                2 -> FastbootPartitionInventory.RiskTier.ADVANCED
                3 -> FastbootPartitionInventory.RiskTier.CRITICAL
                else -> null
            }
            val selectedStorage = when (filter.selectedItemPosition) {
                4 -> FastbootPartitionInventory.StorageKind.LOGICAL
                5 -> FastbootPartitionInventory.StorageKind.PHYSICAL
                else -> null
            }
            val entries = snapshot.filtered(
                query = search.text?.toString().orEmpty(),
                risk = selectedRisk,
                storage = selectedStorage
            )
            results.text = if (entries.isEmpty()) {
                getString(R.string.partition_inventory_no_matches)
            } else {
                entries.joinToString(separator = "\n\n") { formatPartitionInventoryEntry(it) }
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        filter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = render()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = render()
        }
        render()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.partition_inventory_title)
            .setView(root)
            .setPositiveButton(R.string.partition_inventory_close, null)
            .show()
    }

    private fun buildPartitionInventorySummary(snapshot: FastbootPartitionInventory.Snapshot): String {
        val topology = when (snapshot.topology) {
            FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY -> "LEGACY A-ONLY"
            FastbootPartitionInventory.SlotTopology.A_B -> "A/B"
            FastbootPartitionInventory.SlotTopology.UNKNOWN -> "UNKNOWN"
        }
        val physical = snapshot.entries.count { it.storage == FastbootPartitionInventory.StorageKind.PHYSICAL }
        val logical = snapshot.entries.count { it.storage == FastbootPartitionInventory.StorageKind.LOGICAL }
        val unknown = snapshot.entries.count { it.storage == FastbootPartitionInventory.StorageKind.UNKNOWN }
        val incomplete = snapshot.entries.count { it.missingFields.isNotEmpty() }
        return buildString {
            append("product: ").append(snapshot.product ?: "—")
            append(" | topology: ").append(topology)
            snapshot.currentSlot?.let { append(" | current: ").append(it.uppercase(Locale.US)) }
            append("\npartitions: ").append(snapshot.entries.size)
            append(" | physical: ").append(physical)
            append(" | logical: ").append(logical)
            append(" | unknown: ").append(unknown)
            append("\ngetvar:all: ").append(snapshot.finalStatus)
            append(if (snapshot.complete) " / complete" else " / partial")
            append(" | point queries: ").append(snapshot.pointQueryCount)
            append(" | unresolved: ").append(snapshot.unresolvedPointQueryCount)
            append(" | incomplete entries: ").append(incomplete)
        }
    }

    private fun formatPartitionInventoryEntry(entry: FastbootPartitionInventory.Entry): String {
        val risk = when (entry.risk) {
            FastbootPartitionInventory.RiskTier.NORMAL -> "NORMAL"
            FastbootPartitionInventory.RiskTier.ADVANCED -> "ADVANCED"
            FastbootPartitionInventory.RiskTier.CRITICAL -> "CRITICAL"
        }
        val storage = when (entry.storage) {
            FastbootPartitionInventory.StorageKind.PHYSICAL -> "PHYSICAL"
            FastbootPartitionInventory.StorageKind.LOGICAL -> "LOGICAL"
            FastbootPartitionInventory.StorageKind.UNKNOWN -> "STORAGE ?"
        }
        val slot = when (entry.slotBinding) {
            FastbootPartitionInventory.SlotBinding.SLOT_A -> "slot A"
            FastbootPartitionInventory.SlotBinding.SLOT_B -> "slot B"
            FastbootPartitionInventory.SlotBinding.UNSLOTTED -> "no slot"
            FastbootPartitionInventory.SlotBinding.SLOT_FAMILY_BASE -> "A/B family metadata"
            FastbootPartitionInventory.SlotBinding.UNKNOWN -> "slot ?"
        }
        val source = entry.evidenceSources.joinToString("+") {
            when (it) {
                FastbootPartitionInventory.EvidenceSource.GETVAR_ALL -> "all"
                FastbootPartitionInventory.EvidenceSource.POINT_QUERY -> "point"
            }
        }.ifBlank { "?" }
        return buildString {
            append(entry.name)
            append("\n  ").append(risk).append(" · ").append(storage).append(" · ").append(slot)
            append("\n  size: ").append(entry.sizeBytes?.let(::formatFileSize) ?: "—")
            append(" · type: ").append(entry.type ?: "—")
            append(" · evidence: ").append(source)
            if (entry.missingFields.isNotEmpty()) {
                append("\n  ⚠ missing: ")
                append(entry.missingFields.joinToString { it.name.lowercase(Locale.US) })
            }
            entry.warnings
                .filter { it.code == "PARTITION_METADATA_CONFLICT" }
                .take(1)
                .forEach { append("\n  ⚠ ").append(it.message) }
        }
    }

    private fun extractConnectionField(info: String?, field: String): String? {
        if (info.isNullOrBlank()) return null
        val marker = "$field:"
        val start = info.indexOf(marker)
        if (start < 0) return null
        val after = info.substring(start + marker.length).trim()
        return after.substringBefore("|").trim().ifBlank { null }
    }

    private fun restoreWindowState(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val target = sanitizeWindow(
            savedInstanceState?.getString(STATE_SELECTED_WINDOW)
                ?: prefs.getString(PREF_LAST_WINDOW, "home")
        )
        restoringWindowState = true
        try {
            switchTab(target)
        } finally {
            restoringWindowState = false
        }
        if (target == "unlock") buildUnlockPage()
    }

    private fun sanitizeWindow(value: String?): String = when (value) {
        "home", "fastboot", "adb", "unlock", "settings" -> value
        else -> "home"
    }

    private fun openConsole(requestCommandFocus: Boolean) {
        consoleDockController.open(requestCommandFocus = requestCommandFocus)
    }

    private fun switchTab(tab: String) {
        when (tab) {
            "reports" -> {
                showReportsMenu()
                return
            }
        }

        val target = sanitizeWindow(tab)
        tabController.switchTab(target)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(PREF_LAST_WINDOW, tabController.selectedWindow)
        }

        // The unified Console is a persistent Bottom Sheet, never a separate page.
        findViewById<View>(R.id.consolePanel).visibility = View.VISIBLE
    }


    /**
     * Обновляет OTG-индикатор. Прямого API «OTG вкл/выкл» в Android нет, поэтому
     * статус выводится косвенно: поддержка USB Host (железо) + наличие устройств
     * в deviceList. Если OTG отключён в системе, deviceList пуст даже при кабеле —
     * пользователь видит подсказку включить OTG.
     */
    private fun updateOtgStatus() {
        val tv = tvOtgStatus ?: return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)) {
            tv.text = getString(R.string.otg_status_unsupported)
            tv.setTextColor("#E06C75".toColorInt())
            return
        }
        val hasDevices = try { usbManager.deviceList.isNotEmpty() } catch (_: Exception) { false }
        if (hasDevices) {
            tv.text = getString(R.string.otg_status_active)
            tv.setTextColor("#69C779".toColorInt())
        } else {
            tv.text = getString(R.string.otg_status_no_device)
            tv.setTextColor("#F2B766".toColorInt())
        }
    }

    private fun connectionStatusPresentation(): Pair<String, String> {
        val transport = when (viewModel.connectionState.value ?: DeviceViewModel.ConnectionState.NONE) {
            DeviceViewModel.ConnectionState.NONE -> ConnectionModeUiPolicy.Transport.NONE
            DeviceViewModel.ConnectionState.CONNECTING -> ConnectionModeUiPolicy.Transport.CONNECTING
            DeviceViewModel.ConnectionState.FASTBOOT -> ConnectionModeUiPolicy.Transport.FASTBOOT
            DeviceViewModel.ConnectionState.ADB -> ConnectionModeUiPolicy.Transport.ADB
            DeviceViewModel.ConnectionState.ERROR -> ConnectionModeUiPolicy.Transport.ERROR
        }
        val adbMode = when (viewModel.adbPeerMode.value) {
            AdbProtocol.PeerMode.DEVICE -> ConnectionModeUiPolicy.AdbMode.SYSTEM
            AdbProtocol.PeerMode.RECOVERY -> ConnectionModeUiPolicy.AdbMode.RECOVERY
            AdbProtocol.PeerMode.SIDELOAD -> ConnectionModeUiPolicy.AdbMode.SIDELOAD
            AdbProtocol.PeerMode.UNKNOWN -> ConnectionModeUiPolicy.AdbMode.UNKNOWN
            null -> null
        }
        val fastbootd = viewModel.fastbootDiagnostics.value?.isUserspace?.let { raw ->
            when {
                raw.equals("yes", ignoreCase = true) -> true
                raw.equals("no", ignoreCase = true) -> false
                else -> null
            }
        }

        return when (ConnectionModeUiPolicy.resolve(transport, adbMode, fastbootd)) {
            ConnectionModeUiPolicy.DisplayMode.NO_DEVICE -> getString(R.string.status_no_device) to "#758397"
            ConnectionModeUiPolicy.DisplayMode.CONNECTING -> getString(R.string.status_connecting) to "#F2B766"
            ConnectionModeUiPolicy.DisplayMode.FASTBOOT_BOOTLOADER -> getString(R.string.status_fastboot) to "#E9782B"
            ConnectionModeUiPolicy.DisplayMode.FASTBOOTD -> getString(R.string.status_fastbootd) to "#E98B49"
            ConnectionModeUiPolicy.DisplayMode.FASTBOOT_UNKNOWN -> getString(R.string.status_fastboot_unknown) to "#AEB8C5"
            ConnectionModeUiPolicy.DisplayMode.ADB_SYSTEM -> getString(R.string.status_adb_system) to "#69C779"
            ConnectionModeUiPolicy.DisplayMode.ADB_RECOVERY -> getString(R.string.status_adb_recovery) to "#6FB7D8"
            ConnectionModeUiPolicy.DisplayMode.ADB_SIDELOAD -> getString(R.string.status_adb_sideload) to "#F2B766"
            ConnectionModeUiPolicy.DisplayMode.ADB_UNKNOWN -> getString(R.string.status_adb_unknown) to "#AEB8C5"
            ConnectionModeUiPolicy.DisplayMode.ERROR -> getString(R.string.status_error) to "#E06C75"
        }
    }

    private fun refreshConnectionStatusLabel() {
        if (!::tvStatus.isInitialized) return
        val (text, color) = connectionStatusPresentation()
        tvStatus.text = text
        tvStatus.setTextColor(color.toColorInt())
    }

    private fun renderFlashProgressDialog(progress: DeviceViewModel.OperationProgress?) {
        // Phase 2: Operation progress must not be drawn as a free-floating
        // overlay above task screens. It caused Unlock cards, action buttons and
        // Console to overlap. The operation state remains visible through
        // Console logs and the Home Operation Center; this hook only keeps old
        // references hidden and refreshes stateful pages.
        flashProgressPanel?.visibility = View.GONE
        if (progress?.finished == true && progress.outcome == DeviceViewModel.OperationOutcomeKind.FAILED) {
            consoleDockController.showLiveLogPreview()
        }
        if (selectedWindow == "unlock") buildUnlockPage()
    }

    private fun confirmCancelFlashProgress() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.flash_progress_cancel_confirm_title))
            .setMessage(getString(R.string.flash_progress_cancel_confirm_body))
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .setPositiveButton(getString(R.string.flash_progress_cancel)) { _, _ ->
                viewModel.cancelActiveOperation()
            }
            .show()
    }

    private fun renderOperationSteps(steps: List<DeviceViewModel.OperationStep>) {
        if (!::tvOperationStepQueue.isInitialized) return
        if (steps.isEmpty()) {
            tvOperationStepQueue.text = getString(R.string.layout_operation_steps_empty)
            tvOperationStepQueue.setTextColor("#AEB8C5".toColorInt())
            return
        }
        val runningIndex = steps.indexOfFirst { it.status == DeviceViewModel.OperationStepStatus.RUNNING }
        val visibleSteps = when {
            steps.size <= 12 -> steps
            runningIndex >= 0 -> {
                val from = (runningIndex - 4).coerceAtLeast(0)
                val to = (runningIndex + 8).coerceAtMost(steps.size)
                steps.subList(from, to)
            }
            else -> steps.take(12)
        }
        val hiddenCount = steps.size - visibleSteps.size
        val body = buildString {
            visibleSteps.forEach { step ->
                val icon = when (step.status) {
                    DeviceViewModel.OperationStepStatus.PENDING -> "·"
                    DeviceViewModel.OperationStepStatus.RUNNING -> "▶"
                    DeviceViewModel.OperationStepStatus.OK -> "✓"
                    DeviceViewModel.OperationStepStatus.FAILED -> "✕"
                    DeviceViewModel.OperationStepStatus.SKIPPED -> "↷"
                    DeviceViewModel.OperationStepStatus.INFO -> "i"
                }
                append(icon)
                append(' ')
                append(step.index)
                append('/')
                append(step.total)
                append(' ')
                append(step.title.take(96))
                step.subtitle?.takeIf { it.isNotBlank() }?.let {
                    append(" — ")
                    append(it.take(72))
                }
                append('\n')
            }
            if (hiddenCount > 0) {
                append(resources.getQuantityString(R.plurals.layout_operation_steps_more, hiddenCount, hiddenCount))
            }
        }.trimEnd()
        tvOperationStepQueue.text = body
        val hasFailed = steps.any { it.status == DeviceViewModel.OperationStepStatus.FAILED }
        val hasRunning = steps.any { it.status == DeviceViewModel.OperationStepStatus.RUNNING }
        val allOk = steps.isNotEmpty() && steps.all { it.status == DeviceViewModel.OperationStepStatus.OK || it.status == DeviceViewModel.OperationStepStatus.SKIPPED }
        val color = when {
            hasFailed -> "#E06C75"
            hasRunning -> "#F2B766"
            allOk -> "#69C779"
            else -> "#AEB8C5"
        }
        tvOperationStepQueue.setTextColor(color.toColorInt())
    }

    private fun updateOperationCenter(lines: List<String>) {
        if (!::tvOperationCenterStatus.isInitialized || !::tvOperationCenterLastEvent.isInitialized) return
        val active = viewModel.operationActive.value == true
        val recent = lines.asReversed().firstOrNull { line ->
            val trimmed = line.trim()
            trimmed.isNotBlank() &&
                !trimmed.startsWith("💡") &&
                !trimmed.contains("System terminal ready", ignoreCase = true) &&
                !trimmed.contains("Full terminal", ignoreCase = true)
        }
        val recentText = recent?.let { if (it.length > 260) it.take(257) + "…" else it }
        val (status, color) = when {
            active -> getString(R.string.layout_operation_center_running) to "#F2B766"
            recentText == null -> getString(R.string.layout_operation_center_idle) to "#AEB8C5"
            recentText.contains("❌") || recentText.contains("ОШИБКА") || recentText.contains("FAILED", ignoreCase = true) || recentText.contains("БЛОКИРОВКА") ->
                getString(R.string.layout_operation_center_failed) to "#E06C75"
            recentText.contains("✅") || recentText.contains("COMPLETED", ignoreCase = true) || recentText.contains("ЗАВЕРШЕНА") ->
                getString(R.string.layout_operation_center_completed) to "#69C779"
            recentText.contains("⚠") || recentText.contains("WARN", ignoreCase = true) ->
                getString(R.string.layout_operation_center_warning) to "#F2B766"
            else -> getString(R.string.layout_operation_center_idle) to "#AEB8C5"
        }
        tvOperationCenterStatus.text = status
        tvOperationCenterStatus.setTextColor(color.toColorInt())
        tvOperationCenterLastEvent.text = recentText?.let { getString(R.string.layout_operation_center_last_event, it) }
            ?: getString(R.string.layout_operation_center_last_event_empty)

        val cancelButton = findViewById<Button>(R.id.btnOperationCenterCancel)
        cancelButton.isEnabled = active
        cancelButton.alpha = if (active) 1.0f else 0.45f

        // Operation Center is for real work state, not every read-only terminal
        // command. Read-only Fastboot getvar results stay in Console only.
        val progress = viewModel.operationProgress.value
        val progressTitle = progress?.title.orEmpty()
        val terminalReadOnlyProgress =
            progressTitle.equals("Fastboot-команда", ignoreCase = true) &&
                recentText?.startsWith("✅ getvar:", ignoreCase = true) == true

        val hasErrorOrWarning = recentText != null && (
            recentText.contains("❌") || recentText.contains("ОШИБКА") ||
            recentText.contains("FAILED", ignoreCase = true) || recentText.contains("БЛОКИРОВКА") ||
            recentText.contains("⚠") || recentText.contains("WARN", ignoreCase = true)
        )
        val hasImportantFinishedOperation =
            progress?.finished == true &&
                !terminalReadOnlyProgress &&
                !progressTitle.equals("Fastboot-команда", ignoreCase = true)

        findViewById<View>(R.id.cardOperationCenter).visibility =
            if (active || hasErrorOrWarning || hasImportantFinishedOperation) View.VISIBLE else View.GONE
    }

    // Console snapshots are coalesced for a short window so bursty USB output
    // produces one RecyclerView update instead of one layout pass per line.
    private var compactLogRenderState = CompactLogRenderPolicy.State()
    private val consoleRenderHandler = Handler(Looper.getMainLooper())
    private var pendingConsoleSnapshot: List<String>? = null
    private var consoleRenderScheduled: Boolean = false
    private val flushConsoleRenderRunnable = Runnable { flushConsoleRender() }

    private fun renderLog(lines: List<String>) {
        pendingConsoleSnapshot = CompactLogRenderPolicy.boundedSnapshot(lines)
        lines.lastOrNull()?.let(consoleDockController::updatePeek)
            ?: consoleDockController.updatePeek("")

        if (!consoleRenderScheduled) {
            consoleRenderScheduled = true
            consoleRenderHandler.postDelayed(
                flushConsoleRenderRunnable,
                CompactLogRenderPolicy.RENDER_DEBOUNCE_MS,
            )
        }
    }

    private fun flushConsoleRender() {
        consoleRenderScheduled = false
        val lines = pendingConsoleSnapshot ?: return
        pendingConsoleSnapshot = null

        val decision = CompactLogRenderPolicy.decide(lines, compactLogRenderState)
        compactLogRenderState = decision.nextState

        val layoutManager = rvConsoleOutput.layoutManager as? LinearLayoutManager
        val keepPinnedToBottom = !consoleDockController.isExpanded ||
            !rvConsoleOutput.canScrollVertically(1)
        val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: -1
        val firstVisibleOffset = if (firstVisiblePosition >= 0) {
            layoutManager?.findViewByPosition(firstVisiblePosition)?.top ?: 0
        } else {
            0
        }

        if (decision.reset) {
            consoleLogAdapter.replaceAll(lines)
        } else {
            consoleLogAdapter.removeFirst(decision.removeCount)
            consoleLogAdapter.append(lines.drop(decision.startIndex))
        }

        when {
            keepPinnedToBottom -> scrollConsoleToBottom()
            decision.removeCount > 0 && firstVisiblePosition >= 0 -> {
                layoutManager?.scrollToPositionWithOffset(
                    (firstVisiblePosition - decision.removeCount).coerceAtLeast(0),
                    firstVisibleOffset,
                )
            }
        }
    }

    private fun scrollConsoleToBottom() {
        val lastPosition = consoleLogAdapter.itemCount - 1
        if (lastPosition < 0) return
        rvConsoleOutput.post { rvConsoleOutput.scrollToPosition(lastPosition) }
    }

    // ─── Авто-снижение яркости во время записи ───────────────────────────────
    private var savedBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var brightnessReduced: Boolean = false

    private fun applyReducedBrightness() {
        if (brightnessReduced) return
        runCatching {
            val attributes = window.attributes
            savedBrightness = attributes.screenBrightness
            attributes.screenBrightness = 0.15f // Экран остаётся читаемым без лишнего нагрева.
            window.attributes = attributes
            brightnessReduced = true
        }.onFailure { error ->
            android.util.Log.w("NekoFlash", "Unable to reduce screen brightness", error)
        }
    }

    private fun restoreBrightness() {
        if (!brightnessReduced) return
        runCatching {
            val attributes = window.attributes
            attributes.screenBrightness = savedBrightness
            window.attributes = attributes
        }.onFailure { error ->
            android.util.Log.w("NekoFlash", "Unable to restore screen brightness", error)
        }
        brightnessReduced = false
        savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    override fun onStop() {
        if (viewModelReady) viewModel.flushDiagnostics("ACTIVITY_BACKGROUND_FLUSH", terminal = false)
        endWelcomeSessionIfTaskClosing()
        super.onStop()
    }

    private fun endWelcomeSessionIfTaskClosing() {
        // Do not reset on rotation/recreation or normal backgrounding. A cold
        // process start resets the in-memory gate automatically; this branch
        // covers an explicitly finished/removed task while the process survives.
        if (isFinishing && !isChangingConfigurations) {
            OnboardingGate.endSession()
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && viewModelReady) {
            viewModel.flushDiagnostics("ACTIVITY_DESTROY", terminal = true)
        }
        // Android may destroy a removed/background task without leaving
        // isFinishing=true. Any non-configuration destruction ends the entry
        // session; a surviving process must therefore show Welcome next time.
        if (!isChangingConfigurations) {
            OnboardingGate.endSession()
        }

        miAuthExchangeJob?.cancel()
        miAuthExchangeJob = null
        flashProgressPanel?.visibility = View.GONE
        usbPermissionTimeouts.values.forEach(usbPermissionHandler::removeCallbacks)
        usbPermissionTimeouts.clear()
        modeSwitchHandler.removeCallbacksAndMessages(null)
        deviceOverviewHandler.removeCallbacksAndMessages(null)
        consoleRenderHandler.removeCallbacks(flushConsoleRenderRunnable)
        consoleRenderScheduled = false
        pendingConsoleSnapshot = null
        runCatching { unregisterReceiver(usbReceiver) }
            .onFailure { error ->
                if (error !is IllegalArgumentException) {
                    android.util.Log.w("NekoFlash", "USB receiver cleanup failed", error)
                }
            }
        super.onDestroy()
    }

    private val PARTITION_NAME_PATTERN = Regex("^[a-z0-9._-]{1,64}$")

    companion object {
        private const val USB_PERMISSION_TIMEOUT_MS = 30_000L
        private const val EXTRA_USB_INTENT_CONSUMED = "nekoflash_usb_intent_consumed"
        private const val STARTUP_USB_SCAN_DELAY_MS = 350L
        private const val MODE_SWITCH_SCAN_INTERVAL_MS = 750L
        private const val MODE_SWITCH_SCAN_ATTEMPTS = 16
        private const val PREFS_NAME = "settings"
        private const val PREF_LANGUAGE_TAG = "language_tag"
        private const val PREF_LAST_WINDOW = "last_window"
        private const val STATE_SELECTED_WINDOW = "state_selected_window"
    }
}
