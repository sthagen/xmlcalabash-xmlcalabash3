package com.xmlcalabash.datamodel

import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.namespace.Ns
import com.xmlcalabash.namespace.NsP
import com.xmlcalabash.namespace.NsXs
import com.xmlcalabash.util.ValueTemplateFilter
import com.xmlcalabash.util.ValueTemplateFilterNone
import com.xmlcalabash.util.ValueTemplateFilterXml
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.XdmMap
import net.sf.saxon.s9api.XdmNode
import net.sf.saxon.s9api.XdmValue
import java.lang.IllegalStateException

class InlineInstruction(parent: XProcInstruction, xmlDocument: XdmNode): ConnectionInstruction(parent, NsP.inline) {
    private var _xml: XdmNode = xmlDocument
    var xml: XdmNode
        get() = _xml
        set(value) {
            checkOpen()
            _xml = value
        }

    private var _contentType: MediaType? = null
    var contentType: MediaType?
        get() = _contentType
        set(value) {
            checkOpen()
            _contentType = value
        }

    private var _documentProperties: XProcExpression? = null
    var documentProperties: XProcExpression
        get() = _documentProperties!!
        set(value) {
            checkOpen()
            _documentProperties = value.cast(parent!!.stepConfig.qnameMapType)
        }

    var encoding: String? = null
        set(value) {
            checkOpen()
            field = value
        }

    private val variables = mutableSetOf<QName>()
    private lateinit var _valueTemplateFilter: ValueTemplateFilter
    val valueTemplateFilter: ValueTemplateFilter
        get() = _valueTemplateFilter

    override fun elaborateInstructions() {
        if (_documentProperties == null) {
            val stype = "map(Q{${NsXs.namespace}}QName, item()*)"
            _documentProperties = XProcExpression.constant(parent!!.stepConfig, XdmMap(), parent!!.stepConfig.parseSequenceType(stype))
        }

        val isRunPipeline = false // = parent != null && parent!!.parent is RunBuilder

        // Force inlines to have unique URIs because document-uri(). Bleh.
        val uri = xml.baseURI?.toString() ?: ""
        val inlineBaseUri = stepConfig.uniqueUri(uri)

        if (contentType == null) {
            _contentType = MediaType.XML
        }

        if (encoding != null) {
            if (encoding != "base64") {
                throw XProcError.xsUnsupportedEncoding(encoding!!).exception()
            }
            if (contentType!!.xmlContentType() || contentType!!.htmlContentType()) {
                throw XProcError.xdEncodingWithXmlOrHtml(encoding!!).exception()
            }
        }

        _valueTemplateFilter = if (encoding == null && !isRunPipeline) {
            ValueTemplateFilterXml(stepConfig, xml, inlineBaseUri)
        } else {
            ValueTemplateFilterNone(stepConfig, xml, inlineBaseUri)
        }

        val staticBindings = mutableMapOf<QName, XProcExpression>()
        for ((name, value) in stepConfig.inscopeVariables) {
            if (value.canBeResolvedStatically()) {
                val details = builder.staticOptionsManager.get(value)
                staticBindings[name] = value.select!!
                documentProperties.setStaticBinding(name, details.staticValue)
            }
        }

        _xml = _valueTemplateFilter.expandStaticValueTemplates(expandText!!, staticBindings)

        val usesContext = _valueTemplateFilter.usesContext()
        variables.addAll(_valueTemplateFilter.usesVariables())

        if (usesContext) {
            val wi = WithInputInstruction(this, stepConfig)
            wi.port = "source"
            _children.add(wi)
            wi.pipe()
        }

        super.elaborateInstructions()
    }

    override fun promoteToStep(parent: StepDeclaration, step: StepDeclaration): List<AtomicStepInstruction> {
        val newSteps = mutableListOf<AtomicStepInstruction>()

        val inlineStep = AtomicInlineStepInstruction(this, valueTemplateFilter)
        stepConfig.addVisibleStepName(inlineStep)
        inlineStep.depends.addAll(step.depends)

        for (name in variables) {
            val binding = stepConfig.inscopeVariables[name] ?: throw XProcError.xsXPathStaticError(name).exception()
            if (!binding.canBeResolvedStatically()) {
                val eqname = "Q{${name.namespaceUri}}${name.localName}"
                val wi = WithInputInstruction(this, stepConfig)
                wi._port = eqname
                val pipe = wi.pipe()
                val vbuilder = when (binding) {
                    is VariableInstruction -> binding.exprStep!!
                    is OptionInstruction -> binding.exprStep!!
                    else -> throw IllegalStateException("Invalid binding: ${binding}")
                }
                pipe.setReadablePort(vbuilder.primaryOutput()!!)
                inlineStep._children.add(wi)
            }
        }

        if (documentProperties.canBeResolvedStatically()) {
            inlineStep._staticOptions[Ns.documentProperties] = StaticOptionDetails(stepConfig.copy(), Ns.documentProperties, stepConfig.qnameMapType, emptyList(), documentProperties)
        } else {
            val context = if (stepConfig.drp == null) {
                emptyList<PortBindingContainer>()
            } else {
                listOf(stepConfig.drp!!)
            }
            val exprSteps = documentProperties.promoteToStep(parent, context, false)
            val expr = exprSteps.last()

            val wi = inlineStep.withInput()
            wi._port = "Q{}document-properties"
            val pipe = wi.pipe()
            pipe.setReadablePort(expr.primaryOutput()!!)
            newSteps.addAll(exprSteps)
        }

        // If there's no DRP, make sure there's an empty binding for the inlineStep
        // (If there's supposed to be a context binding, staticAnalysis() has inserted
        // an empty WithInput to hold the binding to the drp.)
        if (stepConfig.drp == null || children.isEmpty()) {
            //val emptyStep = addRewriteEmpty(parent)
            //newSteps.add(emptyStep)
            //inlineStep.stepConfig.addVisibleStepName(emptyStep)

            val wi = inlineStep.withInput()
            wi.port = "source"
            wi.primary = true
            wi.sequence = true
            wi.weldedShut = true
            //val pipe = wi.pipe()
            //pipe.setReadablePort(emptyStep.primaryOutput()!!)
        } else {
            val bindingChildren = if (children.isNotEmpty()) {
                children.first().children
            } else {
                emptyList()
            }

            if (bindingChildren.isNotEmpty()) {
                val children = children.first().children
                val wi = inlineStep.withInput()
                wi.port = "source"
                for (child in bindingChildren) {
                    when (child) {
                        is PipeInstruction -> {
                            val pipe = wi.pipe()
                            pipe.setReadablePort(child.readablePort!!)
                        }
                        else -> {
                            val csteps = (child as ConnectionInstruction).promoteToStep(parent, step)
                            if (csteps.isNotEmpty()) {
                                val last = csteps.last()
                                val pipe = wi.pipe()
                                pipe.setReadablePort(last.primaryOutput()!!)
                            }
                            newSteps.addAll(csteps)
                        }
                    }
                }
            }
        }

        inlineStep.elaborateAtomicStep()

        val output = inlineStep.primaryOutput()!!
        output._contentTypes.clear()
        if (inlineStep.contentType == null) {
            output._contentTypes.addAll(MediaType.XML_OR_HTML)
        } else {
            output._contentTypes.addAll(listOf(inlineStep.contentType!!))
        }

        newSteps.add(inlineStep)
        return newSteps
    }
}