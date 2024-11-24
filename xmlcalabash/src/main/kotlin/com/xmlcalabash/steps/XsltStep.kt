package com.xmlcalabash.steps

import com.xmlcalabash.datamodel.MediaType
import com.xmlcalabash.documents.DocumentProperties
import com.xmlcalabash.documents.XProcDocument
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.namespace.Ns
import com.xmlcalabash.namespace.NsFn
import com.xmlcalabash.util.*
import net.sf.saxon.Configuration
import net.sf.saxon.event.PipelineConfiguration
import net.sf.saxon.event.Receiver
import net.sf.saxon.expr.XPathContext
import net.sf.saxon.functions.ResolveURI
import net.sf.saxon.lib.ResultDocumentResolver
import net.sf.saxon.lib.SaxonOutputKeys
import net.sf.saxon.om.NodeInfo
import net.sf.saxon.s9api.*
import net.sf.saxon.serialize.SerializationProperties
import net.sf.saxon.trans.XPathException
import net.sf.saxon.tree.wrapper.RebasedDocument
import org.apache.logging.log4j.kotlin.logger
import java.net.URI

open class XsltStep(): AbstractAtomicStep() {
    companion object {
        val BUILD_TREE: QName = ValueUtils.parseClarkName(SaxonOutputKeys.BUILD_TREE)
    }

    val sources = mutableListOf<XProcDocument>()
    lateinit var stylesheet: XProcDocument

    val parameters = mutableMapOf<QName,XdmValue>()
    val staticParameters = mutableMapOf<QName,XdmValue>()
    var globalContextItem: XProcDocument? = null
    var populateDefaultCollection = false
    var initialMode: QName? = null
    var templateName: QName? = null
    var outputBaseUri: URI? = null
    var version: String? = null

    var goesBang: XProcError? = null
    var terminationError: XProcError? = null

    private var primaryDestination: Destination? = null
    private var primaryOutputProperties = mutableMapOf<QName, XdmValue>()

    override fun input(port: String, doc: XProcDocument) {
        if (port == "source") {
            sources.add(doc)
        } else {
            stylesheet = doc
        }
    }

    override fun run() {
        super.run()

        parameters.putAll(qnameMapBinding(Ns.parameters))
        staticParameters.putAll(qnameMapBinding(Ns.staticParameters))
        populateDefaultCollection = booleanBinding(Ns.populateDefaultCollection) ?: false
        initialMode = qnameBinding(Ns.initialMode)
        templateName = qnameBinding(Ns.templateName)
        outputBaseUri = uriBinding(Ns.outputBaseUri)
        version = stringBinding(Ns.version)

        val gcValue = options[Ns.globalContextItem]!!.value
        if (gcValue != XdmEmptySequence.getInstance()) {
            globalContextItem = XProcDocument.ofValue(gcValue,
                options[Ns.globalContextItem]!!.context, MediaType.OCTET_STREAM, DocumentProperties())
        }

        if (version == null) {
            val root = S9Api.firstElement(stylesheet.value as XdmNode)
            version = root?.getAttributeValue(Ns.version)
        }

        when (version) {
            "3.0" -> xslt30()
            "2.0" -> xslt20()
            "1.0" -> xslt10()
            else -> throw XProcError.xcVersionNotAvailable(version ?: "null").exception()
        }
    }

    private fun xslt30() {
        if (globalContextItem == null && sources.size == 1) {
            globalContextItem = sources[0]
        }
        runXsltProcessor(sources.firstOrNull())
    }

    private fun xslt20() {
        if (globalContextItem != null) {
            logger.info { "Global context item doesn't apply to XSLT 2.0"}
            globalContextItem = null
        }

        for (doc in sources) {
            if (doc.contentType == null) {
                throw XProcError.xcXsltInputNot20Compatible().exception()
            }
            if (!doc.contentType!!.textContentType() && !doc.contentType!!.htmlContentType() && !doc.contentType!!.xmlContentType()) {
                throw XProcError.xcXsltInputNot20Compatible(doc.contentType!!).exception()
            }
        }

        for ((name, value) in parameters) {
            when (value) {
                is XdmAtomicValue, is XdmNode -> Unit
                is XdmMap -> throw XProcError.xcXsltParameterNot20Compatible(name, "map").exception()
                is XdmArray -> throw XProcError.xcXsltParameterNot20Compatible(name, "array").exception()
                is XdmFunctionItem -> throw XProcError.xcXsltParameterNot20Compatible(name, "function").exception()
                else -> {
                    logger.debug { "Unexpected parameter type: ${value} passed to p:xslt (2.0)"}
                }
            }
        }

        runXsltProcessor(sources.firstOrNull())
    }

