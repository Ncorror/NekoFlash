package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.OutputStream

/**
 * Один кусок разбитого образа.
 *
 * [firstBlock] и [blocks] — координаты в **исходном** образе, а не в куске:
 * каждый кусок объявляет полный размер образа и заполняет только свою часть,
 * поэтому устройство складывает их по номерам блоков, а не по порядку прихода.
 */
public data class SparsePiece(
    val firstBlock: Int,
    val blocks: Int,
    /** Сколько байт займёт готовый кусок вместе со всеми заголовками. */
    val encodedBytes: Long,
)

/** Почему образ разбить не вышло. */
public sealed interface SparsePlanOutcome {
    /** Разбит. Один кусок в списке — законный случай: образ и так помещался. */
    public data class Planned(val pieces: List<SparsePiece>) : SparsePlanOutcome

    /**
     * Вход уже sparse, и переразбить его мы пока не умеем.
     *
     * Это **пробел возможности, а не отказ по политике**: sparse-вход надо
     * сначала разобрать по кускам, и делать вид, что мы его разобрали, было бы
     * хуже, чем сказать прямо. Устройству такой образ по-прежнему можно отдать
     * целиком, если он помещается в буфер.
     */
    public data class AlreadySparse(val detail: String) : SparsePlanOutcome

    /** Предел устройства меньше, чем накладные расходы одного куска. */
    public data class LimitTooSmall(val maxBytes: Long, val required: Long) : SparsePlanOutcome
}

/**
 * Разбиение образа на sparse-куски под предел загрузки устройства.
 *
 * **Новая работа, а не перенос.** Ни Legacy, ни A2 этого не делают, и
 * единственный источник — AOSP `libsparse`. Оттуда взяты две вещи, и обе
 * названы. Первая — формат (`SparseFormat`). Вторая — **накладные расходы при
 * разрезании**, дословно из `sparse_file_resparse`:
 *
 * > «overhead is sparse file header, the potential end skip chunk and crc
 * > chunk»: `sizeof(sparse_header_t) + 2 * sizeof(chunk_header_t) +
 * > sizeof(uint32_t)`.
 *
 * Мы считаем свои куски так же, и по той же причине: под предел обязан
 * помещаться **готовый кусок**, а не голые данные. Промахнуться здесь значит
 * получить `FAIL` от устройства на последнем куске многогигабайтной прошивки.
 *
 * **Предел не превращается в нашу проверку содержимого.** `max-download-size`
 * говорит, какими кусками просить; годится ли образ — решает устройство
 * (`03` §2), ровно как с `max-fetch-size` в [FastbootFetch].
 */
