package com.xmlcalabash.datamodel

import com.xmlcalabash.exceptions.XProcError
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.SequenceType
import net.sf.saxon.s9api.XdmAtomicValue
import net.sf.saxon.s9api.XdmValue

class StaticOptionDetails(val stepConfig: StepConfiguration, val name: QName, val asType: SequenceType, val values: List<XdmAtomicValue>, initialStaticValue: XProcExpression) {
    internal var _staticValue = initialStaticValue
    val staticValue: XProcExpression
        get() = _staticValue

    internal fun override(value: XdmValue) {
        if (!asType.matches(value)) {
            throw XProcError.xsValueDoesNotSatisfyType(value.toString(), asType.toString()).exception()
        }

        if (values.isNotEmpty()) {
            if (value !is XdmAtomicValue) {
                throw XProcError.xsValueDoesNotSatisfyType(value.toString(), asType.toString()).exception()
            }
            if (!values.contains(value)) {
                throw XProcError.xsValueDoesNotSatisfyType(value.toString(), values.toString()).exception()
            }
        }

        _staticValue = XProcExpression.constant(stepConfig, value)
    }

    constructor(binding: OptionInstruction): this(binding.stepConfig, binding.name, binding.asType ?: SequenceType.ANY, binding.values, binding.select!!)
    constructor(binding: WithOptionInstruction): this(binding.stepConfig, binding.name, binding.asType ?: SequenceType.ANY, emptyList(), binding.select!!)
    constructor(binding: VariableInstruction): this(binding.stepConfig, binding.name, binding.asType ?: SequenceType.ANY, emptyList(), binding.select!!)
}