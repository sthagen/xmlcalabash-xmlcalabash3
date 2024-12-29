package com.xmlcalabash.app

import com.xmlcalabash.api.Monitor
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.exceptions.XProcException
import com.xmlcalabash.util.SchematronAssertions
import com.xmlcalabash.util.UriUtils
import com.xmlcalabash.util.Verbosity
import com.xmlcalabash.visualizers.Detail
import com.xmlcalabash.visualizers.Plain
import com.xmlcalabash.visualizers.Silent
import net.sf.saxon.om.NamespaceUri
import java.io.File
import java.net.URI

/**
 * Command line parser.
 *
 * This class parses an array of strings (as might appear as arguments on the command line). The resulting
 * object has properties for all the options provided. If an option is not provided, and it is not required,
 * the value of the corresponding property will be `null`.
 *
 * @param args The array of command line arguments to parse.
 */

class CommandLine private constructor(val args: Array<out String>) {
    /**
     * Provides a static method to create a [CommandLine] from a list of arguments.
     */
    companion object {
        /**
         * Parse a set of arguments.
         *
         * The caller must check the [errors] property. If it is empty, the parse was successful.
         */
        fun parse(args: Array<out String>): CommandLine {
            val cli = CommandLine(args)
            cli.parse()
            return cli
        }
    }

    private val validCommands = listOf("version", "run", "help")
    private var _command: String = "run"
    private var _config: File? = null
    private var _pipelineGraph: String? = null
    private var _licensed = true
    private var _pipelineDescription: String? = null
    private var _debug: Boolean? = null
    private var _debugger = false
    private var _visualizer: Monitor? = null
    private var _verbosity: Verbosity? = null
    private var _assertions = SchematronAssertions.IGNORE
    private var _help = false
    private var _trace: File? = null
    private var _traceDocuments: File? = null
    private var _errors = mutableListOf<XProcException>()
    private var _inputs = mutableMapOf<String, MutableList<URI>>()
    private var _outputs = mutableMapOf<String, OutputFilename>()
    private var _options = mutableMapOf<String,List<String>>()
    private var _namespaces = mutableMapOf<String, NamespaceUri>()
    private var _initializers = mutableListOf<String>()
    private var _pipeline: File? = null
    private var _step: String? = null

    /** The command. */
    val command: String
        get() = _command

    /** The configuration file specified. */
    val config: File?
        get() = _config

    /** The pipeline graph description output filename. */
    val pipelineGraph: String?
        get() = _pipelineGraph

    /** Enable licensed features?
     * <p>Setting licensed to false will disable licensed features in Saxon PE and Saxon EE.</p>
     */
    val licensed: Boolean
        get() = _licensed

    /** The pipeline description output filename. */
    val pipelineDescription: String?
        get() = _pipelineDescription

    /** Enable debugging output? Adjust the logging level accordingly. */
    val debug: Boolean?
        get() = _debug

    /** How chatty shall we be? */
    val verbosity: Verbosity?
        get() = _verbosity

    /** Did we select a visualizer? */
    val visualizer: Monitor?
        get() = _visualizer

    /** Evaluate assertions? */
    val assertions: SchematronAssertions
        get() = _assertions

    /** Display command line help instructions?
     *
     * If this is true, a summary of the command line options will be presented,
     * but no pipeline will be run.
     */
    val help: Boolean
        get() = _help || _command == "help"

    /** Did any parse errors occur? */
    val errors: List<XProcException>
        get() = _errors

    /** Did the user specify a trace output file? */
    val trace: File?
        get() = _trace

    /** Did the user specify a trace documents directory? */
    val traceDocuments: File?
        get() = _traceDocuments

    /** Enable the debugger? */
    val debugger: Boolean
        get() = _debugger

    /** The pipeline inputs.
     * <p>The inputs are a map from input port name to a (list of) URI(s).</p>
     */
    val inputs: Map<String,List<URI>>
        get() = _inputs

    /** The pipeline outputs.
     * <p>The outputs are a map from output port name to output filenames.
     * The [OutputFilename] class is responsible for determining how multiple outputs
     * on a single port are serialized.</p>
     */
    val outputs: Map<String,OutputFilename>
        get() = _outputs

    /** The pipeline options.
     * <p>This is a map from option names to values. The option names must be QNames
     * expressed as <code><a href="https://www.w3.org/TR/xpath-31/#doc-xpath31-EQName">EQName</a></code>s
     * or using namespace bindings provided by the <code>--namespace</code> options.</p>
     * <p>If the value begins with a <code>?</code>, the value after the leading question mark
     * will be evaluated as an XPath expression and the resulting value becomes the value of the option.
     * Otherwise, the value of the option is the string value as an <code>xs:untypedAtomic</code>.</p>
     * <p>If an option is given multiple times, it will have a value that is the sequence of values
     * provided.</p>
     */
    val options: Map<String, List<String>>
        get() = _options