    private fun runXsltProcessor(document: XProcDocument?) {
        val processor = stepConfig.processor
        val config = processor.underlyingConfiguration

        val collectionFinder = config.collectionFinder
        val unparsedTextURIResolver = config.unparsedTextURIResolver

        val errorReporter = ErrorLogger()
        val compiler = processor.newXsltCompiler()
        compiler.resourceResolver = stepConfig.documentManager
        compiler.setSchemaAware(processor.isSchemaAware)
        compiler.setErrorReporter(errorReporter)

        val exec = try {
            compiler.compile((stylesheet.value as XdmNode).asSource())
        } catch (sae: Exception) {
            // Compile time exceptions are caught
            if (goesBang != null) {
                throw goesBang!!.exception()
            }

            // Runtime ones are not
            var cause: QName? = null;
            if (sae.cause != null && sae.cause is XPathException) {
                val sname = (sae.cause as XPathException).errorCodeQName
                if (sname != null) {
                    cause = QName(sname)
                }
            }

            when (cause) {
                null -> Unit
                NsFn.errXTMM9000 -> throw XProcError.xcXsltUserTermination(sae.message!!).exception()
                NsFn.errXTDE0040 -> throw XProcError.xcXsltNoTemplate(templateName!!).exception()
                else -> throw XProcError.xcXsltRuntimeError(sae.message!!).exception()
            }

            throw XProcError.xcXsltCompileError(sae.message!!, sae).exception()
        }

        val transformer = exec.load30()
        transformer.resourceResolver = stepConfig.documentManager
        transformer.setResultDocumentHandler(DocumentHandler())
        transformer.setStylesheetParameters(parameters)

        if (populateDefaultCollection) {
            transformer.underlyingController.setDefaultCollection(XProcCollectionFinder.DEFAULT)
            val docs = mutableListOf<XProcDocument>()
            for (doc in sources) {
                if (doc.value is XdmNode) {
                    docs.add(doc)
                }
            }
            transformer.underlyingController.setCollectionFinder(XProcCollectionFinder(docs, collectionFinder))
        }

        val inputSelection = if (document != null) {
            var sel: XdmValue = XdmEmptySequence.getInstance()
            for (doc in sources) {
                sel = sel.append(doc.value)
            }
            sel
        } else {
            XdmEmptySequence.getInstance()
        }

        transformer.setMessageHandler { message ->
            if (message.isTerminate) {
                terminationError = XProcError.xcXsltUserTermination(message.content.stringValue)
                    .at(stepParams.location).at(message.location)
            }
            println(message)
        }

        val documentResolver = MyResultDocumentResolver(processor.underlyingConfiguration)
        transformer.underlyingController.setResultDocumentResolver(documentResolver)

        primaryOutputProperties.putAll(S9Api.serializationPropertyMap(transformer.underlyingController.executable.primarySerializationProperties))
        var buildTree = false
        if (primaryOutputProperties.contains(BUILD_TREE)) {
            buildTree = ValueUtils.isTrue(primaryOutputProperties.get(BUILD_TREE))
        } else {
            val method = primaryOutputProperties.get(Ns.method)
            if (method != null) {
                buildTree = listOf("xml", "html", "xhtml", "text").contains(method.toString())
            } else {
                buildTree = true
            }
        }
        primaryDestination = if (buildTree) {
            XdmDestination()
        } else {
            RawDestination()
        }

        if (initialMode != null) {
            try {
                transformer.setInitialMode(initialMode!!)
            } catch (sae: SaxonApiException) {
                throw XProcError.xcXsltNoMode(initialMode!!, sae.message!!).exception()
            }
        }

        if (outputBaseUri != null) {
            if (stepConfig.baseUri != null) {
                transformer.setBaseOutputURI(stepConfig.baseUri!!.resolve(outputBaseUri!!).toString())
            } else {
                transformer.setBaseOutputURI(outputBaseUri!!.toString())
            }
        } else {
            if (document != null) {
                if (document.properties.baseURI != null) {
                    transformer.setBaseOutputURI(document.properties.baseURI.toString())
                } else if (document.value is XdmNode) {
                    val base = (document.value as XdmNode).baseURI
                    if (base != null) {
                        transformer.setBaseOutputURI(base.toString())
                    }
                }
            } else {
                if (stylesheet.baseURI != null) {
                    transformer.setBaseOutputURI(stylesheet.baseURI.toString())
                }
            }
        }

        transformer.setSchemaValidationMode(ValidationMode.DEFAULT)
        transformer.getUnderlyingController().setUnparsedTextURIResolver(unparsedTextURIResolver)

        if (globalContextItem != null && globalContextItem!!.value != XdmEmptySequence.getInstance()) {
            transformer.setGlobalContextItem(globalContextItem!!.value as XdmItem)
        }

        if (version != "3.0" && document != null && document.value != XdmEmptySequence.getInstance()) {
            transformer.setGlobalContextItem(document.value as XdmItem)
        }

        /*
        if (defaulted.contains(Ns.globalContextItem)) {
            if (sources.size == 1) {
                transformer.setGlobalContextItem(sources.first().value as XdmItem)
            }
        } else {
            if (globalContextItem!!.value != XdmEmptySequence.getInstance()) {
                transformer.setGlobalContextItem(globalContextItem!!.value as XdmItem)
            }
        }
         */

        try {
            if (templateName != null) {
                transformer.callTemplate(templateName!!, primaryDestination)
            } else {
                transformer.applyTemplates(inputSelection, primaryDestination)
            }
        } catch (ex: SaxonApiException) {
            when (ex.errorCode) {
                NsFn.errXTMM9000 -> {
                    if (terminationError != null) {
                        throw terminationError!!.exception(ex)
                    }
                    throw XProcError.xcXsltUserTermination(ex.message ?: "").exception(ex)
                }
                NsFn.errXTDE0040 -> throw XProcError.xcXsltNoTemplate(templateName!!).exception(ex)
                else -> throw XProcError.xcXsltRuntimeError(ex.message!!).exception(ex)
            }
        }

        when (primaryDestination) {
            is RawDestination -> {
                val seq = mutableListOf<XdmValue>()
                val raw = primaryDestination as RawDestination
                val iter = raw.xdmValue.iterator()
                while (iter.hasNext()) {
                    seq.add(iter.next())
                }

                if (seq.size == 1 && seq.first() is XdmNode) {
                    val result = seq.first() as XdmNode
                    val props = DocumentProperties()
                    if (result.baseURI != null) {
                        props[Ns.baseUri] = result.baseURI
                    }
                    val doc = if (ValueUtils.contentClassification(result) == MediaType.TEXT) {
                        XProcDocument.ofText(result, stepConfig, MediaType.TEXT, props)
                    } else {
                        XProcDocument.ofXml(result, stepConfig, props)
                    }
                    receiver.output("result", doc)
                } else {
                    for (item in seq) {
                        val doc = XProcDocument.ofValue(item, stepConfig, MediaType.JSON, DocumentProperties())
                        receiver.output("result", doc)
                    }
                }
            }
            is XdmDestination -> {
                val tree = (primaryDestination as XdmDestination).xdmNode
                if (tree.baseURI != null) {
                    val props = DocumentProperties()
                    props[Ns.baseUri] = tree.baseURI
                    props[Ns.contentType] =  serializationContentType(primaryOutputProperties, ValueUtils.contentClassification(tree) ?: MediaType.XML)
                    if (primaryOutputProperties.isNotEmpty()) {
                        props[Ns.serialization] = serializationProperties(primaryOutputProperties)
                    }
                    val doc = XProcDocument.ofXml(tree, stepConfig, props)
                    receiver.output("result", doc)
                } else {
                    receiver.output("result", XProcDocument.ofXml(tree, stepConfig))
                }
            }
        }

        for ((uri, result) in documentResolver.results) {
            val destination = result.first
            val serprops = result.second

            when (destination) {
                is RawDestination -> {
                    var empty = true
                    val iter = destination.xdmValue.iterator()
                    while (iter.hasNext()) {
                        empty = false
                        val next = iter.next()
                        consumeSecondary(next, uri, serprops)
                    }
                    if (empty) {
                        val props = DocumentProperties()
                        if (serprops.isNotEmpty()) {
                            props[Ns.serialization] = stepConfig.asXdmMap(serprops)
                        }
                        receiver.output("secondary", XProcDocument.ofText(XdmEmptySequence.getInstance(), stepConfig, MediaType.XML, props))
                    }
                }
                is XdmDestination -> {
                    consumeSecondary(destination.xdmNode, uri, serprops)
                }
            }
        }
    }

