package security

import io.legado.app.help.http.SSLHelper
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.HttpsURLConnection

fun main(args: Array<String>) {
    val password = "test-only".toCharArray()
    fun load(path: String) = KeyStore.getInstance("PKCS12").apply { java.io.File(path).inputStream().use { load(it, password) } }
    val trusted = load(args[0])
    val untrusted = load(args[1])
    System.setProperty("javax.net.ssl.trustStore", args[0])
    System.setProperty("javax.net.ssl.trustStorePassword", "test-only")
    val trustManager = SSLHelper.unsafeTrustManager
    trustManager.checkServerTrusted(arrayOf(trusted.getCertificate("test") as X509Certificate), "RSA")
    check(runCatching { trustManager.checkServerTrusted(arrayOf(untrusted.getCertificate("test") as X509Certificate), "RSA") }.isFailure)
    val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(trusted, password) }
    val context = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
    val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.httpsConfigurator = HttpsConfigurator(context)
    server.createContext("/") { exchange ->
        exchange.sendResponseHeaders(200, 2)
        exchange.responseBody.use { it.write("ok".toByteArray()) }
    }
    server.start()
    try {
        fun get(host: String): String {
            val connection = URL("https://$host:${server.address.port}/").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = SSLHelper.unsafeSSLSocketFactory
            connection.hostnameVerifier = SSLHelper.unsafeHostnameVerifier
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            return try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
        }
        check(get("localhost") == "ok")
        check(runCatching { get("127.0.0.1") }.isFailure)
        println("TLS trusted certificate accepted; untrusted certificate and wrong hostname rejected: PASS")
    } finally { server.stop(0) }
}
