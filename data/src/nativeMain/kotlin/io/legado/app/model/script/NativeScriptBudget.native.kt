@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.legado.app.model.script

import io.legado.app.napi.quickjs.*
import kotlinx.cinterop.*
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Per-runtime heap limit and per-outer-call deadline, including pure JavaScript loops. */
internal class NativeScriptBudget {
    var timeoutMillis = 30_000L
    var cancelled: () -> Boolean = { false }
    private var started: TimeMark? = null
    private var depth = 0
    private val conversions = mutableListOf<JsConversionBudget>()

    fun interrupted(): Boolean = cancelled() || conversions.any { it.expired() } || started?.elapsedNow()?.inWholeMilliseconds
        ?.let { it >= timeoutMillis } == true

    fun <T> converting(budget: JsConversionBudget, block: () -> T): T {
        conversions.add(budget)
        try { return block() }
        finally { conversions.removeAt(conversions.lastIndex) }
    }

    fun <T> use(block: () -> T): T {
        if (depth++ == 0) started = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            if (--depth == 0) started = null
        }
    }

    companion object {
        const val MAX_HEAP_BYTES = 64 * 1024 * 1024
        fun install(rt: CPointer<JSRuntime>, ref: StableRef<NativeScriptBudget>) {
            JS_SetMemoryLimit(rt, MAX_HEAP_BYTES.toULong())
            JS_SetMaxStackSize(rt, (512 * 1024).toULong())
            JS_SetInterruptHandler(rt, staticCFunction { _: CPointer<JSRuntime>?, opaque: COpaquePointer? ->
                // No Kotlin exception may cross the C callback boundary.
                try { if (opaque?.asStableRef<NativeScriptBudget>()?.get()?.interrupted() == true) 1 else 0 }
                catch (_: Throwable) { 1 }
            }, ref.asCPointer())
        }
    }
}
