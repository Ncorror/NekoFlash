package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Формат sparse-образа Android.
 *
 * **Единственный источник здесь — AOSP**, и это записано заранее: ни Legacy, ни
 * A2 sparse не умеют — слова `sparse` нет ни в одном архиве (`09`, строка
 * «разбиение sparse-образа»). Поэтому поля взяты не по памяти, а из
 * `system/core/libsparse/sparse_format.h`, вместе с их комментариями:
 *
 * ```c
 * typedef struct sparse_header {
 *   __le32 magic;          /* 0xed26ff3a */
 *   __le16 major_version;  /* (0x1) - reject images with higher major versions */
 *   __le16 minor_version;  /* (0x0) - allow images with higher minor versions */
 *   __le16 file_hdr_sz;    /* 28 bytes for first revision of the file format */
 *   __le16 chunk_hdr_sz;   /* 12 bytes for first revision of the file format */
 *   __le32 blk_sz;         /* block size in bytes, must be a multiple of 4 (4096) */
 *   __le32 total_blks;     /* total blocks in the non-sparse output image */
 *   __le32 total_chunks;   /* total chunks in the sparse input image */
 *   __le32 image_checksum; /* CRC32 of the original data, counting "don't care" as 0 */
 * } sparse_header_t;
 *
 * typedef struct chunk_header {
 *   __le16 chunk_type;     /* 0xCAC1 -> raw; 0xCAC2 -> fill; 0xCAC3 -> don't care */
 *   __le16 reserved1;
 *   __le32 chunk_sz;       /* in blocks in output image */
 *   __le32 total_sz;       /* in bytes of chunk input file including chunk header and data */
 * } chunk_header_t;
 * ```
 *
 * **Всё little-endian** — `__le32`/`__le16` в объявлении, и это не деталь:
 * прочитать заголовок в порядке платформы значило бы увидеть чужое число на
 * любом big-endian хосте и не заметить.
 */
public object SparseFormat {
    /** `0xed26ff3a`, как в заголовке AOSP. */
    public const val MAGIC: Int = 0xED26FF3A.toInt()

    /** Старшую версию выше своей отвергаем — так сказано в комментарии AOSP. */
    public const val MAJOR_VERSION: Int = 1

    /** Младшую версию выше своей принимаем — там же. */
    public const val MINOR_VERSION: Int = 0

    /** «28 bytes for first revision of the file format». */
    public const val HEADER_BYTES: Int = 28

    /** «12 bytes for first revision of the file format». */
    public const val CHUNK_HEADER_BYTES: Int = 12

    /** Данные как есть: `chunk_sz` блоков следом за заголовком. */
    public const val CHUNK_RAW: Int = 0xCAC1

    /** Четыре байта узора на `chunk_sz` блоков. */
    public const val CHUNK_FILL: Int = 0xCAC2

    /** Блоки, о которых образ ничего не говорит. Данных за заголовком нет. */
    public const val CHUNK_DONT_CARE: Int = 0xCAC3

    /** CRC32 всего образа. Пишется только по отдельной просьбе. */
    public const val CHUNK_CRC32: Int = 0xCAC4

    /** Обычный размер блока Android-образов; `blk_sz` обязан делиться на 4. */
    public const val DEFAULT_BLOCK_BYTES: Int = 4096

    /**
     * Похоже ли начало файла на sparse-образ.
     *
     * Только по магическому числу и только по нему: разбирать остальное, чтобы
     * ответить «да» или «нет», значило бы отказывать в чтении образу, который
     * мы не поняли, — а решает, годится ли образ, устройство (`03` §2).
     */
    public fun looksSparse(head: ByteArray): Boolean =
        head.size >= Int.SIZE_BYTES && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int == MAGIC

    /**
     * Заголовок образа.
     *
     * `imageChecksum` пишется нулём: AOSP заполняет его только когда попросили
     * CRC (`sparse_file_new` оставляет ноль), и выдавать посчитанный ноль за
     * контрольную сумму содержимого значило бы соврать о проверке, которой не
     * было.
     */
    public fun header(blockBytes: Int, totalBlocks: Int, totalChunks: Int): ByteArray {
        require(blockBytes > 0 && blockBytes % BLOCK_ALIGNMENT == 0) {
            "blk_sz обязан быть кратен $BLOCK_ALIGNMENT: $blockBytes"
        }
        require(totalBlocks >= 0) { "total_blks не может быть отрицательным: $totalBlocks" }
        require(totalChunks >= 0) { "total_chunks не может быть отрицательным: $totalChunks" }
        return buffer(HEADER_BYTES)
            .putInt(MAGIC)
            .putShort(MAJOR_VERSION.toShort())
            .putShort(MINOR_VERSION.toShort())
            .putShort(HEADER_BYTES.toShort())
            .putShort(CHUNK_HEADER_BYTES.toShort())
            .putInt(blockBytes)
            .putInt(totalBlocks)
            .putInt(totalChunks)
            .putInt(0)
            .array()
    }

    /**
     * Заголовок куска.
     *
     * `total_sz` — «in bytes of chunk input file **including chunk header** and
     * data», то есть заголовок входит в него сам. Написать туда только длину
     * данных значило бы сдвинуть разбор на двенадцать байт на каждом куске.
     */
    public fun chunk(type: Int, blocks: Int, dataBytes: Int): ByteArray {
        require(blocks >= 0) { "chunk_sz не может быть отрицательным: $blocks" }
        require(dataBytes >= 0) { "длина данных не может быть отрицательной: $dataBytes" }
        return buffer(CHUNK_HEADER_BYTES)
            .putShort(type.toShort())
            .putShort(0)
            .putInt(blocks)
            .putInt(CHUNK_HEADER_BYTES + dataBytes)
            .array()
    }

    private fun buffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** «must be a multiple of 4» — из комментария к `blk_sz`. */
    private const val BLOCK_ALIGNMENT = 4
}
