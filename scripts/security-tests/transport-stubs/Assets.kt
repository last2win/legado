package io.legado.app.web.utils
object WebAssetSources { fun get() = this; suspend fun read(path: String) = "<html><head></head><body></body></html>".encodeToByteArray() }
object WebStringsProviders { fun get() = this; val cannotEmpty = "empty" }
