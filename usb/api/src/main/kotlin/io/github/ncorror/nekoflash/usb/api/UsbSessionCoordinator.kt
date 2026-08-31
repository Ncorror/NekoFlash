package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import kotlinx.coroutines.flow.StateFlow

/**
 * Превращает события USB в состояние сессий.
 *
 * Единственная задача — связывание. Классификация, идентичность, владение
 * сессиями и разбор ответа на запрос разрешения находятся в отдельных, уже
 * проверенных местах; здесь только порядок их применения.
 *
 * Границы намеренно узкие. В A2 ровно эта склейка разрослась в класс на 2495
 * строк, потому что туда же попали запуск и остановка транспортов, оркестрация
 * прошивки и Sideload, экспорт диагностики. Всё это принадлежит другим модулям.
 *
 * Координатор не планирует время. Стартовое сканирование, повторные попытки и
 * таймаут ожидания разрешения запускает платформенный слой, вызывая методы
 * этого класса: планирование нельзя выполнить детерминированно в тесте, а всё
 * остальное здесь — можно.
 */
public class UsbSessionCoordinator(
    private val host: UsbHost,
    private val registry: UsbSessionRegistry = UsbSessionRegistry(),
    private val allowGenericVendor: Boolean = true,
) : UsbHost.Listener {
    /** Незавершённые сессии. Для показа на экране и для владельцев операций. */
    public val sessions: StateFlow<List<UsbSession>>
        get() = registry.activeSessions

    /** Начинает наблюдение и сразу разбирает уже подключённые устройства. */
    public fun start() {
        host.start(this)
        scanAttachedDevices()
    }

    /** Прекращает наблюдение. Открытые сессии не закрываются: устройства никуда не делись. */
    public fun stop() {
        host.stop()
    }

    /**
     * Разбирает устройства, подключённые прямо сейчас.
     *
     * Устройство, для которого уже есть незавершённая сессия, пропускается:
     * повторное открытие вытеснило бы работающую сессию новой без причины.
     */
    public fun scanAttachedDevices() {
        val known = registry.activeSessions.value.map { it.candidate.device.deviceName }.toSet()
        host.devices()
            .filterNot { it.deviceName in known }
            .forEach(::onDeviceAttached)
    }

    override fun onDeviceAttached(device: UsbDeviceDescriptor) {
        val candidate = UsbInterfaceClassifier.primaryCandidate(device, allowGenericVendor) ?: return
        val session = registry.open(UsbTargetIdentity.fromDescriptor(device), candidate)
        if (host.hasPermission(device)) {
            registry.markReady(session.generation)
            return
        }
        registry.markPermissionPending(session.generation)
        if (!host.requestPermission(device)) {
            registry.close(session.generation, UsbSessionClosureReason.DETACHED)
        }
    }

    override fun onDeviceDetached(device: UsbDeviceDescriptor) {
        registry.closeDetached(device)
    }

    override fun onPermissionResult(device: UsbDeviceDescriptor?, granted: Boolean) {
        val pending = registry.activeSessions.value
            .filter { it.state == UsbSessionState.PERMISSION_PENDING }
        when (val decision = UsbPermissionPolicy.resolve(pending, device, granted)) {
            is UsbPermissionPolicy.Decision.Proceed -> proceed(decision)
            is UsbPermissionPolicy.Decision.Denied ->
                registry.close(decision.session.generation, UsbSessionClosureReason.PERMISSION_DENIED)

            UsbPermissionPolicy.Decision.MissingDevice,
            UsbPermissionPolicy.Decision.NoCandidate,
            UsbPermissionPolicy.Decision.UnmatchedDenial,
            -> Unit
        }
    }

    /**
     * Обрабатывает истечение ожидания разрешения.
     *
     * Вызывается платформенным слоем через
     * [UsbPermissionPolicy.RESPONSE_TIMEOUT_MS] после запроса. Возвращает
     * принятое решение, чтобы вызывающий мог сообщить о нём пользователю: молча
     * закрытая сессия выглядела бы как исчезнувшее без причины устройство.
     */
    public fun onPermissionTimeout(
        generation: SessionGeneration,
        permissionGrantedNow: Boolean,
    ): UsbPermissionPolicy.TimeoutOutcome {
        val session = registry.session(generation)
        val outcome = UsbPermissionPolicy.onTimeout(session, permissionGrantedNow)
        if (outcome != UsbPermissionPolicy.TimeoutOutcome.IGNORE) {
            registry.close(generation, UsbSessionClosureReason.PERMISSION_TIMED_OUT)
        }
        return outcome
    }

    private fun proceed(decision: UsbPermissionPolicy.Decision.Proceed) {
        val existing = decision.session
        if (existing == null) {
            val opened = registry.open(
                identity = UsbTargetIdentity.fromDescriptor(decision.candidate.device),
                candidate = decision.candidate,
            )
            registry.markReady(opened.generation)
            return
        }
        registry.refresh(existing.generation, decision.candidate)
        registry.markReady(existing.generation)
    }
}
