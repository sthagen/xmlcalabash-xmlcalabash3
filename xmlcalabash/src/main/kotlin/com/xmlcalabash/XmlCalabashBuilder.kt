package com.xmlcalabash

import com.xmlcalabash.api.MessageReporter
import com.xmlcalabash.config.SaxonConfiguration
import com.xmlcalabash.exceptions.DefaultErrorExplanation
import com.xmlcalabash.exceptions.ErrorExplanation
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.io.DocumentManager
import com.xmlcalabash.io.MediaType
import com.xmlcalabash.io.MessagePrinter
import com.xmlcalabash.namespace.Ns
import com.xmlcalabash.spi.Configurer
import com.xmlcalabash.spi.ConfigurerServiceProvider
import com.xmlcalabash.spi.PagedMediaManager
import com.xmlcalabash.spi.PagedMediaServiceProvider
import com.xmlcalabash.util.AssertionsLevel
import com.xmlcalabash.util.DefaultMessagePrinter
import com.xmlcalabash.util.DefaultMessageReporter
import com.xmlcalabash.util.Verbosity
import com.xmlcalabash.util.spi.StandardPagedMediaProvider
import net.sf.saxon.Configuration
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.ValidationMode
import org.apache.logging.log4j.kotlin.logger
import org.xmlresolver.ResolverFeature
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset

/**
 * The builder for constructing an XML Calabash configuration.
 *
 * There's an unfortunate chicken-and-egg problem here. The builder can configure the [DocumentManager] (this
 * allows you to provide your own [DocumentManager] with, perhaps your own [org.xmlresolver.XMLResolver]).
 * But it's also possible (and useful) to be able to configure mime type mappings through the builder.
 * In particular, these may be provided by the [com.xmlcalabash.config.ConfigurationLoader].
 *
 * So the builder needs the document manager and the document manager is configured through the builder.
 * And many users will not need or want to provide a custom [DocumentManager]. So the workaround is that
 * calls to set mimetype mappings are cached by the builder until build time. At build time, the [DocumentManager]
 * is resolved (either it was provided or it's constructed) and those mappings are applied to
 * the [DocumentManager.mimetypesFileTypeMap].
 */

class XmlCalabashBuilder {
    companion object {
        val OS = System.getProperty("os.name") ?: "unknown"
        val DEFAULT_CONSOLE_ENCODING = if (OS.startsWith("Windows")) {
            "windows-1252"
        } else {
            "utf-8"
        }
    }

    private var saxonConfiguration: SaxonConfiguration? = null
    private val config = BuiltConfiguration()

    internal var _mpt: Double = 0.99999998
    val mpt: Double
        get() = _mpt

    internal val mimetypesCache = mutableMapOf<String,MutableSet<String>>()
    private val uninitializedFormatters = mutableSetOf<URI>()
    private val initializerClasses = mutableListOf<Pair<String,Boolean>>()
    private val configurers = mutableListOf<Configurer>()

    init {
        for (provider in PagedMediaServiceProvider.providers()) {
            val manager = provider.create()
            config._pagedMediaManagers.add(manager)
            uninitializedFormatters.addAll(manager.formatters())
        }

        for (provider in ConfigurerServiceProvider.providers()) {
            val configurer = provider.create()
            configurers.add(configurer)
        }
    }

    fun getAssertions() = config._assertions
    fun setAssertions(level: AssertionsLevel): XmlCalabashBuilder {
        logger.debug { "setAssertions: ${level}" }
        config._assertions = level
        return this
    }

    fun getConsoleEncoding() = config._consoleEncoding
    fun setConsoleEncoding(encoding: String): XmlCalabashBuilder {
        try {
            if (Charset.isSupported(encoding)) {
                config._consoleEncoding = encoding
                config._messagePrinter?.setEncoding(config.consoleEncoding)
                logger.debug { "setConsoleEncoding ${encoding}" }
            } else {
                logger.warn { "Console encoding is not supported: ${encoding}, using ${config.consoleEncoding}" }
            }
        } catch (_: IOException) {
            logger.warn { "Console encoding is not supported: ${encoding}, using ${config.consoleEncoding}" }
        }

        return this
    }

