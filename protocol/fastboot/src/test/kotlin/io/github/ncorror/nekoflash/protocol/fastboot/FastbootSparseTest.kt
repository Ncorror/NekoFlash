package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбиение образа на sparse-куски.
 *
 * Проверяется то, что проверить можно без устройства: форма заголовков, счёт
 * кусков и то, что готовый кусок помещается в объявленный предел. Примет ли их
 * устройство — вопрос аппаратного гейта, и тесты его не закрывают.
 */
class FastbootSparseTest {
    private val planner = FastbootSparsePlanner(blockBytes = BLOCK)

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    /** Магическое число и размеры заголовков — ровно как в AOSP. */
    @Test
    fun theHeaderCarriesTheAosSpelledFields() {
        val header = SparseFormat.header(blockBytes = BLOCK, totalBlocks = 10, totalChunks = 3)

        val view = le(header)
        assertEquals(28, header.size)
        assertEquals(0xED26FF3A.toInt(), view.int)
        assertEquals(1, view.short.toInt())
        assertEquals(0, view.short.toInt())
        assertEquals("file_hdr_sz", 28, view.short.toInt())
        assertEquals("chunk_hdr_sz", 12, view.short.toInt())
        assertEquals(BLOCK, view.int)
        assertEquals(10, view.int)
        assertEquals(3, view.int)
        assertEquals("контрольная сумма не посчитана, значит ноль", 0, view.int)
    }

    /** `total_sz` куска включает собственный заголовок — так сказано в AOSP. */
    @Test
    fun theChunkSizeIncludesItsOwnHeader() {
        val chunk = SparseFormat.chunk(SparseFormat.CHUNK_RAW, blocks = 2, dataBytes = 2 * BLOCK)

        val view = le(chunk)
        assertEquals(SparseFormat.CHUNK_RAW, view.short.toInt() and 0xFFFF)
        assertEquals("reserved1", 0, view.short.toInt())
        assertEquals("chunk_sz в блоках", 2, view.int)
        assertEquals("total_sz в байтах вместе с заголовком", 12 + 2 * BLOCK, view.int)
    }

