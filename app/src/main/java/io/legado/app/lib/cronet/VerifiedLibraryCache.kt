package io.legado.app.lib.cronet

import java.io.File

/** Availability cache, not permission to load native code. The loader still verifies immediately
 * before System.load. Controlled replacements explicitly invalidate even if metadata is unchanged.
 */
internal class VerifiedLibraryCache(
    private val verify: (File, String, String) -> Boolean = CronetLibraryIntegrity::verify,
) {
    private data class Stamp(val path: String, val version: String, val abi: String, val size: Long, val modified: Long)
    @Volatile private var verified: Stamp? = null

    private fun stamp(file: File, version: String, abi: String): Stamp? =
        if (file.isFile) Stamp(file.absolutePath, version, abi, file.length(), file.lastModified()) else null

    fun isCached(file: File, version: String, abi: String): Boolean =
        stamp(file, version, abi)?.let { it == verified } == true

    @Synchronized
    fun available(file: File, version: String, abi: String): Boolean {
        val before = stamp(file, version, abi) ?: run { verified = null; return false }
        if (before == verified) return true
        verified = null
        if (!verify(file, version, abi) || stamp(file, version, abi) != before) return false
        verified = before
        return true
    }

    @Synchronized
    fun invalidate() { verified = null }
}
