package de.yoxcu.stoandl.plugin

import de.yoxcu.stoandl.power.delayWallClock
import de.yoxcu.stoandl.util.openSystemBus
import de.yoxcu.stoandl.util.unwrapVariant
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.plugin.CellGenerations
import io.rebble.libpebblecommon.plugin.Connections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.minutes

private val log = KotlinLogging.logger {}

/** How often the phone's battery and cellular radio are re-read while a plugin subscriber is watching. */
private val POLL = 1.minutes
/** How long a read waits for the system-bus connection, and how soon a failed connect is retried. */
private const val CONNECT_WAIT_MS = 2_000L
private const val RETRY_MS = 10 * 60_000L

private const val NM_NAME = "org.freedesktop.NetworkManager"
private const val NM_PATH = "/org/freedesktop/NetworkManager"

/**
 * The phone's battery and network for the built-in Phone plugin, read off the system bus: UPower's display
 * device, NetworkManager's primary connection and ModemManager's access technologies. Each flow emits the
 * current value when collected and again when it changes, and null where the service or the hardware isn't
 * there (a desktop has no modem). Battery and modem are polled on the wall clock while collected.
 *
 * [connection] follows NetworkManager's signals instead of a timer: libpebble3 collects it for as long as it
 * runs (its data-usage log tags every watchapp and plugin request with the connection), and a poll would wake
 * an idle daemon once a minute.
 */