public class FastbootSparsePlanner(
    private val blockBytes: Int = SparseFormat.DEFAULT_BLOCK_BYTES,
) {
    init {
        require(blockBytes > 0) { "размер блока обязан быть положительным: $blockBytes" }
    }

    /**
     * Раскладывает образ [imageBytes] на куски не длиннее [maxBytes].
     *
     * @param head первые байты файла — по ним видно, не sparse ли он уже.
     */
    public fun plan(imageBytes: Long, maxBytes: Long, head: ByteArray = ByteArray(0)): SparsePlanOutcome {
        require(imageBytes >= 0L) { "размер образа не может быть отрицательным: $imageBytes" }
        return when {
            SparseFormat.looksSparse(head) -> SparsePlanOutcome.AlreadySparse(
                "образ уже sparse: переразбиение sparse-входа не написано",
            )

            maxBytes <= OVERHEAD_BYTES + blockBytes -> SparsePlanOutcome.LimitTooSmall(
                maxBytes,
                OVERHEAD_BYTES + blockBytes.toLong(),
            )

            else -> SparsePlanOutcome.Planned(pieces(imageBytes, maxBytes))
        }
    }

    private fun pieces(imageBytes: Long, maxBytes: Long): List<SparsePiece> {
        val totalBlocks = blocksOf(imageBytes)
        val perPiece = ((maxBytes - OVERHEAD_BYTES) / blockBytes).coerceAtLeast(1L)
        val result = mutableListOf<SparsePiece>()
        var first = 0L
        while (first < totalBlocks) {
            val blocks = minOf(perPiece, totalBlocks - first)
            result += SparsePiece(
                firstBlock = first.toInt(),
                blocks = blocks.toInt(),
                encodedBytes = OVERHEAD_BYTES + blocks * blockBytes,
            )
            first += blocks
        }
        // Пустой образ — один пустой кусок, а не ноль кусков: «нечего слать» и
        // «не о чем говорить» это разные ответы, и второй молчанием не передать.
        return result.ifEmpty { listOf(SparsePiece(0, 0, OVERHEAD_BYTES)) }
    }

    /**
     * Сколько блоков занимает образ.
     *
     * Неполный последний блок считается целым: устройство пишет блоками, и
     * недосказать последний значило бы отдать раздел короче образа.
     */
    public fun blocksOf(imageBytes: Long): Long =
        (imageBytes + blockBytes - 1) / blockBytes

    /**
     * Пишет один кусок: заголовок, пропуск до него, данные, пропуск после.
     *
     * Пропуски — `DONT_CARE`, и они обязательны: кусок объявляет **полный**
     * размер образа, а заполняет свою часть, поэтому пропущенное надо назвать,
     * а не оставить необъяснённым. Так устройство и складывает куски по
     * номерам блоков.
     *
     * Данные берутся у [source] порциями: образ бывает больше памяти, и
     * собирать кусок целиком, чтобы потом записать, значило бы ограничить
     * прошивку оперативной памятью.
     *
     * @param source заполняет буфер и возвращает число байт; `0` или меньше —
     *   источник кончился раньше времени, и это ошибка, а не конец куска.
     * @return сколько байт записано, либо `null`, если источник кончился рано.
     */
    public fun write(
        sink: OutputStream,
        piece: SparsePiece,
        totalBlocks: Int,
        imageBytes: Long,
        source: (ByteArray) -> Int,
    ): Long? {
        val skipBefore = piece.firstBlock
        val skipAfter = totalBlocks - piece.firstBlock - piece.blocks
        val chunks = listOf(skipBefore, piece.blocks, skipAfter).count { it > 0 }
        sink.write(SparseFormat.header(blockBytes, totalBlocks, chunks))
        if (skipBefore > 0) sink.write(SparseFormat.chunk(SparseFormat.CHUNK_DONT_CARE, skipBefore, 0))
        val written = if (piece.blocks > 0) body(sink, piece, imageBytes, source) else 0L
        return if (written == null) {
            null
        } else {
            if (skipAfter > 0) sink.write(SparseFormat.chunk(SparseFormat.CHUNK_DONT_CARE, skipAfter, 0))
            SparseFormat.HEADER_BYTES + chunks.toLong() * SparseFormat.CHUNK_HEADER_BYTES + written
        }
    }

    /**
     * Данные куска, дополненные нулями до границы блока.
     *
     * Дополнение — не подмена содержимого: раздел пишется блоками, и последний
     * блок образа, не кратного блоку, уходит целым в любом случае. Сказать об
     * этом надо здесь, потому что снаружи это выглядит как лишние байты.
     */
    private fun body(
        sink: OutputStream,
        piece: SparsePiece,
        imageBytes: Long,
        source: (ByteArray) -> Int,
    ): Long? {
        val declared = piece.blocks.toLong() * blockBytes
        sink.write(SparseFormat.chunk(SparseFormat.CHUNK_RAW, piece.blocks, declared.toInt()))
        val available = (imageBytes - piece.firstBlock.toLong() * blockBytes).coerceAtLeast(0L)
        val fromSource = minOf(declared, available)
        // Буфер размером ровно в то, что нужно на этом шаге, а не всегда
        // максимальный. Источник видит только буфер, и попросить у него меньше,
        // чем буфер вмещает, нечем: он честно заполнит всё и окажется «слишком
        // длинным» на хвосте. Размер буфера здесь и есть просьба.
        var buffer = ByteArray(minOf(COPY_BUFFER_BYTES.toLong(), fromSource.coerceAtLeast(1L)).toInt())
        var copied = 0L
        var short = false
        while (!short && copied < fromSource) {
            val wanted = minOf(buffer.size.toLong(), fromSource - copied).toInt()
            if (wanted != buffer.size) buffer = ByteArray(wanted)
            val read = source(buffer)
            if (read <= 0 || read > buffer.size) {
                short = true
            } else {
                sink.write(buffer, 0, read)
                copied += read
            }
        }
        return if (short) {
            null
        } else {
            padTo(sink, declared - copied)
            declared
        }
    }

    private fun padTo(sink: OutputStream, bytes: Long) {
        val zeros = ByteArray(COPY_BUFFER_BYTES)
        var left = bytes
        while (left > 0) {
            val step = minOf(zeros.size.toLong(), left).toInt()
            sink.write(zeros, 0, step)
            left -= step
        }
    }

    private companion object {
        /**
         * Накладные расходы куска, дословно из AOSP `sparse_file_resparse`.
         *
         * Заголовок файла, **два** заголовка кусков (пропуск до и пропуск
         * после) и четыре байта под CRC-кусок, которого мы не пишем: место под
         * него в расчёте AOSP зарезервировано, и вычитать его тоже значит
         * оставаться в тех же границах, в которых остаётся `fastboot`.
         */
        const val OVERHEAD_BYTES: Long = SparseFormat.HEADER_BYTES.toLong() +
            2L * SparseFormat.CHUNK_HEADER_BYTES +
            Int.SIZE_BYTES

        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
