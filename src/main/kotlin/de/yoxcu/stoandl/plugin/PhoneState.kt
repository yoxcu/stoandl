package de.yoxcu.stoandl.plugin

import de.yoxcu.stoandl.power.delayWallClock
import de.yoxcu.stoandl.util.openSystemBus
import de.yoxcu.stoandl.util.unwrapVariant
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.plugin.CellGenerations
import io.rebble.libpebblecommon.plugin.Connections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

private val log = KotlinLogging.logger {}

/** How often the phone's battery and radios are re-read while a plugin subscriber is watching. */
private val POLL = 1.minutes
/** How long a read waits for the system-bus connection, and how soon a failed connect is retried. */
private const val CONNECT_WAIT_MS = 2_000L
private const val RETRY_MS = 10 * 60_000L

/**
 * The phone's battery and network for the built-in Phone plugin, read off the system bus: UPower's display
 * device, NetworkManager's primary connection and ModemManager's access technologies. Each flow emits the
 * current value when collected and again when it changes (polled on the wall clock while collected), and
 * null where the service or the hardware isn't there (a desktop has no modem).
 */
class PhoneState(private val wakeups: () -> Flow<Unit>) {
    val batteryLevel: Flow<Int?> = poll { bus -> battery(bus) }
    val connection: Flow<String?> = poll { bus -> connection(bus) }
    val cellGeneration: Flow<String?> = poll { bus -> cellGeneration(bus) }

    // One system-bus connection for all three, opened off the reading coroutine: connecting can block for
    // many seconds where there is no system bus, and a blocked connect would hold up cancelling a read.
    @Volatile private var busFuture: CompletableFuture<DBusConnection>? = null
    @Volatile private var busFailedAtMs = 0L

    private fun bus(): DBusConnection? {
        val f = busFuture?.takeUnless { it.isCompletedExceptionally && System.currentTimeMillis() - busFailedAtMs > RETRY_MS }
            ?: CompletableFuture.supplyAsync({ openSystemBus() }, { r -> Thread(r, "phone-state-bus").apply { isDaemon = true }.start() })
                .also { busFuture = it }
        return try {
            f.get(CONNECT_WAIT_MS, TimeUnit.MILLISECONDS).takeIf { it.isConnected }
        } catch (e: Exception) {
            if (f.isCompletedExceptionally && busFailedAtMs == 0L) log.debug { "No system bus for the phone state: ${e.message}" }
            if (f.isCompletedExceptionally) busFailedAtMs = System.currentTimeMillis()
            null
        }
    }

    private fun <T> poll(read: (DBusConnection) -> T?): Flow<T?> = flow {
        while (true) {
            val v = try {
                bus()?.let(read)
            } catch (e: Exception) {
                log.debug { "Phone state read failed: ${e.message}" }
                null
            }
            emit(v)
            delayWallClock(POLL, wakeups())
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun props(bus: DBusConnection, name: String, path: String, iface: String): Map<String, Any?> =
        bus.getRemoteObject(name, path, Properties::class.java).GetAll(iface).mapValues { unwrapVariant(it.value) }

    private fun battery(bus: DBusConnection): Int? {
        val p = props(bus, "org.freedesktop.UPower", "/org/freedesktop/UPower/devices/DisplayDevice", "org.freedesktop.UPower.Device")
        if (p["IsPresent"] != true) return null
        return (p["Percentage"] as? Number)?.toDouble()?.let { Math.round(it).toInt().coerceIn(0, 100) }
    }

    private fun connection(bus: DBusConnection): String? {
        val p = props(bus, "org.freedesktop.NetworkManager", "/org/freedesktop/NetworkManager", "org.freedesktop.NetworkManager")
        return connectionOf(p["PrimaryConnectionType"] as? String, (p["State"] as? Number)?.toInt())
    }

    private fun cellGeneration(bus: DBusConnection): String? {
        val mm = bus.getRemoteObject("org.freedesktop.ModemManager1", "/org/freedesktop/ModemManager1",
            org.freedesktop.dbus.interfaces.ObjectManager::class.java)
        val modem = mm.GetManagedObjects().keys.firstOrNull { it.path.contains("/Modem/") } ?: return null
        val p = props(bus, "org.freedesktop.ModemManager1", modem.path, "org.freedesktop.ModemManager1.Modem")
        return cellGenerationOf((p["AccessTechnologies"] as? Number)?.toLong() ?: 0L)
    }
}

/** NetworkManager's primary connection as the plugin's vocabulary; null for kinds it has no word for
 *  (Ethernet, VPN). [state] 70 = connected globally; below 50 nothing is connected. */
internal fun connectionOf(type: String?, state: Int?): String? = when {
    state != null && state < 50 -> Connections.NONE
    type == "802-11-wireless" -> Connections.WIFI
    type == "gsm" || type == "cdma" -> Connections.CELLULAR
    type.isNullOrEmpty() || type == "/" -> Connections.NONE
    else -> null
}

/** ModemManager `MMModemAccessTechnology` bits as a cellular generation. */
internal fun cellGenerationOf(bits: Long): String = when {
    bits and (1L shl 15) != 0L -> CellGenerations.FIVE_G                                    // 5GNR
    bits and ((1L shl 14) or (1L shl 16) or (1L shl 17)) != 0L -> CellGenerations.FOUR_G    // LTE, Cat-M, NB-IoT
    bits and (0b11111L shl 5 or (0b111L shl 11)) != 0L -> CellGenerations.THREE_G           // UMTS..HSPA+, EV-DO
    bits and ((0b1111L shl 1) or (1L shl 10)) != 0L -> CellGenerations.TWO_G                // GSM..EDGE, 1xRTT
    else -> CellGenerations.NONE
}
