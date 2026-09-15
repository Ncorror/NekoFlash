package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReadTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замеры чтений в журнале.
 *
 * Проверяется не «строка напечаталась», а два свойства, ради которых запись и
 * заведена: что по ней восстанавливается **размер запроса** (`07` §6.99: весь
 * вывод о зависимости отказа от размера держался на соответствии «фаза →
 * число», нигде не записанном) и что поток чтений не вытесняет из кольцевого
 * журнала всё остальное.
 */
class FastbootReadRecorderTest {
    private val written = mutableListOf<Pair<String, Map<String, String>>>()
    private val recorder = FastbootReadRecorder { message, fields -> written += message to fields }

    /** Запрошенный размер стоит числом, а не подразумевается фазой. */
    @Test
    fun theRequestedSizeIsWrittenDown() {
        recorder.read(FastbootReadTrace.DATA_IN, wantedBytes = 16384, requestedMillis = 10_000, elapsedMicros = 612, bytes = -1)

        val (message, fields) = written.single()
        assertEquals("fastboot_read", message)
        assertEquals("16384", fields["wanted"])
        assertEquals("-1", fields["bytes"])
        assertEquals("612", fields["elapsedUs"])
    }

    /** Проба отличается от следующего блока фазой, а не только числами. */
    @Test
    fun theProbeIsNamedAsItsOwnPhase() {
        recorder.read(FastbootReadTrace.DATA_PROBE, 512, 10_000, 40, 512)

        assertEquals(FastbootReadTrace.DATA_PROBE, written.single().second["phase"])
    }

    /**
     * Поток чтений обрывается головой и говорит об этом.
     *
     * Молчащий журнал неотличим от журнала, в котором чтений не было.
     */
    @Test
    fun theStreamIsCutShortAndSaysSo() {
        repeat(30) { recorder.read(FastbootReadTrace.DATA_IN, 16384, 10_000, 600, -1) }

        val reads = written.count { it.first == "fastboot_read" }
        val truncated = written.single { it.first == "fastboot_read_truncated" }
        assertEquals(12, reads)
        assertEquals("12", truncated.second["after"])
        assertEquals(FastbootReadTrace.DATA_IN, truncated.second["phase"])
    }

    /** Голова считается по фазам отдельно: кадры не съедают место у данных. */
    @Test
    fun eachPhaseHasItsOwnHead() {
        repeat(20) { recorder.read(FastbootReadTrace.FRAME, 512, 900, 300, 6) }
        recorder.read(FastbootReadTrace.DATA_IN, 16384, 10_000, 600, -1)

        val data = written.filter { it.second["phase"] == FastbootReadTrace.DATA_IN }
        assertEquals(1, data.size)
        assertEquals("1", data.single().second["index"])
    }

    /** Новый обмен — новый счёт: голова принадлежит команде, а не сессии. */
    @Test
    fun resettingStartsTheCountAgain() {
        repeat(20) { recorder.read(FastbootReadTrace.FRAME, 512, 900, 300, 6) }
        written.clear()

        recorder.reset()
        recorder.read(FastbootReadTrace.FRAME, 512, 900, 300, 6)

        assertTrue(written.toString(), written.single().first == "fastboot_read")
        assertEquals("1", written.single().second["index"])
    }
}
