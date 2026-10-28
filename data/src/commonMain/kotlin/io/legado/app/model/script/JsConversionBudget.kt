package io.legado.app.model.script

import kotlin.time.TimeSource

/** One budget for a complete result/argument tree, including repeated references. */
internal class JsConversionBudget(private val checkpoint: () -> Unit = {}) {
    private var nodes = 0L
    private var elements = 0L
    private var bytes = 0L
    private val started = TimeSource.Monotonic.markNow()

    fun check() {
        require(started.elapsedNow().inWholeMilliseconds < 5_000) { "JS conversion time limit exceeded" }
        checkpoint()
    }

    fun node(depth: Int) {
        check()
        require(depth <= 64 && ++nodes <= MAX_ELEMENTS) { "JS conversion node/depth limit exceeded" }
        bytes(64) // conservative accounting for boxed values, collection entries and objects
    }

    fun elements(length: Long): Int {
        check()
        require(length in 0..MAX_ELEMENTS && length <= MAX_ELEMENTS - elements) {
            "JS conversion element limit exceeded"
        }
        elements += length
        bytes(length * 16)
        return length.toInt() // narrow only after the checked bound
    }

    fun bytes(length: Long): Int {
        check()
        require(length in 0..MAX_BYTES && length <= MAX_BYTES - bytes) { "JS conversion byte limit exceeded" }
        bytes += length
        return length.toInt()
    }

    companion object {
        const val MAX_ELEMENTS = 100_000L
        const val MAX_BYTES = 16 * 1024 * 1024L
    }
}
