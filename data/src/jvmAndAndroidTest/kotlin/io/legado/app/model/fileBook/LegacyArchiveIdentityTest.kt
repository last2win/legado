package io.legado.app.model.fileBook

import io.legado.app.help.archive.findLegacyArchiveRef
import io.legado.app.help.archive.archiveBookFileNames
import io.legado.app.help.archive.resolveArchiveBookEntry
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LegacyArchiveIdentityTest {
    @Test fun migrationUsesEntryAndContentsAndRejectsAmbiguity() {
        val root = Files.createTempDirectory("archive-identity").toFile()
        try {
            val files = root.resolve("files")
            val cache = root.resolve("cache")
            val path = "folder/book.txt"
            val incoming = files.resolve("ImportedArchives/${"a".repeat(64)}/${path.encodeUtf8().sha256().hex()}/book.txt")
            incoming.parentFile.mkdirs()
            incoming.writeText("same book contents")
            val first = cache.resolve("ArchiveTemp/0123456789abcdef-123/$path")
            first.parentFile.mkdirs()
            first.writeText(incoming.readText())
            val ref = "file://${first.path}"
            fun match(candidates: List<String>) = findLegacyArchiveRef(incoming.path, candidates, files.path, cache.path)
            assertEquals(ref, match(listOf(ref)))
            first.writeText("different contents")
            assertNull(match(listOf(ref)))
            first.writeText(incoming.readText())
            val wrongEntry = cache.resolve("ArchiveTemp/0123456789abcdef-456/another/book.txt")
            wrongEntry.parentFile.mkdirs()
            wrongEntry.writeText(incoming.readText())
            assertNull(match(listOf(wrongEntry.path)))
            val duplicate = cache.resolve("ArchiveTemp/0123456789abcdef-789/$path")
            duplicate.parentFile.mkdirs()
            duplicate.writeText(incoming.readText())
            assertThrows(IllegalArgumentException::class.java) { match(listOf(ref, duplicate.path)) }
            assertNull(match(listOf(root.resolve("missing.txt").path)))
            val outside = root.resolve("outside.txt")
            outside.writeText(incoming.readText())
            assertNull(match(listOf(outside.path)))
        } finally { root.deleteRecursively() }
    }

    @Test fun collidingArchiveNamesRecoverExactBytesAfterDeletion() {
        val contents = linkedMapOf("a/book.txt" to "first book", "b/book.txt" to "second book")
        val zip = ZipTestFixtures.buildZip(contents.map { (path, data) -> ZipTestFixtures.Spec(path, data.toByteArray()) })
        val reader = RemoteZipCore(RangedSource { offset, length, _ -> zip.copyOfRange(offset.toInt(), offset.toInt() + length) }, "books.zip", zip.size.toLong())
        val root = Files.createTempDirectory("archive-redownload").toFile()
        try {
            val names = archiveBookFileNames(contents.keys.toList())
            for ((entry, name) in names) {
                val file = root.resolve(name)
                file.writeBytes(reader.readDecompressed(reader.ensureMeta().getValue(entry)))
                assertTrue(file.delete())
                // Legacy records have only the generated local name; newer records retain entry identity.
                for (storedEntry in listOf(null, entry)) {
                    val resolved = resolveArchiveBookEntry(reader.ensureMeta().keys.toList(), name, storedEntry)
                    assertEquals(entry, resolved)
                    file.writeBytes(reader.readDecompressed(reader.ensureMeta().getValue(resolved)))
                    assertEquals(contents[entry], file.readText())
                }
            }
            assertEquals("a/book.txt", resolveArchiveBookEntry(listOf("a/book.txt"), names.getValue("a/book.txt"), "a/book.txt"))
            assertThrows(IllegalArgumentException::class.java) { resolveArchiveBookEntry(contents.keys.toList(), "book.txt") }
            assertThrows(IllegalArgumentException::class.java) { resolveArchiveBookEntry(contents.keys.toList(), "book.txt", "missing/book.txt") }
            assertThrows(IllegalArgumentException::class.java) { resolveArchiveBookEntry(listOf("../book.txt"), "book.txt") }
        } finally { reader.close(); root.deleteRecursively() }
    }
}
