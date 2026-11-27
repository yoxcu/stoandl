@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.screenshot

import de.yoxcu.stoandl.util.connectedDevice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * Capture the connected watch's screen to a PNG on the host.
 *
 * libpebble3's [ConnectedPebbleDevice.takeScreenshot] does the protocol work — it asks the watch over
 * the SCREENSHOT endpoint, reassembles the chunked transfer, and decodes the 1-bit or 8-bit framebuffer into
 * a `PebbleBitmap`: row-major, non-premultiplied ARGB pixels. We encode those with [PngEncoder] (pure-JDK,
 * no AWT) and write the file. Purely local: no network, so no egress opt-in.
 *
 * The capture is a one-shot suspend that completes in a couple of seconds (5 s watch-side timeout); the
 * daemon runs it as a [de.yoxcu.stoandl.pebble.WatchTasks] task, so no D-Bus call waits on it.
 */
class ScreenshotControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
) {
    private fun device(): ConnectedPebbleDevice? = libPebbleRef.connectedDevice()

    /**
     * Capture to [out]. Returns a status-prefixed string: `ok:<path>\t<width>\t<height>`, `notready:<msg>`
     * (no watch), or `error:<msg>` (transfer timed out, watch busy, or the file couldn't be written).
     */
    suspend fun capture(out: File): String {
        val dev = device() ?: return "notready:No watch connected"
        return try {
            val shot = dev.takeScreenshot()
                ?: return "error:Screenshot failed (watch didn't respond, or a capture is already in progress)"
            val png = PngEncoder.encode(shot.pixels, shot.width, shot.height)
            withContext(Dispatchers.IO) {
                out.absoluteFile.parentFile?.mkdirs()
                out.writeBytes(png)
            }
            log.info { "Saved ${shot.width}×${shot.height} screenshot → ${out.path} (${png.size} bytes)" }
            "ok:${out.path}\t${shot.width}\t${shot.height}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Screenshot capture failed" }
            "error:${e.message ?: "screenshot failed"}"
        }
    }
}
