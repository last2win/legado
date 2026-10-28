package io.legado.app.help.archive

import io.legado.app.constant.AppPattern
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.safeBookFilePath
import io.legado.app.help.storage.ArchiveLimits
import io.legado.app.utils.atomicWriteTempPath
import okio.Buffer
import okio.FileSystem
import okio.HashingSource
import okio.Path.Companion.toPath
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.use

/** Extraction stays isolated; identity uses archive contents + entry path, never the temp directory. */
internal object ArchiveBookFiles {
    fun archiveId(path: String): String {
        val fs = FileSystem.SYSTEM
        require((fs.metadata(path.toPath()).size ?: 0) <= ArchiveLimits.MAX_INPUT_BYTES)
        val hash = HashingSource.sha256(fs.source(path.toPath()))
        hash.use {
            val scratch = Buffer()
            var total = 0L
            while (true) {
                val n = it.read(scratch, 8192)
                if (n == -1L) break
                total += n
                require(total <= ArchiveLimits.MAX_INPUT_BYTES) { "Archive input size limit exceeded" }
                scratch.clear()
            }
        }
        return hash.hash.hex()
    }

    fun store(id: String, extractionRoot: String, files: List<String>): List<String> {
        val fs = FileSystem.SYSTEM
        val root = fs.canonicalize(extractionRoot.toPath())
        return files.filter { it.substringAfterLast('/').matches(AppPattern.bookFileRegex) }.map { path ->
            val source = fs.canonicalize(path.toPath())
            val relative = source.relativeTo(root).toString()
            require(relative != ".." && !relative.startsWith("../")) { "Archive book escapes extraction directory" }
            val directory = AppFilesDirs.get().filesDir.toPath() / "ImportedArchives" / id / relative.encodeUtf8().sha256().hex()
            val target = safeBookFilePath(directory.toString(), source.name).toPath()
            val temp = atomicWriteTempPath(target.toString()).toPath()
            try {
                fs.source(source).use { input ->
                    fs.sink(temp).buffer().use { output ->
                        val chunk = Buffer()
                        var total = 0L
                        while (true) {
                            val n = input.read(chunk, 8192)
                            if (n == -1L) break
                            total += n
                            require(total <= ArchiveLimits.MAX_ENTRY_BYTES) { "Archive book size limit exceeded" }
                            output.write(chunk, n)
                        }
                    }
                }
                fs.atomicMove(temp, target)
                target.toString()
            } finally { fs.delete(temp, mustExist = false) }
        }
    }
}
