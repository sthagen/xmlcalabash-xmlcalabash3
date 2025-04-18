package com.xmlcalabash.parsers.xpl.elements

import com.xmlcalabash.util.TypeUtils
import com.xmlcalabash.namespace.Ns
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.SequenceType
import net.sf.saxon.s9api.XdmNode

class OptionNode(parent: AnyNode, node: XdmNode, val name: QName, val select: String): ElementNode(parent, node) {
    var visible = true
    var asType: SequenceType? = null

    init {
        visible = node.getAttributeValue(Ns.visibility) != "private"

        val typeStr = node.getAttributeValue(Ns.asType)
        if (typeStr != null) {
            asType = stepConfig.typeUtils.parseSequenceType(typeStr)
        }
    }

    override fun resolveUseWhen(context: UseWhenContext) {
        if (useWhen == false) {
            return
        }

        computeUseWhenOnThisElement(context)

        if (context.staticOptions[this] == null) {
            if (useWhen == true) {
                val value = if (context.builder.staticOptionsManager.useWhenOptions.containsKey(name)) {
                    context.builder.staticOptionsManager.useWhenOptions[name]
                } else {
                    context.resolveExpression(stepConfig, select)
                }

                if (value != null && asType != null) {
                    if (!asType!!.matches(value)) {
                        val typeUtils = TypeUtils(stepConfig)
                        typeUtils.xpathPromote(value, asType!!.itemType.typeName)
                    }
                }

                context.staticOptions[this] = value
            } else {
                useWhen = null // try again next time
                context.staticOptions[this] = null
            }
        }
    }
}