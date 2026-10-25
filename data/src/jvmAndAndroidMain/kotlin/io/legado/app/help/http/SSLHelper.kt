package io.legado.app.help.http

import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Compatibility names retained for callers; all values enforce system TLS validation. */
object SSLHelper {
    val unsafeTrustManager: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
    val unsafeSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(unsafeTrustManager), null) }
    }
    val unsafeSSLSocketFactory get() = unsafeSslContext.socketFactory
    val unsafeHostnameVerifier get() = HttpsURLConnection.getDefaultHostnameVerifier()
}
