package io.legado.app.model.fileBook

import io.legado.app.constant.PreferKey
import io.legado.app.help.file.safeBookFilePath
import io.legado.app.help.storage.BackupPreferencePolicy
import io.legado.app.help.http.mergeRequestCookies
import io.legado.app.help.http.mergeRequestCookieHeader
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class PrivacyBoundaryTest {
    @Test fun backupZipAndRestoreExcludeCredentialsAndUnknownPreferences() {
        val all = mapOf("password" to "LOCAL_SECRET", "legado.sourceCookies.v1" to "COOKIE_SECRET",
            PreferKey.webDavPassword to "DAV_SECRET", "futureToken" to "TOKEN_SECRET",
            PreferKey.fontScale to 1.25f, "homeTabs" to "[]")
        val exported = BackupPreferencePolicy.filter(all)
        assertEquals(setOf(PreferKey.fontScale, "homeTabs"), exported.keys)
        val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { stream ->
            stream.putNextEntry(ZipEntry("config.json"))
            stream.write(exported.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }.toByteArray())
            stream.closeEntry()
        } }.toByteArray()
        ZipInputStream(zip.inputStream()).use { stream ->
            assertEquals("config.json", stream.nextEntry.name)
            assertFalse(stream.readBytes().decodeToString().contains("SECRET"))
        }
        // Same allowlist gates legacy config.json / config.xml restore.
        val local = mutableMapOf<String, Any>("password" to "keep-local", "legado.sourceCookies.v1" to "keep-cookie")
        all.filterKeys(BackupPreferencePolicy::allows).forEach { (k, v) -> local[k] = v }
        assertEquals("keep-local", local["password"])
        assertEquals("keep-cookie", local["legado.sourceCookies.v1"])
        assertFalse(local.containsKey(PreferKey.webDavPassword))
    }

    @Test fun remoteNamesCannotEscapeAndNormalFilesCanBeUpdated() {
        val root = Files.createTempDirectory("legado-safe-books").toFile()
        try {
            for (name in listOf("../outside.txt", "/outside.txt", "sub/book.txt", "sub\\book.txt", "..", ".", "x\u0000.txt", "C:book.txt", "NUL.txt", "book.txt.")) {
                assertThrows(name, IllegalArgumentException::class.java) { safeBookFilePath(root.path, name) }
            }
            val file = java.io.File(safeBookFilePath(root.path, "正常书籍.txt"))
            file.writeText("first")
            java.io.File(safeBookFilePath(root.path, file.name)).writeText("updated")
            assertEquals("updated", file.readText())
            val outside = Files.createTempFile("legado-outside", ".txt")
            try {
                Files.writeString(outside, "unchanged")
                Files.createSymbolicLink(root.toPath().resolve("link.txt"), outside)
                assertThrows(IllegalArgumentException::class.java) { safeBookFilePath(root.path, "link.txt") }
                assertEquals("unchanged", Files.readString(outside))
            } finally { Files.delete(outside) }
        } finally { root.deleteRecursively() }
    }

    @Test fun requestCookiesPreservePathOrderAndExplicitOverrides() {
        assertEquals("sid=account; sid=root", mergeRequestCookies("sid=account; sid=root", null))
        assertEquals("sid=temp; other=ok", mergeRequestCookies("sid=account; sid=root; other=ok", "sid=temp"))
        assertEquals("sid=; other=ok", mergeRequestCookies("sid=stored; other=ok", "sid="))
        val headers = mutableMapOf("cOoKiE" to "sid=temp", "Accept" to "text/plain")
        mergeRequestCookieHeader(headers, "sid=account; sid=root; other=ok")
        assertEquals(mapOf("Cookie" to "sid=temp; other=ok", "Accept" to "text/plain"), headers)
    }
}
