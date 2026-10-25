package io.legado.app.web.api

internal actual fun newWebAccessToken(): String = ByteArray(32).also {
    java.security.SecureRandom().nextBytes(it)
}.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
