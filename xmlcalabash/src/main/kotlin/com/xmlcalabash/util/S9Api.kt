package com.xmlcalabash.util

import com.xmlcalabash.config.StepConfiguration
import com.xmlcalabash.io.MediaType
import com.xmlcalabash.documents.DocumentProperties
import com.xmlcalabash.documents.XProcDocument
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.io.DocumentWriter
import com.xmlcalabash.namespace.Ns
import com.xmlcalabash.namespace.NsXml
import com.xmlcalabash.runtime.XProcStepConfiguration
import net.sf.saxon.om.AttributeInfo
import net.sf.saxon.om.AttributeMap
import net.sf.saxon.om.EmptyAttributeMap
import net.sf.saxon.om.FingerprintedQName
import net.sf.saxon.om.NameOfNode
import net.sf.saxon.s9api.*
import net.sf.saxon.serialize.SerializationProperties
import net.sf.saxon.str.UnicodeBuilder
import net.sf.saxon.str.UnicodeString
import net.sf.saxon.type.BuiltInAtomicType
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import javax.management.Query.attr

class S9Api {
    companion object {
        fun documentElement(node: XdmNode): XdmNode {
            var root: XdmNode? = null
            for (child in node.axisIterator(Axis.CHILD)) {
                if (child.nodeKind == XdmNodeKind.ELEMENT) {
                    if (root != null) {
                        throw IllegalArgumentException("XML document has multiple top-level elements")
                    }
                    root = child
                }
            }

            if (root == null) {
                throw IllegalArgumentException("XML document has no document element")
            }

            return root
        }

        fun firstElement(document: XdmNode): XdmNode? {
            when (document.nodeKind) {
                XdmNodeKind.ELEMENT -> return document
                XdmNodeKind.DOCUMENT -> {
                    for (node in document.axisIterator(Axis.CHILD)) {
                        if (node.nodeKind == XdmNodeKind.ELEMENT) {
                            return node
                        }
                    }
                    return null
                }
                else -> return null
            }
        }

        fun uniquePrefix(prefixes: Set<String>): String {
            var count = 0;
            var prefix = "_${++count}"
            while (prefixes.contains(prefix)) {
                prefix = "_${++count}"
            }
            return prefix
        }

        fun isTextDocument(doc: XdmNode): Boolean {
            if (doc.nodeKind == XdmNodeKind.DOCUMENT) {
                for (child in doc.axisIterator(Axis.CHILD)) {
                    if (child.nodeKind != XdmNodeKind.TEXT) {
                        return false
                    }
                }
                return true
            }
            return doc.nodeKind == XdmNodeKind.TEXT
        }

        fun textContent(doc: XProcDocument): String {
            if (doc.value is XdmNode) {
                val stream = ByteArrayOutputStream()
                val serializer = (doc.value as XdmNode).processor.newSerializer(stream)
                serializer.setOutputProperty(Serializer.Property.METHOD, "text")
                serializer.serialize((doc.value as XdmNode).asSource())
                return stream.toString(StandardCharsets.UTF_8)
            } else {
                return doc.value.toString()
            }
        }

        fun valuesToString(values: XdmValue): String {
            val sb = StringBuilder()
            var sep = ""
            for (pos in 1..values.size()) {
                sb.append(sep)
                sb.append(values.itemAt(pos - 1).stringValue)
                sep = " "
            }
            return sb.toString()
        }

        fun makeUnicodeString(text: String): UnicodeString {
            val buf = UnicodeBuilder()
            buf.append(text)
            return buf.toUnicodeString()
        }

        fun adjustBaseUri(xml: XdmNode, baseUri: XdmValue?): XdmNode {
            if (baseUri == null) {
                return xml
            }
            try {
                return adjustBaseUri(xml, URI(baseUri.toString()))
            } catch (ex: URISyntaxException) {
                throw XProcError.xdInvalidUri(baseUri.toString()).exception(ex)
            }
        }

        fun adjustBaseUri(xml: XdmNode, baseUri: URI?, setXmlBase: Boolean? = false): XdmNode {
            if (baseUri == null) {
                return xml
            }

            // If we're passed a document, check the base URI of the root element.
            // (Constructing a document with the correct base URI is pointless
            // if the root element has a different, incorrect base URI.)
            val checkNode = if (xml.nodeKind == XdmNodeKind.DOCUMENT) {
                firstElement(xml) ?: xml
            } else {
                xml
            }

            val dbase = checkNode.baseURI
            if (dbase == null || baseUri.toString() != dbase.toString() || setXmlBase == true) {
                val builder = SaxonTreeBuilder(xml.processor)
                builder.startDocument(baseUri)
                adjustBaseUri(xml, baseUri, builder, setXmlBase == true)
                builder.endDocument()
                return builder.result
            }

            return xml
        }

        private fun adjustBaseUri(xml: XdmNode, baseUri: URI, builder: SaxonTreeBuilder, setXmlBase: Boolean) {
            when (xml.nodeKind) {
                XdmNodeKind.DOCUMENT -> {
                    for (child in xml.axisIterator(Axis.CHILD)) {
                        adjustBaseUri(child, baseUri, builder, setXmlBase)
                    }
                }
                XdmNodeKind.ELEMENT -> {
                    val xmlBase = xml.getAttributeValue(NsXml.base)
                    val adjBaseUri = UriUtils.resolve(baseUri, xmlBase)!!

                    if (setXmlBase && xml.getAttributeValue(NsXml.base) == null) {
                        var attributes: AttributeMap = EmptyAttributeMap.getInstance()
                        val allAttributes = xml.underlyingNode.attributes()
                        for (child in xml.axisIterator(Axis.ATTRIBUTE)) {
                            val name = NameOfNode.makeName(child.underlyingNode)
                            val attr = allAttributes.get(name)
                            attributes = attributes.put(attr)
                        }
                        val fqBase = FingerprintedQName("xml", NsXml.namespace, "base")
                        val baseAi = AttributeInfo(fqBase, BuiltInAtomicType.ANY_URI,
                            adjBaseUri.toString(), xml.underlyingNode.saveLocation(), 0)
                        attributes = attributes.put(baseAi)
                        builder.addStartElement(xml.nodeName, attributes, adjBaseUri)
                    } else {
                        builder.addStartElement(xml, adjBaseUri)
                    }

                    for (child in xml.axisIterator(Axis.CHILD)) {
                        adjustBaseUri(child, baseUri, builder, false)
                    }
                    builder.addEndElement()
                }
                XdmNodeKind.PROCESSING_INSTRUCTION -> {
                    builder.addPI(xml.nodeName.localName, xml.stringValue, baseUri.toString())
                }
                XdmNodeKind.TEXT -> {
                    builder.addText(xml.stringValue)
                }
                else -> builder.addSubtree(xml)
            }
        }

        fun makeDocuments(stepConfig: StepConfiguration, result: XdmValue): List<XProcDocument> {
            val selections = mutableListOf<XProcDocument>()
            for (value in result.iterator()) {
                when (value) {
                    is XdmMap, is XdmArray -> {
                        selections.add(XProcDocument.ofJson(value, stepConfig))
                    }
                    is XdmNode -> {
                        if (value.nodeKind == XdmNodeKind.ATTRIBUTE) {
                            throw XProcError.xdInvalidSelection(value.nodeName).exception()
                        }

                        // Special case for a document element with a relative base URI. Make sure that the base
                        // URI that will get computed for the document element will be correct.
                        val baseUri = if (value.getAttributeValue(NsXml.base) != null && !URI(value.getAttributeValue(NsXml.base)).isAbsolute) {
                            value.parent.baseURI
                        } else {
                            value.baseURI
                        }

                        val props = DocumentProperties()
                        if (baseUri != null) {
                            props[Ns.baseUri] = baseUri
                        }

                        val builder = SaxonTreeBuilder(stepConfig.processor)
                        builder.startDocument(baseUri)
                        builder.addSubtree(value)
                        builder.endDocument()

                        when (value.nodeKind) {
                            XdmNodeKind.TEXT -> {
                                selections.add(XProcDocument.ofText(builder.result, stepConfig, MediaType.TEXT, props))
                            }
                            XdmNodeKind.ELEMENT, XdmNodeKind.DOCUMENT, XdmNodeKind.COMMENT, XdmNodeKind.PROCESSING_INSTRUCTION -> {
                                selections.add(XProcDocument.ofXml(builder.result, stepConfig, MediaType.XML, props))
                            }
                            else -> {
                                throw XProcError.xdInvalidSelection(value.nodeName).exception()
                            }
                        }
                    }
                    is XdmFunctionItem -> {
                        throw XProcError.xdInvalidFunctionSelection().exception()
                    }
                    is XdmAtomicValue -> {
                        selections.add(XProcDocument.ofJson(value, stepConfig))
                    }
                    else -> {
                        selections.add(XProcDocument(value, stepConfig))
                    }
                }
            }

            return selections
        }

        // OMG! This is such a hack!
        fun xdmToInputSource(stepConfig: XProcStepConfiguration, doc: XProcDocument): InputSource {
            val out = ByteArrayOutputStream()
            DocumentWriter(doc, out).write()
            val source = InputSource(ByteArrayInputStream(out.toByteArray()))
            if (doc.baseURI != null) {
                source.systemId = doc.baseURI.toString()
            }
            return source
        }

        fun xdmToInputSource(stepConfig: XProcStepConfiguration, node: XdmNode): InputSource {
            val doc = XProcDocument.ofXml(node, stepConfig)
            return xdmToInputSource(stepConfig ,doc)
        }

        fun serializationPropertyMap(props: SerializationProperties): Map<QName, XdmValue> {
            val map = mutableMapOf<QName, XdmValue>()
            for ((key, value) in props.properties) {
                map.put(ValueUtils.parseClarkName(key as String), XdmAtomicValue(value as String))
            }
            return map
        }
    }
}