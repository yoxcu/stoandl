package de.yoxcu.stoandl.pebble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A resume asks the watch for its backlog once data has been held 15 min, counted from the first pause
 *  since the watch last sent everything. */
class DatalogPauseTest {
    private val sec = 1_000L
    private val min = 60_000L
    private val t0 = 1_760_000_000_000L

    @Test
    fun `a resume without a pause asks for nothing`() {
        val p = DatalogPause()
        assertNull(p.update(true, t0))
        assertNull(p.update(true, t0 + 60 * min))
    }

    @Test
    fun `a short pause asks for nothing`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertNull(p.update(true, t0 + 15 * min - 1))
    }

    @Test
    fun `a pause of 15 min or more asks for the backlog once`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertEquals(15.minutes, p.update(true, t0 + 15 * min))
        assertNull(p.update(true, t0 + 16 * min))
        assertNull(p.update(false, t0 + 20 * min))
        assertEquals(47.minutes, p.update(true, t0 + 67 * min))
    }

    @Test
    fun `a repeated pause keeps its start`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertNull(p.update(false, t0 + 10 * min))
        assertEquals(20.minutes, p.update(true, t0 + 20 * min))
    }

    @Test
    fun `short pauses with short screen-ons add up`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertNull(p.update(true, t0 + 10 * min))
        assertNull(p.update(false, t0 + 10 * min + 30 * sec))
        assertEquals(20.minutes + 30.seconds, p.update(true, t0 + 20 * min + 30 * sec))
    }

    @Test
    fun `a backlog request cut short by the next pause is made again`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertEquals(480.minutes, p.update(true, t0 + 480 * min))
        assertNull(p.update(false, t0 + 480 * min + 5 * sec))
        assertEquals(485.minutes, p.update(true, t0 + 485 * min))
    }

    @Test
    fun `a backlog request that had time to finish ends the held time`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertEquals(480.minutes, p.update(true, t0 + 480 * min))
        assertNull(p.update(false, t0 + 481 * min))
        assertNull(p.update(true, t0 + 490 * min))
        assertNull(p.update(false, t0 + 490 * min + 30 * sec))
        assertEquals(15.minutes, p.update(true, t0 + 496 * min))
    }

    @Test
    fun `a repeated resume after the request ends the held time`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertEquals(30.minutes, p.update(true, t0 + 30 * min))
        assertNull(p.update(true, t0 + 32 * min)) // a fresh connect re-sends the state
        assertNull(p.update(false, t0 + 33 * min))
        assertNull(p.update(true, t0 + 40 * min))
    }

    @Test
    fun `15 min with sends on and no request is the watch's own flush`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertNull(p.update(true, t0 + 10 * min))
        assertNull(p.update(false, t0 + 25 * min))
        assertNull(p.update(true, t0 + 35 * min))
    }

    @Test
    fun `a short screen-on without a request keeps the held time`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertNull(p.update(true, t0 + 10 * min))
        assertNull(p.update(false, t0 + 24 * min))
        assertEquals(30.minutes, p.update(true, t0 + 30 * min))
    }

    @Test
    fun `a clock set back counts as a short pause`() {
        val p = DatalogPause()
        assertNull(p.update(false, t0))
        assertNull(p.update(true, t0 - 60 * min))
        assertNull(p.update(true, t0 + 60 * min))
    }
}
