package com.xmlcalabash.graph

import com.xmlcalabash.datamodel.*
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.namespace.NsCx
import com.xmlcalabash.namespace.NsP
import com.xmlcalabash.steps.internal.SelectStep
import net.sf.saxon.s9api.XdmNode
import java.io.PrintStream
import java.util.*

class Graph private constructor() {
    companion object {
        fun build(decl: DeclareStepInstruction): PipelineModel {
            val graph = Graph()
            return graph.build(decl)
        }
    }

    internal val models = mutableListOf<Model>()
    internal val instructionMap = mutableMapOf<XProcInstruction, Model>()
    internal val edges = mutableListOf<Edge>()
    internal val connections = mutableListOf<Connection>()
    internal var cacheCount = 0L
    private var edgeNumber = 0L

    private var pipelineNode: PipelineModel? = null
    private var _graphXml: XdmNode? = null
    val description: XdmNode?
        get() {
            if (_graphXml == null && pipelineNode != null) {
                _graphXml =  GraphVisualization.build(this, pipelineNode!!)
            }
            return _graphXml
        }

    private fun build(pipeline: DeclareStepInstruction): PipelineModel {
        val node = PipelineModel(this, null, pipeline)
        createModels(node)
        node.init()

        addCaches()

        val loop = loops()
        if (loop != null) {
            throw XProcError.xsLoop(loop).exception()
        }

        addSplitters()
        addJoiners()
        patchEdges()
        node.decompose()
        makeConnections()

        pipelineNode = node
        return node
    }

    private fun createModels(node: CompoundModel) {
        for (child in (node.step as CompoundStepDeclaration).children.filterIsInstance<StepDeclaration>()) {
            val cnode = when (child) {
                is CompoundStepDeclaration -> CompoundModel(this, node, child)
                is AtomicExpressionStepInstruction -> {
                    AtomicModel(this, node, child as AtomicStepInstruction)
                    /*
                    if (child.externalName == null) {
                        AtomicModel(this, node, child as AtomicStepInstruction)
                    } else {
                        AtomicOptionModel(this, node, child)
                    }
                     */
                }
                else -> AtomicModel(this, node, child as AtomicStepInstruction)
            }
            node._children.add(cnode)
            instructionMap[child] = cnode

            if (cnode is CompoundModel) {
                createModels(cnode)
            }
        }
    }

    private fun loops(): String? {
        for (node in models) {
            val loop = loop(node)
            if (loop != null) {
                return loop
            }
        }
        return null
    }

    private fun loop(model: Model, visited: Stack<Model> = Stack()): String? {
        if (visited.contains(model)) {
            val sbx = StringBuilder()
            var first = true
            var found = false
            for (snode in visited) {
                found = found || model == snode
                if (found) {
                    if (!first) {
                        sbx.append(" -> ")
                    }
                    sbx.append(snode)
                    first = false
                }
            }
            sbx.append(" -> ")
            sbx.append(model)
            return sbx.toString()
        }
        visited.push(model)
        for (edge in edges.filter { it.from == model }) {
            val loop = loop(edge.to, visited)
            if (loop != null) {
                return loop
            }
        }
        visited.pop()
        return null
    }

    internal fun addEdge(from: Model, outputPort: String, to: Model, inputPort: String) {
        val edge = Edge(edgeNumber++, from, outputPort, to, inputPort)
        edges.add(edge)
    }

    internal fun addEdge(number: Long, from: Model, outputPort: String, to: Model, inputPort: String) {
        val edge = Edge(number, from, outputPort, to, inputPort)
        edges.add(edge)
    }

    private fun replaceEdge(existing: Edge, from: Model, outputPort: String, to: Model, inputPort: String) {
        val edge = Edge(existing.number, from, outputPort, to, inputPort)
        edges.add(edge)
    }

    private fun intermediateNodes(from: Model, to: Model): List<Model> {
        val modelSequence = mutableListOf<Model>()
        var node = to.parent!!
        while (node != from) {
            modelSequence.add(0, node)
            node = node.parent!!

            if ((node as CompoundModel).children.filter { it === from }.isNotEmpty()) {
                return modelSequence
            }
        }
        return modelSequence
    }

    private fun patchEdges() {
        val currentEdges = mutableListOf<Edge>()
        currentEdges.addAll(edges)

        for (edge in currentEdges) {
            if (edge.to.parent == edge.from) {
                // We're reading from an input
                val head = (edge.from as CompoundModel).head
                replaceEdge(edge, head, edge.outputPort, edge.to, edge.inputPort)
                edges.remove(edge)
            }
        }
    }

    private fun addCaches() {
        var changed = true
        while (changed) {
            changed = false

            val currentEdges = mutableListOf<Edge>()
            currentEdges.addAll(edges)

            //val newEdges = mutableListOf<Edge>()
            edges.clear()

            for (edge in currentEdges) {
                var from = edge.from
                val to = edge.to
                if (from.parent == to.parent) {
                    // edge between siblings
                    edges.add(edge)
                    continue
                }
                if (from == to.parent) {
                    // edge from parent to child
                    edges.add(edge)
                    continue
                }

                changed = true

                val nodeSequence = intermediateNodes(from, to)
                var fromPort = edge.outputPort
                for (node in nodeSequence) {
                    fromPort = cacheEdge(edge, from, fromPort, node as CompoundModel)
                    from = node
                }

                replaceEdge(edge, from, fromPort, edge.to, edge.inputPort)

                // If there are any other steps in the "from" container
                // that also read from the original port, also update them.



            }
        }
    }

    private fun inputEdges(to: Model): Set<String> {
        val portNames = mutableSetOf<String>()
        for (edge in edges.filter { it.to == to }) {
            portNames.add(edge.inputPort)
        }
        return portNames
    }

