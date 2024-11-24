package com.xmlcalabash.steps.validation

import com.thaiopensource.util.PropertyMapBuilder
import com.thaiopensource.validate.SchemaReader
import com.thaiopensource.validate.ValidateProperty
import com.thaiopensource.validate.ValidationDriver
import com.thaiopensource.validate.auto.AutoSchemaReader
import com.thaiopensource.validate.prop.rng.RngProperty
import com.thaiopensource.validate.rng.CompactSchemaReader
import com.xmlcalabash.documents.XProcDocument
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.namespace.Ns
import com.xmlcalabash.steps.AbstractAtomicStep
import com.xmlcalabash.util.S9Api
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.XdmNode
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader

open class ValidateWithRelaxNG(): AbstractAtomicStep() {
    companion object {
        private val dtdAttributeValues = QName("dtd-attribute-values")
        private val dtdIdIdrefWarnings = QName("dtd-id-idref-warnings")
    }

    lateinit var document: XProcDocument
    lateinit var schema: XProcDocument

    override fun input(port: String, doc: XProcDocument) {
        if (port == "source") {
            document = doc
        } else {
            schema = doc
        }
    }

    override fun run() {
        super.run()

        val dtdAttributeValues = booleanBinding(dtdAttributeValues) ?: false
        val dtdIdIdRefWarnings = booleanBinding(dtdIdIdrefWarnings) ?: false
        val assertValid = booleanBinding(Ns.assertValid) ?: true
        val reportFormat = stringBinding(Ns.reportFormat) ?: "xvrl"
        val parameters = qnameMapBinding(Ns.parameters)

        if (reportFormat != "xvrl") {
            throw XProcError.xcUnsupportedReportFormat(reportFormat).exception()
        }

        val report = Errors(stepConfig, reportFormat)
        val listener = CachingErrorListener(report)
        val properties = PropertyMapBuilder()
        properties.put(ValidateProperty.ERROR_HANDLER, listener)
        //properties.put(ValidateProperty.URI_RESOLVER, ...)
        //properties.put(ValidateProperty.ENTITY_RESOLVER, ...)

        if (dtdIdIdRefWarnings) {
            RngProperty.CHECK_ID_IDREF.add(properties)
        }

        val compact = schema.contentType!!.textContentType()
        var sr: SchemaReader? = null
        var schemaInputSource: InputSource? = null

        if (compact) {
            sr = CompactSchemaReader.getInstance()
            // Hack
            val srdr = StringReader(schema.value.underlyingValue.stringValue)
            schemaInputSource = InputSource(srdr)
            schemaInputSource.systemId = schema.baseURI.toString()
        } else {
            sr = AutoSchemaReader()
            schemaInputSource = S9Api.xdmToInputSource(stepConfig, schema)
        }

        var except: XProcError? = null
        var errors: XdmNode? = null
        val driver = ValidationDriver(properties.toPropertyMap(), sr)

        val loaded = try {
            driver.loadSchema(schemaInputSource)
        } catch (ex: Exception) {
            if (document.baseURI == null) {
                throw XProcError.xcNotRelaxNG("Error loading schema").exception(ex)
            }
            throw XProcError.xcNotRelaxNG(document.baseURI!!, "Error loading schema").exception(ex)
        }

        if (!loaded) {
            if (document.baseURI == null) {
                throw XProcError.xcNotRelaxNG("Error loading schema").exception()
            }
            throw XProcError.xcNotRelaxNG(document.baseURI!!, "Error loading schema").exception()
        }

        val din = S9Api.xdmToInputSource(stepConfig, document)
        if (!driver.validate(din)) {
            if (assertValid) {
                errors = report.endErrors()
                for (lex in listener.exceptions) {
                    when (lex) {
                        is SAXParseException -> {
                            // FIXME: errloc
                            if (except == null) {
                                if (document.baseURI == null) {
                                    except = XProcError.xcNotSchemaValidRelaxNG(lex.message ?: "null")
                                } else {
                                    except = XProcError.xcNotSchemaValidRelaxNG(document.baseURI!!, lex.message ?: "null")
                                }
                            }
                        }
                        else -> {
                            if (except == null) {
                                if (document.baseURI == null) {
                                    except = XProcError.xcNotSchemaValidRelaxNG(lex.message ?: "null")
                                } else {
                                    except = XProcError.xcNotSchemaValidRelaxNG(document.baseURI!!, lex.message ?: "null")
                                }
                            }
                        }
                    }
                }
                if (except == null) {
                    if (document.baseURI == null) {
                        except = XProcError.xcNotSchemaValidRelaxNG("RELAX NG validation failed")
                    } else {
                        except = XProcError.xcNotSchemaValidRelaxNG(document.baseURI!!, "RELAX NG validation failed")
                    }
                }

                throw except.exception()
            }
        }

        receiver.output("result", document)
        receiver.output("report", XProcDocument.ofXml(report.endErrors(), stepConfig))
    }

    override fun toString(): String = "p:validate-with-relax-ng"
}