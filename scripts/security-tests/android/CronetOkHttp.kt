package okhttp3
class Request { class Builder { fun url(s: String) = this; fun head() = this; fun build() = Request() } }
class Response : java.io.Closeable {
    val isSuccessful = false
    fun header(name: String): String? = null
    override fun close() {}
}
class Call { fun execute(): Response = error("Unexpected network call") }
class Client { fun newCall(request: Request) = Call() }
