package io.github.ncorror.nekoflash.usb.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbHost
import io.github.ncorror.nekoflash.usb.api.UsbPermissionCallbackIdentity

/**
 * Владение USB на стороне Android: перечисление устройств, приёмники
 * подключения и отключения, запрос разрешения.
 *
 * Хост не принимает решений о том, что делать с устройством. Он сообщает
 * наблюдателю факты и выполняет запрошенные действия; классификация,
 * идентичность target, владение сессиями и разбор ответа на запрос разрешения
 * живут в `usb:api` и покрыты тестами.
 *
 * Наблюдение построено на слушателе, а не на `Flow`: зависимость на корутины
 * появится вместе с транспортным вводом-выводом, где она действительно нужна,
 * и будет подтверждена сборкой в тот же момент. Обёртка в `Flow` — задача
 * слоя выше.
 *
 * Контекст должен быть областью приложения: владение USB переживает
 * пересоздание экрана.
 */
public class AndroidUsbHost(
    context: Context,
    private val callbackIdentity: UsbPermissionCallbackIdentity,
) : UsbHost {
    private val appContext: Context = context.applicationContext
    private val usbManager: UsbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var listener: UsbHost.Listener? = null
    private var permissionAction: String? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (!callbackIdentity.matchesCurrent(intent.action)) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            listener?.onPermissionResult(intent.usbDevice()?.let(AndroidUsbDescriptorMapper::map), granted)
        }
    }

    private val attachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = intent?.usbDevice()?.let(AndroidUsbDescriptorMapper::map) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> listener?.onDeviceAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> listener?.onDeviceDetached(device)
                else -> Unit
            }
        }
    }

    /**
     * Начинает наблюдение и открывает новую активацию.
     *
     * Повторный вызов сначала останавливает предыдущую активацию: два
     * одновременных владельца USB привели бы к двойной обработке одного
     * события.
     */
    override fun start(listener: UsbHost.Listener) {
        stop()
        this.listener = listener
        val action = callbackIdentity.nextAction()
        permissionAction = action
        registerPermissionReceiver(action)
        registerAttachDetachReceiver()
    }

    /**
     * Прекращает наблюдение.
     *
     * Снятие приёмника, который не был зарегистрирован, платформа считает
     * ошибкой, поэтому она подавляется: повторная остановка — обычное дело при
     * завершении, а не сбой.
     */
    override fun stop() {
        if (permissionAction == null) return
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        runCatching { appContext.unregisterReceiver(attachDetachReceiver) }
        permissionAction = null
        listener = null
    }

    /** Дескрипторы всех подключённых сейчас устройств. */
    override fun devices(): List<UsbDeviceDescriptor> =
        usbManager.deviceList.values.map(AndroidUsbDescriptorMapper::map)

    /**
     * Выдано ли разрешение на устройство прямо сейчас.
     *
     * Возвращает `false`, если устройство уже отключено: отсутствующее
     * устройство не имеет разрешения, и это не ошибка.
     */
    override fun hasPermission(device: UsbDeviceDescriptor): Boolean =
        findDevice(device)?.let { usbManager.hasPermission(it) } == true

    /**
     * Запрашивает разрешение на устройство.
     *
     * Возвращает `false`, если устройство исчезло между обнаружением и
     * запросом. Ответ придёт в [Listener.onPermissionResult]; таймаут ожидания
     * задаёт вызывающий по `UsbPermissionPolicy.RESPONSE_TIMEOUT_MS`.
     */
    override fun requestPermission(device: UsbDeviceDescriptor): Boolean {
        val action = permissionAction ?: return false
        val target = findDevice(device) ?: return false
        val intent = Intent(action).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag()
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            target.deviceId,
            intent,
            flags,
        )
        usbManager.requestPermission(target, pendingIntent)
        return true
    }

    /**
     * Соответствующее платформенное устройство.
     *
     * Сопоставление ведётся по имени подключения: идентификатор может
     * поменяться, если платформа пересоздала дескриптор.
     */
    private fun findDevice(device: UsbDeviceDescriptor): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceName == device.deviceName }

    private fun registerPermissionReceiver(action: String) {
        registerReceiverCompat(permissionReceiver, IntentFilter(action))
    }

    /**
     * Подключение и отключение — защищённые системные широковещания. Приёмник
     * регистрируется неэкспортированным: чужое приложение отправить их не
     * может, а системные доходят и до такого приёмника.
     */
    private fun registerAttachDetachReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiverCompat(attachDetachReceiver, filter)
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiverBeforeTiramisu(receiver, filter)
        }
    }

    /**
     * До появления флага экспорта приёмник регистрируется обычным путём.
     *
     * Ответ на запрос разрешения доставляется отложенным намерением,
     * ограниченным пакетом приложения, а подключение и отключение — защищённые
     * системные широковещания, поэтому отсутствие флага здесь ничего не
     * открывает наружу.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Suppress("DEPRECATION")
    private fun registerReceiverBeforeTiramisu(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        appContext.registerReceiver(receiver, filter)
    }

    private fun mutabilityFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            legacyUsbDevice()
        }

    @Suppress("DEPRECATION")
    private fun Intent.legacyUsbDevice(): UsbDevice? = getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
