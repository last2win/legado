package io.legado.app.web.api
import io.legado.app.api.ReturnData
class Response(val returnData: ReturnData = ReturnData())
object WebApi {
    val calls = mutableListOf<WebApiRequest>()
    suspend fun handle(request: WebApiRequest): Response { calls.add(request); return Response() }
}
