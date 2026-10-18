package de.yoxcu.stoandl.notification

import de.yoxcu.stoandl.pebble.sendsImages
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** `notif images <app>`: what the argument stores, and when a notification's picture goes out. */
class PerAppImagesTest {
    // Built like WatchNotifier tracks a new app: sendImages not named.
    private val newApp = NotificationAppItem(
        packageName = "Spectacle", name = "Spectacle", muteState = MuteState.Never,
        channelGroups = emptyList(), stateUpdated = Instant.fromEpochMilliseconds(0).asMillisecond(),
        lastNotified = Instant.fromEpochMilliseconds(0).asMillisecond(), muteExpiration = null,
        vibePatternName = null, colorName = null, iconCode = null,
    )

    private fun app(sendImages: Boolean) = newApp.copy(sendImages = sendImages)

    @Test
    fun `on and default follow the global switch, off turns the app's pictures off`() {
        for (s in listOf("on", "ON", " default ", "", "none", "clear")) assertEquals(true, NotificationAppsControl.parseImagesMode(s), s)
        assertEquals(false, NotificationAppsControl.parseImagesMode("off"))
        assertEquals(false, NotificationAppsControl.parseImagesMode("Off"))
        assertNull(NotificationAppsControl.parseImagesMode("huge"))
    }

    @Test
    fun `a picture goes out only with the global switch on and the app's not off`() {
        assertTrue(sendsImages(true, null))
        assertTrue(sendsImages(true, app(sendImages = true)))
        assertFalse(sendsImages(true, app(sendImages = false)))
        assertFalse(sendsImages(false, null))
        assertFalse(sendsImages(false, app(sendImages = true)))
    }

    @Test
    fun `a newly tracked app follows the global switch`() {
        assertTrue(newApp.sendImages)
        assertTrue(sendsImages(true, newApp))
    }
}