    /** In-scope namespace declarations for argument parsing. */
    val namespaces: Map<String, NamespaceUri>
        get() = _namespaces

    /** Function initializers (--init initializers for Saxon) */
    val initializers: List<String>
        get() = _initializers

    /** The pipeline or library document containing the pipeline to run. */
    val pipeline: File?
        get() = _pipeline

    /** The step (in a library) to run.
     * <p>If the [pipeline] is an <code>p:library</code>, the [step] option identifies
     * a step in the library to run. The step is identified by name, not by type.</p>
     */
    val step: String?
        get() = _step

    private val arguments = listOf(
        ArgumentDescription("--input", listOf("-i"), ArgumentType.STRING) { it -> parseInput(it) },
        ArgumentDescription("--output", listOf("-o"), ArgumentType.STRING) { it -> parseOutput(it) },
        ArgumentDescription("--namespace", listOf("-ns"), ArgumentType.STRING) { it -> parseNamespace(it) },
        ArgumentDescription("--init", listOf(), ArgumentType.STRING) { it -> _initializers.add(it) },
        ArgumentDescription("--configuration", listOf("-c", "--config"), ArgumentType.EXISTING_FILE) { it -> _config = File(it) },
        ArgumentDescription("--step", listOf(), ArgumentType.STRING) { it -> _step = it },
        ArgumentDescription("--graph", listOf(), ArgumentType.FILE) { it -> _pipelineGraph = it },
        ArgumentDescription("--description", listOf(), ArgumentType.FILE) { it -> _pipelineDescription = it },
        ArgumentDescription("--licensed", listOf(), ArgumentType.BOOLEAN, "true") { it -> _licensed = it == "true" },
        ArgumentDescription("--debug", listOf("-D"), ArgumentType.BOOLEAN, "true") { it -> _debug = it == "true" },
        ArgumentDescription("--debugger", listOf(), ArgumentType.BOOLEAN, "true") { it -> _debugger = it == "true" },
        ArgumentDescription("--help", listOf(), ArgumentType.BOOLEAN, "true") { it -> _help = it == "true" },
        ArgumentDescription("--trace", listOf(), ArgumentType.FILE) { it -> _trace = File(it) },
        ArgumentDescription("--trace-documents", listOf("--trace-docs"), ArgumentType.DIRECTORY) { it -> _traceDocuments = File(it) },
        ArgumentDescription("--verbosity", listOf("-V"),
            ArgumentType.STRING, "info", listOf("trace", "debug", "progress", "info", "warn", "error")) { it ->
            _verbosity = when(it) {
                "error" -> Verbosity.ERROR
                "warn" -> Verbosity.WARN
                "info" -> Verbosity.INFO
                "debug" -> Verbosity.DEBUG
                "trace" -> Verbosity.TRACE
                else -> Verbosity.INFO
            }},
        ArgumentDescription("--assertions", listOf(),
            ArgumentType.STRING, "warn", listOf("ignore", "warn", "warning", "error")) { it ->
            _assertions = when(it) {
                "ignore" -> SchematronAssertions.IGNORE
                "warn", "warning" -> SchematronAssertions.WARNING
                "error" -> SchematronAssertions.ERROR
                else -> SchematronAssertions.IGNORE
            }},
        ArgumentDescription("--visualizer", listOf("--vis"),
            ArgumentType.STRING, "plain", emptyList()) { it -> parseVisualizer(it) }
        )

    private fun parse() {
        if (args.isEmpty()) {
            _command = "help"
            return
        }

        for (opt in args) {
            val pos = opt.indexOf(':')
            val option = if (pos >= 0) {
                opt.substring(0, pos)
            } else {
                opt
            }
            val suppliedValue = if (pos >= 0) {
                opt.substring(pos + 1)
            } else {
                null
            }

            var processed = false
            for (arg in arguments) {
                if (option == arg.name || arg.synonyms.contains(option)) {
                    processed = true

                    var value = suppliedValue ?: arg.default
                    if (value == null) {
                        _errors.add(XProcError.xiCliValueRequired(option).exception())
                        continue
                    }

                    if (arg.valid.isNotEmpty() && !arg.valid.contains(value)) {
                        _errors.add(XProcError.xiCliInvalidValue(option, value).exception())
                        continue
                    }

                    when (arg.type) {
                        ArgumentType.STRING -> Unit
                        ArgumentType.BOOLEAN -> {
                            if (value != "true" && value != "false") {
                                _errors.add(XProcError.xiCliInvalidValue(option, value).exception())
                                continue
                            }
                        }
                        ArgumentType.FILE -> {
                            val file = File(value)
                            if (file.exists() && !file.isFile) {
                                _errors.add(XProcError.xiCliInvalidValue(option, value).exception())
                                continue
                            }
                        }
                        ArgumentType.EXISTING_FILE -> {
                            val file = File(value)
                            if (!file.exists() || !file.isFile || !file.canRead()) {
                                _errors.add(XProcError.xiCliInvalidValue(option, value).exception())
                                continue
                            }
                        }
                        ArgumentType.DIRECTORY -> {
                            val file = File(value)
                            if (!file.exists()) {
                                if (!file.mkdirs()) {
                                    _errors.add(XProcError.xiCliInvalidValue(option, value).exception())
                                    continue
                                }
                            }
                            if (!file.isDirectory) {
                                _errors.add(XProcError.xiCliInvalidValue(option, value).exception())
                                continue
                            }
                        }
                        ArgumentType.URI -> {
                            value = UriUtils.cwdAsUri().resolve(value).toString()
                        }
                    }

                    try {
                        arg.process(value)
                    } catch (ex: XProcException) {
                        _errors.add(ex)
                    }

                    break
                }
            }

            if (!processed) {
                if (option.startsWith("-")) {
                    _errors.add(XProcError.xiCliUnrecognizedOption(option).exception())
                } else if (opt.contains("=")) {
                    parseOptionParam(opt)
                } else {
                    if (_pipeline != null) {
                        _errors.add(XProcError.xiCliMoreThanOnePipeline(_pipeline.toString(), opt).exception())
                    } else {
                        if (opt in validCommands) {
                            _command = opt
                        } else {
                            try {
                                _pipeline = parseFile(opt)
                            } catch (ex: XProcException) {
                                _errors.add(ex)
                            }
                        }
                    }
                }
            }
        }

        if (_debug == true && (verbosity == null || verbosity!! > Verbosity.DEBUG)) {
            _verbosity = Verbosity.DEBUG
        }
    }

