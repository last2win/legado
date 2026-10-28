package io.legado.app.help.coroutine
import kotlinx.coroutines.runBlocking
object Coroutine { fun async(block: suspend () -> Unit) = runBlocking { block() } }
