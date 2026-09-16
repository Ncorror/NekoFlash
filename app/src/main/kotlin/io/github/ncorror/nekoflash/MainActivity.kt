package io.github.ncorror.nekoflash

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.ncorror.nekoflash.ui.NekoFlashApp
import io.github.ncorror.nekoflash.ui.OperationsPanel
import io.github.ncorror.nekoflash.ui.PaletteAction
import io.github.ncorror.nekoflash.ui.RecentEvents
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbFileState
import io.github.ncorror.nekoflash.adb.AdbInstallState
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.AdbTerminalTab
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.ui.FastbootConsolePanel
import io.github.ncorror.nekoflash.ui.FastbootPanel
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbScanSummary
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.artifact.SafArtifactSink
import io.github.ncorror.nekoflash.artifact.SafArtifactSource
import io.github.ncorror.nekoflash.ui.FileActions
import io.github.ncorror.nekoflash.ui.FirstRun
import io.github.ncorror.nekoflash.ui.ForwardPanel
import io.github.ncorror.nekoflash.ui.RawServicePanel
import io.github.ncorror.nekoflash.ui.RebootPanel
import io.github.ncorror.nekoflash.ui.ReversePanel
import io.github.ncorror.nekoflash.ui.SideloadPanel
import io.github.ncorror.nekoflash.ui.TerminalActions
import io.github.ncorror.nekoflash.ui.TerminalTab
import io.github.ncorror.nekoflash.ui.WelcomeScreen
import io.github.ncorror.nekoflash.ui.theme.NekoFlashTheme
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Владение USB живёт на уровне приложения: подключённое устройство не
        // должно теряться при повороте экрана или пересоздании активности.
        val application = application as NekoFlashApplication
        val coordinator = application.usbSessions
        val adbLink = application.adbLink
        val fastbootLink = application.fastbootLink

        setContent {
            val firstRun = remember { FirstRun(this) }
            var welcomeSeen by rememberSaveable { mutableStateOf(firstRun.seen) }
            NekoFlashTheme {
                // Вводный экран показывается один раз и ничего не решает: это
                // «понял, дальше», а не согласие с условиями. Права быть
                // профессионалом он не выдаёт — оно уже есть (`01` §3).
                if (welcomeSeen) {
                    NekoFlashScreen(application, coordinator, adbLink, fastbootLink)
                } else {
                    WelcomeScreen(
                        onContinue = {
                            firstRun.seen = true
                            welcomeSeen = true
                        },
                    )
                }
            }
        }
    }

    /**
     * Дерево экрана целиком.
     *
     * Вынесено из `onCreate` не ради слоёв: `onCreate` перешёл порог detekt в
     * 60 строк, а поднимать пороги запрещено (`15` §4.1). Граница вышла
     * осмысленная — выше неё только владение процессом, ниже только экран.
     */
    /**
     * Системный диалог сохранения отчёта.
     *
     * Вынесен из экрана не ради длины, а потому что это законченная вещь: файл
     * создаёт пользователь там, где ему нужно, и приложение не заводит
     * собственного хранилища отчётов.
     */
    @Composable
    private fun diagnosticsSaveLauncher(
        application: NekoFlashApplication,
        onStatus: (String) -> Unit,
    ): ManagedActivityResultLauncher<String, Uri?> {
        val savedTemplate = stringResource(R.string.diagnostics_export_done)
        val failedTemplate = stringResource(R.string.diagnostics_export_failed)
        return rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { destination ->
            exportDiagnostics(application, destination, savedTemplate, failedTemplate, onStatus)
        }
    }

    @Composable
    private fun NekoFlashScreen(
        application: NekoFlashApplication,
        coordinator: UsbSessionCoordinator,
        adbLink: AdbLinkController,
        fastbootLink: FastbootLinkController,
    ) {
        var exportStatus by remember { mutableStateOf<String?>(null) }
        var selectedGeneration by remember { mutableStateOf(application.selectedUsbGeneration) }
        val shown = shownState(coordinator, adbLink, fastbootLink, selectedGeneration)
        val claimFailedTemplate = stringResource(R.string.usb_claim_failed)
        val saveLauncher = diagnosticsSaveLauncher(application) { message -> exportStatus = message }

        // Скан при каждом возвращении на экран.
        //
        // Закрывает открытый вопрос `07` §6.22: приложение сканировало при
        // старте и по кнопке, и если система перечислила устройство позже,
        // не прислав `USB_DEVICE_ATTACHED` — например, пока приложение не
        // работало, — узнать об этом можно было только кнопкой.
        //
        // Тот конкретный случай из §6.22 это **не** закрывает, и там так и
        // написано: ручной скан тогда дал ноль. Закрывается другой,
        // соседний: устройство подключили при свёрнутом приложении.
        RescanOnResume(coordinator)

        NekoFlashApp(
            sessions = shown.sessions,
            selectedSession = shown.session,
            onSelectTarget = { session ->
                selectedGeneration = session.generation.value
                application.selectedUsbGeneration = session.generation.value
            },
            scan = shown.scan,
            usbHostSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            exportStatus = exportStatus,
            adbLink = shown.adb,
            adbConnectAvailable = shown.connectAvailable,
            adbCommand = shown.owned.command,
            terminal = shown.owned.terminal,
            terminalTabs = shown.owned.terminalTabs,
            terminalSelected = shown.owned.terminalSelected,
            terminalActions = terminalActions(adbLink),
            files = shown.owned.files,
            install = shown.owned.install,
            fileActions = fileActions(adbLink),
            onRescanUsb = { coordinator.scanAttachedDevices() },
            onClaim = claimAction(coordinator, claimFailedTemplate) { exportStatus = it },
            onRelease = { session -> coordinator.release(session.generation) },
            onAdbConnect = { session -> adbLink.connect(session.generation) },
            onAdbDisconnect = { session -> adbLink.disconnect(session.generation) },
            onRunCommand = adbLink::runCommand,
            reboot = rebootPanel(adbLink),
            rawService = rawServicePanel(adbLink),
            forward = forwardPanel(adbLink),
            reverse = reversePanel(adbLink),
            sideload = sideloadPanel(adbLink),
            operations = operationsPanel(application),
            paletteActions = paletteActions(
                adbLink = adbLink,
                adbState = shown.adb,
                fastbootLink = fastbootLink,
                fastbootState = shown.fastboot,
                coordinator = coordinator,
                onExport = saveLauncher::launch,
                application = application,
            ),
            recentEvents = RecentEvents { application.recentDiagnostics() },
            fastboot = fastbootPanel(fastbootLink, shown.fastboot, shown.session),
            fastbootConsole = fastbootConsolePanel(fastbootLink, shown.console, shown.fastboot, shown.session),
            onExportDiagnostics = { saveLauncher.launch(application.suggestedDiagnosticsFileName()) },
        )
    }
}