    fun getConfigurers(): List<Configurer> {
        return configurers.toList()
    }
    fun addConfigurer(configurer: Configurer): XmlCalabashBuilder {
        configurers.add(configurer)
        return this
    }

    fun getDebug() = config._debug
    fun setDebug(debug: Boolean): XmlCalabashBuilder {
        logger.debug { "setDebug ${debug}" }
        config._debug = debug
        return this
    }

    fun getDebugger() = config._debugger
    fun setDebugger(debugger: Boolean): XmlCalabashBuilder {
        logger.debug { "setDebugger ${debugger}" }
        config._debugger = debugger
        if (debugger) {
            config._maxThreadCount = 1
        }
        return this
    }

    fun getEagerEvaluation() = config._eagerEvaluation
    fun setEagerEvaluation(eager: Boolean): XmlCalabashBuilder {
        logger.debug { "setEagerEvaluation ${eager}" }
        config._eagerEvaluation = eager
        return this
    }

    fun getGraphStyle() = config._graphStyle
    fun setGraphStyle(style: URI?): XmlCalabashBuilder {
        logger.debug { "setGraphStyle ${style}" }
        config._graphStyle = style
        return this
    }

    fun getGraphviz() = config._graphviz
    fun setGraphviz(executable: File?): XmlCalabashBuilder {
        logger.debug { "setGraphviz ${executable}" }
        config._graphviz = executable
        return this
    }

    fun getImplicitParameterName() = config._implicitParameterName
    fun setImplicitParameterName(name: QName?): XmlCalabashBuilder {
        logger.debug { "setImplicitParameterName ${name}" }
        config._implicitParameterName = name
        return this
    }

    fun getInlineTrimeWhitespace() = config._inlineTrimWhitespace
    fun setInlineTrimWhitespace(trim: Boolean): XmlCalabashBuilder {
        logger.debug { "setInlineTrimWhitespace ${trim}" }
        config._inlineTrimWhitespace = trim
        return this
    }

    fun getLicensed() = config._licensed
    fun setLicensed(licensed: Boolean): XmlCalabashBuilder {
        logger.debug { "setLicensed ${licensed}" }
        if (config.licensed != licensed) {
            saxonConfiguration = null
            config._licensed = licensed
        }
        return this
    }

    fun getMessageBufferSize() = config._messageBufferSize
    fun setMessageBufferSize(size: Int): XmlCalabashBuilder {
        logger.debug { "setMessageBufferSize ${size}" }
        config._messageBufferSize = size
        return this
    }

    fun getMessagePrinter() = config._messagePrinter
    fun setMessagePrinter(printer: MessagePrinter): XmlCalabashBuilder {
        logger.debug { "setMessagePrinter ${printer}" }
        printer.setEncoding(config.consoleEncoding)
        config._messagePrinter = printer
        if (config._messageReporter != null) {
            config.messageReporter.setMessagePrinter(printer)
        }
        return this
    }

    fun getMessageReporter() = config._messageReporter
    fun setMessageReporter(reporter: MessageReporter): XmlCalabashBuilder {
        logger.debug { "setMessageReporter ${reporter}" }
        config._messageReporter = reporter
        if (config._messagePrinter != null) {
            reporter.setMessagePrinter(config.messagePrinter)
        }
        return this
    }

    /**
     * Add mapping from mime types to filename extensions.
     *
     * These mappings will be applied to the [DocumentManager.mimetypesFileTypeMap] at build time.
     * The extensions should not include the leading ".": `listOf("txt", "text")` not
     * `listOf(".txt", ".text")`.
     */
    fun addMimeType(contentType: String, extensions: List<String>) {
        val ext = mimetypesCache[contentType] ?: mutableSetOf()
        ext.addAll(extensions)
        mimetypesCache[contentType] = ext
    }

    fun getErrorExplanation() = config.errorExplanation
    fun setErrorExplanation(explanation: ErrorExplanation): XmlCalabashBuilder {
        logger.debug { "setErrorExplanation ${explanation}" }
        config._errorExplanation = explanation
        return this
    }

