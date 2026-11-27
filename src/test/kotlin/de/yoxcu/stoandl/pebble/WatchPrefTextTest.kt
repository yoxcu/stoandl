package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.assertGuiFixture
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.database.entity.ScheduleWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WatchPrefText] is keyed by libpebble3's pref ids, which a fork bump can add or rename silently: a new pref
 * would land in "Other" and a renamed one would lose its section, help and requires. These tests tie the table
 * to the enumerated prefs, and pin the `ListWatchPrefs` columns 8–10 and the reset path.
 */
class WatchPrefTextTest {
    private val all = WatchPref.enumeratePrefs()

    @Test
    fun `every pref libpebble3 knows has a section, and the table names only real prefs`() {
        val ids = all.map { it.id }.toSet()
        assertTrue(WatchPrefText.knownIds.all { it in ids }, "unknown ids: ${WatchPrefText.knownIds - ids}")
        val unplaced = all.filter { WatchPrefText.section(it) == WatchPrefText.OTHER }.map { it.id }
        assertEquals(emptyList(), unplaced, "place these prefs in WatchPrefText")
        assertTrue(all.filter { it.isDebugSetting }.all { WatchPrefText.section(it) == WatchPrefText.ADVANCED })
    }

    /** A requires clause names a value its pref can show (`ListWatchPrefs`' `current` spelling). */
    @Test
    fun `requires clauses name a value the other pref can have`() {
        all.forEach { pref ->
            val clause = WatchPrefText.requires(pref).takeIf { it.isNotEmpty() } ?: return@forEach
            clause.split('|').forEach { alt ->
                val (id, value) = alt.split('=', limit = 2)
                val other = WatchPref.from(id) ?: error("${pref.id}: requires unknown pref $id")
                val values = when (other) {
                    is BoolWatchPref -> listOf("true", "false")
                    is EnumWatchPref -> other.options.map { it.displayName }
                    else -> error("${pref.id}: requires a ${other::class.simpleName}")
                }
                assertTrue(value in values, "${pref.id}: '$value' is not a value of $id ($values)")
            }
        }
    }

    /**
     * libpebble3 has no descriptions any more (upstream 76fc8f4a), so the table owns them: each names a real
     * pref (the first test), and the ones prefs had before the bump are still there.
     */
    @Test
    fun `the descriptions are stoandl's and survive the bump`() {
        assertEquals("Turn on backlight by flicking wrist", WatchPrefText.description(BoolWatchPref.BacklightMotion))
        assertEquals("Text size for system menus and the launcher", WatchPrefText.description(EnumWatchPref.SystemTextSize))
        assertEquals("Enable the touchscreen", WatchPrefText.description(BoolWatchPref.TouchEnabled))
        assertEquals(null, WatchPrefText.description(BoolWatchPref.Clock24h))
        // The fork keeps `language` for one-pack firmware, with the text it had before upstream dropped it.
        assertEquals("Choose Custom to use the installed language pack.", WatchPrefText.description(EnumWatchPref.Language))
        assertEquals("Time, units & language", WatchPrefText.section(EnumWatchPref.Language))
        // The firmware note follows the sentence, once.
        assertEquals(
            "Turn on the backlight for notifications during Quiet Time. Needs PebbleOS 4.41 or later.",
            WatchPrefsControl.describe(BoolWatchPref.QuietTimeNotificationBacklight),
        )
        assertEquals("Needs PebbleOS 4.37 or later.", WatchPrefsControl.describe(EnumWatchPref.WindSpeed).substringAfter(". "))
    }

    @Test
    fun `the two text sizes get names of their own`() {
        assertEquals("Menu Text Size", WatchPrefText.name(EnumWatchPref.SystemTextSize))
        assertEquals("Notification Text Size", WatchPrefText.name(EnumWatchPref.NotificationTextSize))
        assertEquals(BoolWatchPref.Clock24h.displayName, WatchPrefText.name(BoolWatchPref.Clock24h))
        val names = all.map { WatchPrefText.name(it) }
        assertEquals(names.distinct(), names, "two prefs share a name")
    }

    @Test
    fun `list appends section, help and requires and groups by section`() {
        listed = listOf(
            WatchPreference(BoolWatchPref.Clock24h, null),
            WatchPreference(NumberWatchPref.BacklightTimeoutMs, null),
            WatchPreference(QuicklaunchWatchPref.QlUp, null),
        )
        firmware = fw("v4.41.1")
        val rows = control().list().map { it.split('\t') }
        assertTrue(rows.all { it.size == 11 }, rows.toString())
        assertEquals(listOf("qlUp", "lightTimeoutMs", "clock24h"), rows.map { it[0] })
        assertEquals(listOf("Quick launch", "Backlight & display", "Time, units & language"), rows.map { it[8] })
        assertTrue(rows[0][9].startsWith("Choose an app"), rows[0][9])
        assertEquals(listOf("", "lightPreset=Advanced", ""), rows.map { it[10] })
    }