/**
 * Что экран читает у владельцев — уже приведённое к **выбранной** цели.
 *
 * Держится одним объектом, а не десятком `val` в теле экрана, по существу, а не
 * ради длины: маскировать чужое состояние приходится у каждого поля, и стоит
 * забыть один — Terminal или Файлы покажут работу с телефоном, которого на
 * Target Bar нет. Здесь это делается один раз и в одном месте.
 */
private class ShownState(
    val sessions: List<UsbSession>,
    val scan: UsbScanSummary,
    val session: UsbSession?,
    val adb: AdbLinkState,
    val connectAvailable: Boolean,
    val owned: OwnedByAdb,
    val fastboot: FastbootLinkState,
    val console: FastbootConsoleState,
)

/**
 * То, что принадлежит **живому соединению ADB**, а не устройству вообще.
 *
 * Отдельной группой, потому что маскируется целиком и по одной причине: когда
 * ADB принадлежит другой цели, каждое из этих полей относится к чужому
 * телефону. Разложенные по одному, они и маскировались по одному, и забыть
 * одно значило бы показать чужую оболочку в рабочем месте выбранной цели.
 */
private class OwnedByAdb(
    val command: AdbCommandState,
    val terminal: AdbTerminalState,
    val terminalTabs: List<TerminalTab>,
    val terminalSelected: Int?,
    val files: AdbFileState,
    val install: AdbInstallState,
) {
    companion object {
        /** Соединение принадлежит не этой цели: показывать нечего. */
        fun none(): OwnedByAdb = OwnedByAdb(
            command = AdbCommandState.None,
            terminal = AdbTerminalState(),
            terminalTabs = emptyList(),
            terminalSelected = null,
            files = AdbFileState.None,
            install = AdbInstallState.None,
        )
    }
}

