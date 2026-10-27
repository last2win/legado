package io.legado.app.format.epub

import io.legado.app.help.archive.NativeArchiveProvider
import io.legado.app.help.file.AppFilesDir
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.storage.NativeZipCodec
import okio.FileSystem
import kotlin.test.*

class ArchiveDirectoryIsolationTest {
    @Test fun repeatedArchiveNamesNeverReturnFilesFromPreviousImport() {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "legado-archive-isolation"
        fs.deleteRecursively(root, mustExist = false)
        fs.createDirectories(root)
        val previous = runCatching { AppFilesDirs.get() }.getOrNull()
        AppFilesDirs.register(object : AppFilesDir {
            override val filesDir = root.toString()
            override val cacheDir = root.toString()
            override val externalFilesDir: String? = null
            override val externalCacheDir: String? = null
        })
        try {
            val archive = root / "same.zip"
            fun pack(name: String) {
                val path = root / name
                fs.write(path) { writeUtf8(name) }
                assertTrue(NativeZipCodec.zipFiles(listOf(path.toString()), archive.toString()))
            }
            pack("first.txt")
            val first = NativeArchiveProvider.deCompress(archive.toString())
            pack("second.txt")
            val second = NativeArchiveProvider.deCompress(archive.toString())
            assertEquals(listOf("first.txt"), first.map { it.substringAfterLast('/') })
            assertEquals(listOf("second.txt"), second.map { it.substringAfterLast('/') })
            assertNotEquals(first.single().substringBeforeLast('/'), second.single().substringBeforeLast('/'))
        } finally {
            if (previous == null) AppFilesDirs.reset() else AppFilesDirs.register(previous)
            fs.deleteRecursively(root, mustExist = false)
        }
    }
}
