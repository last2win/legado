package security

import android.net.Uri
import android.os.Binder
import android.content.ContentValues
import android.content.pm.PackageManager
import io.legado.app.api.ReaderProvider
import io.legado.app.web.api.WebApi
import io.legado.app.lib.cronet.CronetLibraryIntegrity
import java.nio.file.Files
import java.util.zip.ZipFile

fun main() {
    val provider = ReaderProvider()
    fun uri(path: String) = Uri("content://io.legado.test.readerProvider/$path")
    Binder.caller = 999
    val operations = listOf<() -> Any?>(
        { provider.query(uri("bookSources/query"), null, null, null, null) },
        { provider.insert(uri("bookSource/insert"), ContentValues()) },
        { provider.delete(uri("bookSources/delete"), "[]", null) },
        { provider.update(uri("book/insert"), null, null, null) },
        { provider.getType(uri("bookSources/query")) },
    )
    operations.forEach { op ->
        check(runCatching(op).exceptionOrNull() is SecurityException)
        check(WebApi.calls.isEmpty())
    }
    for (sameUid in listOf(true, false)) {
        Binder.caller = if (sameUid) 100 else 999
        PackageManager.sameSignature = !sameUid
        provider.query(uri("bookSources/query"), null, null, null, null)
        check(WebApi.calls.last().path == "/getBookSources" && WebApi.calls.last().trustedLocalCall)
    }
    val count = WebApi.calls.size
    for (path in listOf("rssSource/query", "rssSources/query"))
        check(runCatching { provider.query(uri(path), null, null, null, null) }.exceptionOrNull() is UnsupportedOperationException)
    for (path in listOf("rssSource/insert", "rssSources/insert"))
        check(runCatching { provider.insert(uri(path), ContentValues()) }.exceptionOrNull() is UnsupportedOperationException)
    check(runCatching { provider.delete(uri("rssSources/delete"), "[]", null) }.exceptionOrNull() is UnsupportedOperationException)
    check(WebApi.calls.size == count)
    println("Production ReaderProvider: caller rejection, trusted dispatch, all legacy RSS URIs PASS (Android Binder/DB adapters stubbed)")

    val temp = Files.createTempFile("cronet-integrity", ".so").toFile()
    try {
        temp.writeText("untrusted cached library")
        check(!CronetLibraryIntegrity.verify(temp, "143.7445.0", "arm64-v8a"))
        System.getenv("CRONET_TEST_AAR")?.let { aar -> ZipFile(aar).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".so") }.forEach { entry ->
                val abi = entry.name.split('/')[1]
                zip.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it) } }
                check(CronetLibraryIntegrity.verify(temp, "143.7445.0", abi))
                check(!CronetLibraryIntegrity.verify(temp, "future", abi))
                check(!CronetLibraryIntegrity.verify(temp, "143.7445.0", "unknown"))
                java.io.RandomAccessFile(temp, "rw").use { it.seek(10); val byte = it.readByte(); it.seek(10); it.writeByte(byte.toInt() xor 1) }
                check(!CronetLibraryIntegrity.verify(temp, "143.7445.0", abi))
            }
        } }
    } finally { temp.delete() }
    println("Production Cronet digest validation: untrusted cache, pinned official ABIs and tampering PASS")
}
