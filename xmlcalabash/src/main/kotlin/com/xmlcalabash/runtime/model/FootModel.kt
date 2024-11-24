package com.xmlcalabash.runtime.model

import com.xmlcalabash.graph.Model
import com.xmlcalabash.runtime.XProcRuntime
import com.xmlcalabash.runtime.RuntimeStepConfiguration
import com.xmlcalabash.runtime.steps.AbstractStep

class FootModel(runtime: XProcRuntime, model: Model): StepModel(runtime, model) {
    override fun initialize(model: Model) {
        // nop
    }

    override fun runnable(yconfig: RuntimeStepConfiguration): () -> AbstractStep {
        throw UnsupportedOperationException("You can't make a runnable from a foot")
    }

    override fun toString(): String {
        return "(foot)"
    }
}