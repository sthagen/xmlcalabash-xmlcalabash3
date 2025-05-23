package com.xmlcalabash.steps

import com.xmlcalabash.documents.XProcDocument
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.namespace.Ns
import com.xmlcalabash.runtime.ProcessMatch
import com.xmlcalabash.runtime.ProcessMatchingNodes
import com.xmlcalabash.runtime.parameters.StepParameters
import net.sf.saxon.om.AttributeMap
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.XdmNode
import net.sf.saxon.s9api.XdmNodeKind

class InsertStep(): AbstractAtomicStep(), ProcessMatchingNodes {
    companion object {
        private const val FIRST_CHILD = "first-child"
        private const val LAST_CHILD = "last-child"
        private const val BEFORE = "before"
        private const val AFTER = "after"
    }

    var insertions = mutableListOf<XProcDocument>()
    var pattern = ""
    var position = AFTER
    var _matcher: ProcessMatch? = null
    val matcher: ProcessMatch
        get() = _matcher ?: throw RuntimeException("Configuration error...")

    override fun run() {
        super.run()

        val document = queues["source"]!!.first()
        insertions.addAll(queues["insertion"]!!)

        pattern = stringBinding(Ns.match)!!
        position = stringBinding(Ns.position)!!
        _matcher = ProcessMatch(stepConfig, this, valueBinding(Ns.match).context.inscopeNamespaces)
        matcher.process(document.value as XdmNode, pattern)
        receiver.output("result", XProcDocument.ofXml(matcher.result, stepConfig, document.properties))
    }

    override fun reset() {
        super.reset()
        insertions.clear()
    }

    override fun startDocument(node: XdmNode): Boolean {
        if (position == BEFORE || position == AFTER) {
            throw stepConfig.exception(XProcError.xcBadPosition(pattern, position).at(node))
        }
        if (position == FIRST_CHILD) {
            doInsert()
        }
        return true
    }

    override fun endDocument(node: XdmNode) {
        if (position == LAST_CHILD) {
            doInsert()
        }
        matcher.endDocument()
    }

    override fun startElement(node: XdmNode, attributes: AttributeMap): Boolean {
        if (position == BEFORE) {
            doInsert()
        }

        matcher.addStartElement(node, attributes)

        if (position == FIRST_CHILD) {
            doInsert()
        }

        return true
    }

    override fun attributes(node: XdmNode,
                            matchingAttributes: AttributeMap,
                            nonMatchingAttributes: AttributeMap): AttributeMap? {
        throw stepConfig.exception(XProcError.xcInvalidSelection(pattern, "attribute").at(node))
    }

    override fun endElement(node: XdmNode) {
        if (position == LAST_CHILD) {
            doInsert()
        }

        matcher.addEndElement()

        if (position == AFTER) {
            doInsert()
        }
    }

    override fun text(node: XdmNode) {
        process(node)
    }

    override fun comment(node: XdmNode) {
        process(node)
    }

    override fun pi(node: XdmNode) {
        process(node)
    }

    private fun doInsert() {
        for (insertion in insertions) {
            matcher.addSubtree(insertion.value)
        }
    }

    private fun process(node: XdmNode) {
        if (position == BEFORE) {
            doInsert()
        }

        when (node.nodeKind) {
            XdmNodeKind.COMMENT -> matcher.addComment(node.stringValue)
            XdmNodeKind.PROCESSING_INSTRUCTION -> matcher.addPI(node.nodeName.localName, node.stringValue)
            XdmNodeKind.TEXT -> matcher.addText(node.stringValue)
            else -> throw stepConfig.exception(XProcError.xiImpossibleNodeType(node.nodeKind).at(node))
        }

        if (position == AFTER) {
            doInsert()
        }

        if (position == FIRST_CHILD || position == LAST_CHILD) {
            throw stepConfig.exception(XProcError.xcBadTextPosition(pattern, position).at(node))
        }
    }

    override fun toString(): String = "p:insert"
}