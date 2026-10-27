package io.legado.app.help.http

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.legado.app.help.storage.ArchiveLimits
import io.legado.app.help.storage.readArchiveBytes

/** In-memory responses have a hard 32 MiB budget; downloads use streaming file writers instead. */
internal suspend fun HttpResponse.readLimitedBody(limit: Int = ArchiveLimits.MAX_ENTRY_BYTES): ByteArray {
    val declared = headers["Content-Length"]?.toLongOrNull()
    require(declared == null || declared in 0..limit.toLong()) { "HTTP response size limit exceeded" }
    val channel = bodyAsChannel()
    return readArchiveBytes(limit) { buffer, count -> channel.readAvailable(buffer, 0, count) }
}
