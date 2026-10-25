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
            IosCookieStore.syncFromWebView(source, page, listOf(cookie("cookies-test.invalid"), cookie("other-test.invalid")))
            assertEquals("session=value", IosCookieStore.getCookieForSource(source, page))
            assertEquals("", IosCookieStore.getCookieForSource(otherSource, page))
            assertEquals("", IosCookieStore.getCookieForSource(source, "https://other-test.invalid/account"))
            assertEquals("", IosCookieStore.getCookieForSource(source, "https://sub.cookies-test.invalid/account"))
            assertEquals("", IosCookieStore.getCookieForSource(source, "https://cookies-test.invalid/accounting"))
            assertEquals("", IosCookieStore.getCookieForSource(source, "http://cookies-test.invalid/account"))
            IosCookieStore.syncFromWebView(source, page, emptyList())
            assertEquals("", IosCookieStore.getCookieForSource(source, page))
        } finally {
            IosCookieStore.removeCookieForSource(source, page)
        }
    }
}
