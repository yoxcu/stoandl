package de.yoxcu.stoandl

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class KermitSlf4jWriter : LogWriter() {
    private val cache = ConcurrentHashMap<String, org.slf4j.Logger>()

    // Strip device-path suffix: "BlobDB-{...}" → "BlobDB", "PebbleBle/{...}" → "PebbleBle"
    // Also strip plain app-name suffix: "GraalJsRunner-Hooky" → "GraalJsRunner"
    private fun cleanTag(tag: String) = tag
        .replace(DEVICE_PATH_SUFFIX, "")
        .replace(APP_NAME_SUFFIX, "")

    private fun slf4j(tag: String): org.slf4j.Logger {
        val name = cleanTag(tag)
        return cache.getOrPut(name) { LoggerFactory.getLogger(name) }
    }

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

    companion object {
        // Constant patterns — hoisted out of cleanTag so they aren't recompiled on every log line.
        private val DEVICE_PATH_SUFFIX = Regex("[/-]\\{.*")
        private val APP_NAME_SUFFIX = Regex("-[^{].*")
    }
}