/**
 * Собирает состояние экрана вокруг одной [SessionGeneration].
 *
 * Глобальные владельцы — один ADB и одна полоса Fastboot — принадлежат какой-то
 * одной цели, и показывать их содержимое рядом с другой значило бы утверждать
 * про неё чужое. Поэтому всё, что принадлежит соединению, обнуляется, как
 * только выбранная цель им не владеет.
 */
@Composable
private fun shownState(
    coordinator: UsbSessionCoordinator,
    adbLink: AdbLinkController,
    fastbootLink: FastbootLinkController,
    selectedGeneration: Long?,
): ShownState {
    val sessions by coordinator.sessions.collectAsState()
    val scan by coordinator.lastScan.collectAsState()
    val linkState by adbLink.state.collectAsState()
    val commandState by adbLink.command.collectAsState()
    val tabs by adbLink.terminalTabs.collectAsState()
    val selectedTab by adbLink.terminalSelected.collectAsState()
    val fileState by adbLink.files.collectAsState()
    val installState by adbLink.install.collectAsState()
    val fastbootState by fastbootLink.state.collectAsState()
    val console by fastbootLink.console.collectAsState()

    val session = sessions.firstOrNull { it.generation.value == selectedGeneration } ?: sessions.firstOrNull()
    val adb = linkState.forSession(session)
    val fastboot = fastbootState.forSession(session)
    val owns = adb is AdbLinkState.Connected
    return ShownState(
        sessions = sessions,
        scan = scan,
        session = session,
        adb = adb,
        // Кнопка подключения гаснет не по политике, а потому, что второй CNXN
        // запрещён контрактом: ADB уже принадлежит другой цели, и нажатие
        // ничего бы не сделало. Рядом сказано, чем именно оно занято.
        connectAvailable = linkState is AdbLinkState.Idle ||
            linkState is AdbLinkState.Failed ||
            adb !is AdbLinkState.Idle,
        owned = if (!owns) {
            OwnedByAdb.none()
        } else {
            OwnedByAdb(
                command = commandState,
                terminal = shownTerminalState(tabs.firstOrNull { it.id == selectedTab }),
                terminalTabs = tabs.map { tab -> TerminalTab(tab.id, tab.title) },
                terminalSelected = selectedTab,
                files = fileState,
                install = installState,
            )
        },
        fastboot = fastboot,
        console = if (fastboot is FastbootLinkState.Connected) console else FastbootConsoleState.Idle,
    )
}

private fun AdbLinkState.forSession(
    session: UsbSession?,
): AdbLinkState {
    if (session == null) return AdbLinkState.Idle
    val generation = when (this) {
        AdbLinkState.Idle -> null
        is AdbLinkState.Connecting -> generation
        is AdbLinkState.WaitingForAuthorization -> generation
        is AdbLinkState.Connected -> generation
        is AdbLinkState.Failed -> generation
    }
    return if (generation == session.generation) this else AdbLinkState.Idle
}

private fun FastbootLinkState.forSession(session: UsbSession?): FastbootLinkState {
    if (session == null) return FastbootLinkState.Idle
    val generation = when (this) {
        FastbootLinkState.Idle -> null
        is FastbootLinkState.Probing -> generation
        is FastbootLinkState.Connected -> generation
        is FastbootLinkState.Failed -> generation
    }
    return if (generation == session.generation) this else FastbootLinkState.Idle
}

/**
 * История операций.
 *
 * Живые и законченные читаются из владельца операций, который живёт на уровне
 * приложения: операция не принадлежит экрану и переживает его (`06` §1).
 */
@Composable
private fun operationsPanel(application: NekoFlashApplication): OperationsPanel = OperationsPanel(
    live = application.operations.live.collectAsState().value,
    history = application.operations.history.collectAsState().value,
)

/**
 * Пересканирует USB при каждом возвращении на экран.
 *
 * Скан дешёвый: он спрашивает у системы список и ничего не захватывает. Делать
 * его по расписанию было бы хуже — шум в диагностике без нового знания, — а по
 * возвращению он приходится ровно на тот момент, когда пользователь и мог
 * что-то воткнуть.
 */
@Composable
private fun RescanOnResume(coordinator: UsbSessionCoordinator) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) coordinator.scanAttachedDevices()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Набор действий для палитры.
 *
 * Сюда попадают только действия **без набираемого аргумента**: путь к файлу,
 * имя раздела и текст команды набираются в своих полях, и дублировать их в
 * поиске значило бы завести второе место, где то же самое вводится иначе.
 *
 * Ничего нового палитра не добавляет и ничего не прячет: каждое действие здесь
 * есть и на своей карточке. Это второй путь к тому же — для того, кто помнит
 * название и не хочет листать.
 */
