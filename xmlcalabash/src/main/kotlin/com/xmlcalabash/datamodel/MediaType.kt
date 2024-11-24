package com.xmlcalabash.datamodel

import com.xmlcalabash.exceptions.XProcError
import net.sf.saxon.s9api.XdmAtomicValue
import java.util.*
import java.util.regex.Pattern

class MediaType private constructor(val mediaType: String, val mediaSubtype: String,
                                    val suffix: String? = null, val inclusive: Boolean = true,
                                    val parameters: List<MediaTypeParameter> = listOf()) {
    companion object {
        val ANY = MediaType("*", "*")
        val OCTET_STREAM = MediaType("application", "octet-stream")
        val TEXT = MediaType("text", "plain")
        val XML  = MediaType("application", "xml")
        val JSON = MediaType("application", "json")
        val YAML = MediaType("application", "vnd.yaml")
        val HTML = MediaType("text", "html")
        val XHTML = MediaType("application", "xhtml+xml")
        val ZIP = MediaType("application", "zip")
        val PDF = MediaType("application", "pdf")
        val MULTIPART = MediaType("multipart", "*")
        val MULTIPART_MIXED = MediaType("multipart", "mixed")

        val XML_OR_HTML = listOf<MediaType>(
            MediaType("application", "xml"),
            MediaType("text", "xml"),
            MediaType("*", "*", "xml")
        )

        val MATCH_XML = listOf<MediaType>(
            MediaType("application", "xml"),
            MediaType("text", "xml"),
            MediaType("*", "*", "xml"),
            MediaType("application", "xhtml", "xml", inclusive = false)
        )

        val MATCH_NOT_XML = listOf<MediaType>(
            MediaType("application", "xml", inclusive = false),
            MediaType("text", "xml", inclusive = false),
            MediaType("*", "*", "xml", inclusive = false)
        )

        val MATCH_HTML = listOf<MediaType>(
            MediaType("text", "html"),
            MediaType("application", "xhtml", "xml")
        )

        val MATCH_NOT_HTML = listOf<MediaType>(
            MediaType("text", "html", inclusive = false),
            MediaType("application", "xhtml", "xml", inclusive = false),
        )

        val MATCH_TEXT = listOf<MediaType>(
            MediaType("text", "*"),
            MediaType("application", "relax-ng-compact-syntax"),
            MediaType("application", "xquery"),
            MediaType("application", "javascript"),
            MediaType("text", "html", inclusive = false),
            MediaType("text", "xml", inclusive = false)
        )

        val MATCH_NOT_TEXT = listOf<MediaType>(
            MediaType("text", "*", inclusive = false),
            MediaType("application", "relax-ng-compact-syntax", inclusive = false),
            MediaType("application", "xquery", inclusive = false),
            MediaType("application", "javascript", inclusive = false),
        )

        val MATCH_JSON = listOf<MediaType>(
            MediaType("application", "json"),
            MediaType("*", "*", "json")
        )

        val MATCH_NOT_JSON = listOf<MediaType>(
            MediaType("application", "json", inclusive = false)
        )

        val MATCH_YAML = listOf<MediaType>(
            MediaType("application", "vnd.yaml")
        )

        val MATCH_ANY = listOf<MediaType>(
            MediaType("*", "*")
        )

        val MATCH_NOT_ANY = listOf<MediaType>(
            MediaType("*", "*", inclusive = false)
        )

        fun parse(mtype: String?): MediaType? {
            if (mtype == null) {
                return null
            } else {
                return parse(mtype)
            }
        }

        fun parse(mtype: String, forceEncoding: String? = null): MediaType {
            var ctype = parseMatch(mtype, forceEncoding)
            if (!"[A-Za-z0-9][-A-Za-z0-9!#\$&0^_\\\\.+]*".toRegex().matches(ctype.mediaType) || ctype.mediaType.length > 127) {
                throw XProcError.xdInvalidContentType(mtype).exception()
            }
            if (!"[A-Za-z0-9][-A-Za-z0-9!#\$&0^_\\\\.+]*".toRegex().matches(ctype.mediaSubtype) || ctype.mediaSubtype.length > 127) {
                throw XProcError.xdInvalidContentType(mtype).exception()
            }
            return ctype
        }

        fun parseMatch(mtype: String, forceEncoding: String? = null): MediaType {
            // [-]type/subtype+ext; name1=val1; name2=val2
            var pos = mtype.indexOf("/")
            if (pos < 0) {
                throw XProcError.xdInvalidContentType(mtype).exception()
            }

            var mediaType = mtype.substring(0, pos).trim()
            var rest = mtype.substring(pos + 1).trim()
            var inclusive = true
            if (mediaType.startsWith("-")) {
                inclusive = false
                mediaType = mediaType.substring(1)
            }

            // This is a bit convoluted because of the way 'rest' is reused.
            // There was a bug and this was the easiest fix. #hack
            var params = ""
            pos = rest.indexOf(";")
            if (pos >= 0) {
                params = rest.substring(pos+1)
                rest = rest.substring(0, pos).trim()
            }

            var mediaSubtype = rest
            var suffix: String? = null

            if (mediaSubtype.contains("+")) {
                pos = mediaSubtype.indexOf("+")
                suffix = mediaSubtype.substring(pos+1).trim()
                mediaSubtype = mediaSubtype.substring(0, pos).trim()
            }

            if (forceEncoding == null && params == "") {
                return MediaType(mediaType, mediaSubtype, suffix, inclusive = inclusive)
            }

            rest = params
            pos = rest.indexOf(";")
            val plist = mutableListOf<MediaTypeParameter>()
            if (forceEncoding != null) {
                plist.add(MediaTypeParameter("charset=" + forceEncoding))
            }

            while (pos >= 0) {
                val param = rest.substring(0, pos).trim()
                rest = rest.substring(pos+1)
                if (param != "") {
                    if (forceEncoding == null || !param.startsWith("charset=")) {
                        plist.add(MediaTypeParameter(param))
                    }
                }
                pos = rest.indexOf(";")
            }

            if (rest.isNotBlank()) {
                rest = rest.trim()
                if (forceEncoding == null || !rest.startsWith("charset=")) {
                    plist.add(MediaTypeParameter(rest))
                }
            }

            return MediaType(mediaType, mediaSubtype, suffix, inclusive, plist.toList())
        }

        fun parseList(ctypes: String): List<MediaType> {
            val mlist = mutableListOf<MediaType>()
            for (ctype in ctypes.split("\\s+".toRegex())) {
                val excl = ctype[0] == '-'
                val type = if (excl) {
                    ctype.substring(1)
                } else {
                    ctype
                }

                when (type) {
                    "xml" -> if (excl) mlist.addAll(MATCH_NOT_XML) else mlist.addAll(MATCH_XML)
                    "html" -> if (excl) mlist.addAll(MATCH_NOT_HTML) else mlist.addAll(MATCH_HTML)
                    "text" -> if (excl) mlist.addAll(MATCH_NOT_TEXT) else mlist.addAll(MATCH_TEXT)
                    "json" -> if (excl) mlist.addAll(MATCH_NOT_JSON) else mlist.addAll(MATCH_JSON)
                    "any" -> if (excl) mlist.addAll(MATCH_NOT_ANY) else mlist.addAll(MATCH_ANY)
                    else -> {
                        if (ctype.contains("/")) {
                            mlist.add(parse(ctype))
                        } else {
                            throw XProcError.xsInvalidContentType(ctype).exception()
                        }
                    }
                }
            }
            return mlist
        }
    }

    fun discardParameters(): MediaType {
        if (parameters.isEmpty()) {
            return this
        }
        return MediaType(mediaType, mediaSubtype, suffix, inclusive)
    }

    fun discardParameters(paramNames: List<String>): MediaType {
        var discarded = false
        val newParams = mutableListOf<MediaTypeParameter>()
        for (param in parameters) {
            val name = param.name
            if (paramNames.contains(name)) {
                discarded = true
            } else {
                newParams.add(param)
            }
        }
        if (!discarded) {
            return this
        }
        return MediaType(mediaType, mediaSubtype, suffix, inclusive, newParams.toList())
    }

    fun addParam(name: String, value: String): MediaType {
        val newParams = mutableListOf<MediaTypeParameter>()
        for (param in parameters) {
            if (param.name != name) {
                newParams.add(param)
            }
        }
        newParams.add(MediaTypeParameter(name, value))
        return MediaType(mediaType, mediaSubtype, suffix, inclusive, newParams.toList())
    }

    fun matchingMediaType(mtypes: List<MediaType>): MediaType? {
        var match: MediaType? = null

        // Go through the whole list in case there are exclusions as well as inclusions
        for (mtype in mtypes) {
            if (matches(mtype)) {
                match = mtype
            }
        }

        return match
    }

    fun matches(mtype: MediaType): Boolean  {
        if (mtype.mediaType == "application" && mtype.mediaSubtype == "octet-stream") {
            return true
        }

        // application/xml should match */*
        // but text/plain shouldn't match */*+xml
/*
        // I wonder how many special cases there really are...can this be generalized somehow?
        if (testMatching(mtype, "xml", "xml")) {
            return true
        }
        if (testMatching(mtype, "json", "json")) {
            return true
        }
*/
        var mmatch = mediaType == mtype.mediaType || mtype.mediaType == "*"
        mmatch = mmatch && (mediaSubtype == mtype.mediaSubtype || mtype.mediaSubtype == "*")
        mmatch = mmatch && (mtype.suffix == null || suffix == mtype.suffix)
        return mmatch
    }

    private fun testMatching(mtype: MediaType, hassubtype: String, hassuffix: String): Boolean {
        if ((mediaType == "text" || mediaType == "application") && (mediaSubtype == hassubtype || suffix == hassuffix)) {
            if ((mtype.mediaType == "text" || mtype.mediaType == "application")
                && (mtype.mediaSubtype == hassubtype || mtype.suffix == hassuffix)) {
                return true
            }
        }
        return false
    }

    fun textContentType(): Boolean {
        val mtype = matchingMediaType(MATCH_TEXT)
        return mtype != null && mtype.inclusive
    }

    fun xmlContentType(): Boolean {
        val mtype = matchingMediaType(MATCH_XML)
        return mtype != null && mtype.inclusive
    }

    fun jsonContentType(): Boolean {
        val mtype = matchingMediaType(MATCH_JSON)
        return mtype != null && mtype.inclusive
    }

    fun yamlContentType(): Boolean {
        val mtype = matchingMediaType(MATCH_YAML)
        return mtype != null && mtype.inclusive
    }

    fun htmlContentType(): Boolean {
        val mtype = matchingMediaType(MATCH_HTML)
        return mtype != null && mtype.inclusive
    }

    fun anyContentType(): Boolean {
        return true
    }

    fun markupContentType(): Boolean {
        return xmlContentType() || htmlContentType()
    }

    fun classification(): MediaType {
        return if (xmlContentType()) {
            XML
        } else if (htmlContentType()) {
            HTML
        } else if (textContentType()) {
            TEXT
        } else if (jsonContentType()) {
            JSON
        } else {
            OCTET_STREAM
        }
    }

    fun allowed(types: List<MediaType>): Boolean {
        // This media type is allowed if it's allowed by at least one member of types
        // and is not forbidden by the last member
        var allowed = false
        for (ctype in types) {
            if (matches(ctype)) {
                allowed = ctype.inclusive
            }
        }

        return allowed
    }

    fun paramValue(name: String): String? {
        for (param in parameters) {
            if (param.name == name) {
                val value = param.value
                if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                    return value.substring(1, value.length - 1)
                }
                return value
            }
        }
        return null
    }

    fun charset(): String? {
        return paramValue("charset")
    }

    fun assertValid(): MediaType {
        if (assertValidName(mediaType) && assertValidName(mediaSubtype)) {
            return this
        }
        throw RuntimeException("Unrecognized content type: ${mediaType}/${mediaSubtype}")
    }

    private fun assertValidName(literal: String): Boolean {
        val name = literal.lowercase(Locale.getDefault())
        val lengthOk = name.isNotEmpty() && name.length <= 127
        val startOk = lengthOk && name.substring(0,1).matches("^[a-z0-9]".toRegex())
        val contentOk = startOk && name.matches("[a-z0-9${Pattern.quote("!#$&-^_.+")}]+$$".toRegex())
        return contentOk
    }

    override fun equals(other: Any?): Boolean {
        return when (other) {
            is MediaType -> return mediaType == other.mediaType && mediaSubtype == other.mediaSubtype
            else -> false
        }
    }

    override fun hashCode(): Int {
        return (41 * mediaType.hashCode()) + mediaSubtype.hashCode()
    }

    fun toAtomicValue(): XdmAtomicValue {
        return XdmAtomicValue(toString())
    }

    fun toStringWithoutParameters(): String {
        if (suffix == null) {
            return "${mediaType}/${mediaSubtype}"
        }
        return "${mediaType}/${mediaSubtype}+${suffix}"
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(toStringWithoutParameters())

        for (param in parameters) {
            // Space after ; for compatibility with Morgana XProc results
            sb.append("; ").append(param)
        }

        return sb.toString()
    }
}