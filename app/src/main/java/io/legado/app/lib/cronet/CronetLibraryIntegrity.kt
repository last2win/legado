package io.legado.app.lib.cronet

import java.io.File
import java.security.MessageDigest

/** Pinned at review time from Google Maven, never learned from the runtime download server.
 * Artifact: https://dl.google.com/dl/android/maven2/org/chromium/net/cronet-embedded/143.7445.0/cronet-embedded-143.7445.0.aar
 * AAR SHA-256: afdd7af7568e9758e3690c3a4a228f30993ac3e4ef0f94c72bb68503c0167697
 * Version upgrades must update these independently reviewed ABI digests.
 */
internal object CronetLibraryIntegrity {
    private const val VERSION = "143.7445.0"
    private val sha256 = mapOf(
        "arm64-v8a" to "7d955904c4fd86cece96f7bd5f95248bd7907b19da67bcc7b73f436d864b37c4",
        "armeabi-v7a" to "363f796ed09bc584f6dff96f1a2cc7485c5f3b1fcc6e6aca1825d0f1164c7a45",
        "x86_64" to "0419dfa8d333f7cc72538d23f6c1a7acd06ae63322dee0b85b5312c6feb051bd",
        "x86" to "2b0eed10cd92d6de56055c6c6484897e28690b474fab193699606e53410a75d0",
    )

    fun verify(file: File, version: String, abi: String): Boolean {
        if (version != VERSION) return false
        val expected = sha256[abi] ?: return false
        if (!file.isFile || file.length() !in 1..32L * 1024 * 1024) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > 32L * 1024 * 1024) return false
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == expected
        }.getOrDefault(false)
    }
}