@Composable
private fun paletteActions(
    adbLink: AdbLinkController,
    adbState: AdbLinkState,
    fastbootLink: FastbootLinkController,
    fastbootState: FastbootLinkState,
    coordinator: UsbSessionCoordinator,
    onExport: (String) -> Unit,
    application: NekoFlashApplication,
): List<PaletteAction> {
    val actions = mutableListOf(
        PaletteAction(stringResource(R.string.action_rescan), "usb scan скан устройства") {
            coordinator.scanAttachedDevices()
        },
        PaletteAction(stringResource(R.string.action_export), "diagnostics отчёт логи bundle") {
            onExport(application.suggestedDiagnosticsFileName())
        },
    )

    // Protocol actions появляются только когда выбранная цель и живой owner —
    // одна и та же generation. Палитра не имеет права обходить Target Bar.
    if (adbState is AdbLinkState.Connected) {
        actions += listOf(
            PaletteAction(stringResource(R.string.action_recovery_baseline), "baseline база журнал log") {
                adbLink.recovery.captureBaseline()
            },
            PaletteAction(stringResource(R.string.action_recovery_verdict), "verdict вердикт install исход") {
                adbLink.recovery.readVerdict()
            },
            PaletteAction(stringResource(R.string.action_sideload_small), "sideload пакет малый small") {
                adbLink.recovery.sideload(SMALL_PACKAGE_BYTES)
            },
            PaletteAction(stringResource(R.string.action_sideload_large), "sideload пакет большой large") {
                adbLink.recovery.sideload(LARGE_PACKAGE_BYTES)
            },
            PaletteAction(stringResource(R.string.action_reboot_system), "reboot перезагрузка система") {
                adbLink.requestReboot("")
            },
            PaletteAction(stringResource(R.string.action_reboot_bootloader), "reboot bootloader загрузчик") {
                adbLink.requestReboot("bootloader")
            },
            PaletteAction(stringResource(R.string.action_reboot_recovery), "reboot recovery рекавери") {
                adbLink.requestReboot("recovery")
            },
            PaletteAction(stringResource(R.string.action_reboot_sideload), "reboot sideload сайдлоад") {
                adbLink.requestReboot("sideload")
            },
        )
    }

    if (fastbootState is FastbootLinkState.Connected) {
        actions += listOf(
            PaletteAction(stringResource(R.string.action_fastboot_getvar_all), "getvar all переменные") {
                fastbootLink.readAllVariables()
            },
            PaletteAction(stringResource(R.string.action_fastboot_reboot_bootloader), "fastboot reboot загрузчик") {
                fastbootLink.runCommand("reboot-bootloader")
            },
            PaletteAction(stringResource(R.string.action_fastboot_reboot_fastbootd), "fastbootd userspace") {
                fastbootLink.runCommand("reboot fastboot")
            },
        )
    }

    return actions
}

/**
 * Размеры пакетов для палитры — те же, что на кнопках Sideload.
 *
 * Держатся рядом с палитрой, а не переиспользуются из панели: там они приватны,
 * и открывать их наружу ради двух строк значило бы расширить чужой контракт.
 */
private const val SMALL_PACKAGE_BYTES = 64L * 1024L
private const val LARGE_PACKAGE_BYTES = 16L * 1024L * 1024L

/** Проводка панели перезагрузки: состояние экрана и действие контроллера. */
@Composable
private fun rebootPanel(adbLink: AdbLinkController): RebootPanel = RebootPanel(
    state = adbLink.reboot.collectAsState().value,
    onReboot = adbLink::requestReboot,
)

/** То же для произвольного сервиса. */
@Composable
private fun rawServicePanel(adbLink: AdbLinkController): RawServicePanel = RawServicePanel(
    state = adbLink.rawService.collectAsState().value,
    onCall = adbLink::callRawService,
)

/**
 * То же для передачи пакета в Recovery — и выбор пакета через системный диалог.
 *
 * Копия непрозрачного источника кладётся в кэш приложения: она не переживает
 * операцию намеренно, и система вправе убрать её сама, если места станет мало.
 */
