package io.legado.app.help.http

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.legado.app.help.storage.ArchiveLimits
import io.legado.app.help.storage.readArchiveBytes

/** In-memory responses have a hard 32 MiB budget; downloads use streaming file writers instead. */
internal fun responseHasContent(method: String, status: Int): Boolean =
    !method.equals("HEAD", true) && status !in 100..199 && status !in listOf(204, 205, 304)

internal suspend fun HttpResponse.readLimitedBody(limit: Int = ArchiveLimits.MAX_ENTRY_BYTES): ByteArray {
    // HEAD/304 Content-Length describes the selected representation, not bytes to receive.
    // These responses have no content regardless of advertised length or encoding (RFC 9110).
    if (!responseHasContent(call.request.method.value, status.value)) return ByteArray(0)
    val declared = headers["Content-Length"]?.toLongOrNull()
    require(declared == null || declared in 0..limit.toLong()) { "HTTP response size limit exceeded" }
    val channel = bodyAsChannel()
    return readArchiveBytes(limit) { buffer, count -> channel.readAvailable(buffer, 0, count) }
}
