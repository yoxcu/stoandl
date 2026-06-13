package de.yoxcu.stoandl.location

import de.yoxcu.stoandl.dbus.GEOCLUE_BUS_NAME
import de.yoxcu.stoandl.dbus.GEOCLUE_CLIENT_IFACE
import de.yoxcu.stoandl.dbus.GEOCLUE_LOCATION_IFACE
import de.yoxcu.stoandl.dbus.GEOCLUE_MANAGER_PATH
import de.yoxcu.stoandl.dbus.GeoClueAccuracy
import de.yoxcu.stoandl.dbus.GeoClueClient
import de.yoxcu.stoandl.dbus.GeoClueManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32

/**
 * Resolves the device's current position from GeoClue2 — the standard Linux geolocation service
 * (system bus), which aggregates modem GPS, Wi-Fi and A-GPS. This is the headless, DE-agnostic way
 * to get a fix without GPS-specific code. It backs both weather's "current location" entry and the
 * watch-facing `navigator.geolocation` hook (see [GeoClueSystemGeolocation]).
 *
 * GeoClue authorises clients by their `DesktopId`; a headless daemon must be allow-listed in
 * `/etc/geoclue/geoclue.conf`:
 * ```
 * [stoandl]
 * allowed=true
 * system=true
 * users=
 * ```
 *
 * The client is created and `Start()`ed once; while active GeoClue keeps the client's `Location`
 * property current, so [currentFix]/[currentLatLon] just read it on demand. A `/` location path
 * means no fix yet. The provider self-heals: if the system-bus connection drops it is rebuilt on
 * the next read.
 */
class GeoClueLocationProvider(private val desktopId: String) {
    private val log = KotlinLogging.logger {}

    @Volatile private var conn: DBusConnection? = null
    @Volatile private var clientPath: String? = null

    /** A GeoClue position fix. [accuracy] (metres), [altitude] (metres), [speed] (m/s) and [heading]
     *  (degrees clockwise from north) are null when GeoClue reports them as unknown. */
    data class GeoClueFix(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double?,
        val altitude: Double?,
        val speed: Double?,
        val heading: Double?,
    )

    /** Establish (or re-establish) the GeoClue client. Returns true on success. */
    @Synchronized
    fun start(): Boolean = try {
        ensureClient()
        true
    } catch (e: Exception) {
        log.warn(e) {
            "GeoClue init failed (${e.message}). GPS location is unavailable until resolved — ensure " +
                "GeoClue is running and '$desktopId' is allow-listed in /etc/geoclue/geoclue.conf."
        }
        false
    }

    @Synchronized
    fun stop() {
        try { conn?.disconnect() } catch (_: Exception) {}
        conn = null
        clientPath = null
    }

    /** The latest known full position fix, or null if there's no connection/fix yet. */
    @Synchronized
    fun currentFix(): GeoClueFix? = try {
        ensureClient()
        val c = conn
        val cp = clientPath
        if (c == null || cp == null) {
            null
        } else {
            val clientProps = c.getRemoteObject(GEOCLUE_BUS_NAME, cp, Properties::class.java)
            val locPath = clientProps.Get<DBusPath>(GEOCLUE_CLIENT_IFACE, "Location")
            if (locPath == null || locPath.path == "/" || locPath.path.isEmpty()) {
                log.info { "GeoClue: no location fix yet" }
                null
            } else {
                val p = c.getRemoteObject(GEOCLUE_BUS_NAME, locPath.path, Properties::class.java)
                val lat = readDouble(p, "Latitude")
                val lon = readDouble(p, "Longitude")
                if (lat == null || lon == null) {
                    null
                } else {
                    GeoClueFix(
                        latitude = lat,
                        longitude = lon,
                        // GeoClue uses sentinels for "unknown": negative accuracy, a hugely negative
                        // altitude (-G_MAXDOUBLE), and -1 for speed/heading. Map those to null.
                        accuracy = readDouble(p, "Accuracy")?.takeIf { it >= 0 },
                        altitude = readDouble(p, "Altitude")?.takeIf { it > -1.0e308 },
                        speed = readDouble(p, "Speed")?.takeIf { it >= 0 },
                        heading = readDouble(p, "Heading")?.takeIf { it >= 0 },
                    )
                }
            }
        }
    } catch (e: Exception) {
        log.warn { "GeoClue location read failed: ${e.message}" }
        // Drop the connection so the next call rebuilds it.
        try { conn?.disconnect() } catch (_: Exception) {}
        conn = null
        clientPath = null
        null
    }

    /** The latest known position as a lat/lon pair, or null if there's no connection/fix yet. */
    fun currentLatLon(): Pair<Double, Double>? = currentFix()?.let { it.latitude to it.longitude }

    private fun readDouble(p: Properties, name: String): Double? =
        try { (p.Get<Any>(GEOCLUE_LOCATION_IFACE, name) as? Number)?.toDouble() } catch (_: Exception) { null }

    private fun ensureClient() {
        val existing = conn
        if (existing != null && existing.isConnected && clientPath != null) return
        try { existing?.disconnect() } catch (_: Exception) {}

        val c = DBusConnectionBuilder.forSystemBus().build() as DBusConnection
        conn = c
        val manager = c.getRemoteObject(GEOCLUE_BUS_NAME, GEOCLUE_MANAGER_PATH, GeoClueManager::class.java)
        val client = manager.GetClient()
        val props = c.getRemoteObject(GEOCLUE_BUS_NAME, client.path, Properties::class.java)
        // DesktopId ties the request to the geoclue.conf allow-list entry; EXACT requests GPS-grade accuracy.
        props.Set(GEOCLUE_CLIENT_IFACE, "DesktopId", desktopId)
        props.Set(GEOCLUE_CLIENT_IFACE, "RequestedAccuracyLevel", UInt32(GeoClueAccuracy.EXACT.toLong()))
        val clientObj = c.getRemoteObject(GEOCLUE_BUS_NAME, client.path, GeoClueClient::class.java)
        clientObj.Start()
        clientPath = client.path
        log.info { "GeoClue client started (${client.path}, desktopId=$desktopId)" }
    }
}
