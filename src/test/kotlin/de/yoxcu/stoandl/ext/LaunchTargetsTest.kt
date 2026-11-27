package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.testutil.fakeConnectedWatch
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * An extension's `launchApp` of a firmware action only some watches have must reach only those: on any other
 * watch the UUID is an uninstalled app (before PebbleOS 4.40 a Quick Launch tap on one reboots the watch).
 */
class LaunchTargetsTest {
    private val time2 = fakeConnectedWatch(WatchHardwarePlatform.CORE_OBELIX_PVT, "v4.41.2", identifier = "11:11:11:11:11:11")
    private val duo = fakeConnectedWatch(WatchHardwarePlatform.CORE_ASTERIX, "v4.41.2", identifier = "22:22:22:22:22:22")

    @Test
    fun `an app or an action every watch has goes through libpebble3's own launch`() {
        assertNull(launchTargets(Uuid.random(), listOf(time2, duo)))
        assertNull(launchTargets(SystemAppIDs.MUSIC_APP_UUID, listOf(duo)))
        assertNull(launchTargets(SystemAppIDs.QUIET_TIME_TOGGLE_UUID, emptyList()))
    }

    @Test
    fun `a touch action goes only to the touch watch`() {
        assertEquals(listOf(time2), launchTargets(SystemAppIDs.TOUCH_TOGGLE_UUID, listOf(time2, duo)))
    }

    @Test
    fun `a touch action with no touch watch connected goes nowhere`() {
        assertTrue(launchTargets(SystemAppIDs.TOUCH_TOGGLE_UUID, listOf(duo))!!.isEmpty())
        assertTrue(launchTargets(SystemAppIDs.TOUCH_TOGGLE_UUID, emptyList())!!.isEmpty())
        val oldTime2 = fakeConnectedWatch(WatchHardwarePlatform.CORE_OBELIX_PVT, "v4.38.2")
        assertTrue(launchTargets(SystemAppIDs.TOUCH_TOGGLE_UUID, listOf(oldTime2))!!.isEmpty(), "Toggle Touch needs 4.39")
    }
}
