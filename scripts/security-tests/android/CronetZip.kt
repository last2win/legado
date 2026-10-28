package io.legado.app.model.fileBook
class Entry(val name: String)
class RemoteZipWrapper(source: (Long, Int, Long) -> ByteArray, name: String, size: Long) {
    fun entries(): List<Entry> = error("Unexpected network archive")
    fun getInputStream(entry: Entry): java.io.InputStream = error("Unexpected network archive")
    fun close() {}
}
