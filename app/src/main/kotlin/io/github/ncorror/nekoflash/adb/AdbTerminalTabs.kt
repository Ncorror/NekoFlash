package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Одна вкладка оболочки: номер, имя и собственный владелец сессии.
 *
 * Владелец здесь **целый [AdbTerminalController]**, а не кусок общего
 * состояния. Тот написан под одну сессию и держит её инварианты замком,
 * счётчиком запросов и единственным полем `shell`; растащить его на несколько
 * сессий значило бы переписать заново ровно ту часть, которая уже доказана на
 * железе. Вкладка — это второй экземпляр, а не второй режим.
 */
public data class AdbTerminalTab(
    val id: Int,
    val title: String,
    val sessions: AdbTerminalController,
)

/**
 * Несколько одновременных оболочек.
 *
 * **Одновременность здесь законна по построению.** У ADB потоки различаются
 * идентификаторами, и `AdbStreamDispatcher` раздаёт их независимо (ADR-0004) —
 * этим ADB и отличается от Fastboot, где полоса одна и вторая команда
 * необратимо путает ответы. Поэтому вкладки стоят здесь и невозможны там.
 *
 * Физический reader остаётся **один**: он живёт в диспетчере и раздаёт кадры по
 * идентификаторам. Вкладка занимает не reader, а поток исполнителя под свой
 * цикл разбора; исполнитель — пул, и он для этого и заведён.
 *
 * Состояние вкладок — список, а не слияние их состояний в одно. Слить значило
 * бы пересобирать общий снимок на каждый пришедший байт любой из оболочек;
 * экран вместо этого читает состояние **той** вкладки, которую показывает.
 */
public class AdbTerminalTabs(
    private val readerExecutor: Executor,
    private val writerExecutor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
) {
    private val mutableTabs = MutableStateFlow<List<AdbTerminalTab>>(emptyList())
    private val mutableSelected = MutableStateFlow<Int?>(null)
    private var nextId = 1

    /** Открытые вкладки в порядке открытия. */
    public val tabs: StateFlow<List<AdbTerminalTab>> = mutableTabs.asStateFlow()

    /** Номер показываемой вкладки, либо `null`, если их нет. */
    public val selected: StateFlow<Int?> = mutableSelected.asStateFlow()

    /** Вкладка, которую показывают, либо `null`. */
    public fun current(): AdbTerminalTab? =
        mutableTabs.value.firstOrNull { it.id == mutableSelected.value }

    /**
     * Открывает ещё одну оболочку и показывает её.
     *
     * Новая вкладка выбирается сразу: оператор нажал «ещё одна оболочка»,
     * значит работать он собирается в ней, а не в прошлой.
     */
    public fun open(connection: AdbConnection): Int {
        val id = nextId
        nextId += 1
        val controller = AdbTerminalController(readerExecutor, writerExecutor, diagnostics)
        mutableTabs.value = mutableTabs.value + AdbTerminalTab(id, "$TITLE_PREFIX $id", controller)
        mutableSelected.value = id
        controller.start(connection)
        return id
    }

    /** Показывает вкладку [id], если такая есть. */
    public fun select(id: Int) {
        if (mutableTabs.value.any { it.id == id }) mutableSelected.value = id
    }

    /**
     * Закрывает вкладку: сначала сессию, потом саму вкладку.
     *
     * Порядок существенный. Убрать вкладку из списка и не закрыть сессию значит
     * оставить на устройстве открытый поток, о котором на экране больше ничего
     * не сказано: оболочка там продолжит жить, а спросить о ней будет некому.
     */
    public fun close(id: Int) {
        val tab = mutableTabs.value.firstOrNull { it.id == id } ?: return
        tab.sessions.stop()
        val left = mutableTabs.value.filterNot { it.id == id }
        mutableTabs.value = left
        if (mutableSelected.value == id) mutableSelected.value = left.lastOrNull()?.id
    }

    /**
     * Закрывает все вкладки.
     *
     * Вызывается вместе с потерей соединения: сессии принадлежат ему, и
     * пережить его они не могут. Оставить вкладки после разрыва значило бы
     * показывать оболочки устройства, которого нет.
     */
    public fun closeAll() {
        mutableTabs.value.forEach { tab -> tab.sessions.stop() }
        mutableTabs.value = emptyList()
        mutableSelected.value = null
    }

    private companion object {
        const val TITLE_PREFIX = "Оболочка"
    }
}
