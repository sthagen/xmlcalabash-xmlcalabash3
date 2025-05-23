package com.xmlcalabash.testdriver

import com.xmlcalabash.util.Urify
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.XdmAtomicValue
import java.io.File

class Main {
    companion object {
        @JvmStatic
        fun main(vararg args: String) {
            val testOptions = TestOptions()

            /*
            println("PID: ${ProcessHandle.current().pid()}")
            for (count in 0..9) {
                println(10 - count)
                Thread.sleep(1000)
            }
             */

            for (arg in args) {
                if (arg.startsWith("-t:")) {
                    testOptions.testRegex = arg.substring(3)
                } else if (arg.startsWith("--title:")) {
                    testOptions.title = arg.substring(8)
                } else if (arg.startsWith("--graph:")) {
                    testOptions.outputGraph = arg.substring(8)
                } else if (arg == "--stop-on-fail") {
                    testOptions.stopOnFirstFailed = true
                } else if (arg == "--debug") {
                    testOptions.debug = true
                } else if (arg.startsWith("--debug:")) {
                    testOptions.debug = arg.substring(8) == "true"
                } else if (arg.startsWith("--require-pass:")) {
                    testOptions.requirePass = (arg.substring(15) == "true")
                } else if (arg.startsWith("--report:")) {
                    testOptions.report = arg.substring(9)
                } else if (arg.startsWith("--dir:")) {
                    testOptions.testDirectoryList.add(arg.substring(6))
                } else if (arg.startsWith("--console:")) {
                    testOptions.consoleOutput = (arg.substring(10) == "true")
                } else if (!arg.startsWith("-")) {
                    val pos = arg.indexOf('=')
                    val name = arg.substring(0, pos)
                    val value = arg.substring(pos + 1)
                    testOptions.options[QName(name)] = XdmAtomicValue(value)
                } else {
                    throw RuntimeException("Unrecognized argument: ${arg}")
                }
            }

            if (testOptions.debug) {
                println("Running in debug mode...")
            }

            // The default test suite...
            if (testOptions.testDirectoryList.isEmpty()) {
                testOptions.testDirectoryList.add("../tests/3.0-test-suite/test-suite/tests")
                testOptions.testDirectoryList.add("../tests/extra-suite/test-suite/tests")
                testOptions.testDirectoryList.add("../tests/selenium/test-suite/tests")
            }

            val exclusions = mutableMapOf<String, String>()
            File("src/test/resources/exclusions.txt").forEachLine { line ->
                val pos = line.trim().indexOf(" because ")
                var filename = if (pos >= 0) {
                    line.substring(0, pos).trim()
                } else {
                    line.trim()
                }

                val onpos = filename.indexOf(" on ")
                if (onpos >= 0) {
                    val platform = filename.substring(onpos + 4).trim()
                    filename = filename.substring(0, onpos).trim()
                    when (platform) {
                        "Windows" -> if (!Urify.isWindows) {
                            filename = ""
                        }
                        else -> throw UnsupportedOperationException("Unrecognized platform: $platform")
                    }
                }

                if (filename.isNotEmpty()) {
                    val reason = line.substring(pos + 9).trim()
                    exclusions[filename] = reason
                }
            }

            val testDriver = TestDriver(testOptions, exclusions)
            //testDriver.threadTest()
            testDriver.run()
        }
    }
}
