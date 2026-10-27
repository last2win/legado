package io.legado.app.help.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import io.legado.app.help.file.NativeFileDownloader
import kotlinx.coroutines.*
import okio.FileSystem
import kotlin.test.*

class IosFileDownloadStreamingTest {
    @Test fun fileReceivesBytesBeforeServerFinishesResponse() = runBlocking {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "legado-stream-test"
        fs.deleteRecursively(root, mustExist = false)
        fs.createDirectories(root)
        val finish = CompletableDeferred<Unit>()
        val channel = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(channel, HttpStatusCode.OK) })
        val writer = launch {
            val chunk = ByteArray(8192) { 42 }
            repeat(32) { channel.writeFully(chunk) }
            finish.await()
            channel.writeFully(chunk)
            channel.close()
        }
        val download = async { NativeFileDownloader { client }.download("https://download.invalid/book", root.toString(), "book.txt") }
        try {
            // An eagerly cached Ktor response cannot create a partial file before finish is released.
            withTimeout(5_000) {
                while (fs.list(root).none { (fs.metadata(it).size ?: 0) > 0 }) delay(10)
            }
            assertFalse(download.isCompleted)
            finish.complete(Unit)
            assertTrue(download.await())
            assertEquals(33L * 8192, fs.metadata(root / "book.txt").size)
            assertEquals(listOf("book.txt"), fs.list(root).map { it.name })
        } finally {
            finish.complete(Unit)
            writer.cancelAndJoin()
            download.cancelAndJoin()
            client.close()
            fs.deleteRecursively(root, mustExist = false)
        }
    }
}
