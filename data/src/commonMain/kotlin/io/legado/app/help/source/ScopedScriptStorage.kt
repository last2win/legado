package io.legado.app.help.source

import com.script.jsdispatch.JsApi
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.help.http.sameCookieOrigin
import io.legado.app.utils.MD5Utils

/** Script-facing capabilities never expose the global providers. Cross-origin access is denied. */
@JsApi
class ScopedSourceNetwork(private val sourceKey: String?) : SourceNetworkProvider {
    private fun checkTarget(target: String) {
        require(sameCookieOrigin(sourceKey, target)) { "Cookie access outside the current source is denied" }
    }
    override fun getCookie(tag: String): String {
        checkTarget(tag)
        return CookieStoreProviders.get()?.getCookieForSource(sourceKey, tag).orEmpty()
    }
    override fun replaceCookie(tag: String, cookie: String) {
        checkTarget(tag)
        CookieStoreProviders.get()?.replaceCookieForSource(sourceKey, tag, cookie)
    }
    override fun removeCookie(tag: String) {
        checkTarget(tag)
        CookieStoreProviders.get()?.removeCookieForSource(sourceKey, tag)
    }
}

@JsApi
class ScopedSourceCache(sourceKey: String?, private val delegate: SourceCacheProvider) : SourceCacheProvider {
    private val prefix = "script:${MD5Utils.md5Encode(sourceKey ?: error("Script cache requires a source"))}:"
    override fun get(key: String) = delegate.get(prefix + key)
    override fun put(key: String, value: String) = delegate.put(prefix + key, value)
    override fun put(key: String, value: String, saveTime: Int) = delegate.put(prefix + key, value, saveTime)
    override fun delete(key: String) = delegate.delete(prefix + key)
    override fun getFromMemory(key: String) = delegate.getFromMemory(prefix + key)
    override fun putMemory(key: String, value: Any) = delegate.putMemory(prefix + key, value)
    override fun deleteMemory(key: String) = delegate.deleteMemory(prefix + key)
    override fun clearMemoryByPrefixes(prefixes: List<String>) = delegate.clearMemoryByPrefixes(prefixes.map { prefix + it })
}
