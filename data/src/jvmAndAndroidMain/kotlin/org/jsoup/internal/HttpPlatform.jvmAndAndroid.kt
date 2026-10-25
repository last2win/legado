package org.jsoup.internal

import io.legado.app.help.http.KmpHttpClient
import io.legado.app.help.http.KmpRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.IOException
import okio.source
import org.jsoup.Connection
import java.io.UncheckedIOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import kotlin.io.inputStream
import kotlin.io.readBytes
import kotlin.io.use

/**
 * jsoup 兼容层平台门面 jvmAndAndroid actual。
 *
 * 全部委托原 JDK/OkHttp 实现, 与下沉前 [HttpConnection]/[HttpResponse] 内联实现逐行等价
 * (URLEncoder / GZIPInputStream / OkHttpClient.Builder / MultipartBody / UncheckedIOException)。
 */
internal actual fun urlEncodeForm(value: String, charset: String): String =
    URLEncoder.encode(value, charset)

internal actual fun decompressBody(bytes: ByteArray, contentEncoding: String?): ByteArray =
    when (contentEncoding) {
        "gzip" -> GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        "deflate" -> InflaterInputStream(bytes.inputStream(), Inflater(true)).use { it.readBytes() }
        else -> bytes
    }

internal actual fun buildPlatformClient(
    request: HttpConnectionRequest,
    shared: KmpHttpClient?,
): KmpHttpClient {
    // 宿主注入了共享 client 则 newBuilder 派生(继承拦截器/连接池),否则裸建
    val builder = (shared?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(request.timeout().toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(request.timeout().toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(request.followRedirects())
        .followSslRedirects(request.followRedirects())

    if (shared != null) {
        // 共享 client 的整次请求超时 (见挑战拦截器的时间预算) 会盖过上面 per-request 的
        // connect/read 超时, 二者同时生效时以更短的一方为准, 故清零让 per-request 值生效
        builder.callTimeout(0, TimeUnit.MILLISECONDS)
    }

    (request.sslSocketFactory() as? SSLSocketFactory)?.let { factory ->
        builder.sslSocketFactory(factory, io.legado.app.help.http.SSLHelper.unsafeTrustManager)
    } ?: (request.sslContext() as? SSLContext)?.let { ctx ->
        builder.sslSocketFactory(ctx.socketFactory, io.legado.app.help.http.SSLHelper.unsafeTrustManager)
    }

    return builder.build()
}

/**
 * 零字段 multipart: okhttp 的 MultipartBody.Builder 拒绝构建空 parts,按 OkHttp MultipartBody
 * 的分隔行口径只发结束分隔行 (与 native 端手工拼接结果一致)。
 */
internal actual fun buildMultipartBody(
    data: Collection<Connection.KeyVal>,
    boundary: String
): KmpRequestBody {
    if (data.isEmpty()) {
        val mediaType = "${okhttp3.MultipartBody.FORM}; boundary=$boundary".toMediaType()
        return "--$boundary--\r\n".toRequestBody(mediaType)
    }

    val body = okhttp3.MultipartBody.Builder(boundary)
        .setType(okhttp3.MultipartBody.FORM)

    for (kv in data) {
        val stream = kv.inputStream()
        if (stream != null) {
            val contentType = kv.contentType() ?: "application/octet-stream"
            val fileBody = object : RequestBody() {
                override fun contentType() = contentType.toMediaTypeOrNull()
                override fun contentLength() = -1L

                // 流只能消费一次,禁止 OkHttp 在重试/重定向时二次 writeTo
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    stream.source().use { sink.writeAll(it) }
                }
            }
            // jsoup 语义:文件字段的 value() 即 filename
            body.addFormDataPart(kv.key(), kv.value(), fileBody)
        } else {
            body.addFormDataPart(kv.key(), kv.value())
        }
    }

    return body.build()
}

internal actual fun defaultSharedHttpClient(): KmpHttpClient? = null

internal actual fun uncheckedIoException(e: IOException): Throwable = UncheckedIOException(e)

/** Kept for callers of the compatibility API; uses platform trust and hostname checks. */
internal val UnsafeSslContext: SSLContext
    get() = io.legado.app.help.http.SSLHelper.unsafeSslContext
