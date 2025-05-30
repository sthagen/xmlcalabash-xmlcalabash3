package com.xmlcalabash.util.spi

import com.xmlcalabash.config.StepConfiguration
import com.xmlcalabash.documents.XProcDocument
import com.xmlcalabash.io.MediaType
import com.xmlcalabash.runtime.XProcStepConfiguration
import com.xmlcalabash.spi.ContentTypeConverter
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.XdmValue

class StandardContentTypeConverter(): ContentTypeConverter {
    override fun canConvert(from: MediaType, to: MediaType): Boolean {
        return false
    }

    override fun convert(
        stepConfig: StepConfiguration,
        doc: XProcDocument,
        convertTo: MediaType,
        serialization: Map<QName, XdmValue>
    ): XProcDocument {
        throw IllegalArgumentException("The NopContentTypeConvert doesn't provide any conversions")
    }
}