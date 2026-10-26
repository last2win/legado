@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.legado.app.help.http

import platform.Foundation.*
import kotlin.test.*

/** Platform integration: uses real NSHTTPCookie attributes and the actual iOS store. */
class IosCookieStoreTest {
    @Test
    fun webViewSnapshotPreservesBoundariesAndLogout() {
        val source = "https://cookies-test.invalid/source-a"
        val otherSource = "https://cookies-test.invalid/source-b"
        val page = "https://cookies-test.invalid/account/login"
        fun cookie(domain: String) = NSHTTPCookie.cookieWithProperties(mapOf<Any?, Any?>(
            NSHTTPCookieName to "session",
            NSHTTPCookieValue to "value",
            NSHTTPCookieDomain to domain,
            NSHTTPCookiePath to "/account",
            NSHTTPCookieSecure to "TRUE",
            NSHTTPCookieExpires to NSDate.dateWithTimeIntervalSinceNow(3600.0),
        ))!!
        try {
            IosCookieStore.syncFromWebView(source, page, listOf(cookie("cookies-test.invalid")), emptyList())
            assertEquals("session=value", IosCookieStore.getCookieForSource(source, page))
            assertEquals("", IosCookieStore.getCookieForSource(otherSource, page))
            assertEquals("", IosCookieStore.getCookieForSource(source, "https://other-test.invalid/account"))
            assertEquals("", IosCookieStore.getCookieForSource(source, "https://sub.cookies-test.invalid/account"))
            assertEquals("", IosCookieStore.getCookieForSource(source, "https://cookies-test.invalid/accounting"))
            assertEquals("", IosCookieStore.getCookieForSource(source, "http://cookies-test.invalid/account"))
            IosCookieStore.syncFromWebView(source, page, emptyList(), IosCookieStore.cookiesForWebView(source, page))
            assertEquals("", IosCookieStore.getCookieForSource(source, page))
        } finally {
            IosCookieStore.removeCookieForSource(source, page)
        }
    }

    @Test
    fun publicHttpPagePreservesAccountSecureCookieAndConcurrentUpdates() {
        val source = "https://cookie-regression.invalid/source-a"
        val other = "https://cookie-regression.invalid/source-b"
        val account = "https://cookie-regression.invalid/account"
        val public = "http://cookie-regression.invalid/public"
        try {
            IosCookieStore.setCookieForSource(source, account, "session=one")
            IosCookieStore.setCookieForSource(other, account, "session=other")
            assertEquals("", IosCookieStore.getCookieForSource(source, public))
            val seed = IosCookieStore.cookiesForWebView(source, public)
            assertEquals(1, seed.size)
            IosCookieStore.syncFromWebView(source, public, seed, seed)
            assertEquals("session=one", IosCookieStore.getCookieForSource(source, account))
            // A request updates the login while the older WebView still holds its snapshot.
            IosCookieStore.setCookieForSource(source, account, "session=two")
            IosCookieStore.syncFromWebView(source, public, emptyList(), seed)
            assertEquals("session=two", IosCookieStore.getCookieForSource(source, account))
            IosCookieStore.setCookieForSource(source, "https://identity.invalid/login", "idp=value")
            IosCookieStore.clearCookiesForSource(source)
            assertTrue(IosCookieStore.cookiesForWebView(source, public).isEmpty())
            assertEquals("session=other", IosCookieStore.getCookieForSource(other, account))
        } finally {
            IosCookieStore.clearCookiesForSource(source)
            IosCookieStore.clearCookiesForSource(other)
        }
    }
}
