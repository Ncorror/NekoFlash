package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReadTrace

/**
 * Замеры чтений в журнал — начало каждого обмена, а не весь он.
 *
 * Пишутся **всегда**, а не по флажку отладки: включать телеметрию постфактум
 * означало бы просить повторить прогон, а прогон стоит телефона, кабеля и
 * живого человека. Шесть прогонов `07` §6.86–§6.95 выясняли по одному факту за
 * раз то, что Legacy печатал сам и сразу (`FastbootProtocol.readPacket`).
 *
 * **Ограничение головой обязательно.** Раздел в 96 МиБ — это шесть тысяч
 * блоков, а молчащее устройство даёт больше тысячи пустых чтений за две минуты
 * терпения; журнал кольцевой, и такой поток вытеснил бы из него всё остальное —
 * то есть ровно ту выгрузку, ради которой всё и делается. Первых [HEAD] чтений
 * хватает на вопрос «возвращается ли чтение мгновенно», а он здесь и был
 * главным (`07` §6.91, §6.93).
 *
 * Обрыв записи называется строкой, а не молчанием: молчащий журнал неотличим от
 * журнала, в котором чтений не было.
 *
 * **Запрошенный размер пишется числом, а не подразумевается фазой.** По
 * выгрузке `07` §6.99 фаза `data-in` означала 16384, а `frame` — 512, и весь
 * вывод о том, что отказ зависит от размера запроса, держался на этом
 * соответствии, нигде не записанном. Подразумеваемое — не наблюдение.
 */
internal class FastbootReadRecorder(
    private val emit: (String, Map<String, String>) -> Unit,
) : FastbootReadTrace {
    private val seen = mutableMapOf<String, Int>()

    /** Новый обмен — новый счёт: голова принадлежит команде, а не сессии. */
    fun reset() {
        seen.clear()
    }

    override fun read(
        phase: String,
        wantedBytes: Int,
        requestedMillis: Int,
        elapsedMicros: Long,
        bytes: Int,
    ) {
        val index = (seen[phase] ?: 0) + 1
        seen[phase] = index
        when {
            index <= HEAD -> emit(
                "fastboot_read",
                mapOf(
                    "phase" to phase,
                    "wanted" to wantedBytes.toString(),
                    "requestedMs" to requestedMillis.toString(),
                    "elapsedUs" to elapsedMicros.toString(),
                    "bytes" to bytes.toString(),
                    "index" to index.toString(),
                ),
            )

            index == HEAD + 1 -> emit("fastboot_read_truncated", mapOf("phase" to phase, "after" to HEAD.toString()))
        }
    }

    private companion object {
        const val HEAD = 12
    }
}