    fun getDocumentManager() = config.documentManager
    fun setDocumentManager(manager: DocumentManager): XmlCalabashBuilder {
        logger.debug { "setDocumentManager ${manager}" }
        config._documentManager = manager
        return this
    }

    fun getOther() = config._other.toMap()
    fun setOther(other: Map<QName, List<Map<QName, String>>>): XmlCalabashBuilder {
        logger.debug { "setOther ${other.keys}" }
        config._other.clear()
        config._other.putAll(other)
        return this
    }

    fun addOther(other: QName, properties: Map<QName, String>): XmlCalabashBuilder {
        logger.debug { "addOther ${other}" }
        val otherProperties = mutableListOf<Map<QName, String>>()
        config._other[other]?.let { otherProperties.addAll(it) }
        otherProperties.add(properties)
        config._other[other] = otherProperties.toList()
        return this
    }

    fun addPagedMediaCssProcessor(cssFormatter: URI, properties: Map<QName, String>): XmlCalabashBuilder {
        logger.debug { "addPagedMediaCssProcessor ${cssFormatter}" }
        config._pagedMediaCssProcessors.add(cssFormatter)

        if (cssFormatter == StandardPagedMediaProvider.genericCssFormatter) {
            if (properties.isNotEmpty()) {
                throw XProcError.xiForbiddenConfigurationAttributes(cssFormatter).exception()
            }
        } else {
            uninitializedFormatters.remove(cssFormatter)
            for (manager in config._pagedMediaManagers) {
                if (manager.formatterSupported(cssFormatter)) {
                    manager.configure(cssFormatter, properties)
                }
            }
        }

        return this
    }

    fun addPagedMediaXslProcessor(xslFormatter: URI, properties: Map<QName, String>): XmlCalabashBuilder {
        logger.debug { "addPagedMediaXslProcessor ${xslFormatter}" }
        config._pagedMediaXslProcessors.add(xslFormatter)

        if (xslFormatter == StandardPagedMediaProvider.genericXslFormatter) {
            if (properties.isNotEmpty()) {
                throw XProcError.xiForbiddenConfigurationAttributes(xslFormatter).exception()
            }
        } else {
            uninitializedFormatters.remove(xslFormatter)
            for (manager in config._pagedMediaManagers) {
                if (manager.formatterSupported(xslFormatter)) {
                    manager.configure(xslFormatter, properties)
                }
            }
        }

        return this
    }

    fun getPipe() = config._pipe
    fun setPipe(pipe: Boolean): XmlCalabashBuilder {
        logger.debug { "setPipe ${pipe}" }
        config._pipe = pipe
        return this
    }

    fun getProxies() = config._proxies.toMap()
    fun setProxies(proxies: Map<String, String>): XmlCalabashBuilder {
        logger.debug { "setProxies ${proxies}" }
        config._proxies.clear()
        config._proxies.putAll(proxies)
        return this
    }

    fun addProxy(scheme: String, uri: String): XmlCalabashBuilder {
        logger.debug { "addProxy ${scheme}=${uri}" }
        config._proxies[scheme] = uri
        return this
    }

    fun getSaxonConfiguration() = config._saxonConfigurationFile
    fun setSaxonConfigurationFile(configFile: File?): XmlCalabashBuilder {
        logger.debug { "setSaxonConfigurationFile ${configFile}" }
        saxonConfiguration = null
        config._saxonConfigurationFile = configFile
        if (configFile != null && !configFile.exists()) {
            throw XProcError.xiConfigurationInvalid(configFile.absolutePath, "file does not exist: ${configFile}").exception()
        }
        return this
    }

    fun getSaxonConfigurationProperties() = config._saxonConfigurationProperties.toMap()
    fun setSaxonConfigurationProperties(properties: Map<String,String>): XmlCalabashBuilder {
        logger.debug { "setSaxonConfigurationProperties ${properties}" }
        saxonConfiguration = null
        config._saxonConfigurationProperties.clear()
        config._saxonConfigurationProperties.putAll(properties)
        return this
    }

