package io.legado.app.help.archive

import io.legado.app.help.storage.ArchiveLimits

/** Bounded ustar iteration. No conversion to Int until all offsets fit the input. */
internal object TarReader {
    fun forEach(data: ByteArray, checkActive: () -> Unit = {}, entry: (String, Int, Int, Int) -> Unit) {
        require(data.size <= ArchiveLimits.MAX_TOTAL_BYTES) { "TAR input size limit exceeded" }
        var pos = 0L
        var count = 0
        var total = 0L
        while (pos < data.size) {
            checkActive()
            require(data.size - pos >= 512) { "Truncated TAR header" }
            val start = pos.toInt()
            val header = data.copyOfRange(start, start + 512)
            if (header.all { it == 0.toByte() }) return
            require(++count <= ArchiveLimits.MAX_ENTRIES) { "TAR entry count limit exceeded" }
            fun field(offset: Int, length: Int) = header.decodeToString(offset, offset + length)
                .substringBefore('\u0000').trim()
            val size = field(124, 12).ifEmpty { "0" }.toLongOrNull(8)
                ?: error("Invalid TAR size")
            require(size in 0..ArchiveLimits.MAX_ENTRY_BYTES.toLong()) { "TAR entry size limit exceeded" }
            val expectedChecksum = field(148, 8).toLongOrNull(8) ?: error("Invalid TAR checksum")
            val checksum = header.indices.sumOf { if (it in 148..155) 32L else (header[it].toInt() and 255).toLong() }
            require(checksum == expectedChecksum) { "TAR checksum mismatch" }
            val prefix = field(345, 155)
            val name = (if (prefix.isEmpty()) "" else "$prefix/") + field(0, 100)
            ArchiveLimits.checkPath(name)
            val contentStart = pos + 512
            val contentEnd = contentStart + size
            val next = contentEnd + (512 - size % 512) % 512
            require(contentEnd <= data.size && next <= data.size && next > pos) { "Truncated TAR content" }
            total += size
            require(total <= ArchiveLimits.MAX_TOTAL_BYTES) { "TAR total size limit exceeded" }
            entry(name, header[156].toInt() and 255, contentStart.toInt(), contentEnd.toInt())
            pos = next
        }
    }
}
