package de.yoxcu.stoandl.notification

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** QOL-D3: "mute all for a while" is one state of its own. It ends by itself, survives a restart, and
 *  never takes the per-app states' words, so resuming can't erase them. */
class GlobalMuteTest {
    private val dir = Files.createTempDirectory("globalmute").toFile()
    private val file = File(dir, "notification-muted-until")
    private var now = Instant.parse("2026-10-19T12:10:00Z")
    private val zone = ZoneId.of("Europe/Berlin")
    private fun mute() = GlobalMute(file, clock = { now }, zone = { zone })

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test
    fun `a duration mutes until then, and it ends by itself`() {
        val m = mute()
        assertEquals("ok:Muted all notifications until 14:40", m.set("30m"))
        assertEquals(Instant.parse("2026-10-19T12:40:00Z"), m.mutedUntil())
        now += 31.minutes
        assertNull(m.mutedUntil())
    }

    @Test
    fun `today lasts until local midnight`() {
        val m = mute()
        m.set("today")
        assertEquals(Instant.parse("2026-10-19T22:00:00Z"), m.mutedUntil())
    }

    @Test
    fun `a restart inside the mute stays muted, a resume deletes it`() {
        mute().set("1h")
        val again = mute()
        assertEquals(Instant.parse("2026-10-19T13:10:00Z"), again.mutedUntil())
        assertEquals("ok:Notifications resumed", again.set("never"))
        assertNull(mute().mutedUntil())
        assertEquals("ok:Notifications weren't muted", again.set("never"))
    }

    @Test
    fun `per-app states and nonsense are refused without changing anything`() {
        val m = mute()
        for (spec in listOf("always", "weekdays", "weekends", "tomorrow")) {
            assertTrue(m.set(spec).startsWith("error:"), spec)
        }
        assertNull(m.mutedUntil())
    }

    @Test
    fun `a mute into another day names the day`() {
        val m = mute()
        assertEquals("ok:Muted all notifications until Wed 21 Oct 14:10", m.set("2d"))
    }
}
