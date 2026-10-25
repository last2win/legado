package io.legado.app.help.http

/** Internal request metadata; never send this header to a remote server. */
const val cookieScopeHeader = "X-Legado-Cookie-Scope"

/** Cookie matching uses the request URL, not a registrable-domain cache key. */
data class CookieTarget(val scheme: String, val host: String, val port: Int, val path: String) {
    val origin: String get() = "$scheme://$host:$port"

    companion object {
        fun parse(url: String): CookieTarget? {
            val match = Regex("^(https?)://([^/?#]+)([^?#]*)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
                .matchEntire(url) ?: return null
            val scheme = match.groupValues[1].lowercase()
            val authority = match.groupValues[2].lowercase()
            if (authority.any { it <= ' ' || it == '@' || it == '\\' || it == '%' }) return null
            val host = if (authority.startsWith('[')) authority.substringBefore(']') + "]"
                else authority.substringBefore(':')
            if (host.isEmpty()) return null
            val suffix = authority.removePrefix(host)
            val port = if (suffix.isEmpty()) { if (scheme == "https") 443 else 80 }
                else suffix.takeIf { it.startsWith(':') }?.drop(1)?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return CookieTarget(scheme, host, port, match.groupValues[3].ifEmpty { "/" })
        }
    }
}

fun sameCookieOrigin(source: String?, target: String): Boolean {
    val owner = source?.let(CookieTarget::parse) ?: return false
    return owner.origin == CookieTarget.parse(target)?.origin
}

/** Foundation represents domain cookies with a leading dot; absence means host-only. */
fun cookieDomainMatches(host: String, domain: String): Boolean {
    val bare = domain.removePrefix(".").lowercase()
    val isIp = host.startsWith('[') || host.all { it.isDigit() || it == '.' }
    return host.equals(bare, true) || (!isIp && domain.startsWith('.') && host.lowercase().endsWith(".$bare"))
}

fun cookieMatches(target: CookieTarget, domain: String, path: String, secure: Boolean,
                  expiresAtMillis: Long?, nowMillis: Long): Boolean {
    if (!cookieDomainMatches(target.host, domain) || (secure && target.scheme != "https")) return false
    if (expiresAtMillis != null && expiresAtMillis <= nowMillis) return false
    val cookiePath = path.ifEmpty { "/" }
    return target.path == cookiePath || (target.path.startsWith(cookiePath) &&
        (cookiePath.endsWith('/') || target.path.getOrNull(cookiePath.length) == '/'))
}
