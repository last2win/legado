package io.legado.app.help.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class IosResponseBudgetTest {
    @Test fun bodylessResponsesKeepRepresentationHeadersWithoutReadingOrDecodingContent() = runBlocking {
        for ((method, status) in listOf("HEAD" to HttpStatusCode.OK, "GET" to HttpStatusCode.NotModified,
            "GET" to HttpStatusCode.NoContent, "GET" to HttpStatusCode.ResetContent)) {
            val client = HttpClient(MockEngine {
                respond(ByteReadChannel(ByteArray(0)), status, headersOf(
                    "Content-Length" to listOf("41943040"), "Content-Encoding" to listOf("gzip"),
                    "ETag" to listOf("test-version")))
            })
            try {
                val response = KmpHttpClient(client, 30_000, 30_000).newCall(KmpRequestBuilder()
                    .url("https://budget.invalid/resource").method(method, null).build()).await()
                try {
                    assertEquals(status.value, response.code)
                    assertEquals("41943040", response.header("Content-Length"))
                    assertEquals("test-version", response.header("ETag"))
                    assertEquals(0, response.body.bytes().size)
                } finally { response.close() }
            } finally { client.close() }
        }
    }

    @Test fun actualKmpCallRejectsOversizedBodyWithoutContentLength(): Unit = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(ByteReadChannel(ByteArray(32 * 1024 * 1024 + 1)), HttpStatusCode.OK, headersOf())
        })
        try {
            val kmp = KmpHttpClient(client, 30_000, 30_000)
            val failure = assertFailsWith<okio.IOException> {
                kmp.newCall(KmpRequestBuilder().url("https://budget.invalid/large").build()).await()
            }
            // KmpCallback exposes IOException; preserve the actual budget error as its cause.
            assertTrue(failure.cause is IllegalArgumentException)
            assertEquals(1, requests, "Budget failures must not retry the download")
        } finally { client.close() }
    }

    @Test fun sameNameCookiesAndExplicitTemporaryValueSurviveRequestPipeline() = runBlocking {
        val source = "https://merge.invalid/source"
        IosCookieStore.clearCookiesForSource(source)
        registerDefaultIosCookieStoreProvider()
        registerSharedCookieJarBridge()
        IosCookieStore.setCookieForSource(source, "https://merge.invalid/", "sid=root")
        IosCookieStore.setCookieForSource(source, "https://merge.invalid/account", "sid=account")
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals(if (++calls == 1) "sid=account; sid=root" else "sid=temp", request.headers["Cookie"])
            respond("ok", HttpStatusCode.OK)
        })
        try {
            val kmp = KmpHttpClient(client, 30_000, 30_000)
            fun request() = KmpRequestBuilder().url("https://merge.invalid/account/page")
                .header(cookieScopeHeader, source).header(cookieJarHeader, "1")
            kmp.newCall(request().build()).await().close()
            kmp.newCall(request().header("Cookie", "sid=temp").build()).await().close()
            assertEquals("sid=account; sid=root", IosCookieStore.getCookieForSource(source, "https://merge.invalid/account/page"))
        } finally { client.close(); IosCookieStore.clearCookiesForSource(source) }
    }
}
