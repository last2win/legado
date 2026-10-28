package android.content
import android.net.Uri
import android.database.Cursor
import android.content.pm.PackageManager
class Context {
    val applicationInfo = ApplicationInfo()
    val packageManager = PackageManager()
}
class ApplicationInfo { val packageName = "io.legado.test" }
class ContentValues { fun getAsString(key: String) = "[]" }
class UriMatcher(ignored: Int) {
    private val routes = mutableMapOf<String, Int>()
    fun addURI(authority: String, path: String, value: Int) { routes["content://$authority/$path"] = value }
    fun match(uri: Uri) = routes[uri.value.substringBefore('?')] ?: NO_MATCH
    companion object { const val NO_MATCH = -1 }
}
abstract class ContentProvider {
    val context: Context? = Context()
    abstract fun onCreate(): Boolean
    abstract fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int
    abstract fun getType(uri: Uri): String?
    abstract fun insert(uri: Uri, values: ContentValues?): Uri?
    abstract fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor?
    abstract fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int
}