    private fun split(arg: String, type: String): Pair<String, String> {
        val pos = arg.indexOf("=")
        if (pos <= 0) {
            throw XProcError.xiCliMalformedOption(type, arg).exception()
        }
        return Pair(arg.substring(0, pos).trim(), arg.substring(pos + 1).trim())
    }

    private fun parseInput(arg: String) {
        // -i:port=path
        val (port, href) = split(arg, "input")
        if (!_inputs.containsKey(port)) {
            _inputs.put(port, mutableListOf())
        }
        _inputs[port]!!.add(UriUtils.cwdAsUri().resolve(href))
    }

    private fun parseOutput(arg: String) {
        // -i:port=path
        val (port, filename) = split(arg, "output")
        if (_outputs.containsKey(port)) {
            throw XProcError.xiCliDuplicateOutputFile(filename).exception()
        }
        _outputs[port] = OutputFilename(filename)
    }

    private fun parseNamespace(arg: String) {
        // -ns:path or -ns:prefix=path
        val eqpos = arg.indexOf("=")
        val prefix = if (eqpos >= 0) {
            arg.substring(0, eqpos)
        } else {
            ""
        }
        val uri = if (eqpos >= 0) {
            arg.substring(eqpos + 1)
        } else {
            arg
        }

        if (_namespaces.containsKey(prefix)) {
            throw XProcError.xiCliDuplicateNamespace(prefix).exception()
        }

        _namespaces[prefix] = NamespaceUri.of(uri)
    }

    private fun parseOptionParam(arg: String) {
        val (name, value) = split(arg, "option")
        val values = mutableListOf<String>()
        values.addAll(_options[name] ?: emptyList())
        values.add(value)
        _options[name] = values
    }

    private fun parseFile(arg: String): File {
        val pfile = File(arg)
        if (!pfile.exists() || !pfile.isFile() || !pfile.canRead()) {
            throw XProcError.xiUnreadableFile(arg).exception()
        }
        return pfile
    }

    private fun parseVisualizer(arg: String) {
        val pos = arg.indexOf("?")
        val name = if (pos >= 0) {
            arg.substring(0, pos).trim()
        } else {
            arg
        }
        val opts = if (pos >= 0) {
            arg.substring(pos + 1).trim()
        } else {
            ""
        }

        val options = mutableMapOf<String,String>()

        // Cheap and cheerful. And keep it that way.
        if (opts.trim().isNotEmpty()) {
            for (nvpair in opts.split(";")) {
                val eqpos = nvpair.indexOf("=")
                if (eqpos <= 0) {
                    throw XProcError.xiCliInvalidValue("--visualizer", arg).exception()
                }
                val key = nvpair.substring(0, eqpos).trim()
                val value = nvpair.substring(eqpos + 1).trim()
                options[key] = value
            }
        }

        _visualizer = when(name) {
            "silent" -> Silent(options)
            "plain" -> Plain(options)
            "detail" -> Detail(options)
            else -> throw XProcError.xiCliInvalidValue("--visualizer", arg).exception()
        }
    }

    internal inner class ArgumentDescription(val name: String,
                                    val synonyms: List<String>,
                                    val type: ArgumentType,
                                    val default: String? = null,
                                    val valid: List<String> = listOf(),
                                    val process: (String) -> Unit)

    internal enum class ArgumentType {
        STRING, URI, FILE, EXISTING_FILE, DIRECTORY, BOOLEAN
    }
}