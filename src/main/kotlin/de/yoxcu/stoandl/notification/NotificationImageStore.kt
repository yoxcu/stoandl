@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.notification

import de.yoxcu.stoandl.imaging.ImageLoader
import de.yoxcu.stoandl.imaging.ImageSource
import de.yoxcu.stoandl.imaging.WatchImage
import de.yoxcu.stoandl.screenshot.PngEncoder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.imaging.EncodedImage
import io.rebble.libpebblecommon.imaging.ImagingService
import io.rebble.libpebblecommon.imaging.NotificationImageProvider
import io.rebble.libpebblecommon.imaging.aspectSixteenths
import io.rebble.libpebblecommon.packets.Imaging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

/** Longest side of a stored image: every size the watch asks for fits (it never asks beyond 300). */
private const val MASTER_LONG_SIDE = ImagingService.MAX_DIM

/** Images kept, newest first; older ones are deleted (the watch then shows an empty band, as on Android). */
private const val MAX_STORED = 32

/**
 * The pictures attached to watch notifications. When a notification with an image is sent, [put] decodes
 * it once, shrinks it to a ≤300 px master and stores it as `<itemId>.png` under [dir], returning the
 * aspect the notification announces (`TimelineAttribute.ImageAspectRatio`). The watch pulls the image
 * when the card comes into view, sized for its screen; [register] answers those pulls from the store,
 * so it works after a daemon restart too.
 *
 * Deleted with the notification ([remove]: dismissed on the wrist or elsewhere) and capped at
 * [MAX_STORED]. The directory is in the cache, never in backups or support bundles.
 */
class NotificationImageStore(private val dir: File) : NotificationImageProvider {
    private val log = KotlinLogging.logger {}

    /** Store [src] for [itemId]; the aspect (height/width in sixteenths) to announce, or null when it isn't
     *  a usable image (then the notification goes out without one). */
    suspend fun put(itemId: Uuid, src: ImageSource): UByte? {
        val px = ImageLoader.load(src, MASTER_LONG_SIDE) ?: return null
        return try {
            dir.mkdirs()
            val tmp = File.createTempFile("img", ".tmp", dir)
            tmp.writeBytes(PngEncoder.encodeRgba(px.argb, px.width, px.height))
            Files.move(tmp.toPath(), file(itemId).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            prune()
            aspectSixteenths(px.width, px.height)
        } catch (e: Exception) {
            log.warn { "Couldn't store the image of notification $itemId: ${e.message}" }
            null
        }
    }

    fun remove(itemId: Uuid) { file(itemId).delete() }

    /** Delete every stored image (images switched off). */
    fun purge() { dir.listFiles { f -> f.name.endsWith(".png") }?.forEach { it.delete() } }

    override fun register(imagingService: ImagingService) {
        imagingService.registerHandler(Imaging.ImageType.NotificationImage) { request ->
            val r = request as? Imaging.NotificationImageRequest ?: return@registerHandler null
            image(r.itemId.get(), r.width.get().toInt(), r.height.get().toInt())
        }
    }

    /** The stored image for [itemId] encoded at exactly [width]×[height], or null when there is none. */
    suspend fun image(itemId: Uuid, width: Int, height: Int): EncodedImage? {
        val f = file(itemId)
        if (!f.isFile) { log.debug { "No stored image for notification $itemId" }; return null }
        val px = ImageLoader.load(ImageSource.LocalFile(f.toPath()), MASTER_LONG_SIDE) ?: return null
        return WatchImage.encode(px, width, height).also {
            log.debug { "Notification $itemId image served at ${width}x$height" }
        }
    }

    private fun file(itemId: Uuid) = File(dir, "$itemId.png")

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".png") } ?: return
        if (files.size <= MAX_STORED) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_STORED).forEach { it.delete() }
    }
}
