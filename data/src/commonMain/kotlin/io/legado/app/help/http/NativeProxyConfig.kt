package io.legado.app.help.http

internal data class NativeProxyConfig(val host: String, val port: Int, val username: String?, val password: String?) {
    companion object {
        fun parse(proxy: String): NativeProxyConfig {
            val match = Regex("(http|https|socks4|socks5)://([^/@\\s]+):(\\d{1,5})(?:@([^@]*)@([^@]*))?")
                .matchEntire(proxy) ?: throw IllegalArgumentException("Invalid proxy format")
            require(match.groupValues[1] in setOf("http", "https")) {
                "SOCKS proxies are not supported on this platform; request refused"
            }
            val port = match.groupValues[3].toInt()
            require(port in 1..65535) { "Invalid proxy port" }
            return NativeProxyConfig(match.groupValues[2], port, match.groupValues[4].ifEmpty { null },
                match.groupValues[5].ifEmpty { null })
        }
    }
}
