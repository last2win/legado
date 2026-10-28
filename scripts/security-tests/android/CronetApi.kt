package org.chromium.net
import android.content.Context
open class CronetEngine {
    class Builder { abstract class LibraryLoader { abstract fun loadLibrary(libName: String) } }
}
class CronetProvider(val isEnabled: Boolean = true, val name: String = "external") {
    companion object {
        const val PROVIDER_NAME_FALLBACK = "fallback"
        const val PROVIDER_NAME_APP_PACKAGED = "packaged"
        fun getAllProviders(context: Context): List<CronetProvider> = emptyList()
    }
}
