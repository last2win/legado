package security

import io.legado.app.App
import io.legado.app.lib.cronet.CronetLoader
import io.legado.app.lib.cronet.CronetLibraryIntegrity
import io.legado.app.lib.cronet.VerifiedLibraryCache
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import java.time.Duration
import java.nio.file.Files
import java.util.zip.ZipFile

/** Execute the production Loader + SHA verifier; only Android/provider/transport adapters are stubs. */
fun cronetLoaderRegression() {
    val aar = System.getenv("CRONET_TEST_AAR") ?: return
    val fileField = CronetLoader.javaClass.getDeclaredField("soFile").apply { isAccessible = true }
    val file = fileField.get(CronetLoader) as File
    fun measuredReads(block: () -> Unit): Long {
        val recordingFile = Files.createTempFile("cronet-reads", ".jfr")
        try {
            Recording().use { recording ->
                recording.enable("jdk.FileRead").withThreshold(Duration.ZERO)
                recording.start()
                block()
                recording.stop()
                recording.dump(recordingFile)
            }
            return RecordingFile.readAllEvents(recordingFile).filter {
                it.eventType.name == "jdk.FileRead" && it.getString("path") == file.path
            }.sumOf { it.getLong("bytesRead").coerceAtLeast(0) }
        } finally { Files.deleteIfExists(recordingFile) }
    }
    fun restore() = ZipFile(aar).use { zip ->
        zip.getInputStream(zip.getEntry("jni/arm64-v8a/libcronet.143.0.7445.0.so")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    try {
        restore()
        val bytes = measuredReads {
            repeat(10) { check(CronetLoader.install()) }
            val pool = Executors.newFixedThreadPool(8)
            try { pool.invokeAll(List(100) { Callable { check(CronetLoader.install()) } }).forEach { it.get() } }
            finally { pool.shutdownNow() }
        }
        check(bytes == file.length()) { "110 install calls read $bytes bytes of a ${file.length()}-byte library" }
        println("Production CronetLoader: 110 install calls, 1 full read ($bytes bytes, measured by JFR)")
        val mtime = file.lastModified()
        check(file.setLastModified(mtime + 2000))
        check(measuredReads { check(CronetLoader.install()) } == file.length())
        file.writeText("tampered replacement")
        check(!CronetLoader.install())
        restore()
        check(CronetLoader.install()) // a failed check must not poison future installs
        val size = file.length()
        val time = file.lastModified()
        java.io.RandomAccessFile(file, "rw").use { it.seek(10); val old = it.readByte(); it.seek(10); it.writeByte(old.toInt() xor 1) }
        check(file.setLastModified(time) && file.length() == size)
        val preLoadBytes = measuredReads {
            check(runCatching { CronetLoader.loadLibrary("cronet_nonexistent_test") }.exceptionOrNull() is UnsatisfiedLinkError)
        }
        check(preLoadBytes == size && !file.exists()) // cached availability never authorizes System.load
        check(!CronetLoader.install())
        restore()
        val downloaded = File(App.instance.cacheDir, "so_download/${file.name}")
        downloaded.parentFile.mkdirs()
        file.copyTo(downloaded, overwrite = true)
        file.writeText("corrupt installed file")
        var installed = false
        CronetLoader.preDownload { installed = it }
        check(installed && CronetLoader.install()) // real staged copy + verification + invalidation
        check(measuredReads { repeat(10) { check(CronetLoader.install()) } } == 0L)

        var checks = 0
        val cache = VerifiedLibraryCache { f, version, abi -> checks++; CronetLibraryIntegrity.verify(f, version, abi) }
        check(cache.available(file, "143.7445.0", "arm64-v8a"))
        check(cache.available(file, "143.7445.0", "arm64-v8a") && checks == 1)
        cache.invalidate()
        check(cache.available(file, "143.7445.0", "arm64-v8a") && checks == 2)
        check(!cache.available(file, "future", "arm64-v8a"))
        check(!cache.available(file, "143.7445.0", "unknown"))
        println("Cronet cache invalidation, staged install, version/ABI mismatch and mandatory pre-load verification: PASS")
    } finally {
        App.instance.rootDir.deleteRecursively()
    }
}
