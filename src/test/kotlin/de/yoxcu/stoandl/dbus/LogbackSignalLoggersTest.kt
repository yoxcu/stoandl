package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.messages.DBusSignal
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * dbus-java's Message logs each signal's construction under the signal's own class name, so every
 * DBusSignal subclass in stoandl needs a fixed level in both logback files, or `STOANDL_LOG=DEBUG` adds
 * three lines per signal. Finds the subclasses in the compiled classes and resolves each one's level the
 * way logback does (the longest configured prefix, split at '.' and '$').
 */
class LogbackSignalLoggersTest {
    private val fixedLevels = setOf("INFO", "WARN", "ERROR", "OFF")

    private fun signalClasses(): List<Class<*>> {
        val root = File(StoandlControl::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(root.isDirectory, "expected the main classes as a directory, got $root")
        val loader = StoandlControl::class.java.classLoader
        return root.resolve("de/yoxcu/stoandl").walk()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .mapNotNull { name -> runCatching { Class.forName(name, false, loader) }.getOrNull() }
            .filter { DBusSignal::class.java.isAssignableFrom(it) }
            .toList()
    }

    /** `<logger name=… level=…>` entries, plus the root's level under the empty name. */
    private fun levels(resource: String): Map<String, String> {
        val stream = javaClass.getResourceAsStream(resource) ?: fail("$resource not on the classpath")
        val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val loggers = doc.getElementsByTagName("logger")
        val out = (0 until loggers.length).associate { i ->
            val e = loggers.item(i) as org.w3c.dom.Element
            e.getAttribute("name") to e.getAttribute("level")
        }.toMutableMap()
        val root = doc.getElementsByTagName("root").item(0) as org.w3c.dom.Element
        out[""] = root.getAttribute("level")
        return out
    }

    /** The configured level that applies to [name]: its longest prefix with an entry. */
    private fun effectiveLevel(levels: Map<String, String>, name: String): Pair<String, String> {
        val cuts = name.indices.filter { name[it] == '.' || name[it] == '$' }.reversed()
        val candidates = listOf(name) + cuts.map { name.substring(0, it) } + ""
        val hit = candidates.first { it in levels }
        return hit to levels.getValue(hit)
    }

    @Test
    fun findsTheKnownSignals() {
        val names = signalClasses().map { it.name }
        assertTrue("de.yoxcu.stoandl.dbus.StoandlControl\$WatchesChanged" in names, "scan found $names")
        assertTrue("de.yoxcu.stoandl.dbus.ModemCall\$StateChanged" in names, "scan found $names")
    }

    @Test
    fun everySignalHasAFixedLevelInBothLogbackFiles() {
        val signals = signalClasses()
        for (resource in listOf("/logback.xml", "/logback-cli.xml")) {
            val levels = levels(resource)
            val missing = signals.mapNotNull { cls ->
                val (entry, level) = effectiveLevel(levels, cls.name)
                if (level in fixedLevels) null
                else "${cls.name} (follows '${entry.ifEmpty { "<root>" }}' at $level; add " +
                    "<logger name=\"${(cls.enclosingClass ?: cls).name}\" level=\"INFO\"/>)"
            }
            assertTrue(missing.isEmpty(), "$resource: dbus-java's Message logger is uncapped for $missing")
        }
    }
}
