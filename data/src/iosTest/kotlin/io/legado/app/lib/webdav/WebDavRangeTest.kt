package io.legado.app.lib.webdav

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WebDavRangeTest {
    @Test
    fun rangeHeadersAndExactBody() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("bytes=10-12", request.headers["Range"])
            assertNotNull(request.headers["Authorization"])
            respond("abc", HttpStatusCode.PartialContent)
        })
        try {
            assertEquals("abc", client.readWebDavRange("https://range.invalid/book.zip",
                Authorization("user", "password"), 10, 3, 100).decodeToString())
        } finally { client.close() }
    }

    @Test
    fun oversizedBodyIsRejectedWithOrWithoutContentLength() = runBlocking {
        for (declaredLength in listOf(null, "1048576", "1")) {
            val client = HttpClient(MockEngine {
                respond(ByteReadChannel(ByteArray(1024 * 1024)), HttpStatusCode.PartialContent,
                    if (declaredLength == null) headersOf() else headersOf("Content-Length", declaredLength))
            })
            try {
                assertFailsWith<IllegalArgumentException> {
                    client.readWebDavRange("https://range.invalid/book.zip",
                        Authorization("user", "password"), 0, 100, 1000)
                }
            } finally { client.close() }
        }
    }

    @Test
    fun ignoredRangeAndOverflowAreRejected() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("full body", HttpStatusCode.OK)
        })
        try {
            assertFailsWith<WebDavException> {
                client.readWebDavRange("https://range.invalid/book.zip", Authorization("", ""), 0, 10, 100)
            }
            assertFailsWith<IllegalArgumentException> {
                client.readWebDavRange("https://range.invalid/book.zip", Authorization("", ""), Long.MAX_VALUE, 10, -1)
            }
            assertEquals(1, requests)
        } finally { client.close() }
    }
}
