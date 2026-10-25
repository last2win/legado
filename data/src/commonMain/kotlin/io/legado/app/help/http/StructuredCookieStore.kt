package io.legado.app.help.http

/** Providers implementing this interface preserve attributes through response storage. */
interface StructuredCookieStore {
    fun saveResponseCookies(sourceKey: String?, response: KmpResponse)
}
