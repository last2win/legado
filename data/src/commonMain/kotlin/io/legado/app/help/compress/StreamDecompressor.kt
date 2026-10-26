package io.legado.app.help.compress

import io.legado.app.constant.AppLog
import io.legado.app.help.storage.ArchiveLimits
import kotlinx.coroutines.CancellationException
import okio.Source
import okio.Buffer
import okio.GzipSource
import okio.Inflater
import okio.InflaterSource
import okio.use

/**
 * 一整段压缩数据的内存解压 (gzip / zlib / 裸 deflate), 四端同一实现。
 *
 * 与 [io.legado.app.help.archive.ArchiveProvider] 的分工: 后者按压缩包格式解归档并落盘,
 * 本类只解"一段被压缩的字节" (接口响应体、字段值等)。
 *
 * okio 的 [GzipSource] / [InflaterSource] 在 JVM (委托 java.util.zip) 与 Kotlin/Native
 * (纯 Kotlin 实现) 均可用, 故不必分端写第二套。
 */
internal object StreamDecompressor {

    /**
     * 解压 [bytes]。
     *
     * @param format `gzip` / `zlib` / `deflate`(裸流, 无 zlib 头) / `auto`(按魔数判断)
     * @return 解压结果; 数据损坏、截断或与 [format] 不符时返回 null
     * @throws IllegalArgumentException [format] 不是上述四种之一
     */
    fun decompress(bytes: ByteArray, format: String, checkCancelled: () -> Unit = {}): ByteArray? {
        require(bytes.size <= ArchiveLimits.MAX_INPUT_BYTES) { "Compressed input size limit exceeded" }
        checkCancelled()
        return when (format.trim().lowercase()) {
            GZIP -> gunzip(bytes, checkCancelled)
            ZLIB -> inflate(bytes, nowrap = false, checkCancelled)
            DEFLATE -> inflate(bytes, nowrap = true, checkCancelled)
            AUTO -> when {
                isGzip(bytes) -> gunzip(bytes, checkCancelled)
                isZlib(bytes) -> inflate(bytes, nowrap = false, checkCancelled)
                else -> inflate(bytes, nowrap = true, checkCancelled)
            }

            else -> throw IllegalArgumentException("不支持的解压格式: $format")
        }

    }

    private fun gunzip(bytes: ByteArray, checkCancelled: () -> Unit): ByteArray? = try {
        readBounded(GzipSource(Buffer().write(bytes)), bytes.size, checkCancelled)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.put("gzip 解压失败(size=${bytes.size}): ${e.message}")
        null
    }

    private fun inflate(bytes: ByteArray, nowrap: Boolean, checkCancelled: () -> Unit): ByteArray? = try {
        readBounded(InflaterSource(Buffer().write(bytes), Inflater(nowrap)), bytes.size, checkCancelled)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.put("inflate 解压失败(nowrap=$nowrap, size=${bytes.size}): ${e.message}")
        null
    }

    private fun readBounded(source: Source, compressedSize: Int, checkCancelled: () -> Unit): ByteArray = source.use {
        val limit = minOf(ArchiveLimits.MAX_ENTRY_BYTES.toLong(), compressedSize.toLong() * ArchiveLimits.MAX_COMPRESSION_RATIO)
        val output = Buffer()
        while (true) {
            checkCancelled()
            val count = source.read(output, minOf(8192L, limit - output.size + 1))
            if (count == -1L) break
            require(count > 0 && output.size <= limit) { "Decompression output limit exceeded" }
        }
        output.readByteArray()
    }

    /** gzip 魔数 (RFC1952): 1F 8B。 */
    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    /** zlib 头 (RFC1950): CM=8(deflate), CINFO<=7(窗口<=32K), 且 (CMF shl 8 or FLG) 能被 31 整除。 */
    private fun isZlib(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val cmf = bytes[0].toInt() and 0xFF
        val flg = bytes[1].toInt() and 0xFF
        return cmf and 0x0F == 8 && cmf shr 4 <= 7 && (cmf shl 8 or flg) % 31 == 0
    }

    private const val GZIP = "gzip"
    private const val ZLIB = "zlib"
    private const val DEFLATE = "deflate"
    private const val AUTO = "auto"
}
