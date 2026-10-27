package io.legado.app.help.file

import okio.FileSystem
import okio.Path.Companion.toPath

/** A remote displayname is a single filename, never a path or an encoded path. */
fun requireBookFileName(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 }) {
        "Invalid book filename"
    }
    // Windows canonicalization drops trailing dots/spaces and treats device names specially.
    require(name == name.trimEnd(' ', '.') && name.substringBefore('.').uppercase() !in
        setOf("CON", "PRN", "AUX", "NUL", *(1..9).map { "COM$it" }.toTypedArray(),
            *(1..9).map { "LPT$it" }.toTypedArray())) { "Invalid book filename" }
}

/** Canonicalize the trusted selected root; reject links at the untrusted child boundary. */
fun safeBookFilePath(directory: String, name: String): String {
    requireBookFileName(name)
    val fs = FileSystem.SYSTEM
    val root = directory.toPath(normalize = true)
    fs.createDirectories(root)
    val canonicalRoot = fs.canonicalize(root)
    val target = canonicalRoot / name
    require(target.parent == canonicalRoot) { "Book path escapes its directory" }
    val metadata = fs.metadataOrNull(target)
    require(metadata?.symlinkTarget == null && metadata?.isDirectory != true) { "Book target is a link or directory" }
    if (metadata != null) require(fs.canonicalize(target).parent == canonicalRoot) { "Book path escapes its directory" }
    return target.toString()
}
