@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.http

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieExpires
import platform.Foundation.NSHTTPCookieMaximumAge
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import io.legado.app.utils.PublicSuffixList
import io.legado.app.utils.systemCurrentTimeMillis

/** Versioned, source-scoped cookie storage. Legacy flattened DB cookies are intentionally ignored. */
object IosCookieStore : CookieStoreProvider, StructuredCookieStore {
    private const val STORAGE_KEY = "legado.sourceCookies.v1"
    private val lock = SynchronizedObject()
    private val jars: MutableMap<String, List<NSHTTPCookie>> by lazy {
        val saved = NSUserDefaults.standardUserDefaults.dictionaryForKey(STORAGE_KEY).orEmpty()
        saved.entries.mapNotNull { (key, value) ->
            val source = key as? String ?: return@mapNotNull null
            source to (value as? List<*>).orEmpty().mapNotNull {
                @Suppress("UNCHECKED_CAST")
                (it as? Map<Any?, *>)?.let { properties -> NSHTTPCookie.cookieWithProperties(properties) }
            }
        }.toMap().toMutableMap()
    }

    private fun scope(source: String?, url: String): String =
        source?.takeIf { it.isNotBlank() } ?: CookieTarget.parse(url)?.origin.orEmpty()

    private fun persist() {
        val now = systemCurrentTimeMillis()
        val saved = jars.mapValues { (_, cookies) -> cookies.filter {
            it.expiresDate?.timeIntervalSince1970?.let { seconds -> seconds * 1000 > now } == true
        }.map { cookie ->
            cookie.properties.orEmpty().toMutableMap().apply {
                // Persist an absolute deadline; replaying Max-Age on restart would extend the session.
                remove(NSHTTPCookieMaximumAge)
                cookie.expiresDate?.let { put(NSHTTPCookieExpires, it) }
            }
        } }
        NSUserDefaults.standardUserDefaults.setObject(saved, forKey = STORAGE_KEY)
    }

    fun cookiesForSource(sourceKey: String?, url: String): List<NSHTTPCookie> = synchronized(lock) {
        val target = CookieTarget.parse(url) ?: return@synchronized emptyList()
        jars[scope(sourceKey, url)].orEmpty().filter { cookie ->
            cookieMatches(target, cookie.domain, cookie.path, cookie.secure,
                cookie.expiresDate?.timeIntervalSince1970?.let { (it * 1000).toLong() }, systemCurrentTimeMillis())
        }.sortedByDescending { it.path.length }
    }

    /** Replace this host's snapshot, including an empty snapshot after logout. Never relabel an IdP cookie. */
    fun syncFromWebView(sourceKey: String?, url: String, cookies: List<NSHTTPCookie>) = synchronized(lock) {
        val target = CookieTarget.parse(url) ?: return@synchronized
        val key = scope(sourceKey, url)
        val retained = jars[key].orEmpty().filterNot { cookieDomainMatches(target.host, it.domain) }
        jars[key] = (retained + cookies.filter { cookieDomainMatches(target.host, it.domain) }).takeLast(3000)
        persist()
    }

    override fun getCookieForSource(sourceKey: String?, url: String): String =
        cookiesForSource(sourceKey, url).joinToString("; ") { "${it.name}=${it.value}" }

    override fun getCookie(url: String): String = getCookieForSource(null, url)
    override fun getCookieNoSession(url: String): String = cookiesForSource(null, url)
        .filter { it.expiresDate != null }.joinToString("; ") { "${it.name}=${it.value}" }
    override fun getSessionCookie(domain: String): String? = null
    override fun updateSessionCookie(domain: String, cookies: String) {
        // Attribute-less network cookies must never enter this store.
        error("iOS cookies require their original Set-Cookie attributes")
    }
    override fun getKey(url: String, key: String): String = cookieToMap(getCookie(url))[key].orEmpty()

