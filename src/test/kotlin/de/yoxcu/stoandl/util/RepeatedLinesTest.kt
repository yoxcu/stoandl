package de.yoxcu.stoandl.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** The periodic summaries' INFO-on-change rule: a repeat is not news, a change and a change back are. */
class RepeatedLinesTest {
    @Test
    fun onlyAChangedLineIsNew() {
        val lines = RepeatedLines()
        val seen = listOf("2 calendars", "2 calendars", "3 calendars", "3 calendars", "2 calendars")
            .map { lines.isNew("calendars", it) }
        assertEquals(listOf(true, false, true, false, true), seen)
    }

    @Test
    fun keysAreIndependent() {
        val lines = RepeatedLines()
        assertEquals(true, lines.isNew("caldav a", "1 calendar"))
        assertEquals(true, lines.isNew("caldav b", "1 calendar"))
        assertEquals(false, lines.isNew("caldav a", "1 calendar"))
    }

    /** A repeat goes below DEBUG, which the phone runs at, so the journal sees each line once per change. */
    @Test
    fun aRepeatIsTrace() {
        val name = "RepeatedLinesTest.levels"
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(name) as Logger).apply {
            level = Level.TRACE
            isAdditive = false
            addAppender(appender)
        }
        val log = KotlinLogging.logger(name)
        val lines = RepeatedLines()
        log.infoWhenChanged(lines, "calendars", "2 calendars")
        log.infoWhenChanged(lines, "calendars", "2 calendars")
        log.warnWhenChanged(lines, "uid", "Recurrence expansion failed")
        log.warnWhenChanged(lines, "uid", "Recurrence expansion failed")
        assertEquals(listOf(Level.INFO, Level.TRACE, Level.WARN, Level.TRACE), appender.list.map { it.level })
    }
}
