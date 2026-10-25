package security

import io.legado.app.help.archive.TarReader
import io.legado.app.help.http.*
import io.legado.app.help.storage.ArchiveLimits
import io.legado.app.model.script.DeferredClose
import io.legado.app.web.api.WebAccess
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

private var checks = 0
private fun verify(ok: Boolean) { check(ok); checks++ }
private fun rejected(block: () -> Unit) { verify(runCatching(block).isFailure) }

private fun tar(size: String = "00000000001", payloadSize: Int = 512): ByteArray {
    val bytes = ByteArray(512 + payloadSize)
    "chapter.txt".toByteArray().copyInto(bytes)
    size.toByteArray().copyInto(bytes, 124)
    bytes[156] = '0'.code.toByte()
    for (i in 148..155) bytes[i] = 32
    val sum = bytes.take(512).sumOf { it.toInt() and 255 }
    sum.toString(8).padStart(6, '0').toByteArray().copyInto(bytes, 148)
    bytes[154] = 0
    bytes[155] = 32
    return bytes
}

fun main() {
    val target = CookieTarget.parse("https://books.example.com/account/page")!!
    verify(cookieMatches(target, "books.example.com", "/account", true, 2000, 1000))
    verify(!cookieMatches(target, "example.com", "/", false, null, 1000)) // host-only
    verify(cookieMatches(target, ".example.com", "/", false, null, 1000))
    verify(!cookieMatches(target, ".evil.example", "/", false, null, 1000))
    verify(!cookieMatches(target, ".example.com", "/accounts", false, null, 1000))
    verify(!cookieMatches(target.copy(path = "/accounting"), ".example.com", "/account", false, null, 1000))
    verify(!cookieMatches(target.copy(scheme = "http"), ".example.com", "/", true, null, 1000))
    verify(!cookieMatches(target, ".example.com", "/", false, 1000, 1000))
    verify(!cookieDomainMatches("127.0.0.1", ".0.0.1"))
    verify(!cookieDomainMatches("evil-example.com", ".example.com"))
    verify(!cookieDomainMatches("example.com.evil.org", ".example.com"))
    verify(sameCookieOrigin("https://a.example/path", "https://a.example:443/other"))
    verify(!sameCookieOrigin("https://a.example", "https://b.example"))
    verify(!sameCookieOrigin("https://a.example", "http://a.example"))
    verify(!sameCookieOrigin("https://a.example", "https://a.example:8443"))
    verify(CookieTarget.parse("https://a.example@evil.example") == null)
    verify(CookieTarget.parse("https://a.example\\@evil.example") == null)
    verify(CookieTarget.parse("https://a.example:99999") == null)
    println("Cookie domain/path/Secure/expiry/origin boundaries: PASS")

    var count = 0
    TarReader.forEach(tar()) { _, _, start, end -> verify(start == 512 && end == 513); count++ }
    verify(count == 1)
    for (size in listOf("77777777777", "40000000000", "-0000000001", "garbage")) rejected {
        TarReader.forEach(tar(size)) { _, _, _, _ -> error("Should not visit invalid entry") }
    }
    rejected { TarReader.forEach(tar(payloadSize = 0)) { _, _, _, _ -> } }
    rejected { TarReader.forEach(tar().also { it[0] = 0 }) { _, _, _, _ -> } }
    rejected { TarReader.forEach(tar(), { error("cancelled") }) { _, _, _, _ -> } }
    for (path in listOf("../file", "a/../../file", "/absolute", "a\\..\\file", "a/..", "C:/file")) {
        rejected { ArchiveLimits.checkPath(path) }
        rejected { io.legado.app.help.storage.NativeZipCodec.unzipToMap(zip(path to byteArrayOf(1))) }
    }
    println("TAR overflow/truncation/checksum/cancellation and archive paths: PASS")

    var released = 0
    val lifetime = DeferredClose { released++ }
    lifetime.use {
        lifetime.use {
            lifetime.close()
            verify(released == 0)
            rejected { lifetime.use { error("must not re-enter closing scope") } }
        }
        verify(released == 0)
    }
    verify(released == 1)
    lifetime.close(); verify(released == 1)
    rejected { lifetime.use {} }
    var exceptionalRelease = 0
    val exceptional = DeferredClose { exceptionalRelease++ }
    rejected { exceptional.use { exceptional.close(); error("script threw") } }
    verify(exceptionalRelease == 1)
    println("Runtime nested close/deferred free/repeated close/exception paths: PASS")

    for (proxy in listOf("socks4://localhost:1080", "socks5://localhost:1080", "http://:80", "http://host:0", "http://host:65536", "prefix http://host:80", "http://host:80 trailing")) {
        rejected { NativeProxyConfig.parse(proxy) }
    }
    verify(NativeProxyConfig.parse("http://localhost:80@user@password").port == 80)
    verify(NativeProxyConfig.parse("https://localhost:8443").host == "localhost")
    println("Proxy unsupported/invalid fail-closed and valid HTTP configuration: PASS")

    WebAccess.stop(); verify(!WebAccess.authorized(emptyMap()))
    WebAccess.start()
    val token = WebAccess.accessUrl("http://localhost:1122").substringAfter("access_token=")
    verify(token.length == 64)
    verify(!WebAccess.authorized(emptyMap()))
    verify(!WebAccess.authorized(mapOf("Authorization" to "Bearer wrong")))
    val auth = mapOf("Authorization" to "Bearer $token", "host" to "localhost:1122", "origin" to "http://localhost:1122")
    verify(WebAccess.authorized(auth))
    verify(!WebAccess.authorized(auth + ("origin" to "http://evil.example")))
    verify(!WebAccess.authorized(auth + ("origin" to "null")))
    val cookie = mapOf("Cookie" to "legado_web_session=$token", "host" to "localhost:1123", "origin" to "http://localhost:1122")
    verify(WebAccess.authorized(cookie, webSocket = true))
    verify(!WebAccess.authorized(cookie + ("origin" to "http://evil.example"), webSocket = true))
    for (path in listOf("/getBackupZip", "/restoreBackup", "/saveBookSource", "/assets/../getBackupZip")) verify(!WebAccess.isPublicAsset("GET", path))
    WebAccess.start(); verify(!WebAccess.authorized(auth))
    WebAccess.stop(); verify(!WebAccess.authorized(auth))
    println("Web auth HTTP/WebSocket/origin/session rotation: PASS")

    val normal = zip("empty" to byteArrayOf(), "book/chapter.txt" to "正常章节".toByteArray())
    verify(io.legado.app.help.storage.NativeZipCodec.unzipToMap(normal).size == 2)
    // Forged central-directory size: inflate must stop at the declared bound, not expand then check.
    val forged = zip("forged" to ByteArray(8192) { (it % 253).toByte() })
    val central = (0..forged.size - 4).first { forged[it] == 0x50.toByte() && forged[it+1] == 0x4b.toByte() && forged[it+2] == 1.toByte() && forged[it+3] == 2.toByte() }
    for (i in 0..3) forged[central + 24 + i] = 0
    rejected { io.legado.app.help.storage.NativeZipCodec.unzipToMap(forged) }
    val deflater = Deflater(6, true)
    deflater.setInput(ByteArray(4096) { (it % 127).toByte() }); deflater.finish()
    val compressed = ByteArray(8192); val length = deflater.deflate(compressed); deflater.end()
    rejected { io.legado.app.help.storage.NativeZipCodec.inflate(compressed, 0, length, 16) }
    rejected { io.legado.app.help.storage.NativeZipCodec.inflate(compressed, Int.MAX_VALUE, 20) }
    val many = zip(*(0..ArchiveLimits.MAX_ENTRIES).map { "$it" to byteArrayOf() }.toTypedArray())
    rejected { io.legado.app.help.storage.NativeZipCodec.unzipToMap(many) }
    println("ZIP forged sizes/runtime output budget/range/count limits: PASS")
    println("$checks security boundary assertions passed")
}
