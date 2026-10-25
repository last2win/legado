package io.legado.app.help.coroutine
import kotlinx.coroutines.*
object Coroutine { fun async(block: suspend CoroutineScope.() -> Unit) = GlobalScope.async(block = block) }
