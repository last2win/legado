package security

import io.legado.app.help.compress.StreamDecompressor
import io.legado.app.help.storage.ArchiveLimits
import io.legado.app.help.storage.NativeZipCodec
import io.legado.app.model.fileBook.*
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException

private var checks = 0
private fun rejects(block: () -> Unit) { check(runCatching(block).isFailure); checks++ }
private fun core(bytes: ByteArray, truncated: Boolean = false) = RemoteZipCore(RangedSource { offset, length, _ ->
    val end = minOf(bytes.size.toLong(), offset + length).toInt()
    if (offset >= end) ByteArray(0) else bytes.copyOfRange(offset.toInt(), if (truncated && offset > 0) end - 1 else end)
}, "test", bytes.size.toLong())
private fun stored(): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("x").apply {
            method = java.util.zip.ZipEntry.STORED; size = 1; compressedSize = 1
            crc = CRC32().apply { update(byteArrayOf(65)) }.value
        })
        z.write(byteArrayOf(65)); z.closeEntry()
    }
}.toByteArray()
private fun putInt(b: ByteArray, offset: Int, value: Int) { repeat(4) { b[offset + it] = (value ushr (8 * it)).toByte() } }
fun main() {
    for (input in listOf(stored(), zip("目录/chapter.txt" to "chapter text".repeat(50).toByteArray()))) {
        val c = core(input); val m = c.ensureMeta().values.single()
        check(c.readDecompressed(m).size.toLong() == m.entry.size); checks++
        check(c.readDecompressed(m).size.toLong() == m.entry.size); checks++ // cached offsets
    }
    val good = stored()
    val central = ZipEntryReader.parseEocd(good, ZipEntryReader.findEocd(good)).centralOffset.toInt()
    // Same attack as the audit: tiny actual input, huge compressed declaration, expanded declaration of one byte.
    for (size in listOf(1_048_576, Int.MAX_VALUE, -1)) {
        val corrupt = good.copyOf(); putInt(corrupt, central + ZipConstants.CENSIZ, size)
        rejects { core(corrupt).let { c -> c.readDecompressed(c.ensureMeta().values.single()) } }
        rejects { NativeZipCodec.unzipToMap(corrupt) }
    }
    val short = core(good, truncated = true)
    rejects { short.ensureMeta() }
    val m = core(good).ensureMeta().values.single()
    val c = core(good); c.ensureMeta()
    m.dataOffset = Long.MAX_VALUE
    rejects { c.readCompressed(m) }
    val badCrc = good.copyOf(); badCrc[31] = 66
    rejects { core(badCrc).let { it.readDecompressed(it.ensureMeta().values.single()) } }
    rejects { c.preset(10, 20, 1) }
    rejects { c.preset(100, 0, ArchiveLimits.MAX_ENTRIES + 1) }
    rejects { core(good).restore(central.toLong() + 47, central.toLong(), listOf(ZipEntry("x", size = 1, compressedSize = -1, method = 0))) }
    for (format in listOf("gzip", "zlib", "deflate")) {
        fun pack(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
            val stream = if (format == "gzip") GZIPOutputStream(out) else DeflaterOutputStream(out, Deflater(9, format == "deflate"))
            stream.use { it.write(bytes) }
        }.toByteArray()
        val text = "normal compressed text".toByteArray()
        check(StreamDecompressor.decompress(pack(text), format)!!.contentEquals(text)); checks++
        val bomb = pack(ByteArray(2 * 1024 * 1024))
        check(StreamDecompressor.decompress(bomb, format) == null); checks++
        check(StreamDecompressor.decompress(pack(text).dropLast(4).toByteArray(), format) == null); checks++
        rejects { StreamDecompressor.decompress(pack(text), format) { throw CancellationException("cancelled") } }
    }
    // The output cap must also reject a stream whose compression ratio remains below 1000.
    val pattern = ByteArray(4096).also { java.util.Random(7).nextBytes(it) }
    val oversized = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { gz ->
        repeat(ArchiveLimits.MAX_ENTRY_BYTES / pattern.size + 1) { gz.write(pattern) }
    } }.toByteArray()
    check(oversized.size.toLong() * ArchiveLimits.MAX_COMPRESSION_RATIO > ArchiveLimits.MAX_ENTRY_BYTES)
    check(StreamDecompressor.decompress(oversized, "gzip") == null); checks++
    var calls = 0
    rejects { StreamDecompressor.decompress(oversized, "gzip") { if (++calls == 3) throw CancellationException("cancel") } }
    check(calls == 3); checks++
    rejects { core(good).restore(1000, 0, List(5) { ZipEntry("file$it", size = ArchiveLimits.MAX_ENTRY_BYTES.toLong(),
        compressedSize = 100_000, method = 8) }) }
    val raw = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out, Deflater(9, true)).use { it.write(ByteArray(2 * 1024 * 1024)) } }.toByteArray()
    rejects { inflateRaw(raw, 0, raw.size, 1) }
    rejects { inflateRaw(raw, 0, raw.size, -1) }
    rejects { NativeZipCodec.inflate(raw, 0, raw.size, 1) }
    println("Archive production entry points: PASS ($checks assertions)")
}