@Composable
private fun sideloadPanel(adbLink: AdbLinkController): SideloadPanel {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        if (chosen != null) {
            adbLink.recovery.sideloadFrom(context.cacheDir) { SafArtifactSource(resolver, chosen) }
        }
    }
    return SideloadPanel(
        state = adbLink.sideload.collectAsState().value,
        onSend = adbLink.recovery::sideload,
        onChoose = { chooser.launch(arrayOf("application/zip", "*/*")) },
        onCancel = adbLink.recovery::cancelSideload,
    )
}

/** То же для пробросов портов. */
@Composable
private fun forwardPanel(adbLink: AdbLinkController): ForwardPanel = ForwardPanel(
    state = adbLink.forward.collectAsState().value,
    onAdd = adbLink.forwards::add,
    onRemove = adbLink.forwards::remove,
)

/** То же для обратных пробросов. */
@Composable
private fun reversePanel(adbLink: AdbLinkController): ReversePanel = ReversePanel(
    state = adbLink.reverse.collectAsState().value,
    onAdd = adbLink.reverses::add,
    onRefresh = adbLink.reverses::refresh,
    onRemoveAll = adbLink.reverses::removeAll,
)

private fun MainActivity.exportDiagnostics(
    application: NekoFlashApplication,
    destination: Uri?,
    savedTemplate: String,
    failedTemplate: String,
    onStatus: (String) -> Unit,
) {
    if (destination == null) return
    lifecycleScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching { application.writeDiagnostics(destination) }
        }
        onStatus(
            outcome.fold(
                onSuccess = { result -> savedTemplate.format(result.sectionCount) },
                onFailure = { failure ->
                    failedTemplate.format(failure.message ?: failure.javaClass.simpleName)
                },
            ),
        )
    }
}

/** Действия терминала собраны отдельно: в теле экрана они только шумят. */
/**
 * Панель Fastboot: состояние опроса и два действия над ним.
 *
 * Вынесена из `onCreate` не ради красоты — иначе метод перерастает ориентир
 * длины, а раздувать точку входа именно тем, что легко вынести, значит начинать
 * тот путь, которым `MainActivity` Legacy дошла до 3880 строк.
 */
private fun fastbootPanel(
    link: FastbootLinkController,
    state: FastbootLinkState,
    selectedSession: UsbSession?,
): FastbootPanel = FastbootPanel(
    state = state,
    // Generation берётся из живой сессии: опрос принадлежит тому подключению,
    // в котором он начат.
    onProbe = {
        selectedSession
            ?.takeIf { it.candidate.kind == UsbInterfaceKind.FASTBOOT }
            ?.let { session -> link.connect(session.generation) }
    },
    onDisconnect = link::disconnect,
    // Тот же вход, что и у консоли: второго пути к полосе нет по построению.
    onPlan = link::runPlan,
)

@Composable
private fun fastbootConsolePanel(
    link: FastbootLinkController,
    state: FastbootConsoleState,
    linkState: FastbootLinkState,
    selectedSession: UsbSession?,
): FastbootConsolePanel {
    val resolver = LocalContext.current.contentResolver
    val pendingFetch = remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val partition = pendingFetch.value
        pendingFetch.value = null
        if (destination != null && partition != null) {
            link.fetchPartitionTo(partition) { SafArtifactSink(resolver, destination, partition) }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        if (chosen != null) {
            link.downloadFrom { SafArtifactSource(resolver, chosen) }
        }
    }

    return FastbootConsolePanel(
        state = state,
        onCommand = link::runCommand,
        onVariable = link::readVariable,
        onAllVariables = link::readAllVariables,
        onDownload = link::downloadGenerated,
        onFetch = link::fetchPartition,
        onFetchToFile = { partition ->
            pendingFetch.value = partition
            saveLauncher.launch(partition.ifBlank { "partition" } + ".img")
        },
        onDownloadFile = { openLauncher.launch(arrayOf("*/*")) },
        // Отпустить и взять заново — тем же входом, что и кнопки шапки.
        // Generation берётся из живой сессии, а не из прошлой связи: захват
        // принадлежит тому подключению, которое есть сейчас.
        onReclaim = {
            link.disconnect()
            selectedSession
                ?.takeIf { it.candidate.kind == UsbInterfaceKind.FASTBOOT }
                ?.let { session -> link.connect(session.generation) }
        },
        // Роль берётся из той же связи, что и в шапке, а не из отдельной
        // догадки: две строки о роли, способные разойтись, хуже одной.
        mode = (linkState as? FastbootLinkState.Connected)?.identity?.mode ?: FastbootMode.UNKNOWN,
    )
}

