package de.yoxcu.stoandl.dbus

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the watch's next/previous become a seek. */
class MprisSkipTest {
    @Test
    fun `a player that only seeks gets seeks`() {
        assertTrue(skipSeeksWithinTrack(canNext = false, canPrevious = false, canSeek = true))
    }

    @Test
    fun `track changes win when the player has them`() {
        assertFalse(skipSeeksWithinTrack(canNext = true, canPrevious = true, canSeek = true))
        // A queue at its first item still means next/previous.
        assertFalse(skipSeeksWithinTrack(canNext = true, canPrevious = false, canSeek = true))
    }

    @Test
    fun `a player that can neither skip nor seek keeps plain skipping`() {
        assertFalse(skipSeeksWithinTrack(canNext = false, canPrevious = false, canSeek = false))
    }
}
