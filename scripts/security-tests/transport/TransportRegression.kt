package security

import io.legado.app.web.HttpServer
import io.legado.app.web.WebSocketServer
import io.legado.app.web.api.WebAccess
import io.legado.app.api.controller.Calls
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.Socket

fun main() {
    val http = HttpServer(0)
    val ws = WebSocketServer(0)
    WebAccess.start()
    http.start(); ws.start()
    try {
        val client = HttpClient.newHttpClient()
        val base = "http://127.0.0.1:${http.listeningPort}"
        val token = WebAccess.accessUrl(base).substringAfter("access_token=")
        fun request(path: String, method: String = "GET", headers: Map<String,String> = emptyMap()): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(base + path))
            headers.forEach { (name,value) -> builder.header(name,value) }
            builder.method(method, HttpRequest.BodyPublishers.noBody())
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        val before = Calls.count
        for (path in listOf("/getBackupZip", "/getBookshelf", "/getBookSources")) check(request(path).statusCode() == 401)
        for (path in listOf("/restoreBackup", "/deleteBook", "/saveBookSources")) check(request(path,"POST").statusCode() == 401)
        check(Calls.count == before)
        check(request("/").body().contains("access_token"))
        check(request("/__session","POST").statusCode() == 401)
        val session = request("/__session","POST",mapOf("Authorization" to "Bearer $token", "Origin" to base))
        check(session.statusCode() == 200)
        val cookie = session.headers().firstValue("Set-Cookie").orElseThrow()
        check(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"))
        check(request("/getBookshelf", headers = mapOf("Cookie" to cookie.substringBefore(';'))).statusCode() == 200)
        check(Calls.count == before + 1)
        check(request("/deleteBook", "POST", mapOf("Cookie" to cookie.substringBefore(';'), "Origin" to "http://evil.example")).statusCode() == 401)
        check(Calls.count == before + 1)
        Socket("127.0.0.1", ws.listeningPort).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(("GET /bookSourceDebug HTTP/1.1\r\nHost: 127.0.0.1:${ws.listeningPort}\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            check(socket.getInputStream().bufferedReader().readLine().contains("401"))
        }
        WebAccess.stop()
        check(request("/getBackupZip", headers = mapOf("Cookie" to cookie.substringBefore(';'))).statusCode() == 401)
        println("Real NanoHTTPD/WebApi/NanoWSD authentication and controller isolation: PASS")
    } finally {
        ws.stop(); http.stop(); WebAccess.stop()
    }
}
