package com.xmlcalabash.config

import com.xmlcalabash.datamodel.DeclareStepInstruction
import com.xmlcalabash.datamodel.XProcFunctionLibrary
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.exceptions.XProcException
import com.xmlcalabash.functions.*
import com.xmlcalabash.spi.Configurer
import net.sf.saxon.Configuration
import net.sf.saxon.functions.FunctionLibrary
import net.sf.saxon.lib.ExtensionFunctionDefinition
import net.sf.saxon.lib.FeatureIndex
import net.sf.saxon.lib.Initializer
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.Xslt30Transformer
import org.apache.logging.log4j.kotlin.logger
import org.xml.sax.InputSource
import java.net.URI
import javax.xml.transform.sax.SAXSource

class SaxonConfiguration private constructor(val licensed: Boolean,
                                             val saxonConfigurationFile: URI?,
                                             val saxonConfigurationProperties: Map<String,String>,
                                             initialSchemaDocuments: List<URI>,
                                             val initializerClasses: Map<String,Boolean>,
                                             val configurers: List<Configurer>,
                                             private val contextManager: ExecutionContextManager): ExecutionContextManager by contextManager {
    companion object {
        fun newInstance(licensed: Boolean): SaxonConfiguration {
            return newInstance(licensed, null, emptyMap(), emptyList(), emptyMap(), emptyList())
        }

        fun newInstance(configuration: Configuration): SaxonConfiguration {
            val licensed = configuration.isLicensedFeature(Configuration.LicenseFeature.SCHEMA_VALIDATION)
            val contextManager: ExecutionContextManager = ExecutionContextImpl()
            val saxonConfiguration = SaxonConfiguration(licensed, null, emptyMap(), emptyList(), emptyMap(), emptyList(), contextManager)
            saxonConfiguration.init(true, configuration)
            return saxonConfiguration
        }

        fun newInstance(licensed: Boolean,
                        configurationFile: URI?,
                        properties: Map<String,String>,
                        schemaDocuments: List<URI>,
                        initializers: Map<String,Boolean>,
                        configurers: List<Configurer>): SaxonConfiguration {
            val contextManager: ExecutionContextManager = ExecutionContextImpl()
            val saxonConfiguration = SaxonConfiguration(licensed, configurationFile, properties, schemaDocuments, initializers, configurers, contextManager)
            saxonConfiguration.init(true, null)
            return saxonConfiguration
        }
    }

    private val schemaDocuments = mutableListOf<URI>()
    private lateinit var _configuration: Configuration
    private lateinit var _processor: Processor

    lateinit var _environment: XProcEnvironment
    var environment: XProcEnvironment
        internal set(value) {
            _environment = value
        }
        get() = _environment

    init {
        schemaDocuments.addAll(initialSchemaDocuments)
    }

    private fun init(showLoadingMessage: Boolean, suppliedConfiguration: Configuration?) {
        _configuration = suppliedConfiguration
            ?: if (saxonConfigurationFile == null) {
                if (licensed) {
                    Configuration.newLicensedConfiguration()
                } else {
                    Configuration()
                }
            } else {
                if (showLoadingMessage) {
                    logger.info { "Loading Saxon configuration file ${saxonConfigurationFile}" }
                }
                val source = SAXSource(InputSource(saxonConfigurationFile.toString()))
                Configuration.readConfiguration(source)
            }

        for ((key, value) in saxonConfigurationProperties) {
            val data = FeatureIndex.getData(key) ?: throw XProcError.xiUnrecognizedSaxonConfigurationProperty(key).exception()
            if (data.type == java.lang.Boolean::class.java) {
                if (value == "true" || value == "false") {
                    _configuration.setConfigurationProperty(key, value == "true")
                } else {
                    throw XProcError.xiInvalidSaxonConfigurationProperty(key, value).exception()
                }
            } else {
                _configuration.setConfigurationProperty(key, value)
            }
        }

        loadConfigurationSchemas(_configuration)

        for ((className, ignoreErrors) in initializerClasses) {
            saxonInitializer(_configuration, className, ignoreErrors)
        }

        for (configurer in configurers) {
            configurer.configureSaxon(_configuration)
        }

        _processor = Processor(configuration)
        configuration.processor = _processor
        configureProcessor(_processor)
    }

    val configuration: Configuration
        get() {
            return _configuration
        }

    val processor: Processor
        get() {
            return _processor
        }

    private val inheritedFunctionLibraries = mutableListOf<Pair<URI, XProcFunctionLibrary>>()
    private val functionLibraries = mutableListOf<Pair<URI, XProcFunctionLibrary>>()
    private val pipelineExtensionFunctions = mutableListOf<ExtensionFunctionDefinition>()
    private val standardExtensionFunctions = listOf<(SaxonConfiguration) -> ExtensionFunctionDefinition>(
        { config -> DocumentPropertyFunction(config) },
        { config -> DocumentPropertiesFunction(config) },
        { config -> ErrorFunction(config) },
        { config -> FunctionLibraryImportableFunction(config) },
        { config -> IterationPositionFunction(config) },
        { config -> IterationSizeFunction(config) },
        { config -> StepAvailableFunction(config) },
        { config -> SystemPropertyFunction(config) },
        { config -> UrifyFunction(config) },
        { config -> LookupUriFunction(config) }
    )

    fun newConfiguration(): SaxonConfiguration {
        val newConfig = SaxonConfiguration(licensed, saxonConfigurationFile, saxonConfigurationProperties, schemaDocuments, initializerClasses, configurers, contextManager)
        newConfig.inheritedFunctionLibraries.addAll(inheritedFunctionLibraries)
        newConfig.inheritedFunctionLibraries.addAll(functionLibraries)
        newConfig.pipelineExtensionFunctions.addAll(pipelineExtensionFunctions)
        newConfig.environment = environment
        newConfig.init(false, null)

        newConfig.configuration.namePool = configuration.namePool
        newConfig.configuration.documentNumberAllocator = configuration.documentNumberAllocator
        newConfig.configuration.resourceResolver = configuration.resourceResolver

        newConfig._processor = Processor(newConfig.configuration)
        newConfig.configureProcessor(newConfig._processor)

        newConfig.configuration.processor = newConfig._processor
        return newConfig
    }

    fun clearSchemaCache() {
        // Yes, but keep the ones that were loaded at configuration time...
        configuration.clearSchemaCache()
        loadConfigurationSchemas(configuration)
    }

    fun addSchemaDocument(uri: URI) {
        schemaDocuments.add(uri)
    }

    private fun loadConfigurationSchemas(configuration: Configuration) {
        if (schemaDocuments.isNotEmpty()) {
            if (configuration.isLicensedFeature(Configuration.LicenseFeature.SCHEMA_VALIDATION)) {
                for (schema in schemaDocuments) {
                    val source = SAXSource(InputSource(schema.toString()))
                    configuration.addSchemaSource(source)
                }
            } else {
                // nop; we will already have done the warning when we loaded the first one
            }
        }
    }

    private fun saxonInitializer(configuration: Configuration, name: String, ignoreErrors: Boolean = false) {
        try {
            val klass = Class.forName(name)
            val constructor = klass.getConstructor()
            val init = constructor.newInstance()
            if (init is Initializer) {
                init.initialize(configuration)
            } else {
                throw XProcError.xiInitializerError("${name} is not a net.sf.saxon.lib.Initializer").exception()
            }
        } catch (ex: Exception) {
            if (ex is XProcException) {
                throw ex
            } else {
                if (!ignoreErrors) {
                    throw XProcError.xiInitializerError(ex.toString()).exception(ex)
                }
                // Should we print a warning about this?
            }
        }
    }

    internal fun addFunctionLibrary(href: URI, flib: XProcFunctionLibrary) {
        for (current in inheritedFunctionLibraries) {
            if (current.first == href) {
                return
            }
        }

        for (current in functionLibraries) {
            if (current.first == href) {
                return
            }
        }

        for (binding in functionLibraries) {
            for ((name, arity) in binding.second.exposedNames) {
                if (flib.exposedNames.get(name) == arity) {
                    throw XProcError.xsDuplicateFunction(name, arity).exception()
                }
            }
        }

        functionLibraries.add(Pair(href,flib))
        loadFunctionLibrary(href, flib)
    }

    private fun loadFunctionLibrary(href: URI, flib: FunctionLibrary) {
        // Is this the best way?
        val pc = Class.forName("com.saxonica.config.ProfessionalConfiguration")
        val fc = Class.forName("net.sf.saxon.functions.FunctionLibrary")
        val setBinder = pc.getMethod("setExtensionBinder", String::class.java, fc)
        setBinder.invoke(configuration, "xmlcalabash:${href}", flib)
    }

    fun declareFunction(decl: DeclareStepInstruction) {
        val function = PipelineFunction(decl)
        pipelineExtensionFunctions.add(function)
        processor.registerExtensionFunction(function)
    }

    // ============================================================

    private fun configureProcessor(processor: Processor) {
        for (function in standardExtensionFunctions) {
            processor.registerExtensionFunction(function(this))
        }

        for (function in pipelineExtensionFunctions) {
            processor.registerExtensionFunction(function)
        }

        // So that libraries loaded later "come first" when searching...
        val seen = mutableMapOf<QName, Int>()
        for (flib in functionLibraries.reversed()) {
            val library = flib.second.suppressDuplicates(seen, processor.underlyingConfiguration)
            loadFunctionLibrary(flib.first, library)
            seen.putAll(flib.second.exposedNames)
        }
        for (flib in inheritedFunctionLibraries.reversed()) {
            val library = flib.second.suppressDuplicates(seen, processor.underlyingConfiguration)
            loadFunctionLibrary(flib.first, library)
            seen.putAll(flib.second.exposedNames)
        }
    }

    // ============================================================

    fun assertContextIsEmpty() {
        if (contextManager is ExecutionContextImpl) {
            contextManager.assertContextIsEmpty()
        }
    }

    // ============================================================

    // This is on the SaxonConfiguration because it needs to use a compatible configuration
    // and I want to cache it somewhere so that it doesn't have to be parsed for *every* expression
    private var _xpathTransformer: Xslt30Transformer? = null
    internal val xpathTransformer: Xslt30Transformer
        get() {
            if (_xpathTransformer == null) {
                val styleStream = SaxonConfiguration::class.java.getResourceAsStream("/com/xmlcalabash/xpath.xsl")
                val styleSource = SAXSource(InputSource(styleStream))
                val xsltCompiler = processor.newXsltCompiler()
                xsltCompiler.isSchemaAware = processor.isSchemaAware
                val xsltExec = xsltCompiler.compile(styleSource)
                _xpathTransformer = xsltExec.load30()
            }
            return _xpathTransformer!!
        }
}