/**
 * Проводка файловых действий, включая два, которым нужен системный диалог.
 *
 * Диалог отдаёт `Uri` позже и в другом обратном вызове, чем нажатие кнопки,
 * поэтому путь на устройстве приходится придержать между ними. Держится он
 * ровно до возврата из диалога и сбрасывается в любом случае, включая отказ:
 * иначе следующий выбор файла достался бы прошлой команде.
 *
 * Ни источник, ни приёмник здесь не открываются: разговор с чужим провайдером
 * это ввод-вывод, и на главном потоке он подвесил бы экран. Наружу уходит
 * функция, которую контроллер вызовет на своём потоке.
 */
@Composable
private fun fileActions(link: AdbLinkController): FileActions {
    val resolver = LocalContext.current.contentResolver
    val pendingRead = remember { mutableStateOf<String?>(null) }
    val pendingWrite = remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val path = pendingRead.value
        pendingRead.value = null
        if (destination != null && path != null) {
            val shown = path.substringAfterLast('/')
            link.storage.readTo(path) { SafArtifactSink(resolver, destination, shown) }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        val path = pendingWrite.value
        pendingWrite.value = null
        if (chosen != null && path != null) {
            link.storage.writeFrom(path) { SafArtifactSource(resolver, chosen) }
        }
    }

    // Выбор APK — отдельный диалог, а не тот же самый: у него другой фильтр и
    // другое продолжение, и придерживать между ними нечего — путь на устройстве
    // выбирает протокол, а не оператор.
    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        if (chosen != null) {
            val source = SafArtifactSource(resolver, chosen)
            link.storage.install(chosen.lastPathSegment ?: "package.apk", emptyList()) { source }
        }
    }

    return FileActions(
        onDescribe = link.storage::describe,
        onRead = link.storage::read,
        onWrite = link.storage::write,
        onReadToFile = { path ->
            pendingRead.value = path
            saveLauncher.launch(path.substringAfterLast('/').ifBlank { "artifact.bin" })
        },
        onWriteFromFile = { path ->
            pendingWrite.value = path
            openLauncher.launch(arrayOf("*/*"))
        },
        onRecoveryBaseline = link.recovery::captureBaseline,
        onRecoveryVerdict = link.recovery::readVerdict,
        // Тип называется, но выбор им не запирается: что считать пакетом, решает
        // устройство, и отсеять «не тот» файл своим списком значило бы отказать
        // за него (`01` §3). Второй тип — `*/*` — оставляет выбор оператору.
        onInstallApk = { apkLauncher.launch(arrayOf(APK_MIME, "*/*")) },
        onCancelTransfer = link.storage::cancelTransfer,
    )
}

/** Тип APK как его знает система. Подсказка диалогу, а не наш фильтр. */
private const val APK_MIME = "application/vnd.android.package-archive"

private fun terminalActions(link: AdbLinkController) = TerminalActions(
    onStart = link.shell::start,
    onSend = link.shell::send,
    onInterrupt = link.shell::interrupt,
    onStop = link.shell::stop,
    onOpenTab = link.shell::openTab,
    onSelectTab = link.shell::selectTab,
    onCloseTab = link.shell::closeTab,
)

/**
 * Состояние показываемой вкладки.
 *
 * Собрано здесь, а не слиянием всех вкладок в один снимок: слить значило бы
 * пересобирать общий объект на каждый пришедший байт любой из оболочек, а
 * экран показывает одну. Когда вкладок нет, отдаётся пустое состояние — это не
 * заглушка, а правда: показывать нечего.
 */
@Composable
private fun shownTerminalState(tab: AdbTerminalTab?): AdbTerminalState {
    val flow = remember(tab) { tab?.sessions?.state ?: MutableStateFlow(AdbTerminalState()) }
    return flow.collectAsState().value
}

/** Преобразует технический результат claim в короткое UI-сообщение. */
private fun claimAction(
    coordinator: UsbSessionCoordinator,
    failedTemplate: String,
    onFailure: (String) -> Unit,
): (UsbSession) -> Unit = { session ->
    val result = coordinator.claim(session.generation)
    if (result is UsbClaimResult.Failed) {
        onFailure(failedTemplate.format(result.reason.name))
    }
}
