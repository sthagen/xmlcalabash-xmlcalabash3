package com.xmlcalabash.datamodel

import com.xmlcalabash.documents.XProcDocument
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.parsers.XPathExpressionDetails
import com.xmlcalabash.parsers.xpath31.XPathExpressionParser
import com.xmlcalabash.util.ValueTemplate
import com.xmlcalabash.util.ValueTemplateParser
import net.sf.saxon.s9api.*

abstract class XProcExpression(val stepConfig: StepConfiguration, val asType: SequenceType, val collection: Boolean, val values: List<XdmAtomicValue>) {
    companion object {
        fun select(stepConfig: StepConfiguration, select: String, asType: SequenceType = SequenceType.ANY, collection: Boolean = false, values: List<XdmAtomicValue> = emptyList()): XProcSelectExpression {
            val expr = XProcSelectExpression.newInstance(stepConfig, select, asType, collection, values)
            expr._details = XPathExpressionParser(stepConfig).parse(select)

            if (expr.details.error != null) {
                throw XProcError.xsXPathStaticError(expr.details.error?.message ?: "").exception(expr.details.error!!)
            }

            return expr
        }

        fun avt(stepConfig: StepConfiguration, avt: String, asType: SequenceType = SequenceType.ANY, values: List<XdmAtomicValue> = emptyList()): XProcAvtExpression {
            return avt(stepConfig, ValueTemplateParser.parse(avt), asType, values)
        }

        internal fun avt(stepConfig: StepConfiguration, avt: ValueTemplate, asType: SequenceType = SequenceType.ANY, values: List<XdmAtomicValue> = emptyList()): XProcAvtExpression {
            val expr = XProcAvtExpression.newInstance(stepConfig, avt, asType, values)

            val parser = XPathExpressionParser(stepConfig)
            var error: Exception? = null
            var context = false
            var alwaysDynamic = false
            val variables = mutableSetOf<QName>()
            val functions = mutableSetOf<Pair<QName,Int>>()
            for (avtExpr in avt.expressions()) {
                val details = parser.parse(avtExpr)
                error = error ?: details.error
                context = context || details.contextRef
                alwaysDynamic = alwaysDynamic || details.alwaysDynamic
                variables.addAll(details.variableRefs)
                functions.addAll(details.functionRefs)
            }

            expr._details = XPathExpressionDetails(error, variables, functions, context, alwaysDynamic)
            return expr
        }

        fun constant(stepConfig: StepConfiguration, value: XdmValue, asType: SequenceType = SequenceType.ANY, values: List<XdmAtomicValue> = emptyList()): XProcConstantExpression {
            val expr = XProcConstantExpression.newInstance(stepConfig, value, asType, values)
            expr._details = XPathExpressionDetails(error = null, variableRefs = emptySet(), functionRefs = emptySet(), contextRef = false, alwaysDynamic = false)
            return expr
        }

        fun error(stepConfig: StepConfiguration): XProcErrorExpression {
            return XProcErrorExpression.newInstance(stepConfig)
        }
    }

    protected lateinit var _details: XPathExpressionDetails
    val details: XPathExpressionDetails
        get() = _details

    protected val staticVariableBindings = mutableMapOf<QName, XProcExpression>()
    protected val variableBindings = mutableMapOf<QName, XdmValue>()

    private var _contextItem: Any? = null
    var contextItem: Any?
        get() = _contextItem
        set(value) {
            if (value == null || value is XdmValue || value is XProcDocument) {
                _contextItem = value
            } else {
                throw XProcError.xiImpossible("Context item must be a value or a document").exception()
            }
        }

    private var _defaultCollection = mutableListOf<XProcDocument>()
    val defaultCollection: List<XProcDocument>
        get() = _defaultCollection

    val variableRefs: Set<QName>
        get() = details.variableRefs

    val functionRefs: Set<Pair<QName,Int>>
        get() = details.functionRefs

    val contextRef: Boolean
        get() = details.contextRef

    val alwaysDynamic: Boolean
        get() = details.alwaysDynamic

    fun canBeResolvedStatically(): Boolean {
        if (details.error != null || details.alwaysDynamic || details.contextRef) {
            return false
        }

        for (name in details.variableRefs) {
            if (!staticVariableBindings.contains(name)) {
                return false
            }
        }

        return true
    }

