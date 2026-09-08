package io.github.ncorror.nekoflash.protocol.adb

/**
 * Раскладывает принятые пакеты по ящикам логических потоков.
 *
 * Первый шаг плана `docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`. Сегодня
 * инвариант «один физический читатель» держится соглашением между четырьмя
 * классами: цикл `reader.read()` крутит сам потребитель, и поэтому потребитель
 * может быть только один. Диспетчер снимает именно это: цикл будет один, а
 * ждать своего потока сможет каждый — на своём ящике.
 *
 * Класс остаётся **без ввода-вывода и без потока исполнения**, как и
 * [AdbStreamRouter]: на входе принятый пакет, на выходе список пакетов к
 * отправке. Кто вертит цикл и на каком потоке — решает владелец соединения, и
 * это шаг 2 того же плана. Такое разделение позволяет проверить раскладку
 * тестами, не заводя ни одного потока.
 *
 * Правила маршрутизации не дублируются: их знает [AdbStreamRouter], а
 * диспетчер только доставляет его события адресатам.
 */
public class AdbStreamDispatcher(
    private val router: AdbStreamRouter = AdbStreamRouter(),
    private val mailboxCapacity: Int = AdbStreamMailbox.DEFAULT_CAPACITY,
) {
    private val lock = Any()
    private val mailboxes = LinkedHashMap<Int, AdbStreamMailbox>()

    /** Ящики живых потоков в порядке открытия. */
    public val activeMailboxes: List<AdbStreamMailbox>
        get() = synchronized(lock) { mailboxes.values.toList() }

    /**
     * Открывает поток и заводит ему ящик.
     *
     * Возвращает ящик и пакет, который надо отправить: отправка принадлежит
     * владельцу транспорта, как и в [AdbStreamRouter.openRequest].
     */
    public fun open(service: String): Pair<AdbStreamMailbox, AdbOutboundPacket> = synchronized(lock) {
        val (localId, packet) = router.openRequest(service)
        val mailbox = AdbStreamMailbox(localId, mailboxCapacity)
        mailboxes[localId] = mailbox
        mailbox to packet
    }

    /** Готовит отправку данных в открытый поток; `null` — потока нет или он не открыт. */
    public fun write(localId: Int, payload: ByteArray): AdbOutboundPacket? = synchronized(lock) {
        router.writeRequest(localId, payload)
    }

    /**
     * Закрывает поток по нашей инициативе.
     *
     * Ящик получает конец сразу: потребитель, ждущий на нём, должен узнать о
     * закрытии, а не висеть до таймаута.
     */
    public fun close(localId: Int): AdbOutboundPacket? = synchronized(lock) {
        val packet = router.closeRequest(localId)
        mailboxes.remove(localId)?.end(AdbMailboxEnd.LOCAL, "closed by host")
        packet
    }

    /**
     * Разбирает один принятый пакет и раскладывает его по ящикам.
     *
     * Возвращает пакеты к отправке — как подтверждения от маршрутизатора, так и
     * `CLSE` для потока, чей ящик переполнился.
     */
    public fun dispatch(packet: AdbPacket): List<AdbOutboundPacket> = synchronized(lock) {
        val step = router.onPacket(packet)
        val outbound = step.outbound.toMutableList()
        step.events.forEach { event -> deliver(event, outbound) }
        outbound
    }

    /**
     * Закрывает **все** ящики одной причиной.
     *
     * Потеря кадра делает недостоверным весь поток протокола, а не отдельный
     * логический поток: после неё неизвестно, где начинается следующий пакет.
     * Делать вид, что какой-то из потоков уцелел, нельзя
     * (`03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §3).
     */
    public fun abandonAll(reason: AdbMailboxEnd, detail: String): Unit = synchronized(lock) {
        router.abandonAll()
        mailboxes.values.forEach { mailbox -> mailbox.end(reason, detail) }
        mailboxes.clear()
    }

    private fun deliver(event: AdbStreamEvent, outbound: MutableList<AdbOutboundPacket>) {
        when (event) {
            is AdbStreamEvent.Opened ->
                put(event.localId, AdbMailboxItem.Opened(event.remoteId), outbound)

            is AdbStreamEvent.Data ->
                put(event.localId, AdbMailboxItem.Data(event.payload), outbound)

            is AdbStreamEvent.Closed -> {
                mailboxes.remove(event.localId)?.end(event.reason.toEnd(), "device closed the stream")
            }

            // Чужой и неожиданный пакет адресату не принадлежат: маршрутизатор уже
            // ответил на них тем, чем следовало, а ящику сообщать нечего.
            is AdbStreamEvent.Stale -> Unit
            is AdbStreamEvent.Unexpected -> Unit
        }
    }

    /**
     * Кладёт событие в ящик, а при переполнении закрывает поток.
     *
     * `CLSE` отправляется устройству: оно должно узнать, что поток кончился,
     * иначе продолжит слать данные в никуда.
     */
    private fun put(
        localId: Int,
        item: AdbMailboxItem,
        outbound: MutableList<AdbOutboundPacket>,
    ) {
        val mailbox = mailboxes[localId] ?: return
        if (mailbox.offer(item)) return
        mailbox.end(AdbMailboxEnd.OVERFLOWED, "mailbox of stream $localId is full")
        mailboxes.remove(localId)
        router.closeRequest(localId)?.let { packet -> outbound += packet }
    }
}

private fun AdbStreamClosure.toEnd(): AdbMailboxEnd = when (this) {
    AdbStreamClosure.COMPLETED -> AdbMailboxEnd.COMPLETED
    AdbStreamClosure.REJECTED -> AdbMailboxEnd.REJECTED
    AdbStreamClosure.LOCAL -> AdbMailboxEnd.LOCAL
}
