package com.xmlcalabash.runtime.parameters

import com.xmlcalabash.datamodel.AtomicExpressionStepInstruction
import com.xmlcalabash.datamodel.Location
import com.xmlcalabash.namespace.NsCx
import com.xmlcalabash.runtime.api.RuntimeOption
import com.xmlcalabash.runtime.api.RuntimePort
import net.sf.saxon.s9api.QName

class OptionStepParameters(
    stepName: String,
    location: Location,
    inputs: Map<String, RuntimePort>,
    outputs: Map<String, RuntimePort>,
    options: Map<QName, RuntimeOption>,
    val step: AtomicExpressionStepInstruction
): ExpressionStepParameters(stepName, location, inputs, outputs, options, step, NsCx.option) {
    val name = step.externalName
}
