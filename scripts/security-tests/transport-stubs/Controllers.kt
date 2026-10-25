package io.legado.app.api.controller
import io.legado.app.api.ReturnData
import io.legado.app.web.api.WebApiResponse
object Calls { var count = 0 }
object BookSourceController {
    fun saveSource(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun saveSources(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun deleteSources(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun getSource(query: Map<String, List<String>>): ReturnData { Calls.count++; return ReturnData() }
    fun sources(): ReturnData { Calls.count++; return ReturnData() }
    fun sourcesPart(): ReturnData { Calls.count++; return ReturnData() }
    fun exploreKinds(query: Map<String, List<String>>): ReturnData { Calls.count++; return ReturnData() }
    fun exploreBooks(query: Map<String, List<String>>): ReturnData { Calls.count++; return ReturnData() }
}
object BookController {
    fun saveBook(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun deleteBook(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun saveBookProgress(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun saveWebReadConfig(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun getBooks(query: Map<String, List<String>>): ReturnData { Calls.count++; return ReturnData() }
    fun groups(): ReturnData { Calls.count++; return ReturnData() }
    fun getChapterList(query: Map<String, List<String>>): ReturnData { Calls.count++; return ReturnData() }
    fun refreshToc(query: Map<String, List<String>>): ReturnData { Calls.count++; return ReturnData() }
    fun getBookContent(query: Map<String, List<String>>): ReturnData { Calls.count++; return ReturnData() }
    fun getCover(query: Map<String, List<String>>): ReturnData { Calls.count++; return ReturnData() }
    fun getImg(query: Map<String, List<String>>): ReturnData { Calls.count++; return ReturnData() }
    fun getWebReadConfig(): ReturnData { Calls.count++; return ReturnData() }
    fun addLocalBook(query: Map<String,List<String>>, files: Map<String,String>): ReturnData { Calls.count++; return ReturnData() }
}
object ReplaceRuleController {
    fun saveRule(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun delete(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun testRule(postData: String?): ReturnData { Calls.count++; return ReturnData() }
    fun allRules(): ReturnData { Calls.count++; return ReturnData() }
}
object BackupController {
    fun restoreBackup(files: Map<String, String>): ReturnData { Calls.count++; return ReturnData() }
    fun getBackupZip(): WebApiResponse { Calls.count++; return WebApiResponse.Json(ReturnData()) }
}
