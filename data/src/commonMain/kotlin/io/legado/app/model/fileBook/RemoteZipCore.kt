package io.legado.app.model.fileBook

import io.legado.app.help.storage.ArchiveLimits

/**
 * 远程 zip 不整包读结构核心：EOCD 定位 + central directory 解析 + 按需读压缩数据。
 * 无锁，线程安全由调用方保证（app 侧 RemoteZipWrapper 加 @Synchronized）。
 */
class RemoteZipCore(
    private val source: RangedSource,
    private val name: String,
    val fileSize: Long,
    private val checkCancelled: () -> Unit = {},
) {

    class EntryMetadata(
        val entry: ZipEntry, val entryOffset: Int, var dataOffset: Long? = null
    )

    /** 压缩数据切片，语义同 ByteArrayInputStream(bytes, offset, length) */
    class Chunk(val bytes: ByteArray, val offset: Int, val length: Int)

    private var entriesMetadata: HashMap<String, EntryMetadata>? = null
    private var closed = false
    var eocdOffset = 0L; private set
    var centralOffset = 0L; private set
    var entryCount = 0; private set

    /** 预置已缓存的目录定位，跳过 EOCD 探测 */
    fun preset(eocd: Long, central: Long, count: Int) {
        validateDirectory(eocd, central, count)
        eocdOffset = eocd; centralOffset = central; entryCount = count
    }

    fun ensureMeta(): HashMap<String, EntryMetadata> {
        checkCancelled()
        if (closed) throw IllegalStateException("Closed: $name")
        require(fileSize in 0..ArchiveLimits.MAX_INPUT_BYTES.toLong()) { "ZIP input size limit exceeded" }
        entriesMetadata?.let { return it }
        if (eocdOffset == 0L) {
            val sSize = if (fileSize > 0) minOf(fileSize, 65600L).toInt() else 65600
            val baseOffset = if (fileSize > 0) fileSize - sSize else 0L
            val data = source.readRange(baseOffset, sSize, fileSize)
            val pos = ZipEntryReader.findEocd(data)
            if (pos < 0) throw ZipIOException("No EOCD: $name")
            val dir = ZipEntryReader.parseEocd(data, pos, baseOffset)
            eocdOffset = dir.eocdOffset
            entryCount = dir.entryCount
            centralOffset = dir.centralOffset
        }
        validateDirectory(eocdOffset, centralOffset, entryCount)
        val dir = readExact(centralOffset, (eocdOffset - centralOffset).toInt())
        var total = 0L
        return HashMap<String, EntryMetadata>(entryCount * 2).also { map ->
            ZipEntryReader.readCentralDirectory(dir, entryCount) { entry, _, _, _ ->
                checkCancelled()
                validateEntry(entry)
                total += entry.size
                require(total <= ArchiveLimits.MAX_TOTAL_BYTES) { "ZIP total size limit exceeded" }
                require(!map.containsKey(entry.name)) { "Duplicate ZIP entry" }
                map[entry.name] = EntryMetadata(entry, entry.entryOffset)
            }
            entriesMetadata = map
        }
    }

    fun restore(eocd: Long, central: Long, es: List<ZipEntry>) {
        validateDirectory(eocd, central, es.size)
        es.forEach(::validateEntry)
        require(es.sumOf { it.size } <= ArchiveLimits.MAX_TOTAL_BYTES) { "ZIP total size limit exceeded" }
        require(es.map { it.name }.distinct().size == es.size) { "Duplicate ZIP entry" }
        eocdOffset = eocd; centralOffset = central
        entriesMetadata = HashMap<String, EntryMetadata>(es.size * 2).apply {
            es.forEach { e ->
                put(e.name, EntryMetadata(e, e.entryOffset))
            }
        }
        entryCount = es.size
    }

    fun close() {
        closed = true; entriesMetadata = null
    }

    /**
     * 读 entry 并出内容：结构读取→解压闭环。
     * method==0（STORED）原样返回，method==8（DEFLATE）走 inflateRaw，其余抛异常。
     */
    fun readDecompressed(m: EntryMetadata): ByteArray {
        val chunk = readCompressed(m)
        val result = when (m.entry.method) {
            0 -> if (chunk.offset == 0 && chunk.length == chunk.bytes.size) {
                chunk.bytes
            } else {
                chunk.bytes.copyOfRange(chunk.offset, chunk.offset + chunk.length)
            }

            8 -> {
                val size = m.entry.size.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1
                inflateRaw(chunk.bytes, chunk.offset, chunk.length, size)
            }

            else -> throw ZipIOException("Unknown compression method ${m.entry.method}: ${m.entry.name}")
        }
        require(result.size.toLong() == m.entry.size) { "Invalid ZIP expanded size" }
        if (m.entry.crc >= 0) require(zipCrc32(result) == m.entry.crc) { "ZIP CRC mismatch" }
        checkCancelled()
        return result
    }

    private fun validateDirectory(eocd: Long, central: Long, count: Int) {
        require(fileSize in 0..ArchiveLimits.MAX_INPUT_BYTES.toLong()) { "ZIP input size limit exceeded" }
        require(central >= 0 && eocd >= central && eocd <= ArchiveLimits.MAX_INPUT_BYTES &&
            (fileSize == 0L || eocd <= fileSize - ZipConstants.ENDHDR)) { "Invalid ZIP directory range" }
        require(count in 0..ArchiveLimits.MAX_ENTRIES && count.toLong() * ZipConstants.CENHDR <= eocd - central) {
            "Invalid ZIP entry count"
        }
    }

    private fun validateEntry(entry: ZipEntry) {
        require(entry.compressedSize in 0..ArchiveLimits.MAX_INPUT_BYTES.toLong() &&
            entry.size in 0..ArchiveLimits.MAX_ENTRY_BYTES.toLong()) { "ZIP entry size limit exceeded" }
        ArchiveLimits.checkEntry(entry.compressedSize.toInt(), entry.size.toInt())
        ArchiveLimits.checkPath(entry.name)
        if (entry.method != 0 && entry.method != 8) throw ZipIOException("Unknown compression method ${entry.method}: ${entry.name}")
        require(entry.flags and 1 == 0) { "Encrypted ZIP entry is unsupported" }
        if (entry.method == 0) require(entry.compressedSize == entry.size) { "Invalid STORED entry size" }
    }

    private fun readExact(offset: Long, length: Int): ByteArray {
        checkCancelled()
        check(!closed) { "Closed: $name" }
        val limit = if (fileSize > 0) fileSize else ArchiveLimits.MAX_INPUT_BYTES.toLong()
        require(offset >= 0 && length >= 0 && offset <= limit && length.toLong() <= limit - offset) {
            "Invalid ZIP data range"
        }
        return source.readRange(offset, length, fileSize).also {
            require(it.size == length) { "Truncated ZIP input" }
        }
    }

    /** 读 entry 原始压缩数据（不解压），首次读附带解析 LOC 头并缓存数据区偏移 */
    fun readCompressed(m: EntryMetadata): Chunk {
        checkCancelled()
        val entry = m.entry
        validateEntry(entry)
        val cSize = entry.compressedSize.toInt()
        val dataOffset = m.dataOffset ?: run {
            val entryOffset = m.entryOffset.toLong() and 0xffffffffL
            require(entryOffset <= centralOffset - ZipConstants.LOCHDR) { "Invalid ZIP local header range" }
            val header = readExact(entryOffset, ZipConstants.LOCHDR)
            require(ZipConstants.readLeInt(header, 0) == ZipConstants.LOCSIG.toInt()) { "Invalid ZIP local signature" }
            require(ZipConstants.readLeShort(header, 6) and 1 == 0 &&
                ZipConstants.readLeShort(header, 8) == entry.method) { "Invalid ZIP local method or flags" }
            val nameLength = ZipConstants.readLeShort(header, ZipConstants.LOCNAM)
            val extraLength = ZipConstants.readLeShort(header, ZipConstants.LOCEXT)
            entryOffset + ZipConstants.LOCHDR + nameLength + extraLength
        }
        require(dataOffset >= 0 && dataOffset <= centralOffset && cSize.toLong() <= centralOffset - dataOffset) {
            "Invalid ZIP compressed data range"
        }
        val bytes = readExact(dataOffset, cSize)
        m.dataOffset = dataOffset
        return Chunk(bytes, 0, bytes.size)
    }
}

private fun zipCrc32(bytes: ByteArray): Long {
    var crc = -1
    for (byte in bytes) {
        crc = crc xor (byte.toInt() and 0xff)
        repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
    }
    return crc.inv().toLong() and 0xffffffffL
}
