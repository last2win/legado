package io.legado.app.help.image

import coil3.network.NetworkClient
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import io.legado.app.help.http.KmpHttpClient
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.await
import io.legado.app.help.http.toKmpRequestBody
import io.legado.app.utils.canonicalizeUri
import io.legado.app.utils.systemCurrentTimeMillis
import okio.Buffer

/** Coil uses the same per-hop cookie selection and credential stripping as source requests. */
internal class IosControlledNetworkClient(private val client: KmpHttpClient) : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T {
        val body = request.body?.let {
            val buffer = Buffer()
            it.writeTo(buffer)
            buffer.readByteArray().toKmpRequestBody(null)
        }
        val builder = KmpRequestBuilder().url(request.url.canonicalizeUri()).method(request.method, body)
        request.headers.asMap().forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
        val started = systemCurrentTimeMillis()
        val response = client.newCall(builder.build()).await()
        try {
            val headers = NetworkHeaders.Builder().apply {
                response.headers().toMultimap().forEach { (name, values) -> set(name, values) }
            }.build()
            return block(NetworkResponse(
                code = response.code,
                requestMillis = started,
                responseMillis = systemCurrentTimeMillis(),
                headers = headers,
                body = NetworkResponseBody(Buffer().write(response.body.bytes())),
                delegate = response,
            ))
        } finally {
            response.close()
        }
    }
}
