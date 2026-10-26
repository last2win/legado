package io.legado.app.model.fileBook

import io.legado.app.help.storage.ArchiveLimits
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** java.util.zip.Inflater(nowrap=true)，字节输出与 app 侧 InflaterInputStream 逐字节一致 */
actual fun inflateRaw(
    compressed: ByteArray, offset: Int, length: Int, expectedSize: Int
): ByteArray {
    require(offset >= 0 && length >= 0 && offset <= compressed.size - length) { "Invalid deflate range" }
    val limit = minOf(ArchiveLimits.MAX_ENTRY_BYTES.toLong(), length.toLong() * ArchiveLimits.MAX_COMPRESSION_RATIO,
        if (expectedSize >= 0) expectedSize.toLong() else Long.MAX_VALUE).toInt()
    val inflater = Inflater(true)
    inflater.setInput(compressed, offset, length)
    try {
        val out = ByteArrayOutputStream(minOf(limit, 8192))
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n > 0) {
                require(!Thread.currentThread().isInterrupted) { "ZIP decompression interrupted" }
                require(n <= limit - out.size()) { "ZIP output limit exceeded" }
                out.write(buf, 0, n)
            } else {
                require(inflater.finished()) { "Truncated or invalid deflate input" }
            }
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
