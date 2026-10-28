package io.legado.app.model.fileBook

import androidx.room3.Room
import androidx.sqlite.driver.NativeSQLiteDriver
import io.legado.app.constant.BookType
import io.legado.app.data.*
import io.legado.app.data.entities.Book
import io.legado.app.help.archive.NativeArchiveProvider
import io.legado.app.help.file.AppFilesDir
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.storage.NativeZipCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.*

/** Real ZIP -> stable files -> production import -> real Room/SQLite, including REPLACE collisions. */
class ArchiveImportStateTest {
    @Test fun repeatedZipImportAndLegacyTempMigrationKeepReadingState() = runBlocking {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "legado-import-state"
        fs.deleteRecursively(root, mustExist = false)
        fs.createDirectories(root)
        val oldDirs = runCatching { AppFilesDirs.get() }.getOrNull()
        val oldDb = runCatching { AppDatabaseProviders.get() }.getOrNull()
        val oldAccessor = runCatching { AppDbProviders.get() }.getOrNull()
        val oldFiles = runCatching { FileBookProviders.get() }.getOrNull()
        val db = Room.databaseBuilder<AppDatabase>(name = (root / "state.db").toString())
            .setDriver(NativeSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        AppFilesDirs.register(object : AppFilesDir {
            override val filesDir = (root / "files").toString()
            override val cacheDir = (root / "cache").toString()
            override val externalFilesDir: String? = null
            override val externalCacheDir: String? = null
        })
        AppDatabaseProviders.register(object : AppDatabaseProvider { override val appDb = db })
        AppDbProviders.register(NativeAppDbAccessor())
        FileBookProviders.register(NativeFileBookAccessor())
        try {
            val txt = root / "book.txt"
            val zip = root / "same.zip"
            fs.write(txt) { writeUtf8("book contents") }
            assertTrue(NativeZipCodec.zipFiles(listOf(txt.toString()), zip.toString()))
            // Simulate the previous release's randomized bookUrl with actual stored user state.
            val legacy = Book(bookUrl = "file:///ArchiveTemp/old-random/book.txt", name = "book", author = "",
                originName = "book.txt", type = BookType.text or BookType.local,
                durChapterIndex = 8, durChapterPos = 260, group = 64, order = -7,
                readConfig = Book.ReadConfig(reverseToc = true, dailyChapters = 9))
            db.bookDao.insert(legacy)
            val firstPath = NativeArchiveProvider.deCompressForImport(zip.toString()).single()
            val first = FileBook.importLocalFile(firstPath)
            assertEquals(legacy.durChapterIndex, first.durChapterIndex)
            assertEquals(legacy.durChapterPos, first.durChapterPos)
            first.name = "custom title"
            db.bookDao.update(first)
            val secondPath = NativeArchiveProvider.deCompressForImport(zip.toString()).single()
            assertEquals(firstPath, secondPath)
            val second = FileBook.importLocalFile(secondPath)
            val saved = db.bookDao.getBook(second.bookUrl)!!
            assertEquals(1, db.bookDao.allBookCount())
            assertEquals("custom title", saved.name)
            assertEquals(8, saved.durChapterIndex)
            assertEquals(260, saved.durChapterPos)
            assertEquals(64L, saved.group)
            assertEquals(-7, saved.order)
            assertEquals(legacy.readConfig, saved.readConfig)
            assertNull(db.bookDao.getBook(legacy.bookUrl))
            fs.deleteRecursively(root / "cache", mustExist = false)
            assertTrue(fs.exists(firstPath.toPath()))
        } finally {
            db.close()
            if (oldFiles == null) FileBookProviders.reset() else FileBookProviders.register(oldFiles)
            if (oldAccessor == null) AppDbProviders.unregister() else AppDbProviders.register(oldAccessor)
            if (oldDb == null) AppDatabaseProviders.reset() else AppDatabaseProviders.register(oldDb)
            if (oldDirs == null) AppFilesDirs.reset() else AppFilesDirs.register(oldDirs)
            fs.deleteRecursively(root, mustExist = false)
        }
    }
}
