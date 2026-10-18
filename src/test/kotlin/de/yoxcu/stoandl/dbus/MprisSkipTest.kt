package de.yoxcu.stoandl.dbus

import io.rebble.libpebblecommon.music.PlaybackState
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.PlayerInfo
import io.rebble.libpebblecommon.music.RepeatType
import kotlin.test.Test
import kotlin.test.assertSame
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

    private fun status(seeks: Boolean) = PlaybackStatus(
        playerInfo = PlayerInfo(packageId = "kasts", name = "Kasts"),
        playbackState = PlaybackState.Playing,
        currentTrack = null,
        playbackPositionMs = 0,
        playbackRate = 1f,
        shuffle = false,
        repeat = RepeatType.Off,
        volume = 100,
        skipSeeksWithinTrack = seeks,
    )

    @Test
    fun `music_seek_when_available off turns seeks back into skips`() {
        assertFalse(status(seeks = true).withSeek(seekWhenAvailable = false).skipSeeksWithinTrack)
        assertTrue(status(seeks = true).withSeek(seekWhenAvailable = true).skipSeeksWithinTrack)
        // A player that skips is unchanged either way.
        val skipper = status(seeks = false)
        assertSame(skipper, skipper.withSeek(seekWhenAvailable = false))
    }
}
