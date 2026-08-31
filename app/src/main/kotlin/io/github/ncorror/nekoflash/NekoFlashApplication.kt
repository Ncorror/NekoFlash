package io.github.ncorror.nekoflash

import android.app.Application
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.usb.android.AndroidUsbHost
import io.github.ncorror.nekoflash.usb.api.UsbPermissionCallbackIdentity
import io.github.ncorror.nekoflash.usb.api.UsbPermissionPolicy
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

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

    /** Состояние сессий USB. Экран подписывается на него и ничего не опрашивает. */
    public val usbSessions: UsbSessionCoordinator by lazy {
        UsbSessionCoordinator(
            host = host,
            onPermissionRequested = ::schedulePermissionTimeout,
        )
    }

    override fun onCreate() {
        super.onCreate()
        usbSessions.start()
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
            val session = usbSessions.sessions.value.firstOrNull { it.generation == generation }
                ?: return@launch
            usbSessions.onPermissionTimeout(
                generation = generation,
                permissionGrantedNow = host.hasPermission(session.candidate.device),
            )
        }
    }
}
