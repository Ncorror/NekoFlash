package ru.forum.adbfastboottool

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
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
import android.text.Html
import android.text.InputType
import android.text.TextUtils
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
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private enum class MiAuthExchangeState { IDLE, LOADING, SUCCESS, ERROR }

    private lateinit var usbManager: UsbManager
    private lateinit var tvLog: TextView
    private lateinit var etCommand: EditText
    private lateinit var scrollViewLog: ScrollView
    // Состояние сворачиваемой консоли (по умолчанию свёрнута).
    private var consoleExpanded = false
    // Состояние полноэкранного терминала (вкладка).
    private var terminalAutoscroll = true
    private var terminalFilter = 0  // 0=все, 1=info, 2=warn, 3=error
    private var lastLogLines: List<String> = emptyList()
    private lateinit var tvStatus: TextView
    private var tvOtgStatus: TextView? = null
    private lateinit var tvOperationCenterStatus: TextView
    private lateinit var tvOperationCenterLastEvent: TextView
    private lateinit var tvOperationStepQueue: TextView
    private var flashProgressDialog: android.app.Dialog? = null
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

    private var safetyProfile = SafetyProfile.NOVICE
    private var proModeEnabled = false
    private var terminalReturnWindow = "home"
    private var restoringWindowState = false
    private var highRiskActionsUnlocked = false
    private var quickFlashExpertModeEnabled = false
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
                // selectModeSwitchCandidate уже гарантировал другой логический
                // профиль. Для настоящей смены режима разрешаем одну новую
                // попытку, даже если этот профиль ошибался в далёкой сессии.
                viewModel.allowModeSwitchUsbRetry(candidate)
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

    private enum class SafetyProfile {
        NOVICE,
        PRO
    }

    private sealed class TerminalAction {
        data object LocalStatus : TerminalAction()
        data object SelfTest : TerminalAction()
        data object SelfTestReport : TerminalAction()
        data object SelfTestForumReport : TerminalAction()
        data object OpenReportsFolder : TerminalAction()
        data class RawFastboot(val command: String) : TerminalAction()
        data class DestructiveFastboot(val command: String, val risk: String) : TerminalAction()
        data class DestructiveFastbootDownloadAndRun(val file: File, val commandAfterDownload: String, val risk: String) : TerminalAction()
        data class FastbootFlash(val partition: String, val file: File) : TerminalAction()
        data class FastbootDownloadAndRun(val file: File, val commandAfterDownload: String) : TerminalAction()
        data class FastbootLogicalInfo(val partition: String) : TerminalAction()
        data class FastbootFetch(val partition: String, val outputFile: File) : TerminalAction()
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

        tvLog = findViewById(R.id.tvLog)
        etCommand = findViewById(R.id.etCommand)
        scrollViewLog = findViewById(R.id.scrollViewLog)
        setupCollapsibleConsole()
        setupTerminal()
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
            lastLogLines = lines
            renderLog(lines)
            renderTerminalLog(lines)
            updateOperationCenter(lines)
        }

        viewModel.connectionState.observe(this) {
            refreshConnectionStatusLabel()
            updateDeviceOverview()
        }

        viewModel.connectionInfo.observe(this) { updateDeviceOverview() }
        viewModel.fastbootDiagnostics.observe(this) {
            // Диагностика приходит после connectionState — переобновим точный режим.
            refreshConnectionStatusLabel()
            updateDeviceOverview()
        }
        viewModel.fastbootPartitionInventory.observe(this) { snapshot ->
            // Read-only inventory is refreshed only by an explicit user action.
            findViewById<Button>(R.id.btnHomePartitions).isEnabled = snapshot != null
            updateDeviceOverview()
        }
        viewModel.adbPeerMode.observe(this) {
            // ADB transport один, но peer mode различается: system/recovery/sideload.
            refreshConnectionStatusLabel()
            updateDeviceOverview()
        }
        viewModel.diagnosticMode.observe(this) {
            refreshConnectionStatusLabel()
            updateDeviceOverview()
        }
        viewModel.readOnlyMutationLock.observe(this) {
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
                // v4: лог всегда виден, не нужно переключать вкладку
            }
            updateDeviceOverview()
            updateOperationCenter(viewModel.logSnapshot())
            updateSafetyProfileUi()
        }

        viewModel.operationSteps.observe(this) { steps -> renderOperationSteps(steps) }
        viewModel.operationProgress.observe(this) { progress -> renderFlashProgressDialog(progress) }

        registerUsbReceiver()
        registerImportLauncher()
        registerMiLoginLauncher()
        setupButtons()
        loadSafetyPreferences()
        updateSafetyProfileUi()
        buildSettingsPage()
        restoreWindowState(savedInstanceState)
        updateFlashQueueUi()
        updateDeviceOverview()
        checkPermissions()
        logBatteryOptimizationState()
        viewModel.log(getString(R.string.log_init_v20))
        viewModel.log("Версия приложения: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
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
        outState.putString(STATE_TERMINAL_RETURN_WINDOW, terminalReturnWindow)
        super.onSaveInstanceState(outState)
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnScan).setOnClickListener { updateOtgStatus(); scanForDevices() }
        findViewById<Button>(R.id.btnSafetyNovice).setOnClickListener { setSafetyProfile(SafetyProfile.NOVICE) }
        findViewById<Button>(R.id.btnSafetyPro).setOnClickListener { setSafetyProfile(SafetyProfile.PRO) }
        findViewById<Button>(R.id.btnSafetyHighRisk).setOnClickListener { toggleHighRiskActions() }
        // Импорт файла из угла блока прошивки (в контексте Fastboot).
        findViewById<View>(R.id.btnBlockImportFastboot).setOnClickListener { startImportFilePicker() }
        findViewById<View>(R.id.btnBlockImportQueue).setOnClickListener { startImportFilePicker() }
        // Режим перезагрузки в блоке прошивки — то же меню, что было на главной.
        findViewById<View>(R.id.btnFlashRebootMode).setOnClickListener { showRebootMenu() }
        // Тонкая кнопка терминала над плитками Fastboot.
        findViewById<View>(R.id.btnFastbootTerminal).setOnClickListener { switchTab("console") }
        findViewById<View>(R.id.btnFastbootDataSelfTest).setOnClickListener { showFastbootDataSelfTestDialog() }
        findViewById<View>(R.id.btnFastbootDataSharedStorageProbe).setOnClickListener {
            showFastbootSharedStorageProbeDialog()
        }
        findViewById<View>(R.id.btnFastbootDataQualifyImage).setOnClickListener {
            showFastbootDataQualificationFileSelector()
        }
        findViewById<View>(R.id.btnFastbootDataAutoMatrix).setOnClickListener {
            showFastbootAutoMatrixDialog()
        }
        findViewById<View>(R.id.btnFastbootDataContentProbe).setOnClickListener {
            showFastbootContentProbeFileSelector()
        }
        findViewById<View>(R.id.btnConsoleTerminal).setOnClickListener { switchTab("console") }
        findViewById<View>(R.id.btnTerminalMinimize).setOnClickListener { minimizeTerminal() }
        findViewById<Button>(R.id.btnHomeRefreshData).setOnClickListener { refreshDeviceDataFromUi() }
        findViewById<Button>(R.id.btnHomePartitions).setOnClickListener { showPartitionInventoryDialog() }
        findViewById<Button>(R.id.btnHomeOpenWorkspace).setOnClickListener { openWorkspaceFolder() }
        findViewById<Button>(R.id.btnHomeCopyWorkspace).setOnClickListener {
            copyTextToClipboard(
                "NekoFlash workspace",
                workspaceDisplayPath(),
                getString(R.string.home_workspace_path_copied)
            )
        }
        findViewById<Button>(R.id.btnHomeTerminal).setOnClickListener { switchTab("console") }
        findViewById<Button>(R.id.btnHomeQuickFlash).setOnClickListener { switchTab("fastboot") }
        findViewById<Button>(R.id.btnHomeSideload).setOnClickListener { switchTab("adb") }
        findViewById<Button>(R.id.btnHomeMiUnlock).setOnClickListener { switchTab("unlock") }
        findViewById<Button>(R.id.btnOperationCenterConsole).setOnClickListener { switchTab("console") }
        findViewById<View>(R.id.btnReportsMenu).setOnClickListener { showReportsMenu() }
        findViewById<Button>(R.id.btnOperationCenterCancel).setOnClickListener { viewModel.cancelActiveOperation() }
        findViewById<Button>(R.id.btnQueueBoot).setOnClickListener { chooseFlashQueueFile("boot") }
        findViewById<Button>(R.id.btnQueueInitBoot).setOnClickListener { chooseFlashQueueFile("init_boot") }
        findViewById<Button>(R.id.btnQueueVendorBoot).setOnClickListener { chooseFlashQueueFile("vendor_boot") }
        findViewById<Button>(R.id.btnQueueRecovery).setOnClickListener { chooseFlashQueueFile("recovery") }
        findViewById<Button>(R.id.btnQueueDtbo).setOnClickListener { chooseFlashQueueFile("dtbo") }
        findViewById<Button>(R.id.btnQueueClear).setOnClickListener { clearFlashQueue() }
        guardClick(R.id.btnQueueStart) { confirmFlashQueue() }

        // Slice C: Recovery-first UI. Expert targets are hidden by default and
        // all visible actions are resolved through QuickFlashTopologyCandidateBuilder.
        val expertSwitch = findViewById<SwitchMaterial>(R.id.switchQuickFlashExpert)
        expertSwitch.isChecked = false
        renderQuickFlashExpertMode(false)
        expertSwitch.setOnCheckedChangeListener { _, enabled ->
            renderQuickFlashExpertMode(enabled)
            viewModel.log(
                getString(
                    if (enabled) R.string.quick_flash_ui_enabled_log
                    else R.string.quick_flash_ui_disabled_log
                )
            )
        }
        findViewById<View>(R.id.btnFlashRecovery).setOnClickListener {
            startQuickFlashTargetFlow(QuickFlashTarget.RECOVERY)
        }
        findViewById<View>(R.id.btnFlashBoot).setOnClickListener {
            startQuickFlashTargetFlow(QuickFlashTarget.BOOT)
        }
        findViewById<View>(R.id.btnFlashInitBoot).setOnClickListener {
            startQuickFlashTargetFlow(QuickFlashTarget.INIT_BOOT)
        }
        findViewById<View>(R.id.btnFlashVendorBoot).setOnClickListener {
            startQuickFlashTargetFlow(QuickFlashTarget.VENDOR_BOOT)
        }
        findViewById<View>(R.id.btnFlashDtbo).setOnClickListener {
            startQuickFlashTargetFlow(QuickFlashTarget.DTBO)
        }
        findViewById<View>(R.id.btnFlashVbmeta).setOnClickListener {
            startQuickFlashTargetFlow(QuickFlashTarget.VBMETA)
        }
        findViewById<View>(R.id.btnFlashVendorKernelBoot).setOnClickListener {
            startQuickFlashTargetFlow(QuickFlashTarget.VENDOR_KERNEL_BOOT)
        }
        findViewById<View>(R.id.btnFlashManual).setOnClickListener {
            showManualQuickFlashTargetDialog()
        }
        viewModel.log(getString(R.string.quick_flash_legacy_queue_hidden))

        // Единое меню Reboot (BottomSheet) — собирает все варианты перезагрузки.
        findViewById<Button>(R.id.btnAdbSideload).setOnClickListener {
            showFileSelector(".zip") { file ->
                showSideloadConfirmation(file)
            }
        }
        // Импорт файла и проверка архива на вкладке ADB Sideload.
        findViewById<View>(R.id.btnSideloadImport).setOnClickListener { startImportFilePicker() }
        findViewById<View>(R.id.btnSideloadCheckArchive).setOnClickListener { showFirmwareAnalysisSelector() }

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
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            device?.let { cancelUsbPermissionTimeout(it.deviceId) }

            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                device?.let { takePendingUsbConnect(it) }
                viewModel.log("ОШИБКА: Доступ к USB отклонён пользователем")
                return
            }
            if (device == null) {
                viewModel.log("ОШИБКА: USB-устройство не передано системой")
                return
            }

            viewModel.log("Доступ к USB разрешён. Анализ интерфейсов...")
            val pending = takePendingUsbConnect(device)
            analyzeAndConnectDevice(device, pending)
        }
    }

    private fun handleUsbDetached(intent: Intent) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device == null) {
            viewModel.log("USB-устройство отключено: неизвестно")
            updateOtgStatus()
            return
        }

        pendingUsbCandidates.remove(device.deviceId)
        // A real OS detach is the only event that releases a poisoned Fastboot USB generation.
        viewModel.noteUsbDetached(device)
        if (viewModel.isCurrentUsbDevice(device)) {
            val previousSignature = viewModel.currentUsbLogicalSignature()
                ?: UsbDeviceInspector.selectPrimaryCandidate(device, allowGenericFastboot = true)?.logicalSignature
            val previousVendorId = viewModel.currentUsbVendorId() ?: device.vendorId
            viewModel.log("USB-устройство отключено: ${device.productName ?: device.deviceName}")
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

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        intent.putExtra(EXTRA_USB_INTENT_CONSUMED, true)

        if (device == null) {
            viewModel.log("⚠️ USB attach: система не передала устройство")
            return true
        }

        cancelStartupUsbDiscovery()
        stopModeSwitchWatch()
        val candidate = UsbDeviceInspector.selectPrimaryCandidate(device, allowGenericFastboot = false)
        if (candidate == null) {
            val manualCandidates = UsbDeviceInspector.findCandidates(device, includeGenericFastboot = true)
            if (manualCandidates.any { it.matchKind == UsbDeviceInspector.MatchKind.GENERIC_FASTBOOT }) {
                viewModel.log(
                    "⚠️ Найден только generic vendor USB-интерфейс. Автоподключение запрещено; " +
                        "нажмите «Поиск» и подтвердите устройство вручную."
                )
            } else {
                viewModel.log("⚠️ USB-устройство подключено, но ADB/Fastboot-интерфейс не найден")
            }
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
        if (viewModel.isUsbRetryQuarantined(candidate)) {
            if (candidate.mode == UsbDeviceInspector.Mode.FASTBOOT) {
                if (automatic) {
                    viewModel.logFileOnly(
                        "USB auto-connect skipped for quarantined Fastboot generation: ${candidate.logicalSignature}"
                    )
                } else {
                    viewModel.log("⛔ Повторное открытие этой Fastboot USB-сессии запрещено. Переподключите кабель/OTG.")
                }
                return
            }
            if (automatic) {
                viewModel.logFileOnly(
                    "USB auto-connect skipped after previous failure: ${candidate.logicalSignature}"
                )
                return
            }
        }

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
            ?: UsbDeviceInspector.selectPrimaryCandidate(device, allowGenericFastboot = false)

        if (candidate == null) {
            viewModel.log("ОШИБКА: Устройство не распознано как ADB/Fastboot")
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
                viewModel.log("ОШИБКА: нет ответа на запрос USB-доступа за 30 секунд. Переподключите OTG-кабель и нажмите «Поиск» ещё раз.")
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

        val candidates = UsbDeviceInspector.findSafeCandidates(usbManager.deviceList.values)
        val candidate = candidates.singleOrNull() ?: return
        if (viewModel.isUsbRetryQuarantined(candidate)) return

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
                viewModel.log("ОШИБКА: совместимые ADB/Fastboot USB-устройства не найдены")
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

        if (isSelfTestForumReportCommand(rawLower)) {
            viewModel.log("> $raw")
            runSelfTestForumReportFromUi()
            return
        }

        if (isSelfTestReportCommand(rawLower)) {
            viewModel.log("> $raw")
            runSelfTestReportFromUi()
            return
        }

        if (rawLower == "self-test" || rawLower == "selftest" || rawLower == "smoke-test" || rawLower == "doctor") {
            viewModel.log("> $raw")
            viewModel.runSelfTest()
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
            TerminalAction.SelfTest -> viewModel.runSelfTest()
            TerminalAction.SelfTestReport -> runSelfTestReportFromUi()
            TerminalAction.SelfTestForumReport -> runSelfTestForumReportFromUi()
            TerminalAction.OpenReportsFolder -> openReportsFolder()
            is TerminalAction.RawFastboot -> viewModel.runFastbootCommand(action.command)
            is TerminalAction.DestructiveFastboot -> confirmDestructiveFastbootCommand(action.command, action.risk)
            is TerminalAction.DestructiveFastbootDownloadAndRun -> confirmDestructiveFastbootDownloadAndRun(action.file, action.commandAfterDownload, action.risk)
            is TerminalAction.FastbootFlash -> viewModel.runFlash(action.partition, action.file)
            is TerminalAction.FastbootDownloadAndRun -> viewModel.runFastbootDownloadAndRun(action.file, action.commandAfterDownload)
            is TerminalAction.FastbootLogicalInfo -> viewModel.inspectFastbootLogicalPartition(action.partition)
            is TerminalAction.FastbootFetch -> viewModel.runFastbootFetch(action.partition, action.outputFile)
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
            TerminalAction.SelfTest -> viewModel.runSelfTest()
            TerminalAction.SelfTestReport -> runSelfTestReportFromUi()
            TerminalAction.SelfTestForumReport -> runSelfTestForumReportFromUi()
            TerminalAction.OpenReportsFolder -> openReportsFolder()
            is TerminalAction.AdbService -> viewModel.runAdbService(action.service)
            is TerminalAction.AdbShell -> viewModel.runAdbShell(action.command)
            is TerminalAction.AdbPush -> viewModel.runAdbPush(action.localFile, action.remotePath)
            is TerminalAction.AdbPull -> viewModel.runAdbPull(action.remotePath, action.localFile)
            is TerminalAction.AdbInstall -> viewModel.runAdbInstall(action.packageFile, action.options)
            is TerminalAction.AdbInstallMultiple -> viewModel.runAdbInstallMultiple(action.apkFiles, action.options)
            is TerminalAction.RawFastboot,
            is TerminalAction.DestructiveFastboot,
            is TerminalAction.DestructiveFastbootDownloadAndRun,
            is TerminalAction.FastbootFlash,
            is TerminalAction.FastbootDownloadAndRun,
            is TerminalAction.FastbootLogicalInfo,
            is TerminalAction.FastbootFetch -> Unit
        }
    }

    private fun parseFastbootCommand(cmd: String): TerminalAction? {
        val clean = cmd.trim()
        if (clean.isBlank()) return null
        val tokens = tokenizeCommandLine(clean)
        if (tokens.isEmpty()) return null
        val op = tokens[0].lowercase(Locale.US)

        if (warnIfBatchOrShellSyntax(op, clean)) return null

        return when (op) {
            "status", "devices" -> TerminalAction.LocalStatus
            "reports", "open-reports", "report-folder", "reports-folder" -> TerminalAction.OpenReportsFolder
            "self-test", "selftest", "smoke-test", "doctor" -> parseSelfTestAction(tokens)

            "-w", "--wipe" -> TerminalAction.DestructiveFastboot(
                "erase:userdata",
                "Флаг -w — это команда ПК-версии fastboot (стирает userdata, на устройствах со старой разметкой также cache). У протокола устройства такой wire-команды нет, поэтому она разворачивается в erase:userdata. ВСЕ пользовательские данные будут удалены."
            )

            "flash" -> {
                if (tokens.size < 3) {
                    viewModel.log("❌ Формат: fastboot flash <partition> <file.img>")
                    return null
                }
                val partition = tokens[1]
                val file = resolveTerminalFile(tokens[2]) ?: return null
                TerminalAction.FastbootFlash(partition, file)
            }

            "boot" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot boot <file.img>")
                    return null
                }
                val file = resolveTerminalFile(tokens[1]) ?: return null
                TerminalAction.FastbootDownloadAndRun(file, "boot")
            }

            "getvar" -> {
                val variable = tokens.drop(1).joinToString(" ").ifBlank { "all" }
                TerminalAction.RawFastboot("getvar:$variable")
            }

            "is-logical", "logical-info" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot $op <partition>")
                    return null
                }
                TerminalAction.FastbootLogicalInfo(tokens[1])
            }

            "create-logical-partition" -> {
                if (tokens.size < 3) {
                    viewModel.log("❌ Формат: fastboot create-logical-partition <partition> <size>")
                    return null
                }
                val partition = tokens[1]
                val size = parseFastbootSizeArgument(tokens[2]) ?: return null
                val wire = "create-logical-partition:$partition:$size"
                TerminalAction.DestructiveFastboot(wire, "Создание logical partition изменит metadata super-раздела. Обычно команда должна выполняться в fastbootd и только при точном понимании разметки устройства.")
            }

            "delete-logical-partition" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot delete-logical-partition <partition>")
                    return null
                }
                val partition = tokens[1]
                val wire = "delete-logical-partition:$partition"
                TerminalAction.DestructiveFastboot(wire, "Удаление logical partition фактически стирает раздел из metadata super. Неверный раздел может привести к незагружаемой системе.")
            }

            "resize-logical-partition" -> {
                if (tokens.size < 3) {
                    viewModel.log("❌ Формат: fastboot resize-logical-partition <partition> <size>")
                    return null
                }
                val partition = tokens[1]
                val size = parseFastbootSizeArgument(tokens[2]) ?: return null
                val wire = "resize-logical-partition:$partition:$size"
                TerminalAction.DestructiveFastboot(wire, "Изменение размера logical partition меняет metadata super и может завершиться FAIL при нехватке места. Перед запуском проверьте слот, размер и наличие fastbootd.")
            }

            "update-super" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot update-super <super.img> [wipe] [superPartition]")
                    return null
                }
                val file = resolveTerminalFile(tokens[1]) ?: return null
                val wipe = tokens.drop(2).any { it.equals("wipe", ignoreCase = true) || it.equals("--wipe", ignoreCase = true) }
                val explicitSuper = tokens.drop(2).firstOrNull { !it.equals("wipe", ignoreCase = true) && !it.equals("--wipe", ignoreCase = true) }
                val superName = explicitSuper ?: viewModel.currentFastbootDiagnostics()?.superPartitionName ?: "super"
                val wire = "update-super:$superName" + if (wipe) ":wipe" else ""
                TerminalAction.DestructiveFastbootDownloadAndRun(file, wire, "update-super переписывает metadata super-раздела из образа lpmake. Режим wipe удаляет существующие logical partitions. Обычно требуется fastbootd, корректный super image и разблокированный bootloader.")
            }

            "gsi" -> {
                val sub = tokens.getOrNull(1)?.lowercase(Locale.US)
                when (sub) {
                    "status" -> TerminalAction.RawFastboot("gsi:status")
                    "wipe", "disable" -> TerminalAction.DestructiveFastboot(
                        "gsi:$sub",
                        "Команда gsi $sub изменяет состояние Dynamic System Update. Она разрешается только в подтверждённом fastbootd и проходит общий snapshot/control-plane preflight."
                    )
                    else -> {
                        viewModel.log("❌ Формат: fastboot gsi <wipe|disable|status>")
                        null
                    }
                }
            }

            "wipe-super" -> {
                viewModel.log("⛔ fastboot wipe-super не отправляется как raw-команда.")
                viewModel.log("ℹ️ В desktop fastboot это host-side helper, который строит/использует super_empty image. В NekoFlash используйте только явный update-super с заранее проверенным образом.")
                null
            }

            "snapshot-update" -> {
                val action = tokens.getOrNull(1)?.lowercase(Locale.US)
                when (action) {
                    null -> TerminalAction.RawFastboot("snapshot-update")
                    "cancel" -> TerminalAction.DestructiveFastboot(
                        "snapshot-update:cancel",
                        "Отмена Virtual A/B snapshot update удаляет pending update state и может оставить устройство незагружаемым до полной перепрошивки. Команда разрешается только при свежем подтверждённом состоянии SNAPSHOTTED."
                    )
                    "merge" -> TerminalAction.DestructiveFastboot(
                        "snapshot-update:merge",
                        "Принудительное завершение Virtual A/B merge меняет update state. Команда разрешается только при свежем состоянии MERGING и после выполнения повторно проверяется."
                    )
                    else -> {
                        viewModel.log("❌ Формат: fastboot snapshot-update [cancel|merge]")
                        null
                    }
                }
            }

            "fetch" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot fetch <partition> [out.img]")
                    return null
                }
                val partition = tokens[1]
                val defaultName = "$partition-fetch.img"
                val output = resolveTerminalOutputFile(tokens.getOrNull(2).orEmpty(), defaultName) ?: return null
                TerminalAction.FastbootFetch(partition, output)
            }

            "erase" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot erase <partition>")
                    return null
                }
                TerminalAction.DestructiveFastboot("erase:${tokens[1]}", "Команда erase удалит содержимое раздела ${tokens[1]}. Восстановление возможно только прошивкой корректного образа или полной прошивкой устройства.")
            }

            "format" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot format <partition>")
                    return null
                }
                TerminalAction.DestructiveFastboot("format:${tokens[1]}", "Команда format переформатирует раздел ${tokens[1]} и может удалить пользовательские данные или служебную разметку.")
            }

            "set_active" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot set_active <a|b>")
                    return null
                }
                TerminalAction.DestructiveFastboot("set_active:${tokens[1].removePrefix("_")}", "Команда set_active переключит активный слот. При несовместимой прошивке устройство может перестать загружаться.")
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
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot flashing <unlock|lock|get_unlock_ability>")
                    return null
                }
                val sub = tokens[1].lowercase(Locale.US)
                when (sub) {
                    "unlock_critical", "lock_critical" -> {
                        viewModel.log("⛔ $clean заблокирован: NekoFlash пока не умеет доказуемо проверить critical lock-state после команды.")
                        null
                    }
                    "unlock", "lock" -> TerminalAction.DestructiveFastboot(
                        clean,
                        "Команда $clean меняет постоянное состояние загрузчика и может стереть данные. Unlock не считается успехом до нового Fastboot-подключения и проверки unlocked=yes; relock блокируется safety policy до доказуемой проверки stock/verified state."
                    )
                    else -> TerminalAction.RawFastboot(clean)
                }
            }

            "oem" -> {
                if (isPotentiallyDestructiveFastbootCommand(clean)) {
                    TerminalAction.DestructiveFastboot(clean, "OEM-команды зависят от производителя. Эта команда похожа на destructive/service-команду и может стереть данные, изменить блокировку или критические настройки загрузчика.")
                } else {
                    TerminalAction.RawFastboot(clean)
                }
            }

            "update", "flashall" -> {
                viewModel.log("⚠️ fastboot $op требует пакетной логики desktop-fastboot и здесь не эмулируется. Используйте отдельные flash-команды или ADB Sideload.")
                null
            }

            else -> {
                if (isPotentiallyDestructiveFastbootCommand(clean)) {
                    TerminalAction.DestructiveFastboot(clean, "Команда выглядит как запись, очистка, форматирование или изменение критического состояния загрузчика. Проверьте модель, слот и смысл команды перед запуском.")
                } else {
                    TerminalAction.RawFastboot(clean)
                }
            }
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
            "self-test", "selftest", "smoke-test", "doctor" -> parseSelfTestAction(tokens)

            "shell" -> {
                val shellCommand = clean.substringAfterWord("shell").trim()
                TerminalAction.AdbShell(shellCommand)
            }

            "exec" -> {
                val execCommand = clean.substringAfterWord("exec").trim()
                if (execCommand.isBlank()) {
                    viewModel.log("❌ Формат: adb exec <command>")
                    null
                } else {
                    TerminalAction.AdbService("exec:$execCommand")
                }
            }

            "reboot" -> {
                val target = tokens.getOrNull(1)?.lowercase(Locale.US).orEmpty()
                TerminalAction.AdbService(if (target.isBlank() || target == "system") "reboot:" else "reboot:$target")
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
                    viewModel.log("❌ Формат: adb $op <service>, например adb raw shell:getprop")
                    null
                } else {
                    TerminalAction.AdbService(service)
                }
            }

            "logcat" -> TerminalAction.AdbShell(clean)
            "getprop", "setprop", "pm", "am", "cmd", "settings", "wm", "input", "svc", "dumpsys", "cat", "ls", "cd", "pwd", "id", "su", "sh" ->
                TerminalAction.AdbShell(clean)

            "push" -> {
                if (tokens.size < 3) {
                    viewModel.log("❌ Формат: adb push <local-file> <remote-path>")
                    null
                } else {
                    val localPath = resolveTerminalInputPath(tokens[1]) ?: return null
                    val remoteArg = tokens[2]
                    val remotePath = if (localPath.isFile && remoteArg.endsWith("/")) remoteArg + localPath.name else remoteArg
                    TerminalAction.AdbPush(localPath, remotePath)
                }
            }

            "pull" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: adb pull <remote-path> [local-file]")
                    null
                } else {
                    val remotePath = tokens[1]
                    val defaultName = remotePath.substringAfterLast('/').ifBlank { "adb-pull.bin" }
                    val localFile = resolveTerminalOutputFile(tokens.getOrNull(2).orEmpty(), defaultName) ?: return null
                    TerminalAction.AdbPull(remotePath, localFile)
                }
            }

            "install" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: adb install [-r] [-d] [-g] <local.apk|local.apks|local.xapk>")
                    null
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
        if (tokens.size < 3) {
            viewModel.log("❌ Формат: adb install-multiple [-r] [-d] [-g] <base.apk> <split1.apk> [split2.apk...]")
            return null
        }

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


    private fun confirmDestructiveFastbootCommand(command: String, risk: String) {
        if (!ensureHighRiskAllowed("fastboot $command")) return
        showTypedDangerConfirmation(
            title = getString(R.string.dialog_destructive_fastboot_title),
            message = getString(R.string.dialog_destructive_fastboot_message, command, risk),
            requiredPhrase = dangerPhraseForCommand(command),
            logLabel = "destructive fastboot: $command"
        ) {
            viewModel.log("⚠️ Typed-confirmed destructive Fastboot command: $command")
            if (command.startsWith("create-logical-partition:", ignoreCase = true) ||
                command.startsWith("delete-logical-partition:", ignoreCase = true) ||
                command.startsWith("resize-logical-partition:", ignoreCase = true) ||
                command.startsWith("update-super:", ignoreCase = true)
            ) {
                viewModel.runFastbootLogicalPartitionCommand(command)
            } else {
                viewModel.runFastbootCommand(command)
            }
        }
    }

    private fun confirmDestructiveFastbootDownloadAndRun(file: File, commandAfterDownload: String, risk: String) {
        if (!ensureHighRiskAllowed("fastboot $commandAfterDownload")) return
        showTypedDangerConfirmation(
            title = getString(R.string.dialog_destructive_fastboot_title),
            message = getString(R.string.dialog_destructive_fastboot_message, "$commandAfterDownload ← ${file.name}", risk),
            requiredPhrase = dangerPhraseForCommand(commandAfterDownload),
            logLabel = "destructive fastboot download+command: $commandAfterDownload ← ${file.name}"
        ) {
            viewModel.log("⚠️ Typed-confirmed destructive Fastboot download+command: $commandAfterDownload ← ${file.name}")
            viewModel.runFastbootDownloadAndRun(file, commandAfterDownload)
        }
    }

    private fun showTypedDangerConfirmation(
        title: String,
        message: String,
        requiredPhrase: String,
        logLabel: String,
        onConfirmed: () -> Unit
    ) {
        val input = EditText(this).apply {
            isSingleLine = true
            hint = requiredPhrase
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSelectAllOnFocus(true)
        }
        val body = TextView(this).apply {
            text = getString(R.string.typed_confirm_message, message, requiredPhrase)
            setTextIsSelectable(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, 0, pad, 0)
            addView(body)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .setPositiveButton(getString(R.string.start_upper), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val typed = input.text?.toString()?.trim().orEmpty()
                if (typed == requiredPhrase) {
                    dialog.dismiss()
                    onConfirmed()
                } else {
                    input.error = getString(R.string.typed_confirm_error, requiredPhrase)
                    viewModel.log(getString(R.string.typed_confirm_failed_log, logLabel))
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun dangerPhraseForCommand(command: String): String {
        val clean = command.trim().lowercase(Locale.US)
        return when {
            "unlock" in clean || "lock" in clean -> "LOCK"
            "wipe" in clean || clean.startsWith("erase") || clean.startsWith("format") -> "WIPE"
            clean.startsWith("update-super") || "update-super" in clean -> "SUPER"
            else -> "CONFIRM"
        }
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

    private fun isPotentiallyDestructiveFastbootCommand(command: String): Boolean {
        val clean = command.trim().lowercase(Locale.US)
        val destructivePrefixes = listOf(
            "erase:", "erase ",
            "format:", "format ",
            "set_active:", "set_active ",
            "create-logical-partition:", "create-logical-partition ",
            "delete-logical-partition:", "delete-logical-partition ",
            "resize-logical-partition:", "resize-logical-partition ",
            "update-super:", "update-super ",
            "wipe-super", "snapshot-update",
            "flashing unlock", "flashing lock",
            "flashing unlock_critical", "flashing lock_critical",
            "oem unlock", "oem lock"
        )
        if (destructivePrefixes.any { clean.startsWith(it) }) return true
        val riskyTokens = listOf(" erase", " wipe", " format", " unlock", " lock", " factory", " reset")
        return clean.startsWith("oem ") && riskyTokens.any { clean.contains(it) }
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
        val bytes = value * multiplier
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
                viewModel.log("ОШИБКА: системный выбор файла не вернул URI")
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Не все проводники выдают persistable-доступ. Для немедленного копирования достаточно временного доступа.
            }
            importFirmwareFile(uri)
        }
    }

    private fun registerMiLoginLauncher() {
        miLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                viewModel.log("Вход в Mi-аккаунт отменён")
                return@registerForActivityResult
            }
            val passToken = result.data?.getStringExtra("passToken")
            val deviceId = result.data?.getStringExtra("deviceId")
            val userId = result.data?.getStringExtra("userId")
            if (passToken.isNullOrEmpty() || deviceId.isNullOrEmpty() || userId.isNullOrEmpty()) {
                viewModel.log("❌ Вход в Mi-аккаунт: не получены данные авторизации")
                return@registerForActivityResult
            }
            viewModel.log("🔑 Вход выполнен (ID: $userId). Получение unlockApi-сессии...")
            miAuthExchangeJob?.cancel()
            miAuthExchangeState = MiAuthExchangeState.LOADING
            miAuthExchangeJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { MiAccountClient.exchangeToken(passToken, userId, deviceId) }
                }
                result.onSuccess { auth ->
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
            viewModel.log("ОШИБКА: не удалось открыть системный выбор файла: ${e.message}")
        }
    }

    private fun ensureWorkspaceReady(): Boolean {
        if (::workspacePath.isInitialized && workspacePath.exists()) return true
        viewModel.log("ОШИБКА: рабочая папка ещё не готова. Выдайте доступ ко всем файлам и повторите.")
        checkPermissions()
        return false
    }

    private fun importFirmwareFile(uri: Uri) {
        if (!ensureWorkspaceReady()) return

        val displayName = sanitizeImportedFileName(queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}")
        val lower = displayName.lowercase(Locale.US)
        val allowed = lower.endsWith(".img") || lower.endsWith(".zip") || lower.endsWith(".bin") ||
            lower.endsWith(".tgz") || lower.endsWith(".tar") || lower.endsWith(".tar.gz") ||
            lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".obb") ||
            lower.endsWith(".sha256") || lower.endsWith(".md5")
        if (!allowed) {
            viewModel.log("ОШИБКА: импорт разрешён только для .img, .zip, .tgz, .tar, .tar.gz, .bin, .apk, .apks, .xapk, .obb, .sha256 и .md5. Выбран файл: $displayName")
            return
        }

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
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("не удалось открыть входной поток")
                val copy = HashUtils.copyToFileVerified(
                    input = input,
                    target = temp,
                    expectedSize = expectedSize
                )
                if (!copy.ok) throw IllegalStateException(copy.message)
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("не удалось подготовить конечный путь")
                }
                if (!temp.renameTo(target)) {
                    throw IllegalStateException("не удалось атомарно завершить импорт")
                }
                viewModel.log("✅ Файл импортирован и проверен: /sdcard/Download/$folderName/${target.name} (${formatFileSize(target.length())})")
                viewModel.log("Import SHA-256: ${copy.destinationSha256}")
            } catch (e: Exception) {
                temp.delete()
                target.delete()
                viewModel.log("ОШИБКА: не удалось импортировать файл: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }


    private fun queryFileSize(uri: Uri): Long? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0).takeIf { it >= 0L }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                uri.lastPathSegment?.substringAfterLast('/')
            }
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/')
        } finally {
            cursor?.close()
        }
    }

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
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    // Многоуровневый фолбэк для прошивок без точечного экрана.
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e2: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
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
                    viewModel.log("ОШИБКА: Нет прав на чтение памяти")
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
            viewModel.log("ОШИБКА: Не удалось создать папку ${workspaceDisplayPath()}")
            return
        }
        viewModel.log("Рабочая папка: ${workspaceDisplayPath()}")
        viewModel.configureLogDirectory(workspacePath)
        updateDeviceOverview()
    }

    /** Человекочитаемый путь рабочей папки для логов и диалогов. */
    private fun workspaceDisplayPath(): String = "/sdcard/Download/$folderName"

    private data class FastbootDataSelfTestOption(
        val sizeBytes: Long,
        val mode: FastbootProtocol.DataTransportMode
    )

    private fun diagnosticDataTransportModes(): List<FastbootProtocol.DataTransportMode> = listOf(
        FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST,
        FastbootProtocol.DataTransportMode.SYNC_BULK,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_64K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_256K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_16K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_64K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_128K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_256K
    )

    private fun nativeUsbfsDiagnosticModes(): List<FastbootProtocol.DataTransportMode> = listOf(
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_64K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_256K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_16K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_64K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_128K,
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_256K
    )

    private fun showFastbootAutoMatrixDialog() {
        val product = viewModel.fastbootProtocol?.compatibilityProduct?.trim().orEmpty().ifBlank { "unknown" }
        val sizeBytes = 100L * 1024L * 1024L
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_auto_matrix_title))
            .setMessage(getString(R.string.fastboot_data_auto_matrix_message, product, formatFileSize(sizeBytes)))
            .setPositiveButton(getString(R.string.fastboot_data_auto_matrix_run)) { _, _ ->
                viewModel.runFastbootAutoNativeUsbfsMatrix(sizeBytes, nativeUsbfsDiagnosticModes())
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun showFastbootContentProbeFileSelector() {
        if (!ensureWorkspaceReady()) return
        val files = workspacePath.walkTopDown()
            .maxDepth(8)
            .filter { it.isFile && it.canRead() && it.extension.equals("img", ignoreCase = true) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
            .toList()
        if (files.isEmpty()) {
            viewModel.log("ОШИБКА: Не найдены .img файлы для content probe в ${workspaceDisplayPath()}")
            return
        }
        val labels = files.map { file ->
            val relative = runCatching { file.relativeTo(workspacePath).path }.getOrElse { file.name }
            "$relative · ${formatFileSize(file.length())}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_content_probe_title))
            .setItems(labels) { _, which -> showFastbootContentProbeTransportDialog(files[which]) }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun showFastbootContentProbeTransportDialog(file: File) {
        val modes = nativeUsbfsDiagnosticModes().toTypedArray()
        val labels = modes.map(::fastbootDataTransportLabel).toTypedArray()
        val product = viewModel.fastbootProtocol?.compatibilityProduct?.trim()?.lowercase(Locale.US).orEmpty()
        var selectedIndex = if (product == "onyx") {
            modes.indexOf(FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_64K).coerceAtLeast(0)
        } else {
            0
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_content_probe_title))
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                confirmFastbootContentProbe(file, modes[selectedIndex])
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun confirmFastbootContentProbe(file: File, mode: FastbootProtocol.DataTransportMode) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_content_probe_title))
            .setMessage(
                getString(
                    R.string.fastboot_data_content_probe_message,
                    file.name,
                    formatFileSize(file.length()),
                    fastbootDataTransportLabel(mode)
                )
            )
            .setPositiveButton(getString(R.string.fastboot_data_content_probe_run)) { _, _ ->
                viewModel.runFastbootImagePrefixProbe(file, mode)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun showFastbootDataSelfTestDialog() {
        val options = FastbootProtocol.SAFE_DATA_SELF_TEST_SIZES_BYTES
            .sorted()
            .flatMap { size ->
                diagnosticDataTransportModes().map { mode -> FastbootDataSelfTestOption(size, mode) }
            }
        val labels = options.map { option ->
            getString(
                R.string.fastboot_data_self_test_option,
                formatFileSize(option.sizeBytes),
                fastbootDataTransportLabel(option.mode)
            )
        }.toTypedArray()

        var selectedIndex = 0
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_self_test_dialog_title))
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                confirmFastbootDataSelfTest(options[selectedIndex])
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun confirmFastbootDataSelfTest(option: FastbootDataSelfTestOption) {
        val product = viewModel.fastbootProtocol?.compatibilityProduct?.trim().orEmpty().ifBlank { "unknown" }
        val transport = fastbootDataTransportLabel(option.mode)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_self_test_confirm_title))
            .setMessage(
                getString(
                    R.string.fastboot_data_self_test_confirm_message,
                    product,
                    formatFileSize(option.sizeBytes),
                    transport
                )
            )
            .setPositiveButton(getString(R.string.fastboot_data_self_test_run)) { _, _ ->
                viewModel.runFastbootDataSelfTest(option.sizeBytes, option.mode)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun showFastbootSharedStorageProbeDialog() {
        if (!ensureWorkspaceReady()) return
        val options = FastbootProtocol.SAFE_DATA_SELF_TEST_SIZES_BYTES
            .sorted()
            .flatMap { size ->
                diagnosticDataTransportModes().map { mode -> FastbootDataSelfTestOption(size, mode) }
            }
        val labels = options.map { option ->
            getString(
                R.string.fastboot_data_self_test_option,
                formatFileSize(option.sizeBytes),
                fastbootDataTransportLabel(option.mode)
            )
        }.toTypedArray()

        var selectedIndex = options.indexOfFirst {
            it.sizeBytes == 100L * 1024L * 1024L &&
                it.mode == FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST
        }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_shared_probe_dialog_title))
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                confirmFastbootSharedStorageProbe(options[selectedIndex])
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun confirmFastbootSharedStorageProbe(option: FastbootDataSelfTestOption) {
        val product = viewModel.fastbootProtocol?.compatibilityProduct?.trim().orEmpty().ifBlank { "unknown" }
        val fileName = "fastboot-shared-storage-probe-${option.sizeBytes}.img"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_shared_probe_confirm_title))
            .setMessage(
                getString(
                    R.string.fastboot_data_shared_probe_confirm_message,
                    product,
                    fileName,
                    formatFileSize(option.sizeBytes),
                    fastbootDataTransportLabel(option.mode),
                    workspaceDisplayPath()
                )
            )
            .setPositiveButton(getString(R.string.fastboot_data_shared_probe_run)) { _, _ ->
                viewModel.runFastbootSharedStorageDataProbe(option.sizeBytes, option.mode, workspacePath)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun showFastbootDataQualificationFileSelector() {
        if (!ensureWorkspaceReady()) return
        val files = workspacePath.walkTopDown()
            .maxDepth(8)
            .filter { it.isFile && it.canRead() && it.extension.equals("img", ignoreCase = true) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
            .toList()
        if (files.isEmpty()) {
            viewModel.log("ОШИБКА: Не найдены .img файлы для size-matched DATA qualification в ${workspaceDisplayPath()}")
            return
        }
        val labels = files.map { file ->
            val relative = runCatching { file.relativeTo(workspacePath).path }.getOrElse { file.name }
            "$relative · ${formatFileSize(file.length())}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_qualify_transport_title))
            .setItems(labels) { _, which -> showFastbootDataQualificationTransportDialog(files[which]) }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun showFastbootDataQualificationTransportDialog(file: File) {
        val modes = diagnosticDataTransportModes().toTypedArray()
        val labels = modes.map(::fastbootDataTransportLabel).toTypedArray()
        val product = viewModel.fastbootProtocol?.compatibilityProduct?.trim()?.lowercase(Locale.US).orEmpty()
        var selectedIndex = if (product == "onyx") {
            modes.indexOf(FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_64K).coerceAtLeast(0)
        } else {
            0
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_qualify_transport_title))
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                confirmFastbootDataFileQualification(file, modes[selectedIndex])
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun confirmFastbootDataFileQualification(
        file: File,
        mode: FastbootProtocol.DataTransportMode
    ) {
        val product = viewModel.fastbootProtocol?.compatibilityProduct?.trim().orEmpty().ifBlank { "unknown" }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fastboot_data_qualify_confirm_title))
            .setMessage(
                getString(
                    R.string.fastboot_data_qualify_confirm_message,
                    product,
                    file.name,
                    formatFileSize(file.length()),
                    fastbootDataTransportLabel(mode)
                )
            )
            .setPositiveButton(getString(R.string.fastboot_data_qualify_run)) { _, _ ->
                viewModel.runFastbootDataFileQualification(file, mode)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun fastbootDataTransportLabel(mode: FastbootProtocol.DataTransportMode): String = when (mode) {
        FastbootProtocol.DataTransportMode.ASYNC_USB_REQUEST -> getString(R.string.fastboot_data_transport_async)
        FastbootProtocol.DataTransportMode.SYNC_BULK -> getString(R.string.fastboot_data_transport_sync)
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_64K -> getString(R.string.fastboot_data_transport_native_usbfs_single_64k)
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_SINGLE_256K -> getString(R.string.fastboot_data_transport_native_usbfs_single_256k)
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_16K -> getString(R.string.fastboot_data_transport_native_usbfs_pipeline_16k)
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_64K -> getString(R.string.fastboot_data_transport_native_usbfs_pipeline_64k)
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_128K -> getString(R.string.fastboot_data_transport_native_usbfs_pipeline_128k)
        FastbootProtocol.DataTransportMode.NATIVE_USBFS_PIPELINE_256K -> getString(R.string.fastboot_data_transport_native_usbfs_pipeline_256k)
    }

    private fun showFileSelector(extension: String, onFileSelected: (File) -> Unit) {
        if (!::workspacePath.isInitialized || !workspacePath.exists()) {
            viewModel.log("ОШИБКА: Папка не инициализирована. Выдайте разрешения.")
            return
        }
        // FIX #8: сортируем по дате изменения — свежий импорт всегда первый
        val files = workspacePath
            .listFiles { _, name -> name.lowercase().endsWith(extension) }
            ?.filter { it.isFile && it.canRead() }
            ?.sortedByDescending { it.lastModified() }
            ?.toTypedArray()

        if (files.isNullOrEmpty()) {
            viewModel.log("ОШИБКА: Нет файлов *$extension в папке $folderName. Нажмите «Импорт», чтобы скопировать файл через системный выбор.")
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
            viewModel.log("ОШИБКА: Нет Fastboot-соединения для перезагрузки")
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
            setBackgroundColor(android.graphics.Color.parseColor("#121A24"))
            setPadding(0, dp(8), 0, dp(16))
        }
        // Заголовок
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.layout_reboot_menu)
            setTextColor(android.graphics.Color.parseColor("#E9782B"))
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        })
        items.forEach { (label, cmd) ->
            container.addView(android.widget.TextView(this).apply {
                text = label
                setTextColor(android.graphics.Color.parseColor("#F3F6FA"))
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(24), dp(16), dp(24), dp(16))
                isClickable = true
                setOnClickListener {
                    viewModel.runFastbootCommand(cmd)
                    switchTab("console")
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
        val modeLabel = DiagnosticModePolicy.state(viewModel.currentDiagnosticMode()).userLabel
        val readOnlyLabel = if (viewModel.isReadOnlyMutationLockEnabled()) "READ-ONLY: ВКЛ" else "READ-ONLY: ВЫКЛ"
        val items = listOf(
            "ПРЕДТЕСТОВАЯ ПРОВЕРКА" to { runDiagnosticReadinessFromUi() },
            "РЕЖИМ: $modeLabel" to { cycleDiagnosticModeFromUi() },
            readOnlyLabel to { toggleDiagnosticReadOnlyFromUi() },
            "СКОПИРОВАТЬ КРАТКИЙ ИТОГ" to { copyDiagnosticSummary() },
            getString(R.string.reports_open_folder) to { openReportsFolder() },
            getString(R.string.reports_forum_zip) to { createForumReport() },
            getString(R.string.reports_log_actions) to { showLogActions() },
            getString(R.string.reports_selftest) to { runSelfTestReportFromUi() },
            getString(R.string.reports_selftest_forum) to { runSelfTestForumReportFromUi() }
        )
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#121A24"))
            setPadding(0, dp(8), 0, dp(16))
        }
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.reports_sheet_title)
            setTextColor(android.graphics.Color.parseColor("#E9782B"))
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        })
        items.forEach { (label, action) ->
            container.addView(android.widget.TextView(this).apply {
                text = label
                setTextColor(android.graphics.Color.parseColor("#F3F6FA"))
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

    /**
     * Единое меню настроек (BottomSheet): профиль безопасности (выбор из 3 +
     * high-risk toggle для Expert), язык, разрешения, оптимизация батареи.
     * Вызывает существующие методы — скрытые кнопки профиля остаются дублёрами,
     * а updateSafetyProfileUi() продолжает обновлять их состояние.
     */
    /**
     * Наполняет страницу Настроек (pageSettings) программно. Переиспользует те же
     * действия, что и старое меню: профиль безопасности, high-risk, язык, разрешения,
     * батарея, плюс сервисные пункты (очистка папки, about). Вызывается из onCreate
     * и после смены профиля, чтобы отметки были актуальны.
     */
    /**
     * Страница «Разблокировка загрузчика» (Mi Unlock). Каркас этапа 1 —
     * объясняет процесс и показывает шаги. Логин в Mi-аккаунт и сама
     * разблокировка добавляются на следующих этапах.
     */
    private fun confirmAndRunMiUnlock(auth: MiAccountClient.AuthResult) {
        val input = android.widget.EditText(this).apply {
            hint = "UNLOCK"
            setTextColor(android.graphics.Color.parseColor("#F3F6FA"))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Разблокировать загрузчик?")
            .setMessage("Это СОТРЁТ все данные устройства и снимет защиту загрузчика. Действие необратимо.\n\nДля подтверждения введите UNLOCK:")
            .setView(input)
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .setPositiveButton("Разблокировать") { _, _ ->
                if (input.text.toString().trim().equals("UNLOCK", ignoreCase = true)) {
                    switchTab("console")
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
                } else {
                    Toast.makeText(this, "Подтверждение не совпало", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun buildUnlockPage() {
        val container = findViewById<android.widget.LinearLayout>(R.id.unlockContainer)
        container.removeAllViews()

        fun title(text: String, color: String = "#E9782B") = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor(color))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(16), dp(6), dp(8))
            letterSpacing = 0.08f
        }
        fun card(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#121A24"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), android.graphics.Color.parseColor("#324052"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        fun body(text: String, color: String = "#AEB8C5") = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor(color))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }

        container.addView(title("🔓 РАЗБЛОКИРОВКА ЗАГРУЗЧИКА", "#E9782B"))

        container.addView(card().apply {
            addView(body("Разблокировка загрузчика Xiaomi через официальный протокол Mi Unlock. Нужна для прошивки recovery, boot и других поддерживаемых образов.", "#F3F6FA"))
            addView(body("⚠️ Разблокировка СТИРАЕТ все данные устройства и снимает часть гарантий защиты. Выполняйте осознанно."))
        })

        container.addView(title("ТРЕБОВАНИЯ"))
        container.addView(card().apply {
            addView(body("1. Mi-аккаунт, привязанный к устройству (Настройки → Mi аккаунт)."))
            addView(body("2. Получено одобрение разблокировки в официальном приложении/настройках Xiaomi (привязка аккаунта 7+ дней)."))
            addView(body("3. Устройство переведено в режим Fastboot и подключено по OTG."))
        })

        container.addView(title("ВХОД В MI-АККАУНТ"))
        val auth = miAuth
        if (auth == null) {
            container.addView(card().apply {
                addView(body("Войдите в свой Mi-аккаунт (официальная страница Xiaomi), чтобы продолжить разблокировку.", "#F3F6FA"))
                addView(android.widget.Button(this@MainActivity).apply {
                    text = "🔑 Войти в Mi-аккаунт"
                    isAllCaps = false
                    setTextColor(android.graphics.Color.parseColor("#080D13"))
                    setBackgroundColor(android.graphics.Color.parseColor("#E98B49"))
                    setOnClickListener { startMiLogin() }
                })
            })
        } else {
            container.addView(card().apply {
                addView(body("✅ Авторизован. ID: ${auth.userId}", "#69C779"))
                addView(body("Регион: ${auth.region} • dataCenterZone: ${auth.dataCenterZone} (${auth.zoneSource})", "#AEB8C5"))
                addView(android.widget.Button(this@MainActivity).apply {
                    text = "🌍 Сменить dataCenterZone"
                    isAllCaps = false
                    setTextColor(android.graphics.Color.parseColor("#F3F6FA"))
                    setBackgroundColor(android.graphics.Color.parseColor("#192431"))
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
                    setTextColor(android.graphics.Color.parseColor("#F3F6FA"))
                    setBackgroundColor(android.graphics.Color.parseColor("#192431"))
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

            container.addView(title("РАЗБЛОКИРОВКА", "#E9782B"))
            container.addView(card().apply {
                addView(body("Устройство должно быть в режиме Fastboot и подключено по OTG. Убедитесь, что аккаунт одобрен для разблокировки.", "#F3F6FA"))
                addView(body("⚠️ Все данные устройства будут стёрты.", "#F2B766"))
                addView(android.widget.Button(this@MainActivity).apply {
                    text = "🔓 Разблокировать загрузчик"
                    isAllCaps = false
                    setTextColor(android.graphics.Color.parseColor("#080D13"))
                    setBackgroundColor(android.graphics.Color.parseColor("#E9782B"))
                    setOnClickListener { confirmAndRunMiUnlock(auth) }
                })
            })
        }

        container.addView(title("ПРОЦЕСС (как будет)"))
        container.addView(card().apply {
            addView(body("• Вход в Mi-аккаунт (официальная страница Xiaomi)"))
            addView(body("• Проверка статуса одобрения"))
            addView(body("• Чтение токена устройства (fastboot)"))
            addView(body("• Запрос ключа разблокировки у Mi API"))
            addView(body("• Выполнение fastboot oem unlock"))
        })
    }

    private fun buildSettingsPage() {
        val container = findViewById<android.widget.LinearLayout>(R.id.settingsContainer)
        container.removeAllViews()

        fun sectionTitle(text: String) = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor("#E9782B"))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(18), dp(6), dp(8))
            letterSpacing = 0.1f
        }
        fun card(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#121A24"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), android.graphics.Color.parseColor("#324052"))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun row(text: String, sub: String? = null, onClick: () -> Unit) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(20), dp(14), dp(20), dp(14))
            addView(android.widget.TextView(this@MainActivity).apply {
                this.text = text
                setTextColor(android.graphics.Color.parseColor("#F3F6FA"))
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            if (sub != null) addView(android.widget.TextView(this@MainActivity).apply {
                this.text = sub
                setTextColor(android.graphics.Color.parseColor("#AEB8C5"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            setOnClickListener { onClick() }
        }

        // ── Профиль безопасности ──
        container.addView(sectionTitle(getString(R.string.settings_section_profile)))
        val profileCard = card()
        val profiles = listOf(
            SafetyProfile.NOVICE to getString(R.string.safety_profile_novice),
            SafetyProfile.PRO to getString(R.string.safety_profile_pro)
        )
        profiles.forEach { (profile, label) ->
            val mark = if (safetyProfile == profile) "●  " else "○  "
            profileCard.addView(row(mark + label) { setSafetyProfile(profile) })
        }
        if (safetyProfile == SafetyProfile.PRO) {
            val hrLabel = if (highRiskActionsUnlocked)
                getString(R.string.safety_high_risk_unlocked)
            else
                getString(R.string.safety_high_risk_locked)
            profileCard.addView(row("⚠\uFE0F  $hrLabel") { toggleHighRiskActions() })
        }
        container.addView(profileCard)

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
                } catch (_: Exception) {}
                viewModel.log(getString(R.string.settings_clear_workspace_done, count))
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


    private fun showFirmwareAnalysisSelector() {
        if (!ensureWorkspaceReady()) return
        val files = workspacePath
            .listFiles { file -> ImageInspector.isSupportedFirmwareFile(file) }
            ?.filter { it.isFile && it.canRead() }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.toTypedArray()

        if (files.isNullOrEmpty()) {
            viewModel.log("ОШИБКА: Нет файлов для анализа в /sdcard/Download/$folderName. Нажмите «Импорт» или скопируйте .img/.zip вручную.")
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_analysis_title))
            .setItems(files.map { "🔍 ${it.name}" }.toTypedArray()) { _, which ->
                showFirmwareAnalysisResult(files[which])
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun showFirmwareAnalysisResult(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val analysis = ImageInspector.analyze(file, includeHashes = true)
                viewModel.log(getString(R.string.analysis_header))
                analysis.toDisplayText().lines().forEach { line ->
                    if (line.isNotBlank()) viewModel.log(line)
                }
                runOnUiThread {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(getString(R.string.analysis_file_title, file.name))
                        .setMessage(analysis.toDisplayText())
                        .setPositiveButton(getString(R.string.to_log_upper)) { _, _ -> viewModel.analyzeFirmwareFile(file) }
                        .setNeutralButton(getString(R.string.copy_sha256_upper)) { _, _ ->
                            val sha = analysis.sha256
                            if (sha != null) copyTextToClipboard("SHA-256", sha, getString(R.string.sha256_copied))
                        }
                        .setNegativeButton(getString(R.string.close_upper), null)
                        .show()
                }
            } catch (e: Exception) {
                viewModel.log("ОШИБКА: не удалось проанализировать файл: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun renderQuickFlashExpertMode(enabled: Boolean) {
        quickFlashExpertModeEnabled = enabled
        findViewById<View>(R.id.containerQuickFlashExpertTargets).visibility =
            if (enabled) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvQuickFlashExpertState).setText(
            if (enabled) R.string.quick_flash_expert_on else R.string.quick_flash_expert_off
        )
    }

    private fun startQuickFlashTargetFlow(
        target: QuickFlashTarget,
        manualPartitionName: String? = null,
        manualPartitionConfirmation: String? = null
    ) {
        if (!QuickFlashUiPolicy.isVisible(target, quickFlashExpertModeEnabled)) {
            blockQuickFlash(getString(R.string.quick_flash_expert_required))
            return
        }
        showFileSelector(".img") { file ->
            val inventorySessionId = viewModel.currentTransportSessionId()?.takeIf { it.isNotBlank() }
            val inventory = viewModel.currentFastbootPartitionInventory()
            if (inventorySessionId == null || inventory == null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.quick_flash_inventory_required_title))
                    .setMessage(getString(R.string.quick_flash_inventory_required_message))
                    .setNegativeButton(getString(R.string.cancel_upper), null)
                    .setPositiveButton(getString(R.string.quick_flash_refresh)) { _, _ ->
                        refreshDeviceDataFromUi()
                    }
                    .show()
                return@showFileSelector
            }

            viewModel.log(getString(R.string.quick_flash_hashing, file.name))
            lifecycleScope.launch {
                val sha256 = withContext(Dispatchers.IO) {
                    runCatching { HashUtils.calculateSha256(file) }
                }.getOrElse { error ->
                    blockQuickFlash("SHA-256: ${error.message ?: error.javaClass.simpleName}")
                    return@launch
                }

                if (viewModel.currentTransportSessionId() != inventorySessionId) {
                    blockQuickFlash(getString(R.string.quick_flash_session_changed))
                    return@launch
                }

                val result = QuickFlashTopologyCandidateBuilder.buildFromInventory(
                    QuickFlashTopologyCandidateBuilder.InventoryRequest(
                        inventory = inventory,
                        imageDisplayName = file.name,
                        expertModeEnabled = quickFlashExpertModeEnabled,
                        manualPartitionName = manualPartitionName,
                        sessionBroken = viewModel.fastbootProtocol?.isSessionBroken == true
                    )
                )
                val candidates = result.candidates.filter { it.target == target }
                if (!result.canChooseTarget || candidates.isEmpty()) {
                    val technical = result.errors.joinToString().ifBlank { result.status.name }
                    blockQuickFlash(
                        getString(R.string.quick_flash_no_candidate) + "\n\n" + technical
                    )
                    return@launch
                }
                showQuickFlashCandidateSelector(
                    target = target,
                    candidates = candidates,
                    file = file,
                    sha256 = sha256,
                    expectedSessionId = inventorySessionId,
                    manualPartitionConfirmation = manualPartitionConfirmation
                )
            }
        }
    }

    private fun showManualQuickFlashTargetDialog() {
        if (!quickFlashExpertModeEnabled) {
            blockQuickFlash(getString(R.string.quick_flash_expert_required))
            return
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val first = EditText(this).apply {
            hint = getString(R.string.quick_flash_manual_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val repeated = EditText(this).apply {
            hint = getString(R.string.quick_flash_manual_repeat_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(first)
            addView(repeated)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.quick_flash_manual_title))
            .setMessage(getString(R.string.quick_flash_manual_message))
            .setView(body)
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                val partition = first.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
                val confirmation = repeated.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
                when {
                    partition != confirmation -> blockQuickFlash(getString(R.string.quick_flash_manual_mismatch))
                    !QuickFlashPlanValidator.isManualPartitionAllowed(partition) -> {
                        blockQuickFlash(getString(R.string.quick_flash_manual_forbidden))
                    }
                    else -> startQuickFlashTargetFlow(
                        target = QuickFlashTarget.MANUAL,
                        manualPartitionName = partition,
                        manualPartitionConfirmation = confirmation
                    )
                }
            }
            .show()
    }

    private fun showQuickFlashCandidateSelector(
        target: QuickFlashTarget,
        candidates: List<QuickFlashCandidate>,
        file: File,
        sha256: String,
        expectedSessionId: String,
        manualPartitionConfirmation: String?
    ) {
        if (candidates.size == 1) {
            showQuickFlashPlanConfirmation(
                target,
                candidates,
                candidates.single(),
                file,
                sha256,
                expectedSessionId,
                manualPartitionConfirmation
            )
            return
        }
        val items = candidates.map(::quickFlashCandidateLabel).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.quick_flash_candidate_title, target.name.lowercase(Locale.US)))
            .setMessage(
                getString(
                    R.string.quick_flash_candidate_message,
                    file.name,
                    formatFileSize(file.length()),
                    sha256
                )
            )
            .setItems(items) { _, which ->
                showQuickFlashPlanConfirmation(
                    target,
                    candidates,
                    candidates[which],
                    file,
                    sha256,
                    expectedSessionId,
                    manualPartitionConfirmation
                )
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun showQuickFlashPlanConfirmation(
        target: QuickFlashTarget,
        candidates: List<QuickFlashCandidate>,
        candidate: QuickFlashCandidate,
        file: File,
        sha256: String,
        expectedSessionId: String,
        manualPartitionConfirmation: String?
    ) {
        if (!ensureGuidedFlashAllowed(candidate.basePartition)) return
        if (viewModel.fastbootProtocol?.isConnected != true) {
            blockQuickFlash(getString(R.string.error_no_fastboot))
            return
        }
        if (viewModel.currentTransportSessionId() != expectedSessionId) {
            blockQuickFlash(getString(R.string.quick_flash_session_changed))
            return
        }
        val sessionId = expectedSessionId
        val diagnostics = viewModel.currentFastbootDiagnostics()
        if (diagnostics?.unlocked?.equals("no", ignoreCase = true) == true) {
            blockQuickFlash("Bootloader unlocked = no")
            return
        }
        val deviceLabel = viewModel.currentConnectionInfo()
            ?.takeIf { it.isNotBlank() }
            ?: diagnostics?.product?.takeIf { it.isNotBlank() }
            ?: "Fastboot device"
        val validation = QuickFlashPlanValidator.validate(
            QuickFlashPlanRequest(
                deviceSessionId = sessionId,
                deviceDisplayName = deviceLabel,
                target = target,
                selectedPartitionName = candidate.partitionName,
                candidates = candidates,
                imageUri = file.toURI().toString(),
                imageDisplayName = file.name,
                imageSizeBytes = file.length(),
                imageSha256 = sha256,
                expertModeEnabled = quickFlashExpertModeEnabled,
                manualPartitionConfirmation = manualPartitionConfirmation
            )
        )
        val plan = validation.plan
        if (!validation.canProceed || plan == null) {
            blockQuickFlash(validation.errors.joinToString())
            return
        }
        val ticketIssue = QuickFlashMutationGate.issueConfirmation(
            plan = plan,
            confirmationId = UUID.randomUUID().toString()
        )
        val confirmationTicket = ticketIssue.ticket
        if (!ticketIssue.issued || confirmationTicket == null) {
            blockQuickFlash(ticketIssue.errors.joinToString())
            return
        }

        val preflight = PreflightValidator.validateFlash(
            context = this,
            partition = candidate.basePartition,
            file = file,
            currentSlot = diagnostics?.currentSlot,
            safePartitions = setOf(candidate.basePartition)
        )
        preflight.toDisplayText().lines().forEach { line ->
            if (line.isNotBlank()) viewModel.log(line)
        }
        val slotLabel = quickFlashSlotLabel(candidate.slot)
        val evidenceLabel = quickFlashEvidenceLabel(candidate.evidence)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.quick_flash_plan_title))
            .setIcon(R.drawable.ic_nf_recovery_green)
            .setMessage(
                getString(
                    R.string.quick_flash_plan_message,
                    plan.deviceDisplayName,
                    plan.imageDisplayName,
                    formatFileSize(plan.imageSizeBytes),
                    plan.imageSha256,
                    plan.target.name.lowercase(Locale.US),
                    plan.partitionName,
                    slotLabel,
                    evidenceLabel,
                    plan.commandPreview,
                    plan.confirmationFingerprint().take(16),
                    preflight.toDisplayText()
                )
            )
            .setNegativeButton(getString(R.string.cancel_upper), null)

        if (preflight.canProceed) {
            builder.setPositiveButton(getString(R.string.preflight_proceed)) { _, _ ->
                val execute = {
                    when {
                        viewModel.currentTransportSessionId() != plan.deviceSessionId -> {
                            blockQuickFlash(getString(R.string.quick_flash_session_changed))
                        }
                        plan.target.isExpert && !quickFlashExpertModeEnabled -> {
                            blockQuickFlash(getString(R.string.quick_flash_expert_disabled_after_plan))
                        }
                        !QuickFlashPlanValidator.validatePlan(plan).canProceed -> {
                            blockQuickFlash(getString(R.string.quick_flash_no_candidate))
                        }
                        else -> viewModel.runConfirmedQuickFlash(
                            plan = plan,
                            sourceFile = file,
                            ticket = confirmationTicket,
                            expertModeEnabled = quickFlashExpertModeEnabled
                        )
                    }
                }
                if (PreflightValidator.requiresDoubleConfirm(plan.basePartition)) {
                    confirmCriticalFlash(plan.basePartition, execute)
                } else {
                    execute()
                }
            }
        } else {
            builder.setPositiveButton(getString(R.string.preflight_blocked), null)
        }
        builder.show()
    }

    private fun quickFlashCandidateLabel(candidate: QuickFlashCandidate): String =
        getString(
            R.string.quick_flash_candidate_label,
            candidate.partitionName,
            quickFlashSlotLabel(candidate.slot),
            quickFlashEvidenceLabel(candidate.evidence)
        )

    private fun quickFlashSlotLabel(slot: QuickFlashSlot): String = getString(
        when (slot) {
            QuickFlashSlot.UNSLOTTED -> R.string.quick_flash_candidate_slot_unslotted
            QuickFlashSlot.SLOT_A -> R.string.quick_flash_candidate_slot_a
            QuickFlashSlot.SLOT_B -> R.string.quick_flash_candidate_slot_b
        }
    )

    private fun quickFlashEvidenceLabel(evidence: QuickFlashCandidateEvidence): String = getString(
        when (evidence) {
            QuickFlashCandidateEvidence.POINT_QUERY -> R.string.quick_flash_candidate_evidence_point
            QuickFlashCandidateEvidence.INVENTORY,
            QuickFlashCandidateEvidence.UNCONFIRMED -> R.string.quick_flash_candidate_evidence_inventory
        }
    )

    private fun blockQuickFlash(message: String) {
        viewModel.log("⛔ Quick Flash: $message")
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.quick_flash_blocked_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun showFlashConfirmation(partition: String, file: File) {
        if (!ensureGuidedFlashAllowed(partition)) return
        if (viewModel.fastbootProtocol?.isConnected != true) {
            viewModel.log(getString(R.string.error_no_fastboot))
            return
        }
        val diagnostics = viewModel.currentFastbootDiagnostics()
        if (diagnostics?.unlocked?.equals("no", ignoreCase = true) == true) {
            viewModel.log("⛔ Bootloader locked: fastboot flash заблокирован. Разблокируйте загрузчик или используйте только команды без записи разделов.")
            MaterialAlertDialogBuilder(this)
                .setTitle("Fastboot flash заблокирован")
                .setMessage("Устройство сообщает bootloader unlocked = no. Приложение не будет выполнять fastboot flash, чтобы не создавать ложное ощущение безопасной прошивки. Диагностика, reboot, getvar, erase/format/raw-команды остаются в полном терминале.")
                .setPositiveButton(getString(R.string.close_upper), null)
                .show()
            return
        }

        // ─── ПРЕДПОЛЁТНАЯ ПРОВЕРКА (Foolproof) ───
        val preflight = PreflightValidator.validateFlash(
            context = this,
            partition = partition,
            file = file,
            currentSlot = diagnostics?.currentSlot,
            safePartitions = FastbootProtocol.TYPICAL_FLASH_PARTITIONS
        )
        preflight.toDisplayText().lines().forEach { if (it.isNotBlank()) viewModel.log(it) }

        val sizeMb = "%.2f".format(file.length().toDouble() / 1024.0 / 1024.0)

        // V5.6.7: slot-count может быть не просто «не прочитан», а не поддерживаться
        // однослотовым bootloader (пример: Poco X3 Pro / vayu). Поэтому при отсутствии
        // доказанного A/B не показываем выбор A/B в UI. Реальную цель всё равно
        // подтверждает FastbootSlotResolver по has-slot и partition-size probes.
        val slotCountRaw = diagnostics?.slotCount?.trim()
        val slotCount = slotCountRaw?.toIntOrNull()
        val normalizedCurrentSlot = diagnostics?.currentSlot
            ?.trim()?.removePrefix("_")?.lowercase(Locale.US)
            ?.takeIf { it == "a" || it == "b" }
        val explicitFileSlot = FastbootSlotResolver.explicitSlotFromFileName(file.name)
        val potentialSlotPartition = isSlottedPartition(partition)
        val abKnown = potentialSlotPartition && ((slotCount ?: 0) >= 2 || normalizedCurrentSlot != null)
        val targetSelectionAvailable = abKnown && explicitFileSlot == null
        val needsRuntimeTargetResolution = potentialSlotPartition && !targetSelectionAvailable
        val slotHint = when {
            !potentialSlotPartition -> ""
            abKnown -> "\n\n" + getString(R.string.slot_hint_ab_device)
            slotCount == 1 -> "\n\n" + getString(R.string.slot_hint_single_device)
            else -> "\n\n" + getString(R.string.slot_hint_runtime_topology)
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.preflight_title))
            .setIcon(R.drawable.ic_nf_recovery_green)
            .setMessage(
                "Раздел: $partition\n" +
                    "Файл: ${file.name}\n" +
                    "Размер: $sizeMb MB\n\n" +
                    preflight.toDisplayText() + slotHint
            )
            .setNegativeButton(getString(R.string.cancel_upper), null)

        if (preflight.canProceed) {
            val positiveLabel = if (targetSelectionAvailable) {
                getString(R.string.flash_choose_target_button)
            } else {
                getString(R.string.preflight_proceed)
            }
            builder.setPositiveButton(positiveLabel) { _, _ ->
                if (targetSelectionAvailable) {
                    showFlashTargetSelector(partition, file)
                } else if (needsRuntimeTargetResolution) {
                    val execute = { viewModel.runFlashTarget(partition, file, FastbootSlotResolver.RequestedSlot.ACTIVE) }
                    if (PreflightValidator.requiresDoubleConfirm(partition)) {
                        confirmCriticalFlash(partition, execute)
                    } else {
                        execute()
                    }
                } else if (PreflightValidator.requiresDoubleConfirm(partition)) {
                    confirmCriticalFlash(partition) { viewModel.runFlash(partition, file) }
                } else {
                    viewModel.runFlash(partition, file)
                }
            }
        } else {
            // Критическая ошибка — кнопка прошивки недоступна
            builder.setPositiveButton(getString(R.string.preflight_blocked), null)
        }
        builder.show()
    }

    private fun showFlashTargetSelector(partition: String, file: File) {
        val currentSlot = viewModel.currentFastbootDiagnostics()?.currentSlot
            ?.trim()?.removePrefix("_")?.lowercase(Locale.US)
            ?.takeIf { it == "a" || it == "b" }

        val explicitFileSlot = FastbootSlotResolver.explicitSlotFromFileName(file.name)
        val choices: List<Pair<String, FastbootSlotResolver.RequestedSlot>> = when (explicitFileSlot) {
            "a" -> listOf(getString(R.string.flash_target_slot_a) to FastbootSlotResolver.RequestedSlot.SLOT_A)
            "b" -> listOf(getString(R.string.flash_target_slot_b) to FastbootSlotResolver.RequestedSlot.SLOT_B)
            else -> listOf(
                getString(R.string.flash_target_active, currentSlot?.uppercase(Locale.US) ?: "?") to FastbootSlotResolver.RequestedSlot.ACTIVE,
                getString(R.string.flash_target_slot_a) to FastbootSlotResolver.RequestedSlot.SLOT_A,
                getString(R.string.flash_target_slot_b) to FastbootSlotResolver.RequestedSlot.SLOT_B,
                getString(R.string.flash_target_both) to FastbootSlotResolver.RequestedSlot.BOTH
            )
        }
        val items = choices.map { it.first }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.flash_target_selector_title, partition))
            .setItems(items) { _, which ->
                val request = choices[which].second
                val execute = { viewModel.runFlashTarget(partition, file, request) }
                if (PreflightValidator.requiresDoubleConfirm(partition)) {
                    confirmCriticalFlash(partition, execute)
                } else {
                    execute()
                }
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    /** Typed-confirm для опасных разделов (boot/recovery/vbmeta/...). */
    private fun confirmCriticalFlash(partition: String, onConfirmed: () -> Unit) {
        val phrase = when (partition.trim().lowercase(Locale.US)) {
            "vbmeta", "vbmeta_system", "vbmeta_vendor" -> "VBMETA"
            "boot", "init_boot", "vendor_boot", "recovery" -> "FLASH"
            else -> "CONFIRM"
        }
        showTypedDangerConfirmation(
            title = getString(R.string.double_check_title),
            message = getString(R.string.double_check_message, partition),
            requiredPhrase = phrase,
            logLabel = "critical partition flash: $partition"
        ) {
            onConfirmed()
        }
    }

    private fun showSideloadConfirmation(file: File) {
        if (viewModel.adbProtocol?.isConnected != true) {
            viewModel.log("ОШИБКА: Нет ADB-соединения")
            return
        }
        val sizeMb = "%.2f".format(file.length().toDouble() / 1024.0 / 1024.0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_sideload_confirm_title))
            .setMessage(
                "Архив: ${file.name}\n" +
                    "Размер: $sizeMb MB\n\n" +
                    "Перед отправкой приложение проверит SHA-256/MD5 и сравнит их с .sha256/.md5, если такие файлы лежат рядом.\n\n" +
                    "Целевое устройство должно быть в recovery/sideload mode."
            )
            .setPositiveButton(getString(R.string.start_upper)) { _, _ -> viewModel.runSideload(file) }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }


    private fun chooseFlashQueueFile(partition: String) {
        showFileSelector(".img") { file ->
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
                val state = when (item.verification) {
                    FlashQueueVerification.VERIFIED -> "✓"
                    FlashQueueVerification.VERIFYING -> "⏳"
                    FlashQueueVerification.NEEDS_REVALIDATION -> "↻"
                    else -> "⛔"
                }
                "$state ${item.partition} ← ${item.displayName} (${formatFileSize(item.expectedSizeBytes)})$warning"
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
        val partitions = draft.items.map { it.partition }
        if (partitions.any { isHighRiskPartition(it) } && !ensureHighRiskAllowed("flash queue with high-risk partition")) return
        if (draft.isEmpty) {
            viewModel.log(getString(R.string.flash_queue_empty_log))
            return
        }
        if (!draft.canRequestExecution) {
            viewModel.revalidateFlashQueueDraft()
            val problems = draft.items.filterNot { it.isVerified }.joinToString("\n") { item ->
                "${item.partition}: ${item.verificationDetail ?: item.verification.name}"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Очередь требует проверки")
                .setMessage("Перед прошивкой каждый файл должен повторно пройти проверку доступа, размера и SHA-256.\n\n$problems\n\nПроверка запущена. После её завершения подтвердите операцию заново.")
                .setPositiveButton(getString(R.string.close_upper), null)
                .show()
            return
        }
        if (viewModel.fastbootProtocol?.isConnected != true) {
            viewModel.log(getString(R.string.error_no_fastboot))
            return
        }
        val diagnostics = viewModel.currentFastbootDiagnostics()
        if (diagnostics?.unlocked?.equals("no", ignoreCase = true) == true) {
            viewModel.log("⛔ Bootloader locked: очередь fastboot flash заблокирована.")
            MaterialAlertDialogBuilder(this)
                .setTitle("Очередь прошивки заблокирована")
                .setMessage("Устройство сообщает bootloader unlocked = no. Приложение не будет выполнять команды fastboot flash из очереди. Диагностика и остальные терминальные команды остаются доступны.")
                .setPositiveButton(getString(R.string.close_upper), null)
                .show()
            return
        }

        val queueText = draft.items.joinToString("\n") { item ->
            val guessed = guessPartitionFromFileName(item.displayName)
            val warning = if (guessed != null && guessed != item.partition) {
                "  ⚠ ${getString(R.string.flash_queue_filename_warning, item.displayName, guessed, item.partition)}"
            } else ""
            "${item.partition} ← ${item.displayName} (${formatFileSize(item.expectedSizeBytes)})\nSHA-256: ${item.expectedSha256}$warning"
        }
        val extraWarning = buildString {
            if (diagnostics == null) append("\n\n⚠ Fastboot-данные устройства ещё не обновлены. Лучше нажмите «Данные» на главной странице.")
            if (partitions.any { it.equals("vbmeta", ignoreCase = true) }) {
                append("\n\n⚠ В очереди есть vbmeta. Убедитесь, что образ подходит именно для этого устройства.")
            }
            append("\n\nПосле подтверждения файлы будут ещё раз сверены по размеру и SHA-256. Восстановленный черновик никогда не запускается автоматически.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.flash_queue_confirm_title))
            .setMessage(getString(R.string.flash_queue_confirm_message, queueText) + extraWarning)
            .setPositiveButton(getString(R.string.flash_upper)) { _, _ ->
                val phrase = if (partitions.any { it.equals("vbmeta", ignoreCase = true) }) "VBMETA" else "FLASH QUEUE"
                showTypedDangerConfirmation(
                    title = getString(R.string.flash_queue_confirm_title),
                    message = getString(R.string.flash_queue_typed_confirm_message, queueText),
                    requiredPhrase = phrase,
                    logLabel = "flash queue (${draft.items.size} item(s))"
                ) {
                    viewModel.executeFlashQueueDraftAfterConfirmation()
                }
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun guessPartitionFromFileName(fileName: String): String? {
        return PreflightValidator.guessPartitionFromFileName(fileName)
    }

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
            rawLower == "self-test reports" ||
            rawLower == "selftest reports" ||
            rawLower == "adb reports" ||
            rawLower == "fastboot reports"
    }

    private fun parseSelfTestAction(tokens: List<String>): TerminalAction {
        val sub = tokens.getOrNull(1)?.lowercase(Locale.US)
        return when (sub) {
            "report", "export", "log", "txt", "json" -> TerminalAction.SelfTestReport
            "forum", "zip", "bundle", "full", "support" -> TerminalAction.SelfTestForumReport
            else -> TerminalAction.SelfTest
        }
    }

    private fun isSelfTestReportCommand(rawLower: String): Boolean {
        return rawLower == "self-test report" ||
            rawLower == "self-test export" ||
            rawLower == "self-test log" ||
            rawLower == "self-test txt" ||
            rawLower == "self-test json" ||
            rawLower == "selftest report" ||
            rawLower == "selftest export" ||
            rawLower == "selftest log" ||
            rawLower == "selftest txt" ||
            rawLower == "selftest json" ||
            rawLower == "smoke-test report" ||
            rawLower == "smoke-test export" ||
            rawLower == "smoke-test log" ||
            rawLower == "smoke-test txt" ||
            rawLower == "smoke-test json" ||
            rawLower == "doctor report" ||
            rawLower == "doctor export" ||
            rawLower == "doctor log" ||
            rawLower == "doctor txt" ||
            rawLower == "doctor json" ||
            rawLower == "adb self-test report" ||
            rawLower == "fastboot self-test report"
    }

    private fun isSelfTestForumReportCommand(rawLower: String): Boolean {
        return rawLower == "self-test forum" ||
            rawLower == "self-test zip" ||
            rawLower == "self-test bundle" ||
            rawLower == "self-test full" ||
            rawLower == "self-test support" ||
            rawLower == "selftest forum" ||
            rawLower == "selftest zip" ||
            rawLower == "selftest bundle" ||
            rawLower == "selftest full" ||
            rawLower == "selftest support" ||
            rawLower == "smoke-test forum" ||
            rawLower == "smoke-test zip" ||
            rawLower == "smoke-test bundle" ||
            rawLower == "smoke-test full" ||
            rawLower == "smoke-test support" ||
            rawLower == "doctor forum" ||
            rawLower == "doctor zip" ||
            rawLower == "doctor bundle" ||
            rawLower == "doctor full" ||
            rawLower == "doctor support" ||
            rawLower == "adb self-test forum" ||
            rawLower == "fastboot self-test forum"
    }

    private fun runDiagnosticReadinessFromUi() {
        if (!ensureWorkspaceReady()) return
        viewModel.runDiagnosticReadinessCheck { result ->
            runOnUiThread {
                val body = buildString {
                    appendLine(result.summary())
                    appendLine()
                    result.checks.forEach { check ->
                        val icon = when (check.severity) {
                            DiagnosticReadiness.Severity.PASS -> "✅"
                            DiagnosticReadiness.Severity.WARNING -> "⚠️"
                            DiagnosticReadiness.Severity.BLOCKER -> "⛔"
                        }
                        append(icon).append(' ').append(check.message)
                        check.detail?.takeIf { it.isNotBlank() }?.let { append("\n   ").append(it) }
                        appendLine()
                    }
                }.trim()
                MaterialAlertDialogBuilder(this)
                    .setTitle(if (result.ready) "Готово к тесту" else "Проверка не пройдена")
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.close_upper), null)
                    .setNeutralButton("КОПИРОВАТЬ") { _, _ ->
                        copyTextToClipboard("NekoFlash readiness", body, "Итог проверки скопирован")
                    }
                    .show()
            }
        }
    }

    private fun cycleDiagnosticModeFromUi() {
        val mode = viewModel.cycleDiagnosticMode()
        val state = DiagnosticModePolicy.state(mode)
        viewModel.log("ℹ️ Выбран режим: ${state.userLabel}")
        refreshConnectionStatusLabel()
    }

    private fun toggleDiagnosticReadOnlyFromUi() {
        val enabled = !viewModel.isReadOnlyMutationLockEnabled()
        viewModel.setReadOnlyMutationLock(enabled, "User toggle from Reports menu")
        refreshConnectionStatusLabel()
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
            appendLine("Diagnostic mode: ${viewModel.currentDiagnosticMode()}")
            appendLine("READ-ONLY: ${viewModel.isReadOnlyMutationLockEnabled()}")
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

    private fun runSelfTestReportFromUi() {
        if (!ensureWorkspaceReady()) return
        viewModel.runSelfTestReportArtifacts { artifacts ->
            runOnUiThread { showSelfTestReportDialog(artifacts) }
        }
    }

    private fun showSelfTestReportDialog(artifacts: DeviceViewModel.SelfTestReportArtifacts) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Self-test отчёт создан")
            .setMessage("Файлы сохранены:\n${artifacts.textFile.absolutePath}\n${artifacts.jsonFile.absolutePath}\n\nОтчёты санитизированы: серийники, приватные пути и host-идентификаторы замаскированы. TXT удобно читать человеку, JSON удобно прикладывать к баг-репорту.")
            .setPositiveButton(getString(R.string.share_upper)) { _, _ -> shareGenericFile(artifacts.textFile, "text/plain", "ADB Fastboot Tool self-test report") }
            .setNeutralButton("ОТКРЫТЬ REPORTS") { _, _ -> openReportsFolder() }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun runSelfTestForumReportFromUi() {
        if (!ensureWorkspaceReady()) return
        viewModel.runSelfTestReportArtifacts { artifacts ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val report = ForumReportManager.createReport(
                        context = this@MainActivity,
                        usbManager = usbManager,
                        workspace = workspacePath,
                        currentLogFile = viewModel.currentLogFile(),
                        compactLogFiles = viewModel.currentLogFiles(),
                        traceLogFiles = viewModel.currentTraceLogFiles(),
                        sessionSummary = viewModel.currentDiagnosticSessionSummary(),
                        partitionInventory = viewModel.currentFastbootPartitionInventory(),
                        usbSessionSnapshot = viewModel.currentUsbSessionSnapshot(),
                        buildId = viewModel.currentBuildId(),
                        diagnosticMode = viewModel.currentDiagnosticMode(),
                        readOnlyMutationLock = viewModel.isReadOnlyMutationLockEnabled(),
                        transportSessionId = viewModel.currentTransportSessionId(),
                        visibleLogLines = viewModel.logSnapshot(),
                        connectionInfo = viewModel.currentConnectionInfo(),
                        fastbootDiagnostics = viewModel.currentFastbootDiagnostics(),
                        adbDiagnostics = viewModel.currentAdbDiagnostics(),
                        debugLogging = viewModel.isDebugLoggingEnabled(),
                        extraFiles = listOf(
                            artifacts.textFile to "self-test/selftest.txt",
                            artifacts.jsonFile to "self-test/selftest.json"
                        )
                    )
                    val verification = DiagnosticArchiveVerifier.verify(
                        report,
                        requireTrace = viewModel.currentDiagnosticMode() != DiagnosticModePolicy.Mode.NORMAL || viewModel.isDebugLoggingEnabled()
                    )
                    viewModel.log("✅ Self-test ZIP для форума создан: ${report.absolutePath} (privacy: sanitized)")
                    viewModel.log("✅ ${verification.message}")
                    runOnUiThread { showForumReportDialog(report, verification) }
                } catch (e: Exception) {
                    viewModel.log("ОШИБКА: не удалось создать ZIP с self-test: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }


    private fun createForumReport() {
        if (!ensureWorkspaceReady()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val report = ForumReportManager.createReport(
                    context = this@MainActivity,
                    usbManager = usbManager,
                    workspace = workspacePath,
                    currentLogFile = viewModel.currentLogFile(),
                    compactLogFiles = viewModel.currentLogFiles(),
                    traceLogFiles = viewModel.currentTraceLogFiles(),
                    sessionSummary = viewModel.currentDiagnosticSessionSummary(),
                    partitionInventory = viewModel.currentFastbootPartitionInventory(),
                    usbSessionSnapshot = viewModel.currentUsbSessionSnapshot(),
                    buildId = viewModel.currentBuildId(),
                    diagnosticMode = viewModel.currentDiagnosticMode(),
                    readOnlyMutationLock = viewModel.isReadOnlyMutationLockEnabled(),
                    transportSessionId = viewModel.currentTransportSessionId(),
                    visibleLogLines = viewModel.logSnapshot(),
                    connectionInfo = viewModel.currentConnectionInfo(),
                    fastbootDiagnostics = viewModel.currentFastbootDiagnostics(),
                    adbDiagnostics = viewModel.currentAdbDiagnostics(),
                    debugLogging = viewModel.isDebugLoggingEnabled()
                )
                val verification = DiagnosticArchiveVerifier.verify(
                    report,
                    requireTrace = viewModel.currentDiagnosticMode() != DiagnosticModePolicy.Mode.NORMAL || viewModel.isDebugLoggingEnabled()
                )
                viewModel.log("✅ Отчёт для форума создан: ${report.absolutePath} (privacy: sanitized)")
                viewModel.log("✅ ${verification.message}")
                runOnUiThread { showForumReportDialog(report, verification) }
            } catch (e: Exception) {
                viewModel.log("ОШИБКА: не удалось создать отчёт для форума: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun showForumReportDialog(
        report: File,
        verification: DiagnosticArchiveVerifier.Result = DiagnosticArchiveVerifier.verify(report, requireTrace = false)
    ) {
        val verificationText = if (verification.valid) verification.message else "ВНИМАНИЕ: ${verification.message}"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_report_title))
            .setMessage(getString(R.string.report_created_message, report.absolutePath) + "\n\n" + verificationText)
            .setPositiveButton(getString(R.string.share_upper)) { _, _ -> shareGenericFile(report, "application/zip", "ADB Fastboot Tool forum report") }
            .setNeutralButton("ОТКРЫТЬ REPORTS") { _, _ -> openReportsFolder() }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
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
            viewModel.log("ОШИБКА: не удалось открыть рабочую папку: ${e.message ?: e.javaClass.simpleName}")
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
            viewModel.log("ОШИБКА: не удалось создать папку reports: ${reportsDir.absolutePath}")
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
            viewModel.log("ОШИБКА: не удалось открыть DocumentsUI для reports: ${e.message ?: e.javaClass.simpleName}")
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
            viewModel.log("ОШИБКА: не удалось отправить файл: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }


    private fun showLogActions() {
        val file = viewModel.currentLogFile()
        if (file == null || !file.exists()) {
            viewModel.log(getString(R.string.log_file_not_created))
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_log_title))
            .setMessage(
                getString(R.string.log_dialog_message, file.absolutePath) +
                    "\n\nКомпактных сегментов: ${viewModel.currentLogFiles().size}" +
                    "\nTrace-сегментов: ${viewModel.currentTraceLogFiles().size}" +
                    "\nПолный диагностический ZIP создаётся через меню «Отчёты»."
            )
            .setPositiveButton(getString(R.string.copy_path_upper)) { _, _ ->
                copyTextToClipboard("ADB Fastboot log", file.absolutePath, getString(R.string.log_path_copied))
            }
            .setNeutralButton(getString(R.string.share_file_upper)) { _, _ -> shareLogFile(file) }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
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
                viewModel.log("ОШИБКА: не удалось подготовить очищенную копию лога: ${e.message ?: e.javaClass.simpleName}")
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

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_help_title))
            .setMessage(getString(R.string.help_long))
            .setPositiveButton(getString(R.string.ok_understood_upper), null)
            .show()
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
                        data = Uri.parse("package:$packageName")
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

    private fun requestDisableBatteryOptimization() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
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
        ImageInspector.languageOverrideTag = tag
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
                prefs.edit().putString(PREF_LANGUAGE_TAG, selectedTag).apply()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedTag))
                ImageInspector.languageOverrideTag = selectedTag
                dialog.dismiss()
                viewModel.log(getString(R.string.language_changed))
                recreate()
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    // ─── UI ──────────────────────────────────────────────────────────────────


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
        val unlocked = diagnostics?.unlocked ?: "—"
        val maxDownload = diagnostics?.maxDownloadSizeRaw?.let { raw ->
            val bytes = diagnostics.maxDownloadSizeBytes
            if (bytes != null && bytes > 0L) "$raw / ${formatFileSize(bytes)}" else raw
        } ?: "—"
        val workspace = if (::workspacePath.isInitialized) workspacePath.absolutePath else "/sdcard/Download/$folderName"

        val serialno = diagnostics?.serialno?.let { " | Serial: $it" } ?: ""
        val slotExtra = buildString {
            if (inventory?.topology != FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY) {
                diagnostics?.slotCount?.let { append(" | Слотов: $it") }
                diagnostics?.slotSuffix?.let { append(" | Суффикс: $it") }
            }
        }
        val slotDisplay = if (slotExtra.isNotBlank()) "$slot$slotExtra" else slot
        val vbl = diagnostics?.versionBootloader?.let { " | BL: $it" } ?: ""

        findViewById<TextView>(R.id.tvDeviceModeValue).text = "Режим: $modeText"
        findViewById<TextView>(R.id.tvDeviceProductValue).text = "Модель/product: $product$serialno$vbl"
        findViewById<TextView>(R.id.tvDeviceSlotValue).text = "Слот: $slotDisplay"
        findViewById<TextView>(R.id.tvDeviceUnlockedValue).text = "Bootloader: $unlocked"
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
        findViewById<TextView>(R.id.tvDeviceMaxDownloadValue).text =
            "Max download: $maxDownload${maxFetch?.let { " | fetch: $it" } ?: ""}$superPart$inventoryPart"
        val diagnosticState = DiagnosticModePolicy.state(viewModel.currentDiagnosticMode())
        val session = viewModel.currentTransportSessionId() ?: "нет"
        findViewById<TextView>(R.id.tvDeviceWorkspaceValue).text =
            "Папка: $workspace\nBuild: ${viewModel.currentBuildId()} | Session: $session | ${diagnosticState.userLabel} | READ-ONLY=${viewModel.isReadOnlyMutationLockEnabled()}"
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

    private fun loadSafetyPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_SAFETY_PROFILE, null)?.uppercase(Locale.US)
        safetyProfile = when (stored) {
            SafetyProfile.PRO.name, "EXPERT" -> SafetyProfile.PRO
            SafetyProfile.NOVICE.name, "STANDARD" -> SafetyProfile.NOVICE
            else -> if (prefs.getBoolean(PREF_LEGACY_EXPERT_MODE, false)) SafetyProfile.PRO else SafetyProfile.NOVICE
        }
        proModeEnabled = safetyProfile == SafetyProfile.PRO
        highRiskActionsUnlocked = prefs.getBoolean(PREF_HIGH_RISK_UNLOCKED, false) && proModeEnabled

        // Одноразовая миграция старых Standard/Expert-настроек в два новых профиля.
        prefs.edit()
            .putString(PREF_SAFETY_PROFILE, safetyProfile.name)
            .putBoolean(PREF_HIGH_RISK_UNLOCKED, highRiskActionsUnlocked)
            .remove(PREF_LEGACY_EXPERT_MODE)
            .apply()
    }

    private fun setSafetyProfile(profile: SafetyProfile) {
        safetyProfile = profile
        proModeEnabled = profile == SafetyProfile.PRO
        if (!proModeEnabled) highRiskActionsUnlocked = false
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SAFETY_PROFILE, profile.name)
            .putBoolean(PREF_HIGH_RISK_UNLOCKED, highRiskActionsUnlocked)
            .apply()
        updateSafetyProfileUi()
        buildSettingsPage()
        viewModel.log(getString(R.string.safety_profile_changed_log, safetyProfileTitle()))
    }

    private fun toggleHighRiskActions() {
        if (safetyProfile != SafetyProfile.PRO) {
            showSafetyBlockedDialog(getString(R.string.safety_high_risk_pro_only))
            return
        }
        if (highRiskActionsUnlocked) {
            highRiskActionsUnlocked = false
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HIGH_RISK_UNLOCKED, false)
                .apply()
            updateSafetyProfileUi()
            buildSettingsPage()
            viewModel.log(getString(R.string.safety_high_risk_disabled_log))
            return
        }
        showTypedDangerConfirmation(
            title = getString(R.string.safety_high_risk_title),
            message = getString(R.string.safety_high_risk_message),
            requiredPhrase = "PRO",
            logLabel = "enable high-risk safety layer"
        ) {
            highRiskActionsUnlocked = true
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HIGH_RISK_UNLOCKED, true)
                .apply()
            updateSafetyProfileUi()
            buildSettingsPage()
            viewModel.log(getString(R.string.safety_high_risk_enabled_log))
        }
    }

    private fun safetyProfileTitle(): String = when (safetyProfile) {
        SafetyProfile.NOVICE -> getString(R.string.safety_profile_novice)
        SafetyProfile.PRO -> getString(R.string.safety_profile_pro)
    }

    private fun updateSafetyProfileUi() {
        proModeEnabled = safetyProfile == SafetyProfile.PRO
        findViewById<View>(R.id.consoleInputBar).visibility = if (proModeEnabled) View.VISIBLE else View.GONE

        val description = when (safetyProfile) {
            SafetyProfile.NOVICE -> getString(R.string.safety_profile_novice_desc)
            SafetyProfile.PRO -> if (highRiskActionsUnlocked) {
                getString(R.string.safety_profile_pro_unlocked_desc)
            } else {
                getString(R.string.safety_profile_pro_desc)
            }
        }
        findViewById<TextView>(R.id.tvSafetyProfileValue).text = description

        // V5.8.9: the active profile is a selected tile, not merely a different
        // static fill. This makes Novice/Pro state unambiguous at a glance.
        findViewById<MaterialButton>(R.id.btnSafetyNovice).isSelected =
            safetyProfile == SafetyProfile.NOVICE
        findViewById<MaterialButton>(R.id.btnSafetyPro).isSelected =
            safetyProfile == SafetyProfile.PRO

        findViewById<Button>(R.id.btnSafetyHighRisk).text = if (highRiskActionsUnlocked) {
            getString(R.string.safety_high_risk_unlocked)
        } else {
            getString(R.string.safety_high_risk_locked)
        }

        val highRiskAllowed = proModeEnabled && highRiskActionsUnlocked
        setButtonSafetyState(R.id.btnQueueStart, true)
        listOf(R.id.btnFlashBoot, R.id.btnFlashInitBoot, R.id.btnFlashRecovery, R.id.btnFlashVendorBoot, R.id.btnFlashDtbo).forEach {
            setButtonSafetyState(it, true)
        }
        setButtonSafetyState(R.id.btnSafetyHighRisk, proModeEnabled)
    }

    private fun setButtonSafetyState(id: Int, enabled: Boolean) {
        val view = findViewById<View>(id)
        view.alpha = if (enabled) 1.0f else 0.38f
        view.setTag(R.id.tag_safety_blocked, !enabled)
        // Кнопка всегда остаётся активной — клик в заблокированном состоянии
        // обрабатывается обёрткой onClickGuarded (показывает «вы не эксперт»).
        view.isEnabled = true
    }

    /**
     * Вешает обработчик клика с проверкой режима. Если кнопка помечена
     * заблокированной (tag_safety_blocked), показывает уведомление вместо действия.
     */
    private fun guardClick(id: Int, action: () -> Unit) {
        val view = findViewById<View>(id)
        view.setOnClickListener {
            if (view.getTag(R.id.tag_safety_blocked) == true) {
                Toast.makeText(
                    this,
                    getString(R.string.safety_not_pro_toast),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                action()
            }
        }
    }

    private fun ensureGuidedFlashAllowed(partition: String): Boolean {
        if (isHighRiskPartition(partition) && !ensureHighRiskAllowed("flash $partition")) return false
        return true
    }

    private fun ensureHighRiskAllowed(action: String): Boolean {
        if (safetyProfile == SafetyProfile.PRO && highRiskActionsUnlocked) return true
        showSafetyBlockedDialog(getString(R.string.safety_high_risk_blocked_message, action, safetyProfileTitle()))
        return false
    }

    private fun showSafetyBlockedDialog(message: String) {
        viewModel.log(getString(R.string.safety_action_blocked_log, message))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.safety_blocked_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.close_upper), null)
            .show()
    }

    /**
     * Potentially slotted partitions. This list is UI-only: the actual A/B target
     * is resolved at runtime from has-slot and concrete partition probes.
     */
    private fun isSlottedPartition(partition: String): Boolean {
        val clean = partition.trim().lowercase(Locale.US)
            .removeSuffix("_a").removeSuffix("_b")
        return clean in setOf(
            "boot", "init_boot", "vendor_boot", "dtbo", "vbmeta",
            "vbmeta_system", "vbmeta_vendor", "recovery", "system", "vendor",
            "product", "odm", "vendor_kernel_boot"
        )
    }

    private fun isHighRiskPartition(partition: String): Boolean {
        val clean = partition.trim().lowercase(Locale.US)
            .removeSuffix("_ab").removeSuffix("_a").removeSuffix("_b")
        return clean == "vbmeta" ||
            clean == "vbmeta_system" ||
            clean == "vbmeta_vendor" ||
            clean == "userdata" ||
            clean == "metadata" ||
            clean == "super"
    }

    private fun restoreWindowState(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        terminalReturnWindow = sanitizeWindow(
            savedInstanceState?.getString(STATE_TERMINAL_RETURN_WINDOW)
                ?: prefs.getString(PREF_TERMINAL_RETURN_WINDOW, "home")
        )
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
        "home", "fastboot", "adb", "console", "unlock", "settings" -> value
        else -> "home"
    }

    private fun minimizeTerminal() {
        val target = sanitizeWindow(terminalReturnWindow).takeUnless { it == "console" } ?: "home"
        switchTab(target)
    }

    private fun switchTab(tab: String) {
        val target = sanitizeWindow(if (tab == "reports") "console" else tab)
        val previous = tabController.selectedWindow
        if (!restoringWindowState && target == "console" && previous != "console") {
            terminalReturnWindow = sanitizeWindow(previous)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_TERMINAL_RETURN_WINDOW, terminalReturnWindow)
                .apply()
        }

        tabController.switchTab(target)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_WINDOW, tabController.selectedWindow)
            .apply()

        val onTerminal = tabController.selectedWindow == "console"
        findViewById<View>(R.id.consolePanel).visibility = if (onTerminal) View.GONE else View.VISIBLE
        if (onTerminal) renderTerminalLog(lastLogLines)
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
            tv.setTextColor(android.graphics.Color.parseColor("#E06C75"))
            return
        }
        val hasDevices = try { usbManager.deviceList.isNotEmpty() } catch (_: Exception) { false }
        if (hasDevices) {
            tv.text = getString(R.string.otg_status_active)
            tv.setTextColor(android.graphics.Color.parseColor("#69C779"))
        } else {
            tv.text = getString(R.string.otg_status_no_device)
            tv.setTextColor(android.graphics.Color.parseColor("#F2B766"))
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
        val modeState = DiagnosticModePolicy.state(viewModel.currentDiagnosticMode())
        val lockSuffix = if (viewModel.isReadOnlyMutationLockEnabled()) " · READ-ONLY" else ""
        val modeSuffix = if (viewModel.currentDiagnosticMode() == DiagnosticModePolicy.Mode.NORMAL) "" else " · ${modeState.mode.name}"
        tvStatus.text = text + modeSuffix + lockSuffix
        tvStatus.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun renderFlashProgressDialog(progress: DeviceViewModel.OperationProgress?) {
        if (progress == null) {
            flashProgressDialog?.dismiss()
            flashProgressDialog = null
            return
        }

        if (flashProgressDialog == null) {
            buildFlashProgressDialog()
        }

        flashProgressTitleTv?.text = progress.title
        val pct = progress.percent
        if (pct < 0) {
            flashProgressBar?.isIndeterminate = true
            flashProgressPercent?.text = ""
        } else {
            flashProgressBar?.isIndeterminate = false
            flashProgressBar?.progress = pct
            flashProgressPercent?.text = "$pct%"
        }
        flashProgressDetail?.text = progress.detail

        if (progress.finished) {
            // Финальное состояние зависит от явного outcome, а не от отсутствия исключения.
            flashProgressBar?.isIndeterminate = false
            val outcome = progress.outcome ?: if (progress.success) {
                DeviceViewModel.OperationOutcomeKind.SUCCESS
            } else {
                DeviceViewModel.OperationOutcomeKind.FAILED
            }
            when (outcome) {
                DeviceViewModel.OperationOutcomeKind.SUCCESS -> {
                    flashProgressBar?.progress = 100
                    flashProgressPercent?.text = "100%"
                    flashProgressTitleTv?.text = getString(R.string.flash_progress_done_ok)
                    flashProgressTitleTv?.setTextColor(android.graphics.Color.parseColor("#69C779"))
                }
                DeviceViewModel.OperationOutcomeKind.VERIFY_PENDING -> {
                    flashProgressTitleTv?.text = "Ожидается проверка"
                    flashProgressTitleTv?.setTextColor(android.graphics.Color.parseColor("#F2B766"))
                }
                DeviceViewModel.OperationOutcomeKind.CANCELLED -> {
                    flashProgressTitleTv?.text = "Операция отменена"
                    flashProgressTitleTv?.setTextColor(android.graphics.Color.parseColor("#E9782B"))
                }
                DeviceViewModel.OperationOutcomeKind.FAILED -> {
                    flashProgressTitleTv?.text = getString(R.string.flash_progress_done_fail)
                    flashProgressTitleTv?.setTextColor(android.graphics.Color.parseColor("#E9782B"))
                }
            }
            flashProgressWarning?.visibility = View.GONE
            flashProgressButton?.text = getString(R.string.flash_progress_close)
            flashProgressButton?.setOnClickListener {
                flashProgressDialog?.dismiss()
                flashProgressDialog = null
                viewModel.postOperationProgress(null)
            }
        } else {
            flashProgressTitleTv?.setTextColor(android.graphics.Color.parseColor("#F3F6FA"))
            flashProgressWarning?.visibility = View.VISIBLE
            flashProgressButton?.text = getString(R.string.flash_progress_cancel)
            flashProgressButton?.setOnClickListener { confirmCancelFlashProgress() }
        }

        if (flashProgressDialog?.isShowing != true) {
            flashProgressDialog?.show()
        }
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

    private fun buildFlashProgressDialog() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(32), dp(28), dp(24))
            setBackgroundColor(android.graphics.Color.parseColor("#121A24"))
        }
        flashProgressTitleTv = TextView(this).apply {
            text = getString(R.string.flash_progress_title)
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#F3F6FA"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
        }
        root.addView(flashProgressTitleTv)

        flashProgressPercent = TextView(this).apply {
            textSize = 40f
            setTextColor(android.graphics.Color.parseColor("#E9782B"))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(20), 0, dp(12))
        }
        root.addView(flashProgressPercent)

        flashProgressBar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10)
            ).apply { topMargin = dp(4); bottomMargin = dp(16) }
        }
        root.addView(flashProgressBar)

        flashProgressDetail = TextView(this).apply {
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#AEB8C5"))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = android.view.Gravity.CENTER
        }
        root.addView(flashProgressDetail)

        flashProgressWarning = TextView(this).apply {
            text = getString(R.string.flash_progress_warning)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#E9782B"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(24), 0, dp(20))
        }
        root.addView(flashProgressWarning)

        flashProgressButton = Button(this).apply {
            text = getString(R.string.flash_progress_cancel)
            setTextColor(android.graphics.Color.parseColor("#F3F6FA"))
            setBackgroundColor(android.graphics.Color.parseColor("#192431"))
            isAllCaps = false
        }
        root.addView(flashProgressButton)

        flashProgressDialog = android.app.Dialog(this).apply {
            setContentView(root)
            setCancelable(false)
            window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#080D13"))
            )
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun renderOperationSteps(steps: List<DeviceViewModel.OperationStep>) {
        if (!::tvOperationStepQueue.isInitialized) return
        if (steps.isEmpty()) {
            tvOperationStepQueue.text = getString(R.string.layout_operation_steps_empty)
            tvOperationStepQueue.setTextColor(android.graphics.Color.parseColor("#AEB8C5"))
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
            if (hiddenCount > 0) append(getString(R.string.layout_operation_steps_more, hiddenCount))
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
        tvOperationStepQueue.setTextColor(android.graphics.Color.parseColor(color))
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
        tvOperationCenterStatus.setTextColor(android.graphics.Color.parseColor(color))
        tvOperationCenterLastEvent.text = recentText?.let { getString(R.string.layout_operation_center_last_event, it) }
            ?: getString(R.string.layout_operation_center_last_event_empty)

        val cancelButton = findViewById<Button>(R.id.btnOperationCenterCancel)
        cancelButton.isEnabled = active
        cancelButton.alpha = if (active) 1.0f else 0.45f

        // Operation Center виден только во время операции или когда есть значимое
        // событие (ошибка/успех/предупреждение). В простое — скрыт, чтобы не занимать
        // место пустым «ОЖИДАНИЕ».
        val hasSignificantEvent = recentText != null && (
            recentText.contains("❌") || recentText.contains("ОШИБКА") ||
            recentText.contains("FAILED", ignoreCase = true) || recentText.contains("БЛОКИРОВКА") ||
            recentText.contains("✅") || recentText.contains("COMPLETED", ignoreCase = true) ||
            recentText.contains("ЗАВЕРШЕНА") ||
            recentText.contains("⚠") || recentText.contains("WARN", ignoreCase = true)
        )
        findViewById<View>(R.id.cardOperationCenter).visibility =
            if (active || hasSignificantEvent) View.VISIBLE else View.GONE
    }

    // FIX #2: инкрементальный renderLog — добавляем только новые строки,
    // а не перестраиваем весь HTML с нуля. Устраняет лаги при getvar:all (100+ строк).
    private var compactLogRenderState = CompactLogRenderPolicy.State()

    // ─── Сворачиваемая консоль ───────────────────────────────────────────────
    private fun setupCollapsibleConsole() {
        val header = findViewById<View>(R.id.consoleHeader)
        header.setOnClickListener { setConsoleExpanded(!consoleExpanded) }
        // По умолчанию свёрнута — на экране только полоска-заголовок.
        setConsoleExpanded(false)
    }

    private fun setConsoleExpanded(expanded: Boolean) {
        consoleExpanded = expanded
        findViewById<View>(R.id.consoleBody).visibility = if (expanded) View.VISIBLE else View.GONE
        // Стрелка: ▼ когда развёрнуто, ▲ когда свёрнуто.
        findViewById<TextView>(R.id.tvConsoleToggle).text =
            getString(if (expanded) R.string.layout_icon_down else R.string.layout_icon_up)
        // V5.8.6: compact console no longer shows the two ambiguous history
        // glyph buttons. Command history remains available in the full terminal.
        findViewById<View>(R.id.btnHistoryUp).visibility = View.GONE
        findViewById<View>(R.id.btnHistoryDown).visibility = View.GONE
        // Превью последней строки видно только когда свёрнуто.
        findViewById<View>(R.id.tvConsolePeek).visibility = if (expanded) View.GONE else View.VISIBLE
        if (expanded) {
            scrollViewLog.post { scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /** Обновляет превью последней строки лога в свёрнутом заголовке. */
    private fun updateConsolePeek(lastLine: String) {
        if (!consoleExpanded) {
            findViewById<TextView>(R.id.tvConsolePeek).text = lastLine.trim()
        }
    }

    // ─── Полноэкранный терминал (вкладка) ────────────────────────────────────
    private fun setupTerminal() {
        val send = {
            val et = findViewById<EditText>(R.id.etTerminalCommand)
            val cmd = et.text.toString().trim()
            if (cmd.isNotEmpty()) {
                // Переиспользуем существующий обработчик: кладём текст в etCommand
                // (нижняя консоль) и вызываем ту же проверенную логику разбора.
                etCommand.setText(cmd)
                handleCommandInput()
                et.setText("")
            }
        }
        findViewById<View>(R.id.btnTerminalSend).setOnClickListener { send() }
        findViewById<EditText>(R.id.etTerminalCommand).setOnEditorActionListener { _, _, _ -> send(); true }

        findViewById<View>(R.id.btnTerminalClear).setOnClickListener { viewModel.clearLog() }

        // Автопрокрутка вкл/выкл
        findViewById<MaterialButton>(R.id.btnTerminalAutoscroll).setOnClickListener {
            terminalAutoscroll = !terminalAutoscroll
            (it as MaterialButton).text = getString(
                if (terminalAutoscroll) R.string.terminal_autoscroll_on else R.string.terminal_autoscroll_off)
            it.setTextColor(android.graphics.Color.parseColor(if (terminalAutoscroll) "#69C779" else "#AEB8C5"))
            if (terminalAutoscroll) scrollTerminalToBottom()
        }

        // Фильтр уровня: цикл Все → Info → Warn → Error
        findViewById<MaterialButton>(R.id.btnTerminalFilter).setOnClickListener {
            terminalFilter = (terminalFilter + 1) % 4
            (it as MaterialButton).text = getString(when (terminalFilter) {
                1 -> R.string.terminal_filter_info
                2 -> R.string.terminal_filter_warn
                3 -> R.string.terminal_filter_error
                else -> R.string.terminal_filter_all
            })
            renderTerminalLog(lastLogLines)
        }
    }

    /** Классификация строки лога по уровню (для фильтра/цвета). 1=info 2=warn 3=error */
    private fun logLevel(line: String): Int = when {
        line.contains("ОШИБКА") || line.contains("БЛОКИРОВКА") || line.contains("❌") || line.contains("🙀") -> 3
        line.startsWith("⏳") || line.startsWith("⚠") || line.contains("💤") -> 2
        else -> 1
    }

    private fun renderTerminalLog(lines: List<String>) {
        val tv = findViewById<TextView>(R.id.tvTerminalLog)
        val filtered = if (terminalFilter == 0) lines else lines.filter { logLevel(it) == terminalFilter }
        val sb = android.text.SpannableStringBuilder()
        filtered.forEach { line ->
            val (color, emoji) = when (logLevel(line)) {
                3 -> "#E06C75" to "🙀 "
                2 -> "#F2B766" to "💤 "
                else -> when {
                    line.contains("✅") || line.contains("===") || line.contains("ЗАВЕРШЕНА") -> "#69C779" to "✨ "
                    line.startsWith(">") -> "#E98B49" to "😸 "
                    line.startsWith("[") -> "#758397" to "🐾 "
                    else -> "#9FAAB6" to "🐾 "
                }
            }
            val prefix = if (line.firstOrNull()?.isLetterOrDigit() != false) emoji else ""
            val safe = TextUtils.htmlEncode(prefix + line)
            sb.append(Html.fromHtml("<font color=\"$color\">$safe</font><br>", Html.FROM_HTML_MODE_LEGACY))
        }
        tv.text = sb
        if (terminalAutoscroll) scrollTerminalToBottom()
    }

    private fun scrollTerminalToBottom() {
        val sv = findViewById<ScrollView>(R.id.scrollTerminalLog)
        sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun renderLog(lines: List<String>) {
        val decision = CompactLogRenderPolicy.decide(lines, compactLogRenderState)
        compactLogRenderState = decision.nextState
        if (decision.reset) tvLog.text = ""
        if (lines.isEmpty()) return

        val newLines = lines.drop(decision.startIndex)
        if (newLines.isEmpty()) return

        val sb = android.text.SpannableStringBuilder()
        newLines.forEach { line ->
            // Категоризация строки лога → цвет (моно-neko палитра) + neko-эмодзи.
            val (color, emoji) = when {
                line.contains("ОШИБКА") || line.contains("БЛОКИРОВКА") || line.contains("❌") ->
                    "#E06C75" to "🙀 "  // error — приглушённый красный
                line.startsWith("💡") ->
                    "#F2B766" to ""     // подсказка (эмодзи уже в тексте)
                line.contains("✅") || line.contains("===") || line.contains("ЗАВЕРШЕНА") ->
                    "#69C779" to "✨ "  // success — мягкий зелёный
                line.startsWith(">") || line.startsWith("->") || line.startsWith("<-") ->
                    "#E98B49" to "😸 "  // команда — лавандовый
                line.startsWith("⏳") || line.startsWith("⚠") ->
                    "#F2B766" to "💤 "  // warning — тёплый песочный
                line.startsWith("[") ->
                    "#758397" to "🐾 "  // system — серый
                else ->
                    "#9FAAB6" to "🐾 "  // info — серо-голубой
            }
            // Не дублируем эмодзи, если строка уже начинается с emoji/маркера.
            val prefix = if (emoji.isNotEmpty() && line.firstOrNull()?.isLetterOrDigit() != false) emoji else ""
            val safe = TextUtils.htmlEncode(prefix + line)
            sb.append(Html.fromHtml("<font color=\"$color\">$safe</font><br>", Html.FROM_HTML_MODE_LEGACY))
        }
        tvLog.append(sb)
        // Превью последней строки для свёрнутой консоли.
        lines.lastOrNull()?.let { updateConsolePeek(it) }
        scrollViewLog.post { scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ─── Авто-снижение яркости во время записи ───────────────────────────────
    private var savedBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private fun applyReducedBrightness() {
        try {
            val lp = window.attributes
            savedBrightness = lp.screenBrightness
            lp.screenBrightness = 0.15f // 15% — экран читаем, но без перегрева
            window.attributes = lp
        } catch (_: Exception) {
        }
    }

    private fun restoreBrightness() {
        try {
            val lp = window.attributes
            lp.screenBrightness = savedBrightness
            window.attributes = lp
        } catch (_: Exception) {
        }
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
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver could already be unregistered by the system; safe to ignore.
        }
        usbPermissionTimeouts.values.forEach { usbPermissionHandler.removeCallbacks(it) }
        usbPermissionTimeouts.clear()
        modeSwitchHandler.removeCallbacksAndMessages(null)
        deviceOverviewHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val USB_PERMISSION_TIMEOUT_MS = 30_000L
        private const val EXTRA_USB_INTENT_CONSUMED = "nekoflash_usb_intent_consumed"
        private const val STARTUP_USB_SCAN_DELAY_MS = 350L
        private const val MODE_SWITCH_SCAN_INTERVAL_MS = 750L
        private const val MODE_SWITCH_SCAN_ATTEMPTS = 16
        private const val PREFS_NAME = "settings"
        private const val PREF_LANGUAGE_TAG = "language_tag"
        private const val PREF_SAFETY_PROFILE = "safety_profile"
        private const val PREF_LEGACY_EXPERT_MODE = "expert_mode"
        private const val PREF_HIGH_RISK_UNLOCKED = "high_risk_unlocked"
        private const val PREF_LAST_WINDOW = "last_window"
        private const val PREF_TERMINAL_RETURN_WINDOW = "terminal_return_window"
        private const val STATE_SELECTED_WINDOW = "state_selected_window"
        private const val STATE_TERMINAL_RETURN_WINDOW = "state_terminal_return_window"
    }
}