    private fun consumeSecondary(result: XdmItem, uri: URI, serprops: Map<QName,XdmValue>) {
        val prop = DocumentProperties()
        prop[Ns.baseUri] = uri
        if (primaryOutputProperties.isNotEmpty()) {
            prop[Ns.serialization] = serializationProperties(primaryOutputProperties)
        }

        when (result) {
            is XdmNode -> {
                // Sigh. Secondary output documents don't have the correct intrinsict base URI,
                // so we rebuild documents around them where we can with the correct URI.
                // Also make sure they're always documents, not just elements.
                val builder = SaxonTreeBuilder(stepConfig)
                builder.startDocument(uri)
                builder.addSubtree(S9Api.adjustBaseUri(result, uri))
                builder.endDocument()
                val doc = builder.result

                prop[Ns.contentType] =  serializationContentType(primaryOutputProperties, ValueUtils.contentClassification(result) ?: MediaType.XML)

                if (ValueUtils.contentClassification(result) == MediaType.TEXT) {
                    receiver.output("secondary", XProcDocument.ofText(doc, stepConfig, MediaType.TEXT, prop))
                } else {
                    receiver.output("secondary", XProcDocument.ofXml(doc, stepConfig, prop))
                }
            }
            else -> {
                for (item in result.iterator()) {
                    val doc = XProcDocument.ofValue(item, stepConfig, MediaType.JSON, prop)
                    receiver.output("secondary", doc)
                }
            }
        }
    }

