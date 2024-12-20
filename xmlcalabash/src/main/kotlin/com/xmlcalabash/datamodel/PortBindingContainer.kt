package com.xmlcalabash.datamodel

import com.xmlcalabash.exceptions.XProcError
import net.sf.saxon.s9api.QName
import org.apache.logging.log4j.kotlin.logger

open class PortBindingContainer(parent: XProcInstruction, stepConfig: InstructionConfiguration, instructionType: QName): BindingContainer(parent, stepConfig, instructionType) {
    companion object {
        val UNSPECIFIED_PORT_NAME = "*** unspecified port name ***"
    }

    private var _portDefined = false
    val portDefined: Boolean
        get() = _portDefined

    internal var _port: String = UNSPECIFIED_PORT_NAME
        set(value) {
            _portDefined = true
            field = value
        }

    var port: String
        get() = _port
        set(value) {
            checkOpen()
            val pname = stepConfig.parseNCName(value)
            _port = pname
        }

    internal var _sequence: Boolean? = null
    var sequence: Boolean?
        get() = _sequence
        set(value) {
            checkOpen()
            _sequence = value
        }

    internal var _primary: Boolean? = null
    var primary: Boolean?
        get() = _primary
        set(value) {
            checkOpen()
            _primary = value
        }

    internal var weldedShut = false

    internal var _select: XProcExpression? = null
    open var select: XProcExpression?
        get() = _select
        set(value) {
            checkOpen()
            if (value == null) {
                _select = null
            } else {
                _select = value.cast(parent!!.stepConfig.parseSequenceType("item()*"))
                for (name in _select!!.details.variableRefs) {
                    val ref = inscopeVariables[name]
                    if (ref == null) {
                        throw stepConfig.exception(XProcError.xsXPathStaticError(name))
                    }
                    if (ref.canBeResolvedStatically()) {
                        _select!!.setStaticBinding(name, ref.select!!)
                    }
                }
            }
        }

    internal val _contentTypes = mutableListOf<MediaType>()
    var contentTypes: List<MediaType>
        get() = _contentTypes
        set(value) {
            checkOpen()
            _contentTypes.clear()
            _contentTypes.addAll(value)
        }

    internal var _href: XProcExpression? = null
    var href: XProcExpression?
        get() = _href
        set(value) {
            checkOpen()
            if (value == null) {
                _href = null
            } else {
                _href = value.cast(stepConfig.parseXsSequenceType("xs:anyURI"))
            }
        }

    var pipe: String? = null
        set(value) {
            checkOpen()
            field = value
        }

    private var elaborated = false
    override fun elaborateInstructions() {
        if (elaborated) {
            // The order of elaboration matters and sometimes, for example, when manufacturing
            // the "otherwise" clause of a p:if that's been promoted to a p:choose, we need
            // to elaborate a bit as we go even though the whole subtree will be elaborated later
            return
        }
        elaborated = true

        if (href != null && children.isNotEmpty()) {
            throw stepConfig.exception(XProcError.xsHrefAndChildren())
        }

        if (pipe != null && children.isNotEmpty()) {
            throw stepConfig.exception(XProcError.xsPipeAndChildren())
        }

        if (href != null && pipe != null) {
            throw stepConfig.exception(XProcError.xsHrefAndPipe())
        }

        href?.let { promoteHref(it) }
        href = null

        pipe?.let { promotePipe(it) }
        pipe = null

        if (contentTypes.isNotEmpty()) {
            var acceptsSomething = false
            for (ctype in contentTypes) {
                acceptsSomething = acceptsSomething || ctype.inclusive
                if (ctype.mediaType == "*" && ctype.mediaSubtype == "*" && !ctype.inclusive) {
                    acceptsSomething = false
                }
            }
            if (!acceptsSomething) {
                logger.debug { "Content type constraints on port '${port}' excludes all documents" }
            }
        }

        var sawEmpty = false
        var welded = children.filterIsInstance<EmptyInstruction>().isNotEmpty()
        val newChildren = mutableListOf<ConnectionInstruction>()
        for (child in children) {
            child.elaborateInstructions()
            if (child is EmptyInstruction) {
                if (newChildren.isNotEmpty() || sawEmpty) {
                    throw stepConfig.exception(XProcError.xsEmptyNotAllowed())
                }
                sawEmpty = true
            } else {
                if (sawEmpty) {
                    throw stepConfig.exception(XProcError.xsEmptyNotAllowed())
                }
            }
            newChildren.add(child as ConnectionInstruction)
        }

        _children.clear()
        _children.addAll(newChildren)

        // This all seems a bit crude...
        welded = welded && newChildren.isEmpty()
        if (welded) {
            when (this) {
                is InputInstruction -> {
                    when (parent) {
                        is DeclareStepInstruction -> welded = false
                        is CompoundLoopDeclaration -> welded = port != "current"
                        is CatchInstruction -> welded = port != "error"
                        is FinallyInstruction -> welded = port != "error"
                        else -> Unit
                    }
                }
                is WithOutputInstruction -> welded = false
                else -> Unit
            }
        }

        weldedShut = welded
        primary = primary == true
        sequence = sequence == true

        open = false
    }

    override fun toString(): String {
        if (port != UNSPECIFIED_PORT_NAME) {
            return "${instructionType}/${id} port=${port}"
        }
        return "${instructionType}/${id}"
    }
}