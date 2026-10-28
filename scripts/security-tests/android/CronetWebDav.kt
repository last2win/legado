package io.legado.app.lib.webdav
class Authorization(a: String, b: String)
class WebDav(url: String, auth: Authorization) {
    val webDavClient = okhttp3.Client()
    fun readRange(offset: Long, length: Int, size: Long): ByteArray = error("Unexpected network read")
}
