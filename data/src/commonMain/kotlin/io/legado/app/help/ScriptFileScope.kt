package io.legado.app.help

import io.legado.app.utils.MD5Utils

internal fun scriptStorageRoot(sourceKey: String?): String =
    "script-files/" + MD5Utils.md5Encode(sourceKey ?: error("Script files require a source"))

internal fun checkedScriptPath(path: String): String {
    val relative = path.trimStart('/')
    require(relative.isNotEmpty() && !relative.contains('\\') && !relative.contains(':') &&
        !relative.contains('\u0000') && relative.split('/').none { it == ".." || it == "." }) {
        "File access outside the current source is denied"
    }
    return relative
}