    fun addSaxonConfigurationProperty(name: String, value: String): XmlCalabashBuilder {
        logger.debug { "addSaxonConfigurationProperty ${name}=${value}" }
        saxonConfiguration = null
        config._saxonConfigurationProperties[name] = value
        return this
    }

    fun getSendmail() = config._sendmail.toMap()
    fun setSendmail(properties: Map<String,String>): XmlCalabashBuilder {
        logger.debug { "setSendmail ${properties}" }
        config._sendmail.clear()
        config._sendmail.putAll(properties)
        return this
    }

    fun addSendmailProperty(name: String, value: String): XmlCalabashBuilder {
        logger.debug { "addSendmailProperty ${name}=${value}" }
        config._sendmail[name] = value
        return this
    }

    fun getSerialization() = config._serialization.toMap()
    fun setSerialization(properties: Map<MediaType, Map<QName, String>>): XmlCalabashBuilder {
        logger.debug { "setSerialization ${properties.keys}" }
        config._serialization.clear()
        config._serialization.putAll(properties)
        return this
    }

    fun addSerialization(contentType: MediaType, properties: Map<QName, String>): XmlCalabashBuilder {
        logger.debug { "addSerialization ${contentType}" }
        config._serialization[contentType] = properties
        return this
    }

    fun addSerializationProperty(contentType: MediaType, property: QName, value: String): XmlCalabashBuilder {
        logger.debug { "addSerializationProperty ${contentType}: ${property}=${value}" }
        val props = mutableMapOf<QName, String>()
        config._serialization[contentType]?.let { props.putAll(it) }
        props[property] = value
        config._serialization[contentType] = props
        return this
    }

    fun getMaxThreadCount() = config._maxThreadCount
    fun setMaxThreadCount(size: Int): XmlCalabashBuilder {
        logger.debug { "setMaxThreadCount ${size}" }
        val pcount = Runtime.getRuntime().availableProcessors()
        if (size > 0) {
            if (config._debug) {
                config._maxThreadCount = 1
                logger.debug { "Using the debugger requires single threaded operation" }
            } else {
                if (size > pcount) {
                    logger.debug { "Upper limit for thread count is ${pcount}" }
                    config._maxThreadCount = pcount
                } else {
                    config._maxThreadCount = size
                }
            }
        } else {
            logger.warn { "Ignoring absurd thread count: ${size}" }
        }
        return this
    }

    fun getTrace() = config._trace
    fun setTrace(trace: File?): XmlCalabashBuilder {
        logger.debug { "setTrace ${trace}" }
        config._trace = trace
        return this
    }

    fun getTraceDocuments() = config._traceDocuments
    fun setTraceDocuments(traceDocuments: File?): XmlCalabashBuilder {
        logger.debug { "setTraceDocuments ${traceDocuments}" }
        config._traceDocuments = traceDocuments
        return this
    }

    fun getTryNamespaces() = config._tryNamespaces
    fun setTryNamespaces(namespaces: Boolean): XmlCalabashBuilder {
        logger.debug { "setTryNamespaces ${namespaces}" }
        config._tryNamespaces = namespaces
        return this
    }

    fun getUniqueInlineUris() = config._uniqueInlineUris
    fun setUniqueInlineUris(uniqueInlineUris: Boolean): XmlCalabashBuilder {
        logger.debug { "setUniqueInlineUris ${uniqueInlineUris}" }
        config._uniqueInlineUris = uniqueInlineUris
        return this
    }

    fun getUseLocationHints() = config._useLocationHints
    fun setUseLocationHints(locationHints: Boolean): XmlCalabashBuilder {
        logger.debug { "setUseLocationHints ${locationHints}" }
        config._useLocationHints = locationHints
        return this
    }

    fun getValidationMode() = config._validationMode
    fun setValidationMode(mode: ValidationMode): XmlCalabashBuilder {
        logger.debug { "setValidationMode ${mode}" }
        config._validationMode = mode
        return this
    }

    fun getVerbosity() = config._verbosity
    fun setVerbosity(verbosity: Verbosity): XmlCalabashBuilder {
        logger.debug { "setVerbosity ${verbosity}" }
        config._verbosity = verbosity
        return this
    }

