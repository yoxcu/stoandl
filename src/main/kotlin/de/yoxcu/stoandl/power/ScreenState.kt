package de.yoxcu.stoandl.power

import java.io.File

/**
 * Whether the host's display is on, read from the kernel's DRM connectors
 * (`/sys/class/drm/card*-<connector>/dpms`, world-readable). On a phone this follows the panel exactly:
 * Plasma Mobile / KWin turn the DSI panel's DPMS off when the screen goes dark, and the OnePlus 6
 * wake-on-notification scripts use the same file to decide whether the user is looking.
 *
 * Used to keep radio-heavy, user-facing work — the pairing-window BLE scan and BR/EDR inquiry, which
 * make the controller report (and, with links kept across suspend, wake the host for) every nearby
 * advertiser — to times when someone is actually using the device.
 *
 * Returns null when it can't tell (no DRM device, a headless box, sysfs unreadable): callers treat
 * that as "on" so nothing is gated on machines without a local display.
 */
object ScreenState {
    private val DRM = File("/sys/class/drm")

    fun isOn(): Boolean? {
        val connectors = DRM.listFiles { f -> f.name.startsWith("card") && f.name.contains('-') } ?: return null
        var sawOff = false
        for (c in connectors) {
            val status = read(File(c, "status")) ?: continue
            if (status == "disconnected") continue
            when (read(File(c, "dpms"))) {
                "On" -> return true
                "Off", "Standby", "Suspend" -> sawOff = true
            }
        }
        return if (sawOff) false else null
    }

    private fun read(f: File): String? = try { f.readText().trim() } catch (_: Exception) { null }
}
