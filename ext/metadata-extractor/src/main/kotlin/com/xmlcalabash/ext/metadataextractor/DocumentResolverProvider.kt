package com.xmlcalabash.ext.metadataextractor

import com.xmlcalabash.datamodel.MediaType
import com.xmlcalabash.documents.XProcDocument
import com.xmlcalabash.io.DocumentLoader
import com.xmlcalabash.io.DocumentManager
import com.xmlcalabash.config.XProcStepConfiguration
import com.xmlcalabash.spi.DocumentResolver
import com.xmlcalabash.spi.DocumentResolverProvider
import java.io.IOException
import java.net.URI

class DocumentResolverProvider:  DocumentResolverProvider, DocumentResolver {
    companion object {
        val MAGIC_URI = URI("https://xmlcalabash.com/ext/library/metadata-extractor.xpl")
        var library: XProcDocument? = null
    }

    override fun create(): DocumentResolver {
        return this
    }

    override fun configure(manager: DocumentManager) {
        manager.registerPrefix(MAGIC_URI.toString(), this)
    }

    override fun resolve(context: XProcStepConfiguration, uri: URI, current: XProcDocument?): XProcDocument? {
        if (current != null) {
            return current // WAT?
        }

        if (uri != MAGIC_URI) {
            return null // WAT?
        }

        synchronized(Companion) {
            if (library != null) {
                return library
            }

            val loader = DocumentLoader(context, MAGIC_URI)
            val stream = DocumentResolverProvider::class.java.getResourceAsStream("/com/xmlcalabash/ext/metadata-extractor.xpl")
                ?: throw IOException("Failed to find unique-id.xpl as a resource")
            library = loader.load(MAGIC_URI, stream, MediaType.XML)
            return library
        }
    }
}