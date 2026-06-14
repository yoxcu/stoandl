@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.screenshot

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * Capture the connected watch's screen to a PNG on the host.
 *
 * libpebble3's [ConnectedPebbleDevice.takeScreenshotPixels] does the protocol work — it asks the watch over
 * the SCREENSHOT endpoint, reassembles the chunked transfer, and decodes the 1-bit or 8-bit framebuffer into
 * a row-major ARGB `IntArray`. (Its sibling `takeScreenshot()` returns a Compose `ImageBitmap`, which is a
 * null stub on the JVM/desktop build — hence the raw-pixel path.) We encode those pixels with [PngEncoder]
 * (pure-JDK, no AWT) and write the file. Purely local: no network, so no egress opt-in.
 *
 * The capture is a one-shot suspend that completes in a couple of seconds (5 s watch-side timeout), so the
 * D-Bus handler blocks on it directly rather than running async + polling like firmware/language installs.
 */
class ScreenshotControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
) {
    private fun device(): ConnectedPebbleDevice? =
        libPebbleRef.get()?.watches?.value?.filterIsInstance<ConnectedPebbleDevice>()?.firstOrNull()

    /**
     * Capture to the absolute [path] (the daemon's cwd differs from the CLI's, so the CLI resolves and sends
     * an absolute path). Returns a status-prefixed string: `ok:<path>\t<width>\t<height>`, `notready:<msg>`
     * (no watch), or `error:<msg>` (transfer timed out, watch busy, or the file couldn't be written).
     */
    fun capture(path: String): String {
        libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = device() ?: return "notready:No watch connected"
        return try {
            val shot = runBlocking { dev.takeScreenshotPixels() }
                ?: return "error:Screenshot failed (watch didn't respond, or a capture is already in progress)"
            val png = PngEncoder.encode(shot.argb, shot.width, shot.height)
            val out = File(path)
            out.absoluteFile.parentFile?.mkdirs()
            out.writeBytes(png)
            log.info { "Saved ${shot.width}×${shot.height} screenshot → ${out.path} (${png.size} bytes)" }
            "ok:${out.path}\t${shot.width}\t${shot.height}"
        } catch (e: Exception) {
            log.warn(e) { "Screenshot capture failed" }
            "error:${e.message ?: "screenshot failed"}"
        }
    }
}