    private fun inputEdges(to: Model, toPort: String): List<Edge> {
        return edges.filter { it.to == to && it.inputPort == toPort }
    }

    private fun outputEdges(from: Model): Set<String> {
        val portNames = mutableSetOf<String>()
        for (edge in edges.filter { it.from == from }) {
            portNames.add(edge.outputPort)
        }
        return portNames
    }

    private fun outputEdges(from: Model, fromPort: String): List<Edge> {
        return edges.filter { it.from == from && it.outputPort == fromPort }
    }

    private fun cacheEdge(existing: Edge, from: Model, fromPort: String, model: CompoundModel): String {
        var existingCacheEdge: Edge? = null
        for (edge in outputEdges(from, fromPort)) {
            if (edge.to == model && edge.inputPort.startsWith("!cache_")) {
                existingCacheEdge = edge
                break

            }
        }

        if (existingCacheEdge == null) {
            val toPort = if (fromPort.startsWith("!cache_")) {
                //val pos = fromPort.indexOf('/')
                //"!cache_${++cacheCount}/${fromPort.substring(pos+1)}"
                fromPort
            } else {
                "!cache_${++cacheCount}/${fromPort}"
            }

            model.inputs[toPort] = ModelPort(model, toPort, false, false, true, listOf())
            model.head.outputs[toPort] = ModelPort(model, toPort, false, false, true, listOf())
            replaceEdge(existing, from, fromPort, model, toPort)
            return toPort
        }

        return existingCacheEdge.inputPort
    }

    private fun addSplitters() {
        val currentModels = mutableListOf<Model>()
        currentModels.addAll(models)

        for (node in currentModels) {
            for (port in outputEdges(node)) {
                val currentEdges = mutableListOf<Edge>()
                val nodeEdges = outputEdges(node, port)
                if (nodeEdges.size > 1) {
                    currentEdges.addAll(nodeEdges)
                    val pnode = currentEdges.first().to.parent!!
                    val step = Splitter(pnode.step)
                    //pnode.step._children.add(step)

                    val splitter = AtomicModel(this, pnode, step)
                    (pnode as CompoundModel)._children.add(splitter)
                    splitter.init()

                    splitter.inputs["source"] = ModelPort(splitter, "source", false, false, true, listOf())
                    for ((resultNumber, edge) in currentEdges.withIndex()) {
                        val fromPort = "result${resultNumber + 1}"
                        splitter.outputs[fromPort] = ModelPort(splitter, fromPort, false, false, true, listOf())
                        addEdge(edge.number, splitter, fromPort, edge.to, edge.inputPort)
                    }

                    val from = currentEdges.first().from
                    addEdge(from, currentEdges.first().outputPort, splitter, "source")
                    for (edge in currentEdges) {
                        edges.remove(edge)
                    }
                }
            }
        }
    }

    private fun addJoiners() {
        val currentModels = mutableListOf<Model>()
        currentModels.addAll(models)
        for (model in models.filterIsInstance<CompoundModel>()) {
            if (model is PipelineModel) {
                currentModels.add(model.foot)
            } else {
                // Don't put a joiner before a foot that is only ever expected to get output
                // from exactly one step. But *do* put one before a foot that doesn't have
                // that expectation!
                when (model.step.instructionType) {
                    NsP.`if`, NsP.choose -> Unit
                    NsP.`try` -> Unit
                    else -> currentModels.add(model.foot)
                }
            }
        }

        for (node in currentModels) {
            for (port in inputEdges(node)) {
                val currentEdges = mutableListOf<Edge>()
                val nodeEdges = inputEdges(node, port).sortedBy { it.number }
                if (nodeEdges.size > 1) {
                    currentEdges.addAll(nodeEdges)
                    val pnode = currentEdges.first().to.parent!!
                    val step = Joiner(pnode.step)
                    //pnode.step._children.add(step)

                    val joiner = AtomicModel(this, pnode, step)
                    (pnode as CompoundModel)._children.add(joiner)
                    joiner.init()

                    joiner.outputs["result"] = ModelPort(joiner, "result", false, false, true, listOf())
                    for ((sourceNumber, edge) in currentEdges.withIndex()) {
                        val toPort = "result${sourceNumber + 1}"
                        joiner.inputs[toPort] = ModelPort(joiner, toPort, false, false, true, listOf())
                        addEdge(edge.from,  edge.outputPort, joiner, toPort)
                    }
                    addEdge(joiner, "result", currentEdges.first().to, currentEdges.first().inputPort)
                    for (edge in currentEdges) {
                        edges.remove(edge)
                    }
                }
            }
        }
    }

    private fun makeConnections() {
        for (edge in edges) {
            val from = edge.from.outputs[edge.outputPort]
            if (from == null) {
                throw RuntimeException("bang")
            }
            val to = edge.to.inputs[edge.inputPort]!!
            val conn = Connection(from, to)
            connections.add(conn)
        }
    }

    private fun identifyGuards() {
        for ((instruction, node) in instructionMap) {
            if (instruction is WhenInstruction) {
                identifyGuards((node as SubpipelineModel).model)
            }
        }
    }

    private fun identifyGuards(node: CompoundModel) {
        val guardSteps = mutableSetOf<Model>()
        val candidates = mutableSetOf<Model>()
        val guard = node.children.filter { it is AtomicModel && it.step.instructionType == NsCx.guard }.first()
        candidates.add(guard)

        while (candidates.isNotEmpty()) {
            val candidate = candidates.first()
            guardSteps.add(candidate)
            candidates.remove(candidate)

            for (edge in edges) {
                if (edge.to === candidate && edge.from.parent == node) {
                    candidates.add(edge.from)
                }
            }
        }
    }
}