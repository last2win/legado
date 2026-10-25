package io.legado.app.web.socket
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
class NanoWsSession(handshake: NanoHTTPD.IHTTPSession, handler: (Any) -> Any) : NanoWSD.WebSocket(handshake) {
 override fun onOpen() {}
 override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {}
 override fun onMessage(message: NanoWSD.WebSocketFrame) {}
 override fun onPong(pong: NanoWSD.WebSocketFrame) {}
 override fun onException(exception: IOException) {}
}