class PhoneState(
    private val wakeups: () -> Flow<Unit>,
    private val openBus: () -> DBusConnection = ::openSystemBus,
) {
    val batteryLevel: Flow<Int?> = poll { bus -> battery(bus) }
    val connection: Flow<String?> = followed(NM_NAME, NM_PATH) { bus -> connection(bus) }
    val cellGeneration: Flow<String?> = poll { bus -> cellGeneration(bus) }

    // One system-bus connection for all three, opened off the reading coroutine: connecting can block for
    // many seconds where there is no system bus, and a blocked connect would hold up cancelling a read.
    @Volatile private var busFuture: CompletableFuture<DBusConnection>? = null
    /** When [busFuture] was first seen failed (0 while it hasn't): a failed connect is retried [RETRY_MS] after it. */
    @Volatile private var busFailedAtMs = 0L
    @Volatile private var noBusLogged = false

    /** The connect attempt to use now: the current one, unless it failed more than [RETRY_MS] ago. */
    @Synchronized
    private fun connect(): CompletableFuture<DBusConnection> {
        busFuture?.let { f ->
            if (!f.isCompletedExceptionally) return f
            noteFailed(f)
            if (System.currentTimeMillis() - busFailedAtMs <= RETRY_MS) return f
        }
        busFailedAtMs = 0L
        return CompletableFuture.supplyAsync({ openBus() }, { r -> Thread(r, "phone-state-bus").apply { isDaemon = true }.start() })
            .also { busFuture = it }
    }

    /** Stamp the current attempt [f] as failed, once: the retry period counts from here. */
    @Synchronized
    private fun noteFailed(f: CompletableFuture<DBusConnection>) {
        if (f !== busFuture || !f.isCompletedExceptionally || busFailedAtMs != 0L) return
        busFailedAtMs = System.currentTimeMillis()
        if (!noBusLogged) {
            noBusLogged = true
            log.debug { "No system bus for the phone state: ${runCatching { f.join() }.exceptionOrNull()?.cause?.message}" }
        }
    }

    /** The system bus, or null while it is still connecting (up to [CONNECT_WAIT_MS] is waited for) or failed. */
    private fun bus(): DBusConnection? = readBus().bus

    /** [bus], and the connect attempt when it was still under way at the wait's end ([stillConnecting]). */
    private class BusRead(val bus: DBusConnection?, val stillConnecting: CompletableFuture<DBusConnection>? = null)

    private fun readBus(): BusRead {
        val f = connect()
        return try {
            BusRead(f.get(CONNECT_WAIT_MS, TimeUnit.MILLISECONDS).takeIf { it.isConnected })
        } catch (e: TimeoutException) {
            BusRead(null, stillConnecting = f)
        } catch (e: Exception) {
            noteFailed(f)
            BusRead(null)
        }
    }

    private fun <T> poll(read: (DBusConnection) -> T?): Flow<T?> = flow {
        while (true) {
            emit(readOrNull(bus(), read))
            delayWallClock(POLL, wakeups())
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /**
     * [read] when collected, then again when the service [name] changes owner (it starts, restarts or goes),
     * when its object at [path] signals PropertiesChanged, and after a resume: no timer while the system bus is
     * there. A connect still under way when it reads is followed to its end (it answers late on a loaded
     * boot), and a failed one is retried every [RETRY_MS], like the polled flows' connect.
     */
    private fun <T> followed(name: String, path: String, read: (DBusConnection) -> T?): Flow<T?> = channelFlow {
        val changed = Channel<Unit>(Channel.CONFLATED)
        launch { wakeups().collect { changed.trySend(Unit) } }
        var subscribed: DBusConnection? = null
        var subscriptions: List<AutoCloseable> = emptyList()
        try {
            while (true) {
                val attempt = readBus()
                val bus = attempt.bus
                if (bus == null || bus !== subscribed) {
                    subscriptions.forEach { runCatching { it.close() } }
                    // Subscribed before the read, so a change between the two isn't missed.
                    subscriptions = bus?.let { subscribe(it, name, path) { changed.trySend(Unit) } }.orEmpty()
                    subscribed = bus.takeIf { subscriptions.isNotEmpty() }
                }
                send(readOrNull(bus, read))
                if (subscribed == null) {
                    // Still connecting (slow on a loaded boot): read again the moment the connect ends, not a
                    // retry period later. (Registered after the end, it fires at once: one more read.)
                    attempt.stillConnecting?.whenComplete { _, _ -> changed.trySend(Unit) }
                    withTimeoutOrNull(RETRY_MS) { changed.receive() }
                } else {
                    changed.receive()
                }
            }
        } finally {
            subscriptions.forEach { runCatching { it.close() } }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** PropertiesChanged on [path] and NameOwnerChanged for [name]. Neither rule names a sender: dbus-java
     *  matches a sender against the unique name, so a well-known one never fires. Empty if the bus refused. */
    private fun subscribe(bus: DBusConnection, name: String, path: String, onChange: () -> Unit): List<AutoCloseable> {
        val handler = DBusSigHandler<DBusSignal> { onChange() }
        val rules = listOf(
            DBusMatchRuleBuilder.create().withType("signal").withInterface("org.freedesktop.DBus.Properties")
                .withMember("PropertiesChanged").withPath(path).build(),
            DBusMatchRuleBuilder.create().withType("signal").withInterface("org.freedesktop.DBus")
                .withMember("NameOwnerChanged").withArg0123(0, name).build(),
        )
        val added = mutableListOf<AutoCloseable>()
        return try {
            rules.forEach { added += bus.addGenericSigHandler(it, handler) }
            added
        } catch (e: Exception) {
            log.debug { "Can't follow $name: ${e.message}" }
            added.forEach { runCatching { it.close() } }
            emptyList()
        }
    }

    private fun <T> readOrNull(bus: DBusConnection?, read: (DBusConnection) -> T?): T? = try {
        bus?.let(read)
    } catch (e: Exception) {
        log.debug { "Phone state read failed: ${e.message}" }
        null
    }

    private fun props(bus: DBusConnection, name: String, path: String, iface: String): Map<String, Any?> =
        bus.getRemoteObject(name, path, Properties::class.java).GetAll(iface).mapValues { unwrapVariant(it.value) }

    private fun battery(bus: DBusConnection): Int? {
        val p = props(bus, "org.freedesktop.UPower", "/org/freedesktop/UPower/devices/DisplayDevice", "org.freedesktop.UPower.Device")
        if (p["IsPresent"] != true) return null
        return (p["Percentage"] as? Number)?.toDouble()?.let { Math.round(it).toInt().coerceIn(0, 100) }
    }

    private fun connection(bus: DBusConnection): String? {
        val p = props(bus, NM_NAME, NM_PATH, NM_NAME)
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
