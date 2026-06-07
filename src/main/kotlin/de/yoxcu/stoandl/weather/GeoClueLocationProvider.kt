package de.yoxcu.stoandl.weather

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
 * to get a fix without GPS-specific code.
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
 * property current, so [currentLatLon] just reads it on demand each weather sync. A `/` location
 * path means no fix yet. The provider self-heals: if the system-bus connection drops it is rebuilt
 * on the next read.
 */
class GeoClueLocationProvider(private val desktopId: String) {
    private val log = KotlinLogging.logger {}

    @Volatile private var conn: DBusConnection? = null
    @Volatile private var clientPath: String? = null

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

    /** The latest known position, or null if there's no connection/fix yet. */
    @Synchronized
    fun currentLatLon(): Pair<Double, Double>? = try {
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
                val locProps = c.getRemoteObject(GEOCLUE_BUS_NAME, locPath.path, Properties::class.java)
                val lat = (locProps.Get<Any>(GEOCLUE_LOCATION_IFACE, "Latitude") as? Number)?.toDouble()
                val lon = (locProps.Get<Any>(GEOCLUE_LOCATION_IFACE, "Longitude") as? Number)?.toDouble()
                if (lat == null || lon == null) null else lat to lon
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
