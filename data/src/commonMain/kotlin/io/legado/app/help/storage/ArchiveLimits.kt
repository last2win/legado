package io.legado.app.help.storage

/** Shared hard limits, checked before allocation AND while expanding untrusted data. */
object ArchiveLimits {
    const val MAX_INPUT_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 128 * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    const val MAX_COMPRESSION_RATIO = 1_000

    fun checkEntry(compressed: Int, expanded: Int) {
        require(compressed >= 0 && expanded in 0..MAX_ENTRY_BYTES) { "Archive entry size limit exceeded" }
        require(expanded.toLong() <= compressed.toLong() * MAX_COMPRESSION_RATIO) {
            "Archive compression ratio limit exceeded"
        }
    }

    fun checkPath(name: String) {
        require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') &&
            !name.contains(':') && !name.contains('\u0000') && name.split('/').none { it == ".." }) {
            "Unsafe archive entry path"
        }
    }
}
