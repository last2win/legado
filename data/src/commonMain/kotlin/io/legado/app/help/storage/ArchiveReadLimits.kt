package io.legado.app.help.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.Source

/** Bound actual input, even when a remote server or a changing file lies about its size. */
internal fun Source.readArchiveBytes(limit: Int): ByteArray {
    require(limit in 0..ArchiveLimits.MAX_INPUT_BYTES) { "Archive input size limit exceeded" }
    val output = Buffer()
    while (true) {
        val count = read(output, minOf(8192L, limit.toLong() - output.size + 1))
        if (count == -1L) return output.readByteArray()
        require(count > 0 && output.size <= limit) { "Archive response exceeds requested size" }
    }
}

/** Async counterpart for streaming HTTP bodies; callers own and close the transport. */
internal suspend fun readArchiveBytes(limit: Int, read: suspend (ByteArray, Int) -> Int): ByteArray {
    require(limit in 0..ArchiveLimits.MAX_INPUT_BYTES) { "Archive input size limit exceeded" }
    val output = Buffer()
    val chunk = ByteArray(minOf(8192, limit + 1))
    while (true) {
        currentCoroutineContext().ensureActive()
        val requested = minOf(chunk.size.toLong(), limit.toLong() - output.size + 1).toInt()
        val count = read(chunk, requested)
        if (count == -1) return output.readByteArray()
        require(count in 1..requested && count.toLong() <= limit - output.size) {
            "Archive response exceeds requested size"
        }
        output.write(chunk, 0, count)
    }
}
