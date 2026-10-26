package io.legado.app.lib.webdav

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import io.legado.app.help.storage.ArchiveLimits
import io.legado.app.help.storage.readArchiveBytes

/** execute(block) keeps Ktor from buffering the whole body before applying our budget. */
internal suspend fun HttpClient.readWebDavRange(
    url: String, authorization: Authorization, offset: Long, length: Int, fileSize: Long,
): ByteArray {
    require(length in 0..ArchiveLimits.MAX_INPUT_BYTES && offset >= 0 &&
        offset <= Long.MAX_VALUE - length) { "Invalid archive read range" }
    if (length == 0 || fileSize in 1..offset) return ByteArray(0)
    val expected = if (fileSize > 0) minOf(length.toLong(), fileSize - offset).toInt() else length
    return prepareGet(url) {
        header(authorization.name, authorization.data)
        header("Range", "bytes=$offset-${offset + expected - 1}")
    }.execute { response ->
        if (response.status != HttpStatusCode.PartialContent) {
            throw WebDavException("Expected HTTP 206 for Range request: ${response.status.value}")
        }
        val declared = response.headers["Content-Length"]?.toLongOrNull()
        require(declared == null || declared in 0..expected.toLong()) { "Archive response exceeds requested size" }
        val channel = response.bodyAsChannel()
        readArchiveBytes(expected) { chunk, requested -> channel.readAvailable(chunk, 0, requested) }
    }
}
