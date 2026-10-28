/*
 * Copyright (C) 2020 w568w
 */
package io.legado.app.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.legado.app.web.api.WebApi
import io.legado.app.web.api.WebApiRequest
import kotlinx.coroutines.runBlocking

/**
 * Export book data only to this app or an app signed by the same certificate.
 *
 * 与 web 服务共用同一 [WebApi] 路由层: 每个 RequestCode 映射为等价的 web path，
 * 经 [WebApi.handle] 分派后把 ReturnData 序列化进游标 (路径/参数/JSON 结构零改动)。
 */
class ReaderProvider : ContentProvider() {
    private enum class RequestCode {
        SaveBookSource, SaveBookSources, DeleteBookSources, GetBookSource, GetBookSources,
        SaveRssSource, SaveRssSources, DeleteRssSources, GetRssSource, GetRssSources,
        SaveBook, GetBookshelf, RefreshToc, GetChapterList, GetBookContent, GetBookCover,
        SaveBookProgress
    }

    private val postBodyKey = "json"
    private val sMatcher by lazy {
        UriMatcher(UriMatcher.NO_MATCH).apply {
            "${context?.applicationInfo?.packageName}.readerProvider".also { authority ->
                addURI(authority, "bookSource/insert", RequestCode.SaveBookSource.ordinal)
                addURI(authority, "bookSources/insert", RequestCode.SaveBookSources.ordinal)
                addURI(authority, "bookSources/delete", RequestCode.DeleteBookSources.ordinal)
                addURI(authority, "bookSource/query", RequestCode.GetBookSource.ordinal)
                addURI(authority, "bookSources/query", RequestCode.GetBookSources.ordinal)
                addURI(authority, "rssSource/insert", RequestCode.SaveRssSource.ordinal)
                addURI(authority, "rssSources/insert", RequestCode.SaveRssSources.ordinal)
                addURI(authority, "rssSources/delete", RequestCode.DeleteRssSources.ordinal)
                addURI(authority, "rssSource/query", RequestCode.GetRssSource.ordinal)
                addURI(authority, "rssSources/query", RequestCode.GetRssSources.ordinal)
                addURI(authority, "book/insert", RequestCode.SaveBook.ordinal)
                addURI(authority, "books/query", RequestCode.GetBookshelf.ordinal)
                addURI(authority, "book/refreshToc/query", RequestCode.RefreshToc.ordinal)
                addURI(authority, "book/chapter/query", RequestCode.GetChapterList.ordinal)
                addURI(authority, "book/content/query", RequestCode.GetBookContent.ordinal)
                addURI(authority, "book/cover/query", RequestCode.GetBookCover.ordinal)
            }
        }
    }

    override fun onCreate(): Boolean {
        // 快捷方式构建移到 App.onCreate: ContentProvider.onCreate 早于 Application.onCreate,
        // 此时 Compose Resources 还没拿到 Android context, 取多语言文案会抛 MissingResourceException。
        return false
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        enforceCaller(uri)
        if (sMatcher.match(uri) < 0) return -1
        val path = when (RequestCode.entries[sMatcher.match(uri)]) {
            RequestCode.DeleteBookSources -> "/deleteBookSources"
            else -> throw IllegalStateException(
                "Unexpected value: " + RequestCode.entries[sMatcher.match(uri)].name
            )
        }
        runBlocking {
            WebApi.handle(WebApiRequest(trustedLocalCall = true, method = "POST", path = path, postData = selection))
        }
        return 0
    }

    override fun getType(uri: Uri): String? {
        enforceCaller(uri)
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceCaller(uri)
        if (sMatcher.match(uri) < 0) return null
        val path = when (RequestCode.entries[sMatcher.match(uri)]) {
            RequestCode.SaveBookSource -> "/saveBookSource"
            RequestCode.SaveBookSources -> "/saveBookSources"
            RequestCode.SaveBook -> "/saveBook"
            RequestCode.SaveBookProgress -> "/saveBookProgress"
            else -> throw IllegalStateException(
                "Unexpected value: " + RequestCode.entries[sMatcher.match(uri)].name
            )
        }
        runBlocking {
            values?.let {
                WebApi.handle(
                    WebApiRequest(
                        trustedLocalCall = true,
                        method = "POST",
                        path = path,
                        postData = it.getAsString(postBodyKey)
                    )
                )
            }
        }
        return null
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        enforceCaller(uri)
        val map: MutableMap<String, ArrayList<String>> = HashMap()
        uri.getQueryParameter("url")?.let {
            map["url"] = arrayListOf(it)
        }
        uri.getQueryParameter("index")?.let {
            map["index"] = arrayListOf(it)
        }
        uri.getQueryParameter("path")?.let {
            map["path"] = arrayListOf(it)
        }
        if (sMatcher.match(uri) < 0) return null
        val (path, query) = when (RequestCode.entries[sMatcher.match(uri)]) {
            RequestCode.GetBookSource -> "/getBookSource" to map
            RequestCode.GetBookSources -> "/getBookSources" to map
            RequestCode.GetBookshelf -> "/getBookshelf" to emptyMap<String, List<String>>()
            RequestCode.GetBookContent -> "/getBookContent" to map
            RequestCode.RefreshToc -> "/refreshToc" to map
            RequestCode.GetChapterList -> "/getChapterList" to map
            RequestCode.GetBookCover -> "/cover" to map
            else -> throw IllegalStateException(
                "Unexpected value: " + RequestCode.entries[sMatcher.match(uri)].name
            )
        }
        val returnData = runBlocking {
            WebApi.handle(WebApiRequest(trustedLocalCall = true, method = "GET", path = path, query = query)).returnData
        }
        return SimpleCursor(returnData)
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        enforceCaller(uri)
        throw UnsupportedOperationException("Not yet implemented")
    }

    private fun enforceCaller(uri: Uri) {
        val caller = Binder.getCallingUid()
        val owner = Process.myUid()
        if (caller != owner && context?.packageManager?.checkSignatures(caller, owner) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("ReaderProvider requires the app signing certificate")
        }
        // RSS was migrated to typed BookSource records. Legacy RSS payloads must never reach
        // the unrestricted book-source CRUD routes (which could overwrite/delete unrelated sources).
        when (sMatcher.match(uri)) {
            RequestCode.SaveRssSource.ordinal, RequestCode.SaveRssSources.ordinal,
            RequestCode.DeleteRssSources.ordinal, RequestCode.GetRssSource.ordinal,
            RequestCode.GetRssSources.ordinal -> throw UnsupportedOperationException(
                "Legacy RSS provider API is unsupported; use the typed book-source API"
            )
        }
    }


    /**
     * Simple inner class to deliver json callback data.
     *
     * Only getString() makes sense.
     */
    private class SimpleCursor(data: ReturnData?) : MatrixCursor(arrayOf("result"), 1) {

        // Gson().toJson(data) → ReturnData.toJsonString()
        private val mData: String = data?.toJsonString() ?: ""

        init {
            addRow(arrayOf(mData))
        }

    }
}
