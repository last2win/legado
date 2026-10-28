package io.legado.app.utils
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
object FileUtilsBase {
    fun replaceFileAtomically(from: File, to: File) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
}
object LogUtils { fun d(tag: String, message: String) {} }
fun atomicWriteTempPath(path: String) = "$path.${java.util.UUID.randomUUID()}.tmp"
