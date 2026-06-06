package de.yoxcu.stoandl

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class KermitSlf4jWriter : LogWriter() {
    private val cache = ConcurrentHashMap<String, org.slf4j.Logger>()

    // Strip device-path suffix: "BlobDB-{...}" → "BlobDB", "PebbleBle/{...}" → "PebbleBle"
    // Also strip plain app-name suffix: "RhinoJsRunner-Hooky" → "RhinoJsRunner"
    private fun cleanTag(tag: String) = tag
        .replace(Regex("[/-]\\{.*"), "")
        .replace(Regex("-[^{].*"), "")

    private fun slf4j(tag: String) = cache.getOrPut(cleanTag(tag)) { LoggerFactory.getLogger(cleanTag(tag)) }

    override fun isLoggable(tag: String, severity: Severity): Boolean {
        val log = slf4j(tag)
        return when (severity) {
            Severity.Verbose -> log.isTraceEnabled
            Severity.Debug   -> log.isDebugEnabled
            Severity.Info    -> log.isInfoEnabled
            Severity.Warn    -> log.isWarnEnabled
            else             -> log.isErrorEnabled
        }
    }

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val log = slf4j(tag)
        when (severity) {
            Severity.Verbose -> log.trace(message, throwable)
            Severity.Debug   -> log.debug(message, throwable)
            Severity.Info    -> log.info(message, throwable)
            Severity.Warn    -> log.warn(message, throwable)
            else             -> log.error(message, throwable)
        }
    }
}
