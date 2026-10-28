package io.legado.app.help.archive

import io.legado.app.help.file.requireBookFileName
import io.legado.app.help.storage.ArchiveLimits
import okio.ByteString.Companion.encodeUtf8

/** Validate the archive path before flattening it; duplicate basenames get stable disambiguation. */
fun archiveBookFileNames(paths: List<String>): Map<String, String> {
    require(paths.distinct().size == paths.size) { "Duplicate archive entry path" }
    val basenames = paths.associateWith { path ->
        ArchiveLimits.checkPath(path)
        require(path.split('/').none { it.isBlank() || it == "." }) { "Unsafe archive entry path" }
        path.substringAfterLast('/').also(::requireBookFileName)
    }
    val counts = basenames.values.groupingBy { it.lowercase() }.eachCount()
    val result = basenames.mapValues { (path, name) ->
        if (counts.getValue(name.lowercase()) == 1) name
        else name.substringBeforeLast('.') + "__" + path.encodeUtf8().sha256().hex().take(16) +
            "." + name.substringAfterLast('.')
    }
    require(result.values.map { it.lowercase() }.distinct().size == result.size) { "Archive filename collision" }
    return result
}