    fun getVisualizer() = config._visualizer
    fun setVisualizer(visualizer: String, properties: Map<String,String>): XmlCalabashBuilder {
        if (visualizer in listOf("silent", "plain", "detail")) {
            logger.debug { "setVisualizer ${visualizer}" }
            config._visualizer = visualizer
            config._visualizerProperties.clear()
            config._visualizerProperties.putAll(properties)
            return this
        }
        throw IllegalArgumentException("Visualizer $visualizer is not supported.")
    }

    fun getXmlCatalogs() = config._xmlCatalogs
    fun setXmlCatalogs(catalogs: List<URI>): XmlCalabashBuilder {
        logger.debug { "setXmlCatalogs ${catalogs}" }
        config._xmlCatalogs.clear()
        config._xmlCatalogs.addAll(catalogs)
        return this
    }

    fun addXmlCatalog(catalog: URI): XmlCalabashBuilder {
        logger.debug { "addXmlCatalog ${catalog}" }
        config._xmlCatalogs.add(catalog)
        return this
    }

    fun getXmlSchemaDocuments() = config._xmlSchemaDocuments
    fun setXmlSchemaDocuments(schemas: List<URI>): XmlCalabashBuilder {
        logger.debug { "setXmlSchemaDocuments ${schemas}" }
        saxonConfiguration = null
        config._xmlSchemaDocuments.clear()
        config._xmlSchemaDocuments.addAll(schemas)
        return this
    }

    fun addXmlSchemaDocument(schema: URI): XmlCalabashBuilder {
        logger.debug { "addXmlSchemaDocument ${schema}" }
        saxonConfiguration = null
        config._xmlSchemaDocuments.add(schema)
        return this
    }

    fun addInitializer(className: String, ignoreErrors: Boolean = false): XmlCalabashBuilder {
        logger.debug { "addInitializer ${className}" }
        initializerClasses.add(Pair(className, ignoreErrors))
        return this
    }

    fun build(): XmlCalabash {
        val config = commonBuild()

        val initializers = mutableMapOf<String, Boolean>()
        for (pair in initializerClasses) {
            initializers[pair.first] = pair.second
        }

        val saxonConfiguration = SaxonConfiguration.newInstance(config.licensed, config.saxonConfigurationFile?.toURI(), config.saxonConfigurationProperties, config.xmlSchemaDocuments, initializers, configurers)
        saxonConfiguration.configuration.resourceResolver = config._documentManager
        config._saxonConfiguration = saxonConfiguration
        return init(config)
    }

    fun build(configuration: Configuration): XmlCalabash {
        val config = commonBuild()

        if (config.saxonConfigurationFile != null
            || config.saxonConfigurationProperties.isNotEmpty()
            || config.xmlSchemaDocuments.isNotEmpty()
            || initializerClasses.isNotEmpty()) {
                throw XProcError.xiNoConfigurationAllowed().exception()
            }

        val saxonConfiguration = SaxonConfiguration.newInstance(configuration)
        config._saxonConfiguration = saxonConfiguration
        return init(config)
    }

    private fun commonBuild(): BuiltConfiguration {
        for (configurer in configurers) {
            logger.debug { "Configuring with ${configurer}"}
            configurer.configure(this)
        }

        val config = this.config.copy()
        for ((contentType, extensions) in mimetypesCache) {
            val ext = extensions.joinToString(" ")
            logger.debug { "Assigning content type: ${contentType} to ${ext}" }
            config.documentManager.mimetypesFileTypeMap.addMimeTypes("${contentType} ${ext}")
        }

        return config
    }

    private fun init(config: BuiltConfiguration): XmlCalabash {
        mimetypesCache.clear()
        this.config._messagePrinter = null
        this.config._messageReporter = null
        this.config._documentManager = null
        this.config._errorExplanation = null

        if (config.xmlSchemaDocuments.isNotEmpty()
            && !config._saxonConfiguration.configuration.isLicensedFeature(Configuration.LicenseFeature.SCHEMA_VALIDATION)) {
            logger.warn { "Schema validation feature is not enabled, ignored configured schemas" }
        }

        for (manager in config.pagedMediaManagers) {
            for (formatter in manager.formatters()) {
                if (formatter in uninitializedFormatters) {
                    manager.configure(formatter, emptyMap())
                }
            }
        }

        return XmlCalabash(config)
    }

