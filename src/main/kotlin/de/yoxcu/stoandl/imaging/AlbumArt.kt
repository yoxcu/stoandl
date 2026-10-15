package de.yoxcu.stoandl.imaging

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.imaging.EncodedImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long the watch's album-art request waits for a first load before it is answered "no image yet";
 *  the art follows through [AlbumArt.updates]. Short: the request is answered on the watch-protocol
 *  collector, and the watch retries on its own. */
private const val FIRST_ANSWER_WAIT_MS = 800L

/** Decoded covers kept (the playing track's, and a few recent ones for skipping back and forth). */
private const val MAX_COVERS = 4

/** Longest side a cover is decoded to: the largest the watch asks for is 260 (gabbro). */
private const val COVER_LONG_SIDE = 300

/**
 * Album art for the watch's Music app, from what the media player publishes (`mpris:artUrl`: a `file:` URI
 * or a `data:` URI; never fetched from the network). Decodes each cover once, off the caller, and encodes
 * it per requested size. A request that comes before the decode finishes gets null at once, and [updates]
 * fires when the cover is ready, so the watch's pending request is answered then (libpebble3
 * MusicControlManager). Missing or unreadable art is remembered too, so it isn't retried on every poll.
 */
class AlbumArt(private val scope: CoroutineScope) {
    private val log = KotlinLogging.logger {}
    private val covers = object : LinkedHashMap<String, Deferred<ImageSource.Pixels?>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<ImageSource.Pixels?>>?) =
            size > MAX_COVERS
    }
    private val encoded = object : LinkedHashMap<Triple<String, Int, Int>, EncodedImage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, Int, Int>, EncodedImage>?) =
            size > MAX_COVERS
    }
    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when a cover finished loading after its request was answered without it, or when the playing
     *  track's art changed. */
    val updates: SharedFlow<Unit> = _updates

    /** Tell a pending request to look again (the player published or changed its art). */
    fun changed() { _updates.tryEmit(Unit) }

    /** The cover at [artUrl] at exactly [width]×[height], or null when there is none (yet). */
    suspend fun get(artUrl: String, width: Int, height: Int): EncodedImage? {
        val key = Triple(artUrl, width, height)
        synchronized(encoded) { encoded[key] }?.let { return it }
        val src = ImageSource.parse(artUrl) ?: return null
        val load = synchronized(covers) {
            covers.getOrPut(artUrl) {
                scope.async {
                    ImageLoader.load(src, COVER_LONG_SIDE).also {
                        if (it == null) log.info { "No usable album art at ${describe(artUrl)}" }
                    }
                }
            }
        }
        val px = withTimeoutOrNull(FIRST_ANSWER_WAIT_MS) { load.await() } ?: run {
            if (!load.isCompleted) scope.launch {
                if (load.await() != null) {
                    changed()
                    // The watch side records its pending request only after this call returns null; a second
                    // nudge covers a load that finished in between.
                    delay(500)
                    changed()
                }
            }
            return null
        }
        val art = WatchImage.encode(px, width, height)
        synchronized(encoded) { encoded[key] = art }
        return art
    }

    private fun describe(url: String) = if (url.startsWith("data:")) url.substringBefore(',') else url
}
