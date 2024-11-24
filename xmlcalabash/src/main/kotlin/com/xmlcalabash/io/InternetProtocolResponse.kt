package com.xmlcalabash.io

import com.xmlcalabash.datamodel.MediaType
import com.xmlcalabash.documents.XProcDocument
import net.sf.saxon.s9api.XdmAtomicValue
import net.sf.saxon.s9api.XdmMap
import org.apache.hc.client5.http.cookie.BasicCookieStore
import org.apache.hc.client5.http.cookie.CookieStore
import java.net.URI

class InternetProtocolResponse(val responseUri: URI, val statusCode: Int) {
    var report: XdmMap? = null
    private val _headers = mutableMapOf<String, XdmAtomicValue>()
    var mediaType: MediaType? = null
    private val _response = mutableListOf<XProcDocument>()
    private var _cookieStore: CookieStore? = null

    var cookieStore: CookieStore?
        get() = _cookieStore
        set(value) {
            _cookieStore = BasicCookieStore()
            for (cookie in value!!.cookies) {
                _cookieStore!!.addCookie(cookie)
            }
        }


    var headers: Map<String, XdmAtomicValue>
        get() = _headers
        set(value) {
            _headers.clear()
            _headers.putAll(value)
        }

    fun addResponse(response: XProcDocument) {
        _response += response
    }

    val empty: Boolean
        get() = _response.isEmpty()

    val singlepart: Boolean
        get() = !multipart

    val multipart: Boolean
        get() = _response.size > 1 || (mediaType != null && mediaType!!.mediaType == "multipart")

    val response: List<XProcDocument> = _response
}