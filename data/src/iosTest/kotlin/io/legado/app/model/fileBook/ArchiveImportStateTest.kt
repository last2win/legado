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
import okio.Path
import io.legado.app.help.archive.ARCHIVE_ENTRY_VARIABLE
import io.legado.app.help.file.safeBookFilePath
import io.legado.app.data.entities.BaseSource
import io.legado.app.utils.InputStream
import io.legado.app.utils.readAllAndClose
import okio.Path.Companion.toPath
import kotlin.test.*

/** Real ZIP -> stable files -> production import -> real Room/SQLite, including REPLACE collisions. */
class ArchiveImportStateTest {
    private fun withDatabase(block: suspend (FileSystem, Path, AppDatabase) -> Unit) = runBlocking {
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
            block(fs, root, db)
        } finally {
            db.close()
            if (oldFiles == null) FileBookProviders.reset() else FileBookProviders.register(oldFiles)
            if (oldAccessor == null) AppDbProviders.unregister() else AppDbProviders.register(oldAccessor)
            if (oldDb == null) AppDatabaseProviders.reset() else AppDatabaseProviders.register(oldDb)
            if (oldDirs == null) AppFilesDirs.reset() else AppFilesDirs.register(oldDirs)
            fs.deleteRecursively(root, mustExist = false)
        }
    }
    @Test fun repeatedZipImportAndLegacyTempMigrationKeepReadingState() = verifyMigration(false)
    @Test fun renamedBeforeUpgradeRetainsTitleAuthorAndReadingState() = verifyMigration(true)

    private fun verifyMigration(renamed: Boolean) = withDatabase { fs, root, db ->
        val txt = root / "book.txt"
        val zip = root / "same.zip"
        fs.write(txt) { writeUtf8("book contents") }
        assertTrue(NativeZipCodec.zipFiles(listOf(txt.toString()), zip.toString()))
        val oldFile = root / "cache/ArchiveTemp/0123456789abcdef-123/book.txt"
        fs.createDirectories(oldFile.parent!!)
        fs.copy(txt, oldFile)
        val legacy = Book(bookUrl = "file://$oldFile",
            name = if (renamed) "renamed before upgrade" else "book",
            author = if (renamed) "edited author" else "",
            originName = "book.txt", type = BookType.text or BookType.local,
            durChapterIndex = 8, durChapterPos = 260, group = 64, order = -7,
            readConfig = Book.ReadConfig(reverseToc = true, dailyChapters = 9))
        db.bookDao.insert(legacy)
        val firstPath = NativeArchiveProvider.deCompressForImport(zip.toString()).single()
        val first = FileBook.importLocalFile(firstPath)
        assertEquals(legacy.durChapterIndex, first.durChapterIndex)
        assertEquals(legacy.durChapterPos, first.durChapterPos)
        assertEquals(legacy.name, first.name)
        assertEquals(legacy.author, first.author)
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
    }

    @Test fun ambiguousOldRecordsAreNotOverwritten() = withDatabase { fs, root, db ->
        val txt = root / "book.txt"
        val zip = root / "books.zip"
        fs.write(txt) { writeUtf8("same book contents") }
        assertTrue(NativeZipCodec.zipFiles(listOf(txt.toString()), zip.toString()))
        for (i in 1..2) {
            val old = root / "cache/ArchiveTemp/0123456789abcdef-$i/book.txt"
            fs.createDirectories(old.parent!!)
            fs.copy(txt, old)
            db.bookDao.insert(Book(bookUrl = "file://$old", name = "custom $i", author = "author $i",
                type = BookType.local or BookType.text, durChapterIndex = i, durChapterPos = i * 100))
        }
        val incoming = NativeArchiveProvider.deCompressForImport(zip.toString()).single()
        assertFailsWith<IllegalArgumentException> { FileBook.importLocalFile(incoming) }
        assertEquals(2, db.bookDao.allBookCount())
        for (i in 1..2) {
            val book = db.bookDao.getBook("custom $i", "author $i")!!
            assertEquals(i, book.durChapterIndex)
            assertEquals(i * 100, book.durChapterPos)
        }
    }

    @Test fun remoteArchiveRedownloadKeepsOriginalEntryAndBookState() = withDatabase { fs, root, db ->
        for (folder in listOf("a", "b")) {
            fs.createDirectories(root / folder)
            fs.write(root / folder / "book.txt") { writeUtf8("$folder book contents") }
        }
        val zipPath = root / "books.zip"
        assertTrue(NativeZipCodec.zipFiles(listOf((root / "a").toString(), (root / "b").toString()), zipPath.toString()))
        val oldFactory = runCatching { ZipFileWrapperFactoryProviders.get() }.getOrNull()
        val native = NativeFileBookAccessor()
        FileBookProviders.register(object : FileBookAccessor by native {
            override suspend fun saveBookFile(str: String, fileName: String, source: BaseSource?): String {
                assertTrue(str.startsWith("https://example.test/books.zip"))
                assertEquals("books.zip", fileName)
                return "file://$zipPath" // only transport is substituted; parsing/storage/Room are real
            }
            override fun saveBookFile(inputStream: InputStream, fileName: String): String {
                val target = safeBookFilePath((root / "saved").toString(), fileName).toPath()
                fs.write(target) { write(inputStream.readAllAndClose()) }
                return "file://$target"
            }
        })
        ZipFileWrapperFactoryProviders.register(object : ZipFileWrapperFactory {
            override fun create(book: Book) = ZipFileWrapperFactory.CreateResult(
                NativeZipFileWrapper(book.bookUrl.removePrefix("file://")))
        })
        try {
            val zip = NativeZipFileWrapper(zipPath.toString())
            val origin = BookType.webDavTag + "https://example.test/books.zip,{\"serverID\":1}"
            val book = try { FileBookRemoteImportShared.importRemoteArchiveEntry(zip, origin) } finally { zip.close() }
            val originalEntry = book.getVariable(ARCHIVE_ENTRY_VARIABLE)
            assertTrue(originalEntry in listOf("a/book.txt", "b/book.txt"))
            val expectedContents = originalEntry.substringBefore('/') + " book contents"
            assertTrue(book.originName.startsWith("book__"))
            book.name = "custom title"
            book.author = "custom author"
            book.durChapterIndex = 8
            book.durChapterPos = 260
            book.group = 64
            book.readConfig = Book.ReadConfig(reverseToc = true)
            book.save()
            // Restore both pre-metadata records and new records through the complete shared download flow.
            for (legacy in listOf(true, false)) {
                if (legacy) book.putVariable(ARCHIVE_ENTRY_VARIABLE, null)
                fs.delete(book.bookUrl.removePrefix("file://").toPath())
                assertTrue(FileBookRemoteImportShared.downloadRemoteBook(book))
                assertEquals(expectedContents, fs.read(book.bookUrl.removePrefix("file://").toPath()) { readUtf8() })
                val saved = db.bookDao.getBook(book.bookUrl)!!
                assertEquals(origin, saved.origin)
                assertEquals(originalEntry, saved.getVariable(ARCHIVE_ENTRY_VARIABLE))
                assertEquals("custom title", saved.name)
                assertEquals("custom author", saved.author)
                assertEquals(8, saved.durChapterIndex)
                assertEquals(260, saved.durChapterPos)
                assertEquals(64L, saved.group)
                assertEquals(book.readConfig, saved.readConfig)
                assertEquals(1, db.bookDao.allBookCount())
            }
        } finally {
            if (oldFactory == null) ZipFileWrapperFactoryProviders.reset() else ZipFileWrapperFactoryProviders.register(oldFactory)
        }
    }

}
