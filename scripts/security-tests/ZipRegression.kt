package security

import io.legado.app.help.storage.NativeZipCodec
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    } }
}.toByteArray()

fun main() {
    val normal = zip("book/chapter.txt" to "正常章节".toByteArray())
    check(NativeZipCodec.unzipToMap(normal)["book/chapter.txt"]!!.decodeToString() == "正常章节")
    val bomb = zip("bomb.txt" to ByteArray(4 * 1024 * 1024) { 65 })
    check(runCatching { NativeZipCodec.unzipToMap(bomb) }.isFailure) {
        "ZIP compression bomb was accepted (${bomb.size} bytes -> 4 MiB)"
    }
    println("ZIP normal archive and compression bomb: PASS")
}