    private fun serializationProperties(props: Map<QName,XdmValue>): XdmMap {
        var serprop = XdmMap()
        for ((name, value) in primaryOutputProperties) {
            val strval = value.underlyingValue.stringValue
            if (strval == "yes" || strval == "no") {
                // Hack; we should check if the property is boolean...but what about extension properties?
                serprop = serprop.put(XdmAtomicValue(name), XdmAtomicValue(strval == "yes"))
            } else {
                serprop = serprop.put(XdmAtomicValue(name), value)
            }
        }
        return serprop
    }

    private fun serializationContentType(props: Map<QName,XdmValue>, default: MediaType): MediaType {
        if (!props.containsKey(Ns.method)) {
            return default
        }

        val method = props[Ns.method]!!.underlyingValue.stringValue
        when (method) {
            "xml" -> return MediaType.XML
            "html" -> return MediaType.HTML
            "xhtml" -> return MediaType.XHTML
            "text" -> return MediaType.TEXT
            "json" -> return MediaType.JSON
            else -> return default
        }
    }

    private fun xslt10() {
        XProcError.xcVersionNotAvailable(version ?: "null").exception()
    }

    override fun toString(): String = "p:xslt"

    inner class DocumentHandler(): (URI) -> Destination {
        override fun invoke(uri: URI): Destination {
            val xdmResult = XdmDestination()
            xdmResult.setBaseURI(uri)
            xdmResult.onClose(DocumentCloseAction(uri, xdmResult))
            return xdmResult
        }
    }

    inner class BaseURIMapper(val origBase: String?): (NodeInfo) -> String {
        override fun invoke(node: NodeInfo): String {
            var base = node.baseURI
            if (origBase != null && (base == null || base == "")) {
                base = origBase
            }
            return base
        }
    }

    inner class SystemIdMapper(): (NodeInfo) -> String {
        // This is a nop for now
        override fun invoke(node: NodeInfo): String {
            return node.systemId
        }
    }

    inner class DocumentCloseAction(val uri: URI, val destination: XdmDestination): Action {
        override fun act() {
            var doc = destination.xdmNode
            val bmapper = BaseURIMapper(doc.baseURI.toASCIIString())
            val smapper = SystemIdMapper()
            val treeinfo = doc.underlyingNode.treeInfo
            val rebaser = RebasedDocument(treeinfo, bmapper, smapper)
            val xfixbase = rebaser.wrap(doc.underlyingNode)
            doc = XdmNode(xfixbase)

            // FIXME: what should the properties be?
            receiver.output("secondary", XProcDocument.ofXml(doc, stepConfig))
        }
    }

    inner class MyResultDocumentResolver(val sconfig: Configuration): ResultDocumentResolver {
        val results = mutableMapOf<URI, Pair<Destination, MutableMap<QName, XdmValue>>>()

        override fun resolve(context: XPathContext, href: String, baseUri: String, properties: SerializationProperties): Receiver {
            synchronized(results) {
                val tree = properties.getProperty(SaxonOutputKeys.BUILD_TREE)
                val uri = ResolveURI.makeAbsolute(href, baseUri)
                val destination = if (tree == "yes") {
                    XdmDestination()
                } else {
                    RawDestination()
                }

                val xprocProps = mutableMapOf<QName, XdmValue>()
                // FIXME: copy the serialization properties?

                results[uri] = Pair(destination, xprocProps)

                val pc = PipelineConfiguration(sconfig)
                return destination.getReceiver(pc, properties);
            }
        }
    }
}