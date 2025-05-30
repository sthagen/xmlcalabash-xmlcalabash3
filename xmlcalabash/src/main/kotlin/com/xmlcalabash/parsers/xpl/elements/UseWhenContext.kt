package com.xmlcalabash.parsers.xpl.elements

import com.xmlcalabash.datamodel.InstructionConfiguration
import com.xmlcalabash.datamodel.PipelineBuilder
import com.xmlcalabash.exceptions.XProcError
import net.sf.saxon.s9api.*
import net.sf.saxon.trans.XPathException
import net.sf.saxon.value.BooleanValue

class UseWhenContext internal constructor(val builder: PipelineBuilder) {
    companion object {
        private var id = 0
    }
    var unknownStepTypes = false
    val staticOptions = mutableMapOf<OptionNode, XdmValue?>()
    val stepTypes = mutableMapOf<QName, StepImplementation>()
    val useWhen = mutableSetOf<ElementNode>()
    var resolvedCount = 0
    val contextId = ++id

    init {
        val environment = builder.stepConfig
        for ((type, decl) in environment.standardSteps) {
            stepTypes[type] = StepImplementation(true, { environment.atomicStepAvailable(type) })
        }
    }

    fun copy(): UseWhenContext {
        val context = UseWhenContext(builder)
        context.unknownStepTypes = unknownStepTypes
        context.staticOptions.putAll(staticOptions)
        context.stepTypes.putAll(stepTypes)
        context.useWhen.clear()
        context.resolvedCount = 0
        return context
    }

    fun resolveExpression(stepConfig: InstructionConfiguration, expr: String): XdmValue? {
        return resolveExpressionInternal(stepConfig, expr, false)
    }

    fun resolveUseWhen(stepConfig: InstructionConfiguration, expr: String): Boolean? {
        val result = resolveExpressionInternal(stepConfig, expr, true)
        if (result != null) {
            return (result.underlyingValue as BooleanValue).booleanValue
        }
        return null
    }

    private fun resolveExpressionInternal(stepConfig: InstructionConfiguration, expr: String, ebv: Boolean): XdmValue? {
        val context = ConditionalExecutionContext(stepConfig, this)
        stepConfig.saxonConfig.setExecutionContext(context)
        try {
            val selector = makeSelector(stepConfig, expr)
            if (ebv) {
                val result = selector.effectiveBooleanValue()
                return XdmAtomicValue(result)
            } else {
                val result = selector.evaluate()
                return result
            }
        } catch (ex: Exception) {
            when (ex) {
                is ConditionalStepException -> Unit
                is SaxonApiException -> {
                    if (!conditionalOption(stepConfig, ex)) {
                        throw stepConfig.exception(XProcError.xsXPathStaticError(ex.message ?: ""), ex)
                    }
                }
                else -> Unit // Just assume this will work better next time...
            }
        } finally {
            stepConfig.saxonConfig.releaseExecutionContext()
        }
        return null
    }

    private fun makeSelector(stepConfig: InstructionConfiguration, expr: String): XPathSelector {
        val processor = stepConfig.saxonConfig.processor

        val compiler = stepConfig.newXPathCompiler()
        compiler.baseURI = stepConfig.baseUri
        for ((option, value) in staticOptions) {
            if (value != null) {
                compiler.declareVariable(option.name)
            }
        }

        val selector = compiler.compile(expr).load()
        for ((option, value) in staticOptions) {
            if (value != null) {
                selector.setVariable(option.name, value)
            }
        }

        return selector
    }

    private fun conditionalOption(stepConfig: InstructionConfiguration, ex: SaxonApiException): Boolean {
        val cause = ex.cause
        val message = ex.message
        if (cause is XPathException && message != null) {
            val code = cause.showErrorCode()
            if (code == "XPST0008") {
                val pos = message.indexOf("\$")
                if (pos > 0) {
                    val name = message.substring(pos+1)
                    val qname = if (name.startsWith("{")) {
                        // What fresh hell is this? The message contains "${uri}local"!?
                        val cpos = name.indexOf("}")
                        QName("", name.substring(1, cpos), name.substring(cpos+1))
                    } else {
                        stepConfig.typeUtils.parseQName(name)
                    }
                    for ((option, _) in staticOptions) {
                        if (option.name == qname) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    override fun toString(): String {
        return "UseWhenContext: ${contextId}"
    }
}