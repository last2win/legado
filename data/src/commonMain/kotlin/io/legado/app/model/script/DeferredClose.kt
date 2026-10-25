package io.legado.app.model.script

/** Caller serializes access. Closing during a callback waits for the outermost use to exit. */
internal class DeferredClose(private val release: () -> Unit) {
    private var active = 0
    private var closing = false
    private var closed = false

    fun <T> use(block: () -> T): T {
        check(!closing && !closed) { "JavaScript runtime is closed" }
        active++
        try {
            return block()
        } finally {
            active--
            if (closing && active == 0) releaseOnce()
        }
    }

    fun close() {
        closing = true
        if (active == 0) releaseOnce()
    }

    private fun releaseOnce() {
        if (closed) return
        closed = true
        release()
    }
}
