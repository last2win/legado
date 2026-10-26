package io.legado.app.model.fileBook

import io.legado.app.help.storage.readArchiveBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Source
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class ArchiveReadLimitsTest {
    @Test
    fun oversizedResponseStopsAfterBudgetPlusOneByte() {
        var consumed = 0L
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (consumed == 1_048_576L) return -1
                val count = minOf(byteCount, 1_048_576L - consumed)
                sink.write(ByteArray(count.toInt()))
                consumed += count
                return count
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        assertThrows(IllegalArgumentException::class.java) { source.readArchiveBytes(100) }
        assertEquals(101L, consumed)
    }

    @Test
    fun exactAndShortRangesRemainReadable() {
        assertArrayEquals(byteArrayOf(1, 2), Buffer().write(byteArrayOf(1, 2)).readArchiveBytes(2))
        assertArrayEquals(byteArrayOf(1), Buffer().writeByte(1).readArchiveBytes(2))
        assertArrayEquals(byteArrayOf(), Buffer().readArchiveBytes(0))
    }

    @Test
    fun asynchronousResponseStopsAtBudget() = runBlocking {
        var consumed = 0
        try {
            readArchiveBytes(100) { chunk, requested ->
                chunk.fill(0)
                consumed += requested
                requested
            }
            fail("Oversized response accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(101, consumed)
        }
    }

    @Test
    fun asynchronousExactReadAndCancellation() = runBlocking {
        var calls = 0
        assertArrayEquals(byteArrayOf(65), readArchiveBytes(1) { chunk, _ ->
            if (++calls == 1) { chunk[0] = 65; 1 } else -1
        })
        try {
            readArchiveBytes(10) { _, _ -> throw CancellationException("cancel") }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
}
