package io.legado.app.help.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Actual iOS KmpCall and cookie bridge; only the network engine is replaced. */
class IosRedirectTest {
    @Test
    fun redirectsReselectCookiesAndNeverSendInternalHeaders() = runBlocking {
        val source = "https://image-test.invalid/source-a?private-key=secret"
        val other = "https://image-test.invalid/source-b"
        IosCookieStore.clearCookiesForSource(source)
        IosCookieStore.clearCookiesForSource(other)
        registerDefaultIosCookieStoreProvider()
        registerSharedCookieJarBridge()
        IosCookieStore.setCookieForSource(source, "https://image-test.invalid/account", "session=owner")
        IosCookieStore.setCookieForSource(other, "https://image-test.invalid/account", "session=other")
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertTrue(request.headers.names().none(::isInternalHttpHeader))
            when (requests) {
                1 -> {
                    assertEquals("/public", request.url.encodedPath)
                    assertNull(request.headers["Cookie"])
                    assertEquals("Bearer private", request.headers["Authorization"])
                    respond("", HttpStatusCode.Found, headersOf("Location", "/account/image"))
                }
                2 -> {
                    assertEquals("session=owner", request.headers["Cookie"])
                    assertEquals("Bearer private", request.headers["Authorization"])
                    respond("", HttpStatusCode.TemporaryRedirect, headersOf("Location", "https://cdn-test.invalid/image"))
                }
                else -> {
                    assertEquals("cdn-test.invalid", request.url.host)
                    assertNull(request.headers["Cookie"])
                    assertNull(request.headers["Authorization"])
                    respond("image bytes", HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
                }
            }
        }
        val transport = HttpClient(engine) { followRedirects = false }
        val client = KmpHttpClient(transport, 30_000, 30_000)
        try {
            val response = client.newCall(KmpRequestBuilder()
                .url("https://image-test.invalid/public")
                .header("x-LEGADO-cookie-SCOPE", source).header("cOoKiEjAr", "1")
                .header("Authorization", "Bearer private").build()).await()
            try {
                assertEquals(200, response.code)
                assertEquals("image bytes", response.body.string())
                assertEquals(3, requests)
            } finally { response.close() }
        } finally {
            transport.close()
            IosCookieStore.clearCookiesForSource(source)
            IosCookieStore.clearCookiesForSource(other)
        }
    }
}