    /** Заголовок читается little-endian, а не в порядке платформы. */
    @Test
    fun theHeaderIsLittleEndian() {
        val header = SparseFormat.header(BLOCK, totalBlocks = 1, totalChunks = 1)

        assertArrayEquals(
            "магическое число байт за байтом",
            byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte()),
            header.copyOfRange(0, 4),
        )
    }

    /** Sparse-вход узнаётся по магическому числу и только по нему. */
    @Test
    fun aSparseInputIsRecognisedByItsMagic() {
        assertTrue(SparseFormat.looksSparse(SparseFormat.header(BLOCK, 1, 1)))
        assertTrue(!SparseFormat.looksSparse(byteArrayOf(1, 2, 3, 4)))
        assertTrue("коротышка не sparse и не падение", !SparseFormat.looksSparse(byteArrayOf(1)))
    }

    /** Образ, помещающийся в предел, остаётся одним куском. */
    @Test
    fun animageThatFitsStaysWhole() {
        val outcome = planner.plan(imageBytes = 4L * BLOCK, maxBytes = 1_000_000)

        val pieces = (outcome as SparsePlanOutcome.Planned).pieces
        assertEquals(1, pieces.size)
        assertEquals(0, pieces.single().firstBlock)
        assertEquals(4, pieces.single().blocks)
    }

    /**
     * Каждый готовый кусок помещается в объявленный предел.
     *
     * Это главная проверка всего разбиения: промах здесь даёт `FAIL` от
     * устройства на последнем куске многогигабайтной прошивки.
     */
    @Test
    fun everyPieceFitsTheAnnouncedLimit() {
        val max = 10L * BLOCK

        val outcome = planner.plan(imageBytes = 100L * BLOCK, maxBytes = max)

        val pieces = (outcome as SparsePlanOutcome.Planned).pieces
        assertTrue("образ обязан быть разбит", pieces.size > 1)
        pieces.forEach { piece ->
            assertTrue("${piece.encodedBytes} > $max", piece.encodedBytes <= max)
        }
    }

    /** Куски покрывают образ целиком и не перекрываются. */
    @Test
    fun thePiecesCoverTheImageExactlyOnce() {
        val outcome = planner.plan(imageBytes = 100L * BLOCK, maxBytes = 10L * BLOCK)

        val pieces = (outcome as SparsePlanOutcome.Planned).pieces
        var expected = 0
        pieces.forEach { piece ->
            assertEquals("кусок начинается там, где кончился прошлый", expected, piece.firstBlock)
            expected += piece.blocks
        }
        assertEquals(100, expected)
    }

    /** Неполный последний блок считается целым: устройство пишет блоками. */
    @Test
    fun aPartialLastBlockCountsAsAWholeOne() {
        assertEquals(1, planner.blocksOf(1))
        assertEquals(1, planner.blocksOf(BLOCK.toLong()))
        assertEquals(2, planner.blocksOf(BLOCK + 1L))
    }

    /** Уже sparse — это названный пробел возможности, а не отказ по политике. */
    @Test
    fun anAlreadySparseInputIsNamedNotRefused() {
        val outcome = planner.plan(
            imageBytes = 100L * BLOCK,
            maxBytes = 10L * BLOCK,
            head = SparseFormat.header(BLOCK, 100, 1),
        )

        val named = outcome as SparsePlanOutcome.AlreadySparse
        assertTrue(named.detail, named.detail.contains("не написано"))
    }

    /** Предел меньше накладных расходов — это говорится числом, а не молчанием. */
    @Test
    fun aLimitSmallerThanTheOverheadIsNamed() {
        val outcome = planner.plan(imageBytes = 10L * BLOCK, maxBytes = 16)

        val small = outcome as SparsePlanOutcome.LimitTooSmall
        assertEquals(16L, small.maxBytes)
        assertTrue(small.required > small.maxBytes)
    }

    /** Кусок в середине объявляет пропуски с обеих сторон. */
    @Test
    fun aMiddlePieceDeclaresBothSkips() {
        val sink = ByteArrayOutputStream()
        val piece = SparsePiece(firstBlock = 2, blocks = 1, encodedBytes = 0)

        planner.write(sink, piece, totalBlocks = 5, imageBytes = 5L * BLOCK) { buffer ->
            buffer.fill(7)
            buffer.size
        }

        val view = le(sink.toByteArray())
        view.position(16)
        assertEquals("total_blks всего образа, а не куска", 5, view.int)
        assertEquals("пропуск до, данные, пропуск после", 3, view.int)
    }

    /** Первый кусок пропуска до себя не объявляет: пропускать нечего. */
    @Test
    fun theFirstPieceHasNoLeadingSkip() {
        val sink = ByteArrayOutputStream()
        val piece = SparsePiece(firstBlock = 0, blocks = 1, encodedBytes = 0)

        planner.write(sink, piece, totalBlocks = 2, imageBytes = 2L * BLOCK) { buffer ->
            buffer.fill(7)
            buffer.size
        }

        val bytes = sink.toByteArray()
        val firstChunk = le(bytes).also { it.position(SparseFormat.HEADER_BYTES) }
        assertEquals(SparseFormat.CHUNK_RAW, firstChunk.short.toInt() and 0xFFFF)
    }

    /** Записанное совпадает с обещанным размером куска. */
    @Test
    fun theWrittenSizeMatchesThePlannedOne() {
        val outcome = planner.plan(imageBytes = 30L * BLOCK, maxBytes = 10L * BLOCK)
        val piece = (outcome as SparsePlanOutcome.Planned).pieces.first()
        val sink = ByteArrayOutputStream()

        val written = planner.write(sink, piece, totalBlocks = 30, imageBytes = 30L * BLOCK) { buffer ->
            buffer.size
        }

        assertEquals(written, sink.size().toLong())
        assertTrue("обещано ${piece.encodedBytes}, записано $written", written!! <= piece.encodedBytes)
    }

    /**
     * Источник получает буфер ровно того размера, который нужен шагу.
     *
     * Иначе на хвосте он честно заполнит весь буфер и окажется «слишком
     * длинным»: попросить у него меньше, чем вмещает буфер, нечем. Этот тест и
     * поймал такой промах при написании.
     */
    @Test
    fun theSourceIsAskedExactlyForWhatTheStepNeeds() {
        val sink = ByteArrayOutputStream()
        val piece = SparsePiece(firstBlock = 0, blocks = 1, encodedBytes = 0)
        val asked = mutableListOf<Int>()

        planner.write(sink, piece, totalBlocks = 1, imageBytes = 100) { buffer ->
            asked += buffer.size
            buffer.size
        }

        assertEquals("ровно сто байт данных и ни байтом больше", listOf(100), asked)
    }

    /**
     * Хвост образа, не кратный блоку, дополняется до блока.
     *
     * Это не подмена содержимого: раздел пишется блоками, и последний блок
     * уходит целым в любом случае.
     */
    @Test
    fun aTailShorterThanABlockIsPaddedToTheBlock() {
        val sink = ByteArrayOutputStream()
        val piece = SparsePiece(firstBlock = 0, blocks = 1, encodedBytes = 0)
        var left = 10

        planner.write(sink, piece, totalBlocks = 1, imageBytes = 10) { buffer ->
            val given = left
            left = 0
            buffer.fill(3, 0, given)
            given
        }

        val body = sink.toByteArray().copyOfRange(SparseFormat.HEADER_BYTES + SparseFormat.CHUNK_HEADER_BYTES, sink.size())
        assertEquals("блок уходит целым", BLOCK, body.size)
        assertEquals("данные на месте", 3, body[9].toInt())
        assertEquals("хвост — нули, а не мусор", 0, body[10].toInt())
    }

    /** Источник кончился раньше объявленного — это ошибка, а не конец куска. */
    @Test
    fun aSourceEndingEarlyIsAFailureNotAnEnd() {
        val sink = ByteArrayOutputStream()
        val piece = SparsePiece(firstBlock = 0, blocks = 4, encodedBytes = 0)

        val written = planner.write(sink, piece, totalBlocks = 4, imageBytes = 4L * BLOCK) { 0 }

        assertEquals(null, written)
    }

    private companion object {
        const val BLOCK = 4096
    }
}