    /** Firmware before 4.22 has no backlight preset, so the settings it bundles are the user's own there. */
    @Test
    fun `a requires clause on a pref the firmware lacks is dropped`() {
        listed = listOf(WatchPreference(NumberWatchPref.BacklightTimeoutMs, null))
        firmware = fw("v4.21.0")
        assertEquals("", control().list().single().split('\t')[10])
        firmware = fw("v4.22.0")
        assertEquals("lightPreset=Advanced", control().list().single().split('\t')[10])
    }

    /** With the watch away the last firmware it ran decides; with none known the rows stay editable. */
    @Test
    fun `the last known firmware decides while no watch is connected`() {
        listed = listOf(
            WatchPreference(NumberWatchPref.BacklightTimeoutMs, null),
            WatchPreference(ScheduleWatchPref.QuietTimeWeekdaySchedule, null),
        )
        fun requires(lastKnown: FirmwareVersion?) =
            control(lastKnown = lastKnown).list().map { it.split('\t')[10] }
        assertEquals(listOf("", "dndWeekdayScheduleEnabled=true"), requires(lastKnown = fw("v4.4.0")))
        assertEquals(listOf("lightPreset=Advanced", "dndWeekdayScheduleEnabled=true"), requires(lastKnown = fw("v4.41.1")))
        assertEquals(listOf("", "dndWeekdayScheduleEnabled=true"), requires(lastKnown = null))
    }

    @Test
    fun `a stored firmware tag parses`() {
        assertEquals(listOf(4, 4, 0), WatchPrefsControl.firmwareOf("v4.4.0")!!.let { listOf(it.major, it.minor, it.patch) })
        assertEquals(null, WatchPrefsControl.firmwareOf(""))
    }

    /**
     * `gui/tools/watch-pref-text.tsv` is the mock daemon's copy of this table (`id \t section \t help \t
     * requires`, in display order), so the GUI is tested against the real text.
     */
    @Test
    fun `the gui mock's watch-pref fixture is current`() {
        val rendered = all.sortedBy { WatchPrefText.sectionIndex(WatchPrefText.section(it)) }.joinToString("") { pref ->
            listOf(pref.id, WatchPrefText.section(pref), WatchPrefText.help(pref), WatchPrefText.requires(pref))
                .joinToString("\t", postfix = "\n")
        }
        assertGuiFixture("watch-pref-text.tsv", rendered)
    }

    @Test
    fun `reset sets only the changed prefs back and keeps pins in step`() {
        listed = listOf(
            WatchPreference(BoolWatchPref.Clock24h, true),
            WatchPreference(NumberWatchPref.BacklightTimeoutMs, 5000L),
            WatchPreference(EnumWatchPref.BacklightPreset, null),
            WatchPreference(QuicklaunchWatchPref.QlBack, QuickLaunchSetting(false, null)),
            WatchPreference(ScheduleWatchPref.QuietTimeWeekdaySchedule, ScheduleWatchPref.QuietTimeWeekdaySchedule.defaultValue),
        )
        assertEquals("ok:Reset 3 watch settings to their defaults (syncs to the watch on next connect)", control().resetAll())
        assertEquals(listOf("clock24h", "lightTimeoutMs", "qlBack"), written.map { it.pref.id })
        assertEquals(written.map { it.pref.defaultValue }, written.map { it.value })
        // The pins get what `watch.<id>` parses back: no unit suffix, an action by name.
        assertEquals(listOf("clock24h" to "false", "lightTimeoutMs" to "3000", "qlBack" to "Quiet Time"), pins)
    }

    private var listed: List<WatchPreference<*>> = emptyList()
    private val written = mutableListOf<WatchPreference<*>>()
    private val pins = mutableListOf<Pair<String, String>>()
    private var firmware: FirmwareVersion? = null

    private fun control(lastKnown: FirmwareVersion? = null) = WatchPrefsControl(
        fakeLibPebble(),
        resolveAppUuid = { null },
        appName = { null },
        syncPinnedPref = { id, raw -> pins.add(id to raw) },
        connectedFirmware = { firmware },
        lastKnownFirmware = { firmware ?: lastKnown },
    )

    private fun fw(tag: String) = WatchPrefsControl.firmwareOf(tag)!!

    private fun fakeLibPebble(): LibPebble =
        Proxy.newProxyInstance(LibPebble::class.java.classLoader, arrayOf(LibPebble::class.java)) { proxy, method, args ->
            when (method.name) {
                "setWatchPref" -> written.add(args!![0] as WatchPreference<*>).let { null }
                "getWatchPrefs" -> flowOf(listed)
                "toString" -> "fake LibPebble"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("LibPebble.${method.name}")
            }
        } as LibPebble
}
