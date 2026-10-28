package io.legado.app.help.archive

import io.legado.app.help.storage.ArchiveLimits
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.use

/** Recover pre-content-addressed imports without trusting editable titles or authors.
 * Both files must remain in the app's archive directories. Match the original entry path and
 * streamed content digest; refuse ambiguity instead of choosing an arbitrary reading state.
 */
internal fun findLegacyArchiveRef(
    incomingRef: String,
    candidates: List<String>,
    filesDir: String,
    cacheDir: String,
): String? {
    val fs = FileSystem.SYSTEM
    val incoming = fs.canonicalize(incomingRef.removePrefix("file://").toPath())
    val imports = fs.canonicalize((filesDir.toPath() / "ImportedArchives"))
    val stable = incoming.relativeTo(imports).segments
    require(stable.size == 3 && stable.take(2).all { it.matches(Regex("[0-9a-f]{64}")) }) {
        "Invalid imported archive identity"
    }
    val cache = cacheDir.toPath() / "ArchiveTemp"
    if (!fs.exists(cache)) return null
    val legacyRoot = fs.canonicalize(cache)
    val size = fs.metadata(incoming).size ?: return null
    require(size in 1..ArchiveLimits.MAX_ENTRY_BYTES.toLong()) { "Archive book size limit exceeded" }
    var remaining = ArchiveLimits.MAX_TOTAL_BYTES.toLong()
    fun digest(path: Path): String {
        val source = HashingSource.sha256(fs.source(path))
        source.use {
            val buffer = Buffer()
            var total = 0L
            while (true) {
                val read = it.read(buffer, 8192)
                if (read == -1L) break
                total += read
                remaining -= read
                require(total <= ArchiveLimits.MAX_ENTRY_BYTES && remaining >= 0) { "Archive migration read limit exceeded" }
                buffer.clear()
            }
        }
        return source.hash.hex()
    }
    var incomingDigest: String? = null
    val matches = mutableListOf<String>()
    for (ref in candidates.distinct()) {
        val path = ref.removePrefix("file://").toPath()
        // Missing old cache data cannot prove identity. Keep that record for manual recovery.
        val metadata = fs.metadataOrNull(path) ?: continue
        if (!metadata.isRegularFile || metadata.size != size) continue
        val canonical = fs.canonicalize(path)
        val segments = canonical.relativeTo(legacyRoot).segments
        if (segments.size < 2 || !segments.first().matches(Regex("[0-9a-f]{16}(?:-[0-9a-f]+)?"))) continue
        val entry = segments.drop(1).joinToString("/")
        if (entry.encodeUtf8().sha256().hex() != stable[1]) continue
        if (incomingDigest == null) incomingDigest = digest(incoming)
        if (digest(canonical) == incomingDigest) matches.add(ref)
        require(matches.size <= 1) { "Multiple legacy archive books match; existing reading states were retained" }
    }
    return matches.singleOrNull()
}