    inner class BuiltConfiguration(): XmlCalabashConfiguration {
        lateinit internal var _saxonConfiguration: SaxonConfiguration
        internal var _messagePrinter: MessagePrinter? = null
        internal var _messageReporter: MessageReporter? = null
        internal var _errorExplanation: ErrorExplanation? = null
        internal var _documentManager: DocumentManager? = null

        internal var _assertions = AssertionsLevel.WARNING
        internal var _consoleEncoding = DEFAULT_CONSOLE_ENCODING
        internal var _debug = false
        internal var _debugger = false
        internal var _eagerEvaluation = false
        internal var _graphStyle: URI? = null
        internal var _graphviz: File? = null
        internal var _implicitParameterName: QName? = Ns.parameters
        internal var _inlineTrimWhitespace = false
        internal var _licensed = true
        internal var _messageBufferSize = 32
        internal var _mpt: Double = 0.99999998
        internal val _other = mutableMapOf<QName, List<Map<QName, String>>>()
        internal val _pagedMediaCssProcessors = mutableListOf<URI>()
        internal val _pagedMediaManagers = mutableListOf<PagedMediaManager>()
        internal val _pagedMediaXslProcessors = mutableListOf<URI>()
        internal var _pipe = false
        internal val _proxies = mutableMapOf<String, String>()
        internal var _saxonConfigurationFile: File? = null
        internal val _saxonConfigurationProperties = mutableMapOf<String,String>()
        internal val _sendmail = mutableMapOf<String, String>()
        internal val _serialization = mutableMapOf<MediaType, Map<QName,String>>()
        internal var _maxThreadCount = 1
        internal var _trace: File? = null
        internal var _traceDocuments: File? = null
        internal var _tryNamespaces = false
        internal var _uniqueInlineUris = true
        internal var _useLocationHints = false
        internal var _validationMode = ValidationMode.DEFAULT
        internal var _verbosity = Verbosity.INFO
        internal var _visualizer = "silent"
        internal var _visualizerProperties = mutableMapOf<String,String>()
        internal val _xmlCatalogs = mutableListOf<URI>()
        internal val _xmlSchemaDocuments = mutableListOf<URI>()

        override val saxonConfiguration: SaxonConfiguration
            get() { return _saxonConfiguration }

        override val messagePrinter: MessagePrinter
            get() {
                if (_messagePrinter == null) {
                    _messagePrinter = DefaultMessagePrinter(consoleEncoding)
                }
                return _messagePrinter!!
            }

        override val messageReporter: MessageReporter
            get() {
                if (_messageReporter == null) {
                    _messageReporter = DefaultMessageReporter()
                    _messageReporter!!.threshold = verbosity
                    _messageReporter!!.setMessagePrinter(messagePrinter)
                }
                return _messageReporter!!
            }

        override val errorExplanation: ErrorExplanation
            get() {
                if (_errorExplanation == null) {
                    _errorExplanation = DefaultErrorExplanation(messageReporter)
                }
                return _errorExplanation!!
            }

        override val documentManager: DocumentManager
            get() {
                if (_documentManager == null) {
                    _documentManager = DocumentManager()
                }
                return _documentManager!!
            }

        override val assertions: AssertionsLevel
            get() = _assertions
        override val consoleEncoding
            get() = _consoleEncoding
        override val debug: Boolean
            get() = _debug
        override val debugger: Boolean
            get() = _debugger
        override val eagerEvaluation
            get() = _eagerEvaluation
        override val graphStyle: URI?
            get() = _graphStyle
        override val graphviz: File?
            get() = _graphviz
        override val implicitParameterName: QName?
            get() = _implicitParameterName
        override val inlineTrimWhitespace: Boolean
            get() = _inlineTrimWhitespace
        override val licensed: Boolean
            get() = _licensed
        override val messageBufferSize: Int
            get() = _messageBufferSize
        override val other: Map<QName, List<Map<QName, String>>>
            get() = _other
        override val pagedMediaCssProcessors: List<URI>
            get() = _pagedMediaCssProcessors
        override val pagedMediaManagers: List<PagedMediaManager>
            get() = _pagedMediaManagers
        override val pagedMediaXslProcessors: List<URI>
            get() = _pagedMediaXslProcessors
        override val pipe: Boolean
            get() = _pipe
        override val proxies: Map<String, String>
            get() = _proxies
        override val saxonConfigurationFile: File?
            get() = _saxonConfigurationFile
        override val saxonConfigurationProperties: Map<String, String>
            get() = _saxonConfigurationProperties
        override val sendmail: Map<String, String>
            get() = _sendmail
        override val serialization: Map<MediaType, Map<QName, String>>
            get() = _serialization
        override val maxThreadCount: Int
            get() = _maxThreadCount
        override val trace: File?
            get() = _trace
        override val traceDocuments: File?
            get() = _traceDocuments
        override val tryNamespaces: Boolean
            get() = _tryNamespaces
        override val uniqueInlineUris: Boolean
            get() = _uniqueInlineUris
        override val useLocationHints: Boolean
            get() = _useLocationHints
        override val validationMode: ValidationMode
            get() = _validationMode
        override val verbosity: Verbosity
            get() = _verbosity
        override val visualizer: String
            get() = _visualizer
        override val visualizerProperties: Map<String, String>
            get() = _visualizerProperties
        override val xmlCatalogs: List<URI>
            get() = _xmlCatalogs
        override val xmlSchemaDocuments: List<URI>
            get() = _xmlSchemaDocuments

        internal fun copy(): BuiltConfiguration {
            val config = BuiltConfiguration()
            // Not touching saxonConfiguration on purpose!
            config._messagePrinter = _messagePrinter
            config._messageReporter = _messageReporter
            config._documentManager = documentManager
            config._errorExplanation = errorExplanation
            config._assertions = _assertions
            config._consoleEncoding = _consoleEncoding
            config._debug = _debug
            config._debugger = _debugger
            config._eagerEvaluation = _eagerEvaluation
            config._graphStyle = _graphStyle
            config._graphviz = _graphviz
            config._inlineTrimWhitespace = _inlineTrimWhitespace
            config._licensed = _licensed
            config._messageBufferSize = _messageBufferSize
            config._mpt = _mpt
            config._other.putAll(_other)
            config._pagedMediaCssProcessors.addAll(_pagedMediaCssProcessors)
            config._pagedMediaManagers.addAll(_pagedMediaManagers)
            config._pagedMediaXslProcessors.addAll(_pagedMediaXslProcessors)
            config._pipe = _pipe
            config._proxies.putAll(_proxies)
            config._saxonConfigurationFile = saxonConfigurationFile
            config._saxonConfigurationProperties.putAll(_saxonConfigurationProperties)
            config._sendmail.putAll(_sendmail)
            config._serialization.putAll(_serialization)
            config._maxThreadCount = _maxThreadCount
            config._trace = _trace
            config._traceDocuments = _traceDocuments
            config._tryNamespaces = _tryNamespaces
            config._uniqueInlineUris = _uniqueInlineUris
            config._useLocationHints = _useLocationHints
            config._validationMode = _validationMode
            config._verbosity = _verbosity
            config._visualizer = _visualizer
            config._visualizerProperties.putAll(_visualizerProperties)
            config._xmlCatalogs.addAll(_xmlCatalogs)
            config._xmlSchemaDocuments.addAll(_xmlSchemaDocuments)

            val xmlconfig = config.documentManager.resolver.configuration
            val catlist = mutableListOf<String>()
            catlist.addAll(xmlconfig.getFeature(ResolverFeature.CATALOG_FILES))
            for (cat in config.xmlCatalogs) {
                catlist.add(cat.toString())
            }
            xmlconfig.setFeature(ResolverFeature.CATALOG_FILES, catlist)

            return config
        }
    }
}