    /** Explicit cookies have no attributes: restrict new pairs to this host, path and scheme. */
    private fun explicitCookies(url: String, cookie: String?): List<NSHTTPCookie> {
        val target = CookieTarget.parse(url) ?: return emptyList()
        require(cookie.orEmpty().none { it == '\r' || it == '\n' }) { "Invalid cookie header" }
        return cookieToMap(cookie.orEmpty()).mapNotNull { (name, value) ->
            require(name.length + value.length <= 4096) { "Cookie size limit exceeded" }
            val properties = mutableMapOf<Any?, Any?>(
                NSHTTPCookieName to name, NSHTTPCookieValue to value,
                NSHTTPCookieDomain to target.host, NSHTTPCookiePath to target.path,
            )
            if (target.scheme == "https") properties[NSHTTPCookieSecure] = "TRUE"
            NSHTTPCookie.cookieWithProperties(properties)
        }
    }

    override fun setCookie(url: String, cookie: String?) = setCookieForSource(null, url, cookie)
    override fun setCookieForSource(sourceKey: String?, url: String, cookie: String?) = synchronized(lock) {
        val target = CookieTarget.parse(url) ?: return@synchronized
        val key = scope(sourceKey, url)
        val parsed = explicitCookies(url, cookie)
        jars[key] = (jars[key].orEmpty().filterNot { it.domain == target.host && it.path == target.path } + parsed).takeLast(3000)
        persist()
    }
    override fun replaceCookie(url: String, cookie: String) = replaceCookieForSource(null, url, cookie)
    override fun replaceCookieForSource(sourceKey: String?, url: String, cookie: String) = synchronized(lock) {
        val key = scope(sourceKey, url)
        val parsed = explicitCookies(url, cookie)
        // Do not flatten/re-create existing cookies: their expiry, Secure, path and HttpOnly survive.
        val retained = jars[key].orEmpty().filterNot { old ->
            parsed.any { it.name == old.name && it.domain == old.domain && it.path == old.path }
        }
        jars[key] = (retained + parsed).takeLast(3000)
        persist()
    }
    override fun removeCookie(url: String) = removeCookieForSource(null, url)
    override fun removeCookieForSource(sourceKey: String?, url: String) = synchronized(lock) {
        val host = CookieTarget.parse(url)?.host ?: return@synchronized
        val key = scope(sourceKey, url)
        jars[key] = jars[key].orEmpty().filterNot { cookieDomainMatches(host, it.domain) }
        persist()
    }
    override fun removeCookie(url: String, key: String) = synchronized(lock) {
        val source = scope(null, url)
        val host = CookieTarget.parse(url)?.host ?: return@synchronized
        jars[source] = jars[source].orEmpty().filterNot { it.name == key && cookieDomainMatches(host, it.domain) }
        persist()
    }
    override fun clear() = synchronized(lock) { jars.clear(); persist() }

    override fun saveResponseCookies(sourceKey: String?, response: KmpResponse) = synchronized(lock) {
        val url = response.request.url.toString()
        val nsUrl = NSURL.URLWithString(url) ?: return@synchronized
        val target = CookieTarget.parse(url) ?: return@synchronized
        val key = scope(sourceKey, url)
        val current = jars[key].orEmpty().toMutableList()
        response.headers().toMultimap().filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach { value ->
            NSHTTPCookie.cookiesWithResponseHeaderFields(mapOf("Set-Cookie" to value), nsUrl)
                .filterIsInstance<NSHTTPCookie>().filter {
                    cookieDomainMatches(target.host, it.domain) &&
                        (target.host == it.domain.removePrefix(".") || !PublicSuffixList.isPublicSuffix(it.domain.removePrefix(".")))
                }.forEach { cookie ->
                    current.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    current.add(cookie)
                }
        }
        jars[key] = current.takeLast(3000)
        persist()
    }
}

fun registerDefaultIosCookieStoreProvider() {
    CookieStoreProviders.register(IosCookieStore)
}
