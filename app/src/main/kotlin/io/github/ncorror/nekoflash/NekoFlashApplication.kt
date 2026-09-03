package io.github.ncorror.nekoflash

import android.app.Application
import android.net.Uri
import android.os.Build
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticBundle
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticBundleResult
import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.diagnostics.HostFacts
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.usb.android.AndroidUsbHost
import io.github.ncorror.nekoflash.usb.api.UsbDiagnosticReport
import io.github.ncorror.nekoflash.usb.api.UsbPermissionCallbackIdentity
import io.github.ncorror.nekoflash.usb.api.UsbPermissionPolicy
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Владелец USB на уровне приложения.
 *
 * Владение живёт здесь, а не на экране: подключённое устройство не должно
 * теряться при повороте экрана или пересоздании активности. Граф зависимостей
 * собирается явно, без библиотеки внедрения — так решено в ADR-0003, пока
 * масштаб это позволяет.
 */
public class NekoFlashApplication : Application() {
    /**
     * Область для отложенных задач владельца USB.
     *
     * [Dispatchers.Default], а не главный поток: главный диспетчер требует
     * отдельного артефакта корутин, а здесь он и не нужен. Реестр сессий
     * потокобезопасен, наблюдаемое состояние тоже, а на экран его доставляет
     * Compose.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val host by lazy {
        AndroidUsbHost(
            context = this,
            callbackIdentity = UsbPermissionCallbackIdentity(
                actionPrefix = "$packageName.USB_PERMISSION",
                processToken = UUID.randomUUID().toString(),
            ),
        )
    }

    private val events = InMemoryDiagnosticSink()

    /** Состояние сессий USB. Экран подписывается на него и ничего не опрашивает. */
    public val usbSessions: UsbSessionCoordinator by lazy {
        UsbSessionCoordinator(
            host = host,
            onPermissionRequested = ::schedulePermissionTimeout,
            diagnostics = events,
        )
    }

    /**
     * Ключ хоста ADB.
     *
     * Лежит в приватном каталоге приложения: устройство помнит хост по
     * отпечатку публичного ключа, и терять его между запусками нельзя.
     */
    private val adbKeys by lazy { AdbKeyStore(File(filesDir, ADB_KEY_FOLDER)) }

    /**
     * Единственный поток протокольного обмена.
     *
     * Ровно один: контракт требует единственного физического читателя
     * входящего потока, и пул с несколькими потоками эту гарантию бы отменил.
     */
    private val adbThread by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nekoflash-adb") }
    }

    /** Состояние ADB-соединения. Экран подписывается на него. */
    public val adbLink: AdbLinkController by lazy {
        AdbLinkController(
            coordinator = usbSessions,
            keyStore = adbKeys,
            apiLevel = Build.VERSION.SDK_INT,
            executor = adbThread,
            diagnostics = events,
        )
    }

    /** Имя файла, предлагаемое системному диалогу сохранения. */
    public fun suggestedDiagnosticsFileName(): String =
        DiagnosticBundle.suggestedFileName(Instant.now())

    /**
     * Записывает диагностический архив в выбранный пользователем файл.
     *
     * Поток открывает и закрывает вызывающий этого метода владелец: провайдер
     * документов может оказаться медленным, а сборщик архива намеренно не
     * закрывает чужой поток.
     */
    public fun writeDiagnostics(destination: Uri): DiagnosticBundleResult {
        // Незавершённые и недавно завершённые вместе: отключение устройства
        // перед выгрузкой — обычное дело, и без закрытых сессий отчёт был бы
        // пустым именно в самом интересном случае.
        val sections = UsbDiagnosticReport.sections(
            host = HostFacts.collect(this),
            sessions = usbSessions.sessions.value + usbSessions.recentlyClosedSessions(),
            events = events.snapshot(),
            droppedEvents = events.droppedCount(),
        )
        return contentResolver.openOutputStream(destination).use { output ->
            requireNotNull(output) { "Document provider returned no stream" }
            DiagnosticBundle.write(output, sections, Instant.now())
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbSessions.start()
    }

    private companion object {
        /** Каталог ключа ADB внутри приватного хранилища приложения. */
        const val ADB_KEY_FOLDER = "adb"
    }

    /**
     * Заводит отсчёт ожидания ответа на запрос разрешения.
     *
     * Координатор время не планирует намеренно, поэтому отсчёт живёт здесь. По
     * истечении решение принимает та же политика, что и в остальных случаях:
     * если разрешение к этому моменту уже выдано, сессия закрывается молча и
     * подключение не происходит неявно.
     */
    private fun schedulePermissionTimeout(generation: SessionGeneration) {
        scope.launch {
            delay(UsbPermissionPolicy.RESPONSE_TIMEOUT_MS)
            // Будить координатор имеет смысл только если сессия всё ещё ждёт
            // ответа. Иначе таймер опоздал: ответ давно получен, интерфейс мог
            // быть уже захвачен, и запись «истекло ожидание разрешения»
            // читалась бы в отчёте как происшествие, которым она не является.
            // Гонку между этой проверкой и вызовом разрешает сам координатор,
            // возвращая IGNORE.
            val session = usbSessions.sessions.value
                .firstOrNull { it.generation == generation }
                ?.takeIf { it.state == UsbSessionState.PERMISSION_PENDING }
                ?: return@launch
            usbSessions.onPermissionTimeout(
                generation = generation,
                permissionGrantedNow = host.hasPermission(session.candidate.device),
            )
        }
    }
}
