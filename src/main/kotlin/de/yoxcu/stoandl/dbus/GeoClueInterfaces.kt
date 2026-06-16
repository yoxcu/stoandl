package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

const val GEOCLUE_BUS_NAME = "org.freedesktop.GeoClue2"
const val GEOCLUE_MANAGER_PATH = "/org/freedesktop/GeoClue2/Manager"
const val GEOCLUE_MANAGER_IFACE = "org.freedesktop.GeoClue2.Manager"
const val GEOCLUE_CLIENT_IFACE = "org.freedesktop.GeoClue2.Client"
const val GEOCLUE_LOCATION_IFACE = "org.freedesktop.GeoClue2.Location"

/** GeoClue2 accuracy levels (GClueAccuracyLevel). Only [EXACT] is requested today; the rest mirror
 *  the spec enum so a future accuracy setting has them to hand. */
object GeoClueAccuracy {
    const val NONE = 0
    const val COUNTRY = 1
    const val CITY = 4
    const val NEIGHBORHOOD = 5
    const val STREET = 6
    const val EXACT = 8
}

/** org.freedesktop.GeoClue2.Manager — hands out a per-sender client object. */
@DBusInterfaceName("org.freedesktop.GeoClue2.Manager")
interface GeoClueManager : DBusInterface {
    fun GetClient(): DBusPath
}

/** org.freedesktop.GeoClue2.Client — `DesktopId` and `RequestedAccuracyLevel` are set via the
 *  standard Properties interface before [Start]; the resolved location is then exposed as the
 *  `Location` object-path property (`/` until a fix is available). */
@DBusInterfaceName("org.freedesktop.GeoClue2.Client")
interface GeoClueClient : DBusInterface {
    fun Start()
    fun Stop()
}