    protected var checkedStatic = false
    internal var _staticValue: XdmValue? = null
    val staticValue: XdmValue?
        get() = _staticValue

    abstract fun xevaluate(): () -> XdmValue
    abstract fun evaluate(): XdmValue
    abstract fun cast(asType: SequenceType, values: List<XdmAtomicValue> = emptyList()): XProcExpression

    internal open fun computeStaticValue(stepConfig: StepConfiguration): XdmValue? {
        if (checkedStatic) {
            return staticValue
        }
        checkedStatic = true

        for (name in variableRefs) {
            val variable = stepConfig.inscopeVariables[name]
            if (variable == null) {
                throw XProcError.xsXPathStaticError(name).exception()
            } else {
                if (variable.canBeResolvedStatically()) {
                    setStaticBinding(name, variable.select!!)
                }
            }
        }

        // The expression can be resolved statically if it doesn't refer to a collection,
        // doesn't have any bindings, and can be resolved statically.
        if (canBeResolvedStatically()) {
            try {
                _staticValue = evaluate()
            } catch (ex: SaxonApiException) {
                throw XProcError.xsXPathStaticError(ex.message!!).exception()
            }
        }

        return staticValue
    }

    internal fun promoteToStep(step: XProcInstruction, inputContext: List<PortBindingContainer>? = null, explicitBinding: Boolean): List<AtomicStepInstruction> {
        val newSteps = mutableListOf<AtomicStepInstruction>()

        val exprStep = AtomicExpressionStepInstruction(step, this)
        for (name in variableRefs) {
            val variable = step.inscopeVariables[name]!!
            if (variable.canBeResolvedStatically()) {
                exprStep._staticOptions[name] = StaticOptionDetails(stepConfig, variable.name, variable.asType!!, emptyList(), variable.select!!)
            } else {
                val wi = exprStep.withInput()
                wi._port = "Q{${name.namespaceUri}}${name.localName}"
                wi.sequence = true
                val pipe = wi.pipe()
                pipe.setReadablePort(variable.exprStep!!.primaryOutput()!!)
            }
        }

        if ((contextRef || explicitBinding) && (inputContext == null || inputContext.isNotEmpty())) {
            val wi = exprStep.withInput()
            wi.port = "source"
            if (inputContext == null) {
                val pipe = wi.pipe()
                pipe.setReadablePort(step.stepConfig.drp!!)
            } else {
                for (esource in inputContext) {
                    val pipe = wi.pipe()
                    pipe.setReadablePort(esource)
                }
            }
        }

        exprStep.elaborateAtomicStep()

        newSteps.add(exprStep)
        return newSteps
    }

    fun reset() {
        variableBindings.clear()
        contextItem = null
        _defaultCollection.clear()
    }

    fun defaultCollection(items: List<XProcDocument>) {
        _defaultCollection.clear()
        _defaultCollection.addAll(items)
    }

    fun setBinding(name: QName, value: XdmValue) {
        if (variableRefs.contains(name)) {
            variableBindings[name] = value
        }
    }
    fun setStaticBinding(name: QName, value: XProcExpression) {
        if (variableRefs.contains(name)) {
            staticVariableBindings[name] = value
        }
    }

    protected fun setupExecutionContext(selector: XPathSelector) {
        // If this is being called during compilation, there won't be a thread-local one yet
        val execContext = stepConfig.newExecutionContext(stepConfig)

        for (name in variableRefs) {
            val value = if (name in variableBindings) {
                variableBindings[name]!!
            } else {
                staticVariableBindings[name]?.evaluate()
            }
            if (value != null) {
                selector.setVariable(name, value)
            }
        }

        when (contextItem) {
            null -> Unit
            is XdmItem -> {
                selector.contextItem = contextItem as XdmItem
            }
            is XProcDocument -> {
                val doc = contextItem as XProcDocument
                val value = doc.value

                if (value is XdmItem) {
                    selector.contextItem = value
                }
                stepConfig.addProperties(doc)
            }
        }
    }

    protected fun teardownExecutionContext() {
        if (contextItem is XProcDocument && (contextItem as XProcDocument).value is XdmItem) {
            stepConfig.removeProperties((contextItem as XProcDocument))
        }

        for ((_, doc) in variableBindings) {
            if (doc is XProcDocument) {
                stepConfig.removeProperties(doc)
            }
        }

        stepConfig.releaseExecutionContext()
    }
}