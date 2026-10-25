package io.legado.app.web.api

import io.legado.app.help.http.CookieTarget
import kotlin.concurrent.Volatile

/** A new 256-bit credential for every server session. Never accept credentials in query strings. */
object WebAccess {
    private const val COOKIE = "legado_web_session"
    @Volatile private var token: String = ""

    fun start() { token = newWebAccessToken() }
    fun stop() { token = "" }
    fun accessUrl(url: String): String = url.substringBefore('#').trimEnd('/') + "/#access_token=" + token

    fun isPublicAsset(method: String, path: String): Boolean = method == "GET" &&
        (path == "/" || path == "/index.html" || path == "/favicon.ico" || path.startsWith("/assets/")) &&
        !path.contains("..") && !path.contains('\\')

    fun authorized(headers: Map<String, String>, webSocket: Boolean = false): Boolean {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        val expected = token
        if (expected.isEmpty()) return false
        val origin = header("origin")
        if (origin != null) {
            val host = header("host") ?: return false
            val target = CookieTarget.parse("http://$host") ?: return false
            val source = CookieTarget.parse(origin) ?: return false
            val port = if (webSocket) target.port - 1 else target.port
            if (source.scheme != target.scheme || source.host != target.host || source.port != port) return false
        }
        val bearer = header("authorization")?.removePrefix("Bearer ")
        val cookie = header("cookie")?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')
        return constantTimeEquals(expected, bearer) || constantTimeEquals(expected, cookie)
    }

    fun sessionCookie(): String = "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict"

    private fun constantTimeEquals(expected: String, actual: String?): Boolean {
        if (actual == null || actual.length != expected.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor actual[i].code)
        return diff == 0
    }
}

internal expect fun newWebAccessToken(): String
