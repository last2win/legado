@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import io.legado.app.napi.quickjs.*
import kotlinx.cinterop.*
import kotlinx.coroutines.ensureActive

internal fun nativeConversionBudget() = JsConversionBudget {
    val scope = NativeJsEngine.currentScope()
    scope?.coroutineContext?.ensureActive()
    if (scope?.budget?.interrupted() == true) throw NativeScriptException("JS conversion execution limit exceeded")
}

/** Count the UTF-8 payload BEFORE allocating a Kotlin byte array/String. */
internal fun boundedJsString(ctx: CPointer<JSContext>, value: CValue<JSValue>, budget: JsConversionBudget): String? = memScoped {
    budget.check()
    val length = alloc<ULongVar>()
    val chars = JS_ToCStringLen2(ctx, length.ptr, value, false) ?: return@memScoped null
    try {
        require(length.value <= (JsConversionBudget.MAX_BYTES / 4).toULong()) { "JS conversion byte limit exceeded" }
        val count = length.value.toInt()
        budget.bytes(count.toLong() * 4) // UTF-8 copy plus decoded UTF-16 and conversion overhead
        chars.readBytes(count).decodeToString()
    } finally { qjs_FreeCString(ctx, chars) }
}
