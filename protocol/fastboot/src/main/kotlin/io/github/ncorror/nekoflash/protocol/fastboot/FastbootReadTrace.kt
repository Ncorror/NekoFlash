package io.github.ncorror.nekoflash.protocol.fastboot

/**
 * Что показала одна попытка чтения с устройства.
 *
 * Существует затем, что шесть прогонов подряд (`07` §6.86–§6.95) выясняли по
 * одному факту за раз то, что Legacy печатал сам и сразу. У него на каждое
 * чтение уходит строка `[fastboot-timing]` с измеренным временем, запрошенным
 * таймаутом, числом байт и эндпоинтом (`FastbootProtocol.readPacket`, строки
 * 1984–2012). Мгновенно возвращающееся пустое чтение там видно с первого
 * прогона; у нас оно выяснялось три захода.
 *
 * Это не отладочная роскошь, а разница между «оператор жмёт кнопку шесть раз» и
 * «оператор жмёт кнопку один раз».
 */
public fun interface FastbootReadTrace {
    /**
     * @param phase где читали: кадр ответа, фаза данных или проба.
     * @param wantedBytes сколько байт **просили** этой передачей.
     * @param requestedMillis сколько времени отвели этому чтению.
     * @param elapsedMicros сколько оно **заняло** на самом деле.
     * @param bytes сколько принято; отрицательное — платформа не выполнила
     *   передачу, и таймаут от ошибки здесь не отличить (`NOT_COMPLETED`).
     */
    public fun read(phase: String, wantedBytes: Int, requestedMillis: Int, elapsedMicros: Long, bytes: Int)

    public companion object {
        /** Никуда не пишет: протокол обязан работать и без наблюдателя. */
        public val NOTHING: FastbootReadTrace = FastbootReadTrace { _, _, _, _, _ -> }

        /** Чтение кадра ответа — `OKAY`, `FAIL`, `INFO`, `TEXT`, `DATA`. */
        public const val FRAME: String = "frame"

        /** Чтение внутри открытой фазы данных. */
        public const val DATA_IN: String = "data-in"

        /**
         * Повторное чтение той же фазы данных **меньшим** запросом.
         *
         * Отдельная фаза, а не ещё одна строка `data-in`: по журналу обязано
         * быть видно, что это не следующий блок, а та же порция, спрошенная
         * иначе. Зачем спрошенная — `FastbootLane.drain`.
         */
        public const val DATA_PROBE: String = "data-probe"
    }
}
