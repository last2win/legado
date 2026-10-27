package io.legado.app.help.archive

import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.SecurityException
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.storage.ArchiveLimits
import io.legado.app.model.script.JsEngines
import io.legado.app.help.storage.NativeZipCodec
import io.legado.app.help.writeBytesAtomically
import io.legado.app.model.fileBook.RangedSource
import io.legado.app.model.fileBook.RemoteZipCore
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.File
import io.legado.app.utils.atomicWriteTempPath
import okio.Buffer
import okio.GzipSource

/**
 * native (iOS/鸿蒙) [ArchiveProvider]: 复用项目已有纯 Kotlin ZIP 能力
 * ([NativeZipCodec] 解压落盘 + [RemoteZipCore] 内存 zip 结构解析)。
 *
 * # 与 app 端 AndroidArchiveProvider (libarchive 全格式) 差异
 * - 支持 zip/cbz + tar/tar.gz/tgz/gz: tar 是纯格式无需压缩库, gzip 走 okio native
 *   [GzipSource] (okio 的 iosArm64/ohosArm64 klib 依赖 Kotlin/Native 自带的
 *   platform.zlib, 无需额外 cinterop)
 * - rar/7z/bz2/xz/lzma 无 native 解码器 (desktop 靠 JVM 的 commons-compress + junrar,
 *   两者均无 native 变体), deCompress 抛点名格式的异常, getByteArrayContent 返回 null
 * - 每次 deCompress 使用独立目录，避免同名包残留或并发导入相互覆盖。
 */
object NativeArchiveProvider : ArchiveProvider {

    /** 与 app 端 ArchiveUtils.TEMP_FOLDER_NAME 对齐。 */
    override val tempFolderName: String = "ArchiveTemp"

    override fun deCompress(archivePath: String): List<String> {
        require(File(archivePath).length() <= ArchiveLimits.MAX_INPUT_BYTES) { "Archive input size limit exceeded" }
        val name = archivePath.substringAfterLast('/').substringAfterLast('\\')
        val destDir = FileUtilsCommon.getPath(
            FileUtilsCommon.getCachePath(), tempFolderName, MD5Utils.md5Encode16(name) + "-" + kotlin.random.Random.nextLong().toULong().toString(16)
        )
        File(destDir).mkdirs()
        try {
            when {
                // tar 纯格式 (ustar), 无需压缩库
                name.endsWith(".tar", true) -> unTarToPath(File(archivePath), destDir)

                // tar.gz / .tgz: 先 gzip 解压成中间 tar, 解完删掉中间产物
                name.endsWith(".tar.gz", true) || name.endsWith(".tgz", true) -> {
                    val tar = gzipToFile(File(archivePath))
                    try {
                        unTarToPath(tar, destDir)
                    } finally {
                        tar.delete()
                    }
                }

                // 单文件 gzip: 解压成同 base 名文件 (与 gzipToFile 同口径: 唯一临时件 + 改名就位)
                name.endsWith(".gz", true) -> {
                    val out = destDir + "/" + name.removeSuffix(".gz")
                    if (!writeBytesAtomically(out, gzipDecompress(File(archivePath).readBytes()))) {
                        throw NoStackTraceException("gzip 解压写入失败: $out")
                    }
                }

                // zip/cbz: 项目已有 NativeZipCodec
                name.endsWith(".zip", true) || name.endsWith(".cbz", true) ->
                    NativeZipCodec.unZipToPath(archivePath, destDir)

                else -> throw NoStackTraceException(
                    "iOS/鸿蒙端仅支持 zip/cbz/tar/tar.gz/tgz/gz 解压, 不支持 $name" +
                        " (rar/7z/bz2/xz/lzma 无 native 解码库)"
                )
            }
            // 返回解压出的文件绝对路径列表 (递归, 对齐 app 端 LibArchiveUtils.unArchive 返回 List<File>)
            val result = mutableListOf<String>()
            collectFiles(File(destDir), result)
            return result
        } catch (failure: Throwable) {
            FileUtilsCommon.delete(destDir)
            throw failure
        }
    }

    /**
     * gzip 解压到同目录中间件 (okio native GzipSource), 解完由调用方删掉。
     *
     * 中间件名走整份写入的共享契约 ([atomicWriteTempPath]: 目标名 + 随机唯一串 + 契约后缀): 同名源被
     * 两次解压时不会共用一条路径 (共用同名 .tar 时, 后写的那次会截断前一次正在读的中间件, 还会互相
     * 删掉对方的那份)。中间件没有对外成品名, 不需要再改名就位。
     */
    private fun gzipToFile(src: File): File {
        val tmp = File(atomicWriteTempPath(src.path + ".tar"))
        if (!FileUtilsCommon.writeBytes(tmp.path, gzipDecompress(src.readBytes()))) {
            FileUtilsCommon.delete(tmp.path)
            throw NoStackTraceException("gzip 解压写入失败: ${src.path}")
        }
        return tmp
    }

