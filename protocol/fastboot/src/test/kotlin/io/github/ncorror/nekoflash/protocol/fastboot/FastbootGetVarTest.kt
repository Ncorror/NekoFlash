package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение переменных устройства.
 *
 * Оба способа доставки значения наблюдены в Legacy
 * (`FastbootProtocol.readGetVarResponse`) до написания кода: обычно значение
 * лежит в теле `OKAY`, но часть загрузчиков шлёт пустой `OKAY`, а значение —
 * кадрами `INFO` перед ним.
 */
class FastbootGetVarTest {
    @Test
    fun theValueUsuallyArrivesInTheOkayItself() {
        val transport = FakeFastbootTransport().willReply("OKAYvayu")

        val variable = FastbootGetVar(FastbootLane(transport)).read("product")

        assertEquals(FastbootVariable.Present("product", "vayu"), variable)
        assertEquals(listOf("getvar:product"), transport.sent)
    }

    /**
     * Пустой `OKAY` не означает пустого значения.
     *
     * Часть загрузчиков отвечает так, отдав значение кадрами `INFO`.
     * Поддержать только первый способ значило бы объявить такие устройства
     * неотвечающими.
     */
    @Test
    fun anEmptyOkayFallsBackToTheInfoFrames() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("INFOproduct: vayu", "OKAY"))

        assertEquals(FastbootVariable.Present("product", "vayu"), FastbootGetVar(lane).read("product"))
    }

    /** Строки `INFO` перебираются с конца: последняя относится к последнему вопросу. */
    @Test
    fun theLastInfoLineWins() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("INFOstale", "INFOfresh", "OKAY"))

        assertEquals(FastbootVariable.Present("product", "fresh"), FastbootGetVar(lane).read("product"))
    }

    /** Имя переменной перед значением снимается — и через дефис, и через подчёркивание. */
    @Test
    fun aRepeatedVariableNameIsStripped() {
        assertEquals("yes", FastbootVariableValue.of("is-userspace", "is-userspace: yes"))
        assertEquals("yes", FastbootVariableValue.of("is-userspace", "is_userspace:yes"))
    }

    @Test
    fun anInfoPrefixInsideTheLineIsStripped() {
        assertEquals("vayu", FastbootVariableValue.of("product", "INFOproduct: vayu"))
    }

    /** Значение, похожее на путь, не режется по двоеточию: имя не совпало — не трогаем. */
    @Test
    fun aValueThatMerelyContainsAColonIsLeftAlone() {
        assertEquals("a:b:c", FastbootVariableValue.of("product", "a:b:c"))
    }

    /**
     * `FAIL` — это ответ устройства, а не сбой.
     *
     * Разные загрузчики знают разный набор переменных, и «такой у меня нет» —
     * осмысленный ответ, который нельзя смешивать с «спросить не удалось».
     */
    @Test
    fun aRefusalIsReportedAsUnsupportedNotAsFailure() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("FAILunknown variable"))

        val variable = FastbootGetVar(lane).read("is-userspace")

        assertEquals(FastbootVariable.Unsupported("is-userspace", "unknown variable"), variable)
        assertEquals("отказ рамку не портит", FastbootLaneState.IDLE, lane.state)
    }

    @Test
    fun silenceIsUnavailableRatherThanUnsupported() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))

        val variable = FastbootGetVar(lane).read("product", inactivityMillis = 100)

        assertTrue(variable is FastbootVariable.Unavailable)
    }

    /** Занятая полоса называет своё состояние, а не молчит. */
    @Test
    fun aStalledLaneIsReportedAsUnavailableWithItsState() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))
        FastbootGetVar(lane).read("product", inactivityMillis = 100)

        val second = FastbootGetVar(lane).read("serialno") as FastbootVariable.Unavailable

        assertTrue(second.detail.contains("STALLED"))
    }

    /**
     * Фаза данных на `getvar` — потеря рамки, и молчать об этом нельзя.
     *
     * Устройство осталось ждать байты, о которых мы не договаривались.
     */
    @Test
    fun aDataPhaseOnGetVarStallsTheLane() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("DATA00000004"))

        val variable = FastbootGetVar(lane).read("product")

        assertTrue(variable is FastbootVariable.Unavailable)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /** Имя передаётся как набрано: какие переменные есть, знает загрузчик (`01` §3). */
    @Test
    fun anyVariableNameReachesTheDeviceUnchanged() {
        val transport = FakeFastbootTransport().willReply("OKAY")

        FastbootGetVar(FastbootLane(transport)).read("что-угодно".replace("что-угодно", "vendor-specific-thing"))

        assertEquals(listOf("getvar:vendor-specific-thing"), transport.sent)
    }
}
