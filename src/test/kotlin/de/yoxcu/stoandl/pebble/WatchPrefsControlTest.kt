package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A notification timeout under 15 s makes a notification vanish at once and cancels its vibration on
 * every released firmware (the fw's own clamp is only on PebbleOS `main`). The floor is libpebble3's
 * (`NotificationTimeoutMs.min`, upstream 912fde2c), and [WatchPrefsControl] range-checks every number
 * pref against its own bounds, so there is no second copy of the 15 s here. These tests pin that the
 * floor reaches both the CLI/GUI path and the `watch.*` config pins.
 */
@OptIn(ExperimentalUuidApi::class)
class WatchPrefsControlTest {
    private val written = mutableListOf<WatchPreference<*>>()
    private var listed: List<WatchPreference<*>> = listOf(WatchPreference(EnumWatchPref.SystemTextSize, null))
    private val musicUuid = Uuid.parse("1f03293d-47af-4f28-b960-f2b02a6dd757")
    private var firmware: FirmwareVersion? = null
    private val control = WatchPrefsControl(
        fakeLibPebble(),
        resolveAppUuid = { if (it.equals("Music", true)) musicUuid else null },
        appName = { if (it == musicUuid) "Music" else null },
        connectedFirmware = { firmware },
    )

    @Test
    fun `a notification timeout under 15 s is refused`() {
        val r = control.setOne("notifWindowTimeout", "5000")
        assertTrue(r.startsWith("error:"), r)
        assertContains(r, "15000..")
        assertTrue(written.isEmpty(), "nothing may reach the watch")

        assertTrue(control.setOne("notifWindowTimeout", "15000").startsWith("ok:"))
        assertEquals(15_000L, written.single().value)
    }

    @Test
    fun `a pinned timeout under 15 s is skipped on connect`() {
        control.applyConfigured(mapOf("notifWindowTimeout" to "3000", "clock24h" to "true"))
        assertEquals(listOf("clock24h"), written.map { it.pref.id }, "the bad pin is skipped, the rest applied")
    }

    /** Only fw 4.39+ syncs the split text sizes; libpebble3's description doesn't say so. */
    @Test
    fun `the text size pref says which firmware it needs`() {
        val row = control.list().single().split('\t')
        assertEquals("systemTextSize", row[0])
        assertContains(row[7], "Text size for system menus")
        assertContains(row[7], "4.39")
    }

    /**
     * Firmware older than 4.41 rejects `dndNotificationBacklight` (not in its settings_blob_db whitelist).
     * A pin must not be re-applied on every connect to such a watch; it applies once the watch is updated.
     */
    @Test
    fun `a pin the watch's firmware is too old for is skipped on connect`() {
        val pins = mapOf("dndNotificationBacklight" to "false", "clock24h" to "true")
        firmware = fw("v4.40.0")
        control.applyConfigured(pins)
        control.applyConfigured(pins)
        assertEquals(listOf("clock24h", "clock24h"), written.map { it.pref.id })

        written.clear()
        firmware = fw("v4.41.1")
        control.applyConfigured(pins)
        assertEquals(listOf("dndNotificationBacklight", "clock24h"), written.map { it.pref.id })
        assertEquals(false, written.first().value)
    }

    @Test
    fun `setting a pref the watch is too old for keeps it for later and says so`() {
        firmware = fw("v4.40.0")
        assertEquals(
            "ok:Set dndNotificationBacklight = false (needs PebbleOS 4.41 or later, the watch runs v4.40.0; " +
                "it syncs once the watch is updated)",
            control.setOne("dndNotificationBacklight", "off"),
        )
        assertEquals(listOf<Any?>(false), written.map { it.value })

        firmware = null
        assertEquals(
            "ok:Set dndNotificationBacklight = true (syncs to the watch on next connect)",
            control.setOne("dndNotificationBacklight", "on"),
        )
    }

    /** The fork's description already names the firmware; the derived note must not repeat it. */
    @Test
    fun `the quiet time notification backlight says 4_41 once`() {
        listed = listOf(WatchPreference(BoolWatchPref.QuietTimeNotificationBacklight, null))
        val row = control.list().single().split('\t')
        assertEquals(listOf("dndNotificationBacklight", "bool", "true", "true"), row.take(4))
        assertEquals(1, Regex("4\\.41").findAll(row[7]).count(), row[7])
    }

    @Test
    fun `quick launch takes the firmware system actions by name`() {
        assertEquals("ok:Set qlUp = Backlight (syncs to the watch on next connect)", control.setOne("qlUp", "Backlight"))
        listOf("quiet time", "Motion-Backlight", "AirplaneMode", "timeline_past", "Timeline Future").forEach {
            assertTrue(control.setOne("qlDown", it).startsWith("ok:"), it)
        }
        assertEquals(
            listOf(
                SystemAppIDs.BACKLIGHT_UUID, SystemAppIDs.QUIET_TIME_TOGGLE_UUID, SystemAppIDs.MOTION_BACKLIGHT_UUID,
                SystemAppIDs.AIRPLANE_MODE_UUID, SystemAppIDs.TIMELINE_PAST_UUID, SystemAppIDs.TIMELINE_FUTURE_UUID,
            ).map { QuickLaunchSetting(true, it) },
            written.map { it.value },
        )
    }

    /** No locker entry backs a system action, so its UUID (e.g. pinned in stoandl.conf) must parse on its own. */
    @Test
    fun `a system action's uuid is accepted and shown by name`() {
        assertEquals(
            "ok:Set qlSelect = Quiet Time (syncs to the watch on next connect)",
            control.setOne("qlSelect", SystemAppIDs.QUIET_TIME_TOGGLE_UUID.toString()),
        )
    }

    @Test
    fun `apps, off and unknown names still behave as before`() {
        assertEquals("ok:Set qlUp = Music (syncs to the watch on next connect)", control.setOne("qlUp", "Music"))
        assertEquals("ok:Set qlUp = off (syncs to the watch on next connect)", control.setOne("qlUp", "off"))
        assertEquals(listOf(QuickLaunchSetting(true, musicUuid), QuickLaunchSetting(false, null)), written.map { it.value })
        val r = control.setOne("qlUp", "Timeline")
        assertTrue(r.startsWith("error:"), r)
        assertContains(r, "Quiet Time")
    }

    @Test
    fun `quick launch defaults list by name and the actions are offered`() {
        listed = listOf(
            WatchPreference(QuicklaunchWatchPref.QlBack, null),
            WatchPreference(QuicklaunchWatchPref.QlSingleClickDown, null),
            WatchPreference(QuicklaunchWatchPref.QlUp, QuickLaunchSetting(true, SystemAppIDs.AIRPLANE_MODE_UUID)),
        )
        val rows = control.list().map { it.split('\t') }
        assertEquals(listOf("Quiet Time", "Timeline Future", "Airplane Mode"), rows.map { it[2] })
        assertEquals(listOf("Quiet Time", "Timeline Future", "off"), rows.map { it[3] })
        assertEquals(
            "off|Quiet Time|Backlight|Motion Backlight|Airplane Mode|Timeline Past|Timeline Future|<app name or uuid>",
            rows[0][4],
        )
    }

    private fun fw(tag: String) = FirmwareVersion.from(
        tag = tag,
        isRecovery = false,
        gitHash = "",
        timestamp = Instant.DISTANT_PAST,
        isDualSlot = false,
        isSlot0 = false,
    )!!

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
