package io.legado.app.web.api

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

internal actual fun newWebAccessToken(): String =
    FileSystem.SYSTEM.source("/dev/urandom".toPath()).buffer().use { it.readByteArray(32) }
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
