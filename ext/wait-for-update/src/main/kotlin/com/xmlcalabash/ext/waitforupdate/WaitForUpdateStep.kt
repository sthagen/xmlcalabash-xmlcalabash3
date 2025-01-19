package com.xmlcalabash.ext.waitforupdate

import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.io.InternetProtocolRequest
import com.xmlcalabash.namespace.Ns
import com.xmlcalabash.steps.AbstractAtomicStep
import com.xmlcalabash.util.DurationUtils
import net.sf.saxon.s9api.QName
import net.sf.saxon.value.DateTimeValue
import java.io.File
import java.net.URI
import java.time.Duration

class WaitForUpdateStep(): AbstractAtomicStep() {
    companion object {
        private val _pause = QName("pause")
        private val _pauseAfter = QName("pause-after")
    }

    lateinit var href: URI
    lateinit var pause: Duration
    lateinit var pauseAfter: Duration

    override fun run() {
        super.run()

        href = uriBinding(Ns.href)!!
        pause = DurationUtils.parseDuration(stepConfig, stringBinding(_pause)!!)
        pauseAfter = DurationUtils.parseDuration(stepConfig, stringBinding(_pauseAfter)!!)

        when (href.scheme) {
            "file" -> waitForFile()
            "http","https" -> waitForHttp()
            else -> throw stepConfig.exception(XProcError.xiImpossible("Unsupported scheme: ${href.scheme}"))
        }

        Thread.sleep(pauseAfter.toMillis())
        val document = stepConfig.environment.documentManager.load(href, stepConfig)
        receiver.output("result", document)
    }

    private fun waitForFile() {
        val file = File(href.path)
        val exists = file.exists()
        val dt = file.lastModified()

        if (exists) {
            stepConfig.debug { "Waiting for ${file} to update..." }
        } else {
            stepConfig.debug { "Waiting for ${file} to exist..." }
        }

        while (true) {
            Thread.sleep(pause.toMillis())
            if (exists) {
                if (file.lastModified() > dt) {
                    return
                }
            } else {
                if (file.exists()) {
                    return
                }
            }
        }
    }

    private fun waitForHttp() {
        val request = InternetProtocolRequest(stepConfig, href)
        var response = request.execute("head")
        val exists = response.statusCode != 404
        val etag = response.headers["etag"]?.value?.toString()
        var dtHeader = response.headers["last-modified"] ?: response.headers["date"]
        val dt = if (dtHeader?.value is DateTimeValue) {
            (dtHeader.value as DateTimeValue).toJavaInstant()
        } else {
            null
        }

        if (exists) {
            stepConfig.debug { "Waiting for ${href} to update..." }
        } else {
            stepConfig.debug { "Waiting for ${href} to exist..." }
        }

        while (true) {
            Thread.sleep(pause.toMillis())
            response = request.execute("head")
            if (exists) {
                if (response.statusCode == 200) {
                    val newEtag = response.headers["etag"]?.value?.toString()
                    if (etag != null && newEtag != null && etag != newEtag) {
                        return
                    }
                    dtHeader = response.headers["last-modified"] ?: response.headers["date"]
                    val newDt = if (dtHeader?.value is DateTimeValue) {
                        (dtHeader.value as DateTimeValue).toJavaInstant()
                    } else {
                        null
                    }
                    if (newDt != null && dt != null && newDt > dt) {
                        return
                    }
                }
            } else {
                if (response.statusCode == 200) {
                    return
                }
            }
        }
    }

    override fun toString(): String = "cx:wait-for-update"
}