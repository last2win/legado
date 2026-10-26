@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.legado.app.help.http

import platform.Foundation.NSHTTPCookie
import platform.WebKit.WKHTTPCookieStore

/** Fixed source + initial URL keep redirects from changing the cookie partition. Main-thread only. */
class IosWebViewCookieSession(private val sourceKey: String?, private val initialUrl: String) {
    private var baseline = IosCookieStore.cookiesForWebView(sourceKey, initialUrl)
    private var seeded = false
    private var seeding = false
    private var pendingLoad: (() -> Unit)? = null

    fun seed(store: WKHTTPCookieStore, load: () -> Unit) {
        if (seeded) { load(); return }
        pendingLoad = load
        if (seeding) return
        seeding = true
        var remaining = baseline.size
        fun ready() {
            seeded = true
            seeding = false
            val action = pendingLoad
            pendingLoad = null
            action?.invoke()
        }
        if (remaining == 0) ready()
        else baseline.forEach { cookie -> store.setCookie(cookie) { if (--remaining == 0) ready() } }
    }

    fun sync(cookies: List<NSHTTPCookie>) {
        if (!seeded) return
        IosCookieStore.syncFromWebView(sourceKey, initialUrl, cookies, baseline)
        baseline = cookies
    }
}
