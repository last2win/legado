package io.legado.app.utils
import kotlinx.serialization.json.JsonPrimitive
typealias InputStream = java.io.InputStream
fun ByteArray.toInputStream(): InputStream = inputStream()
val Throwable.stackTraceStr: String get() = stackTraceToString()
fun Any?.toJsonElement() = JsonPrimitive(toString())