    /**
     * tar (ustar) 解包: 512 字节块头, 后接文件内容 (按 size 对齐到 512),
     * 以两个全零块结束。
     */
    private fun unTarToPath(tar: File, destDir: String) {
        require(tar.length() <= ArchiveLimits.MAX_TOTAL_BYTES) { "TAR input size limit exceeded" }
        val data = tar.readBytes()
        TarReader.forEach(data, { JsEngines.jsContextOrNull?.ensureActive() }) { name, type, start, end ->
            when {
                type == TYPE_DIR -> File((destDir + "/" + name).trimEnd('/')).mkdirs()
                type in REGULAR_TYPES -> {
                    val target = destDir + "/" + name
                    File(target.substringBeforeLast('/')).mkdirs()
                    File(target).writeBytes(data.copyOfRange(start, end))
                }
            }
        }
    }

    /** 全量解压 gzip 字节 (okio native GzipSource, 底层 platform.zlib; 语义同 jvm GZIPInputStream)。 */
    private fun gzipDecompress(bytes: ByteArray): ByteArray {
        require(bytes.size <= ArchiveLimits.MAX_INPUT_BYTES) { "GZIP input size limit exceeded" }
        val gz = GzipSource(Buffer().write(bytes))
        try {
            val out = Buffer()
            while (true) {
                JsEngines.jsContextOrNull?.ensureActive()
                val remaining = ArchiveLimits.MAX_TOTAL_BYTES - out.size
                val read = gz.read(out, minOf(DEFAULT_CHUNK, remaining + 1))
                if (read == -1L) break
                require(out.size <= ArchiveLimits.MAX_TOTAL_BYTES &&
                    out.size <= bytes.size.toLong() * ArchiveLimits.MAX_COMPRESSION_RATIO) { "GZIP output limit exceeded" }
            }
            return out.readByteArray()
        } finally {
            gz.close()
        }
    }

    override fun getByteArrayContent(bytes: ByteArray, path: String): ByteArray? {
        return runCatching {
            require(bytes.size <= ArchiveLimits.MAX_INPUT_BYTES) { "Archive input size limit exceeded" }
            val zip = RemoteZipCore(bytesRangedSource(bytes), "bytes", bytes.size.toLong()) { JsEngines.jsContextOrNull?.ensureActive() }
            try {
                zip.ensureMeta()[path]?.let {
                    require(it.entry.size in 0..ArchiveLimits.MAX_ENTRY_BYTES.toLong()) { "ZIP entry size limit exceeded" }
                    zip.readDecompressed(it)
                }
            } finally {
                zip.close()
            }
        }.getOrNull()
    }

    /**
     * 列举压缩包内条目名 (仅 zip/cbz)。
     *
     * 复用 [RemoteZipCore] 的 central directory 解析, 不整包解压。
     *
     * # 与 app 端差异
     * - rar/7z/bz2/xz/lzma 无 native 解码器 (见本文件 deCompress 的同类限制), 无条目可列,
     *   返回空列表并由 [AppLog] 记下原因 (不静默); 调用方对空列表的展示与 app 端读失败一致
     * - [filter] 为 null 直接返回空列表, 对齐 app 端 `LibArchiveUtils.getFilesName` 语义
     */
    override fun getFilesName(archivePath: String, filter: ((String) -> Boolean)?): List<String> {
        if (filter == null) return emptyList()
        val file = File(archivePath)
        val name = file.name
        if (!name.endsWith(".zip", true) && !name.endsWith(".cbz", true)) {
            AppLog.put("iOS/鸿蒙端仅支持 zip/cbz 列出条目, 不支持 $name")
            return emptyList()
        }
        return runCatching {
            require(file.length() <= ArchiveLimits.MAX_INPUT_BYTES) { "Archive input size limit exceeded" }
            val bytes = file.readBytes()
            val zip = RemoteZipCore(bytesRangedSource(bytes), name, bytes.size.toLong()) { JsEngines.jsContextOrNull?.ensureActive() }
            try {
                zip.ensureMeta().values
                    .map { it.entry.name }
                    .filter { !it.endsWith("/") && filter(it) }
            } finally {
                zip.close()
            }
        }.getOrElse {
            AppLog.put("列举压缩包条目失败: $archivePath\n${it.message}", it)
            emptyList()
        }
    }

    private fun collectFiles(dir: File, out: MutableList<String>) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) collectFiles(f, out) else out.add(f.absolutePath)
        }
    }

    private fun bytesRangedSource(bytes: ByteArray) = RangedSource { offset, length, _ ->
        val start = offset.toInt().coerceAtLeast(0)
        val end = (start + length).coerceAtMost(bytes.size)
        if (start >= end) ByteArray(0) else bytes.copyOfRange(start, end)
    }

    // tar typeflag 字节值 (POSIX ustar): '5' 目录; 普通文件历史上有 '0'/NUL/空格 三种写法
    private const val TYPE_DIR = 0x35
    private val REGULAR_TYPES = intArrayOf(0x30, 0x00, 0x20)

    /** gzip 流式解压的单次读取上限。 */
    private const val DEFAULT_CHUNK = 64 * 1024L
}

/** iOS/鸿蒙宿主启动早期注册一次 (AppFilesDirs 之后、任何 JsExtensions 压缩方法调用之前)。 */
fun registerNativeArchiveProvider() {
    ArchiveProviders.register(NativeArchiveProvider)
}
