@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.dbus.FreedesktopNotifications
import de.yoxcu.stoandl.dbus.IncomingNotification
import de.yoxcu.stoandl.dbus.ModemManagerCallMonitor
import de.yoxcu.stoandl.calls.MissedCallLog
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.config.StoandlConfig.WeatherLocationSource
import de.yoxcu.stoandl.weather.DeLocationSource
import de.yoxcu.stoandl.weather.GeoClueLocationProvider
import de.yoxcu.stoandl.weather.WeatherSync
import de.yoxcu.stoandl.contacts.ContactResolver
import de.yoxcu.stoandl.contacts.DialerNameCache
import io.rebble.libpebblecommon.calls.SystemCallLog
import de.yoxcu.stoandl.dbus.STOANDL_BUS_NAME
import de.yoxcu.stoandl.dbus.STOANDL_OBJECT_PATH
import de.yoxcu.stoandl.dbus.StoandlControl
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.BleConfig
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.isBonded
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.ConnectingPebbleDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.connection.WatchConnector
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.di.initKoin
import io.rebble.libpebblecommon.js.InjectedPKJSHttpInterceptors
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlinx.io.files.Path
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.system.exitProcess
import kotlin.uuid.Uuid
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private const val MAX_CONNECTION_ATTEMPTS = 5
private const val PAIRING_WINDOW_MS = 120_000L  // 2 minutes

/** Allows pairing with unbonded watches only while the window is open. */
private class PairingGate {
    private val expiryMs = java.util.concurrent.atomic.AtomicLong(0L)
    fun open() { expiryMs.set(System.currentTimeMillis() + PAIRING_WINDOW_MS) }
    fun close() { expiryMs.set(0L) }
    fun isOpen(): Boolean = System.currentTimeMillis() < expiryMs.get()
}

// Reconnect watchdog cadence and the stall window after which we treat the BLE stack as wedged
// and restart the process. The window must comfortably exceed the gap between failed connection
// attempts (~20-60s each) so an out-of-range watch — which keeps failing, i.e. shows activity —
// never trips it; only a true stall (no attempts at all) does.
private val WATCHDOG_INTERVAL = 60.seconds
private val WATCHDOG_STALL_RESTART = 10.minutes
// How long a bonded watch may be unreachable *while the BLE stack is actively retrying* before we
// nudge the user (once) to run `stoandl unpair`. Purely informational — it never touches the bond —
// so it's safe to keep short; the only cost of a low value is an ignorable nudge when the watch is
// merely out of range for a while.
private val UNREACHABLE_NOTIFY = 5.minutes
private const val SCAN_FAILURE_RESTART_THRESHOLD = 5  // ~5 failed scan cycles (~3min) → restart for a clean stack

// Stale-bond reaper: a bonded watch that is in range but keeps failing to connect (the watch no
// longer honours BlueZ's bond — wiped, re-paired elsewhere, or a phantom object). Acts only on
// FailedToConnect (link established/attempted then rejected = watch present); ConnectTimeout
// (out of range) is ignored, so an away watch is never disturbed.
private val REAPER_INTERVAL = 30.seconds
private const val STALE_FAILS_THRESHOLD = 5      // consecutive present-but-failed connects before clearing
private val REPAIR_GRACE = 90.seconds            // after clearing, allow auto re-pair before forgetting

private val log = KotlinLogging.logger {}

class PebbleIntegration(
    private val notificationFlow: Flow<IncomingNotification>,
    private val scope: CoroutineScope,
    private val serviceConn: DBusConnection,
) {
    private lateinit var libPebble: LibPebble
    private lateinit var watchConnector: WatchConnector
    private val libPebbleRef = AtomicReference<LibPebble?>(null)
    private val weatherSyncRef = AtomicReference<WeatherSync?>(null)
    private val watchPrefsControlRef = AtomicReference<WatchPrefsControl?>(null)
    private val config = StoandlConfig.load()
    private val contactResolver = ContactResolver(config.vcardPaths)
    private val dialerNameCache = DialerNameCache()
    private val missedCallLog = MissedCallLog()
    // Headless BlueZ pairing agent so MITM/Secure-Connections pairing (newer Pebble firmware,
    // e.g. Time 2) can complete without a desktop UI — the user just confirms the code on the watch.
    private val pairingAgent = BluezPairingAgent()
    private val pairingGate = PairingGate()
    // "pending:" while pairing is in progress; "ok:…" / "error:…" / "timeout:…" when done.
    private val pairingState = AtomicReference<String>("")
    // Bond-state cache keyed by identifier.asString — avoids repeated BlueZ D-Bus round-trips.
    private val bondCache = ConcurrentHashMap<String, Boolean>()
    // Reflects org.bluez.Adapter1.Powered — set by watchBluetoothPowerState().
    // Distinct from libPebble.bluetoothEnabled: covers rfkill/airplane-mode blocks where
    // GattServerManager still reports Enabled but the radio is actually off.
    private val btAdapterPowered = MutableStateFlow(true)

    fun init() {
        // Register the pairing agent before any connection so MITM pairing has an answerer.
        pairingAgent.register()

        val bleConfig = LibPebbleConfig(bleConfig = BleConfig(reversedPPoG = false))
        val koin = initKoin(
            defaultConfig = bleConfig,
            webServices = NoOpWebServices,
            appContext = AppContext(),
            tokenProvider = NoOpTokenProvider,
            proxyTokenProvider = MutableStateFlow(null),
            transcriptionProvider = NoOpTranscriptionProvider,
            injectedPKJSHttpInterceptors = InjectedPKJSHttpInterceptors(emptyList()),
        )

        // Shared map: watch-side timeline item UUID → daemon-assigned D-Bus notification ID.
        // Populated by DbusNotificationListenerConnection; consumed by DbusNotificationActionHandler.
        val itemIdToDbusId = ConcurrentHashMap<Uuid, UInt32>()

        // Override modules: use our DBus notification bridge, and pin BleConfigFlow so any
        // persisted Java Preferences value cannot override reversedPPoG=false.
        koin.loadModules(listOf(module {
            single<NotificationListenerConnection> {
                DbusNotificationListenerConnection(
                    notificationFlow, scope, itemIdToDbusId,
                    blocklist = config.notificationBlocklist,
                    dialerApps = config.dialerApps,
                    dialerNameCache = dialerNameCache,
                )
            }
            single { BleConfigFlow(MutableStateFlow(bleConfig)) }
            single<PlatformNotificationActionHandler> { DbusNotificationActionHandler(itemIdToDbusId, libPebbleRef) }
            // Back MissedCallSyncer with our ModemManager-fed log so missed calls become timeline pins.
            single<SystemCallLog> { missedCallLog }
        }), allowOverride = true)

        libPebble = koin.get()
        libPebbleRef.set(libPebble)
        watchConnector = koin.get()
        watchBluetoothPowerState()
        libPebble.init()
        startScanLoop()
        startAutoConnect()
        startConnectionWatchdog()
        startStaleBondReaper()
        registerControlService()
        startCallMonitor()
        startWeatherSync()
        startWatchPrefsSync()

        log.info { "libpebble3 initialized" }
    }

    fun gracefulShutdown() {
        if (!::libPebble.isInitialized) return
        log.info { "graceful BLE shutdown: disconnecting watches" }
        runBlocking {
            // Drop the BLE link at the BlueZ level (watch sees us go down → re-advertises), but do NOT
            // use requestDisconnection(): it persists connectGoal=false, which makes the next launch
            // refuse to reconnect to its own watch until a manual re-pair.
            libPebble.watches.value.forEach { device ->
                (device.identifier as? PebbleBleIdentifier)?.let { id ->
                    bluezObjectPath(id.asString)?.let { withContext(Dispatchers.IO) { disconnectBluezDevice(it) } }
                }
            }
            delay(1.5.seconds)
        }
    }

    private fun startScanLoop() {
        scope.launch {
            // Wait for watchBluetoothPowerState() to complete its initial GetManagedObjects() check
            // before the first scan attempt so btAdapterPowered reflects reality from the start.
            delay(1.seconds)
            var consecutiveScanFailures = 0
            while (true) {
                // Suspend when BT is disabled — from libpebble3's state OR from our Powered watcher
                // (covers rfkill / airplane mode where GattServerManager may still report Enabled).
                // Wait for BOTH sources to agree BT is on before attempting anything.
                val btOn = libPebble.bluetoothEnabled.value.enabled() && btAdapterPowered.value
                if (!btOn) {
                    if (libPebble.isScanningBle.value) libPebble.stopBleScan()
                    consecutiveScanFailures = 0
                    combine(libPebble.bluetoothEnabled, btAdapterPowered) { bt, powered ->
                        bt.enabled() && powered
                    }.first { it }
                    delay(2.seconds) // brief settle so BlueZ is ready before we scan
                    continue
                }

                // Fix A: don't scan while connected OR while a connect attempt is in flight. A scan
                // started during a connect collides with it on BlueZ's single discovery slot
                // (org.bluez.Error.InProgress); the connect then never completes — the watch sits in
                // ConnectingPebbleDevice for the full timeout and the cycle repeats forever (wedge #3).
                val busy = libPebble.watches.value.any {
                    it is ConnectedPebbleDevice || it is ConnectingPebbleDevice
                }
                if (busy) {
                    // No need to discover while connected/connecting; stop any lingering scan.
                    if (libPebble.isScanningBle.value) libPebble.stopBleScan()
                    consecutiveScanFailures = 0
                } else {
                    // Stop an in-flight scan before restarting it. Calling startBleScan() while BlueZ
                    // already has a discovery running returns org.bluez.Error.InProgress and the scan
                    // then never refreshes, so a watch that comes back into range is never re-discovered.
                    if (libPebble.isScanningBle.value) {
                        libPebble.stopBleScan()
                        delay(1.seconds)
                    }
                    log.info { "Starting BLE scan" }
                    libPebble.startBleScan()

                    // Fix B: detect a wedged BLE stack the connection watchdog can't see. startBleScan()
                    // sets isScanningBle=true synchronously; if the scan flow throws (e.g. another process
                    // holds the BlueZ discovery slot) it flips back to false within moments, whereas a
                    // healthy scan stays active for ~30s. Repeated failures → stack is wedged → restart.
                    delay(3.seconds)
                    val stillIdle = libPebble.watches.value.none {
                        it is ConnectedPebbleDevice || it is ConnectingPebbleDevice
                    }
                    if (stillIdle && !libPebble.isScanningBle.value) {
                        consecutiveScanFailures++
                        log.warn { "BLE scan failed to start ($consecutiveScanFailures consecutive)" }
                        if (consecutiveScanFailures >= SCAN_FAILURE_RESTART_THRESHOLD) {
                            log.error {
                                "BLE scan stack wedged ($consecutiveScanFailures consecutive scan " +
                                    "failures); exiting for systemd restart"
                            }
                            gracefulShutdown()
                            exitProcess(1)
                        }
                    } else {
                        consecutiveScanFailures = 0
                    }
                }
                delay(35.seconds) // slightly longer than the 30s scan timeout
            }
        }
    }

    /**
     * Backstop for a wedged native BLE stack. A bonded watch that is merely out of range still shows
     * "connection activity" (libpebble keeps attempting → ConnectingPebbleDevice transitions and an
     * ever-incrementing failure counter); a wedged stack shows none at all. If a bonded, non-connected
     * watch produces zero activity for [WATCHDOG_STALL_RESTART] while Bluetooth is on, exit so systemd
     * restarts us with a fresh stack. The WatchManager fork fix should prevent the wedge we diagnosed;
     * this also covers any residual wedge originating in the external kable/btleplug layer.
     */
    private fun startConnectionWatchdog() {
        val lastHealthyMs = AtomicReference(System.currentTimeMillis())
        // Per-watch last-seen attempt counter, polled directly in the loop so we don't depend on the
        // timing of libPebble.watches emissions (which previously let the wedge check false-positive).
        val lastFailTimes = ConcurrentHashMap<String, Int>()
        var notifiedUnreachable = false

        scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL)
                val devices = libPebble.watches.value
                val connected = devices.any { it is ConnectedPebbleDevice }
                val bonded = devices.filterIsInstance<KnownPebbleDevice>().filter { it !is ConnectedPebbleDevice }
                if (connected || bonded.isEmpty() || !libPebble.bluetoothEnabled.value.enabled()) {
                    lastHealthyMs.set(System.currentTimeMillis())
                    lastFailTimes.clear()
                    notifiedUnreachable = false
                    continue
                }
                // Is the BLE stack actively (re)trying? A connect in progress, or any bonded watch's
                // attempt counter moving since the last check, means the stack is alive — the watch is
                // just unreachable (out of range, or bonded to another host). Restarting can't fix that.
                var attempting = devices.any { it is ConnectingPebbleDevice }
                bonded.forEach { d ->
                    val t = d.connectionFailureInfo?.times ?: 0
                    if (lastFailTimes.put(d.identifier.asString, t) != t) attempting = true
                }
                val stalledMs = System.currentTimeMillis() - lastHealthyMs.get()

                if (attempting) {
                    // Stack works, watch won't connect → never restart (futile). Nudge the user once
                    // after the (short, harmless) notify window.
                    if (stalledMs >= UNREACHABLE_NOTIFY.inWholeMilliseconds && !notifiedUnreachable) {
                        notifiedUnreachable = true
                        val name = bonded.firstOrNull()?.displayName() ?: "Your Pebble"
                        log.warn {
                            "Bonded watch unreachable for ${stalledMs / 1000}s but BLE stack is active " +
                                "(still attempting) — not restarting. If you've paired it elsewhere, run 'stoandl unpair'."
                        }
                        sendDesktopNotification(
                            "Pebble not connecting",
                            "$name hasn't connected in ${stalledMs / 60000} min. If you've paired it to " +
                                "another device, run 'stoandl unpair' on this host.",
                        )
                    }
                } else if (stalledMs >= WATCHDOG_STALL_RESTART.inWholeMilliseconds) {
                    // No connection attempts at all → the BLE stack itself is wedged → restart.
                    log.error {
                        "Watchdog: no connection activity for ${stalledMs / 1000}s — BLE stack appears " +
                            "wedged; exiting for systemd restart"
                    }
                    gracefulShutdown()
                    exitProcess(1)
                }
            }
        }
    }

    /**
     * Stale-bond reaper — one mechanism for the whole "BlueZ holds a bond the watch no longer
     * honours" family (wiped watch, re-paired-to-another-host watch, phantom device object), instead
     * of matching individual BlueZ error strings.
     *
     * A bonded watch that is *in range but persistently failing to connect* has a stale bond. The
     * signal is [ConnectionFailureReason.FailedToConnect] — the link established (or the phantom
     * "doesn't exist" object, which the connector maps to the same reason) then the watch rejected
     * us. We clear the bond ([removeBluezBond]) and let libpebble3 attempt a fresh pairing, which
     * auto-heals a wiped+pairable watch. If it still hasn't connected after [REPAIR_GRACE], the watch
     * won't re-pair with us (it's bonded elsewhere), so we [KnownPebbleDevice.forget] it and notify —
     * stopping the reconnect storm and the futile watchdog restarts.
     *
     * Out-of-range watches fail with [ConnectionFailureReason.ConnectTimeout], never FailedToConnect,
     * so an away (but still-wanted) watch's bond is never touched.
     */
    private fun startStaleBondReaper() {
        val clearedAt = ConcurrentHashMap<String, Long>() // identifier -> when we removed its bond
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(REAPER_INTERVAL)
                if (!(libPebble.bluetoothEnabled.value.enabled() && btAdapterPowered.value)) continue
                val devices = libPebble.watches.value
                val now = System.currentTimeMillis()
                for (d in devices.filterIsInstance<KnownPebbleDevice>()) {
                    val key = d.identifier.asString
                    if (d is ConnectedPebbleDevice) { clearedAt.remove(key); continue }
                    val id = d.identifier as? PebbleBleIdentifier ?: continue
                    val cleared = clearedAt[key]
                    if (cleared != null) {
                        // Phase 2: bond already cleared and it still hasn't reconnected — the watch
                        // won't re-pair with us (bonded elsewhere). Forget + notify, stop storming.
                        if (now - cleared >= REPAIR_GRACE.inWholeMilliseconds) {
                            log.info { "Stale-bond reaper: ${d.displayName()} did not re-pair within ${REPAIR_GRACE.inWholeSeconds}s — forgetting" }
                            bluezObjectPath(key)?.let { removeBluezBond(it) } // ensure bond gone (usually already)
                            d.forget()
                            sendDesktopNotification(
                                "Pebble unpaired",
                                "${d.displayName()} is nearby but no longer paired with this device. " +
                                    "Run 'stoandl pair' to use it here.",
                            )
                            clearedAt.remove(key)
                        }
                        continue
                    }
                    val info = d.connectionFailureInfo ?: continue
                    if (info.reason == ConnectionFailureReason.FailedToConnect &&
                        info.times >= STALE_FAILS_THRESHOLD && isBonded(id)
                    ) {
                        // Phase 1: bonded + in range + persistently rejecting us → stale bond. Clear it
                        // and let libpebble3 try a fresh pairing (auto-heals a wiped, pairable watch).
                        val path = bluezObjectPath(key) ?: continue
                        log.info { "Stale-bond reaper: ${d.displayName()} present but failing (${info.times}x ${info.reason}) — clearing stale bond" }
                        if (removeBluezBond(path)) clearedAt[key] = now
                    }
                }
                clearedAt.keys.retainAll(devices.map { it.identifier.asString }.toSet())
            }
        }
    }

    private fun watchBluetoothPowerState() {
        scope.launch(Dispatchers.IO) {
            try {
                val conn = DBusConnectionBuilder.forSystemBus().withShared(false).build()
                try {
                    // Use presence of org.bluez.GattManager1 on any hci adapter as the "BT ready"
                    // signal. More reliable than the Powered property: GNOME/KDE disable BT via rfkill
                    // which leaves Powered=true but removes GattManager1 from the adapter's interfaces.
                    // Don't hardcode hci0 — phones may expose the adapter under a different index.
                    fun isHciAdapter(path: String) = Regex("/org/bluez/hci\\d+$").matches(path)

                    fun isGattReady(): Boolean = try {
                        val objMgr = conn.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
                        @Suppress("UNCHECKED_CAST")
                        (objMgr.GetManagedObjects() as Map<DBusPath, Map<String, *>>).any { (path, ifaces) ->
                            isHciAdapter(path.toString()) && "org.bluez.GattManager1" in ifaces
                        }
                    } catch (_: Exception) { true } // assume ready on error; scan will fail if not

                    fun logAdapters() = try {
                        val objMgr = conn.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
                        @Suppress("UNCHECKED_CAST")
                        val adapters = (objMgr.GetManagedObjects() as Map<DBusPath, Map<String, *>>)
                            .filter { (path, _) -> isHciAdapter(path.toString()) }
                            .map { (path, ifaces) -> "$path [${ifaces.keys.joinToString()}]" }
                        log.info { "BlueZ adapters: ${if (adapters.isEmpty()) "none found" else adapters.joinToString()}" }
                    } catch (_: Exception) {}

                    logAdapters()
                    val ready = isGattReady()
                    btAdapterPowered.value = ready
                    if (!ready) {
                        log.info { "Bluetooth is currently disabled — will start scanning when Bluetooth is re-enabled" }
                    }

                    // InterfacesAdded: GattManager1 appeared → BT became operational
                    val addedRule = DBusMatchRuleBuilder.create()
                        .withType("signal")
                        .withInterface("org.freedesktop.DBus.ObjectManager")
                        .withMember("InterfacesAdded")
                        .build()
                    conn.addGenericSigHandler(addedRule) { msg: DBusSignal ->
                        try {
                            val params = msg.getParameters() ?: return@addGenericSigHandler
                            if (params.size < 2) return@addGenericSigHandler
                            if (!isHciAdapter(params[0].toString())) return@addGenericSigHandler
                            @Suppress("UNCHECKED_CAST")
                            val ifaces = params[1] as? Map<*, *> ?: return@addGenericSigHandler
                            if ("org.bluez.GattManager1" in ifaces) {
                                btAdapterPowered.value = true
                                log.info { "Bluetooth re-enabled — resuming" }
                            }
                        } catch (_: Exception) {}
                    }

                    // InterfacesRemoved: GattManager1 removed → BT is off or blocked
                    val removedRule = DBusMatchRuleBuilder.create()
                        .withType("signal")
                        .withInterface("org.freedesktop.DBus.ObjectManager")
                        .withMember("InterfacesRemoved")
                        .build()
                    conn.addGenericSigHandler(removedRule) { msg: DBusSignal ->
                        try {
                            val params = msg.getParameters() ?: return@addGenericSigHandler
                            if (params.size < 2) return@addGenericSigHandler
                            if (!isHciAdapter(params[0].toString())) return@addGenericSigHandler
                            val removed = params[1]
                            val ifaces = when (removed) {
                                is Array<*> -> removed.filterIsInstance<String>()
                                is List<*>  -> removed.filterIsInstance<String>()
                                else        -> return@addGenericSigHandler
                            }
                            if ("org.bluez.GattManager1" in ifaces) {
                                btAdapterPowered.value = false
                                log.info { "Bluetooth disabled — pausing (will resume automatically when Bluetooth is re-enabled)" }
                            }
                        } catch (_: Exception) {}
                    }

                    awaitCancellation()
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                log.debug { "Bluetooth state monitor unavailable: $e" }
            }
        }
    }

    private fun startCallMonitor() {
        try {
            ModemManagerCallMonitor(libPebble, scope, contactResolver, dialerNameCache, missedCallLog).start()
            log.info { "ModemManager call monitor started" }
        } catch (e: Exception) {
            log.warn(e) { "Failed to start ModemManager call monitor (telephony notifications disabled)" }
        }
    }

    private fun startWeatherSync() {
        val hasSource = config.weatherLocationSource != WeatherLocationSource.MANUAL
        if (config.weatherLocations.isEmpty() && !config.weatherGps && !hasSource) {
            log.info { "Weather sync disabled (no weather.locations, no source, weather.gps off)" }
            return
        }
        val gps = if (config.weatherGps) {
            GeoClueLocationProvider(config.weatherGpsDesktopId).also { it.start() }
        } else null
        val deSource = when (config.weatherLocationSource) {
            WeatherLocationSource.MANUAL -> null
            WeatherLocationSource.GNOME -> DeLocationSource(DeLocationSource.Mode.GNOME, "")
            WeatherLocationSource.COMMAND -> DeLocationSource(DeLocationSource.Mode.COMMAND, config.weatherLocationCommand)
        }
        val extraLocations: (suspend () -> List<StoandlConfig.WeatherLocation>)? =
            if (deSource != null) ({ deSource.locations() }) else null
        val ws = WeatherSync(
            libPebble = libPebble,
            scope = scope,
            locations = config.weatherLocations,
            units = config.weatherUnits,
            intervalMinutes = config.weatherIntervalMinutes,
            gps = gps,
            gpsFallbackName = config.weatherGpsName,
            reverseGeocodeEnabled = config.weatherReverseGeocode,
            extraLocations = extraLocations,
        )
        ws.start()
        weatherSyncRef.set(ws)
        log.info {
            "Weather sync started: ${config.weatherLocations.size} manual location(s)" +
                (if (hasSource) " + ${config.weatherLocationSource} source" else "") +
                (if (config.weatherGps) " + GPS current location" else "") +
                ", units=${config.weatherUnits}, every ${config.weatherIntervalMinutes}m"
        }
    }

    private fun startWatchPrefsSync() {
        // Always build the control (the `settings`/`set-setting` CLI works even with nothing in the config).
        val control = WatchPrefsControl(
            libPebble = libPebble,
            resolveAppUuid = { q -> resolve(libPebble, q).map { it.properties.id }.distinct().singleOrNull() },
            appName = { uuid -> allApps(libPebble).firstOrNull { it.properties.id == uuid }?.properties?.title },
        )
        watchPrefsControlRef.set(control)
        if (config.watchPrefs.isEmpty()) {
            log.info { "No watch.* prefs configured (set them in stoandl.conf or via 'stoandl set-setting')" }
            return
        }
        // Config is authoritative: re-apply the listed prefs on every connect so they win over any
        // on-watch change (most have no on-watch UI anyway).
        log.info { "Watch prefs: ${config.watchPrefs.size} configured (${config.watchPrefs.keys}), applied on connect" }
        libPebble.watches
            .map { devices -> devices.any { it is ConnectedPebbleDevice } }
            .distinctUntilChanged()
            .onEach { connected -> if (connected) control.applyConfigured(config.watchPrefs) }
            .launchIn(scope)
    }

    private fun registerControlService() {
        try {
            serviceConn.exportObject(STOANDL_OBJECT_PATH, StoandlControlImpl(libPebbleRef, weatherSyncRef, watchPrefsControlRef, scope, pairingGate, pairingState, bondCache) { libPebble.bluetoothEnabled.value.enabled() && btAdapterPowered.value })
            log.info { "D-Bus control service registered at $STOANDL_OBJECT_PATH" }
        } catch (e: Exception) {
            log.warn(e) { "Failed to register D-Bus control service" }
        }
    }

    private fun startAutoConnect() {
        libPebble.watches.onEach { devices ->
            for (device in devices.filterIsInstance<BleDiscoveredPebbleDevice>()) {
                val failures = device.connectionFailureInfo
                if (failures != null && failures.times >= MAX_CONNECTION_ATTEMPTS) {
                    log.warn { "Giving up on ${device.identifier} after ${failures.times} attempts (${failures.reason})" }
                    (device.identifier as? PebbleBleIdentifier)?.let { bondCache.remove(it.asString) }
                    watchConnector.requestDisconnection(device.identifier)
                    continue
                }
                // Gate: skip unbonded watches unless a pairing window is open.
                if (!pairingGate.isOpen()) {
                    val bleId = device.identifier as? PebbleBleIdentifier ?: continue
                    val key = bleId.asString
                    val cached = bondCache[key]
                    val bonded = if (cached != null) {
                        cached
                    } else {
                        val result = withContext(Dispatchers.IO) { isBonded(bleId) }
                        bondCache[key] = result
                        result
                    }
                    if (!bonded) {
                        log.debug { "Skipping $bleId: not bonded, pairing mode inactive" }
                        continue
                    }
                }
                log.info { "Requesting connection to ${device.identifier}" }
                // During a pairing window, emit "Found <name>" the first time we connect to
                // what looks like an unbonded watch (not already in the bond cache as bonded).
                if (pairingGate.isOpen()) {
                    val bleId = device.identifier as? PebbleBleIdentifier
                    val likelyUnbonded = bleId == null || bondCache[bleId.asString] != true
                    if (likelyUnbonded && pairingState.get() == "pending:") {
                        val label = device.displayName().takeIf { it.isNotBlank() } ?: "watch"
                        pairingState.set("pending:Found $label — pairing...")
                    }
                }
                watchConnector.requestConnection(device.identifier)
            }
        }.launchIn(scope)
    }
}

private class DbusNotificationListenerConnection(
    private val notificationFlow: Flow<IncomingNotification>,
    private val scope: CoroutineScope,
    private val itemIdToDbusId: ConcurrentHashMap<Uuid, UInt32>,
    private val blocklist: List<String>,
    private val dialerApps: List<String>,
    private val dialerNameCache: DialerNameCache,
) : NotificationListenerConnection {
    private val log = KotlinLogging.logger {}

    override fun init(libPebble: LibPebble) {
        log.info { "DBus notification listener connection initialized" }
        scope.launch {
            notificationFlow.collect { notification ->
                val appLower = notification.appName.lowercase()
                // Dialer notifications: capture the title for caller-name fallback, then suppress
                // them from the watch (the native call screen already shows the call).
                if (dialerApps.any { appLower.contains(it.lowercase()) }) {
                    dialerNameCache.record(notification.summary)
                    log.info { "Suppressed dialer notification from ${notification.appName} (name='${notification.summary}')" }
                    return@collect
                }
                if (blocklist.any { appLower.contains(it.lowercase()) }) {
                    log.info { "Filtered notification from ${notification.appName} (blocklist)" }
                    return@collect
                }
                try {
                    val timelineNotification = buildTimelineNotification(
                        // Pin to the same parent UUID the official Android app uses for phone
                        // notifications. The firmware keys "dismiss should round-trip to the phone"
                        // off a recognized notification-source app; a random parentId is treated as
                        // a local-only item, so SELECT→Dismiss never sends a TimelineAction back.
                        parentId = SystemAppIDs.ANDROID_NOTIFICATIONS_UUID,
                        timestamp = Clock.System.now(),
                    ) {
                        attributes {
                            title { notification.summary }
                            if (notification.body.isNotEmpty()) body { notification.body }
                            if (notification.appName.isNotEmpty()) subtitle { notification.appName }
                            tinyIcon { iconForApp(notification.appName) }
                        }
                        actions {
                            action(TimelineItem.Action.Type.Dismiss) {
                                attributes { title { "Dismiss" } }
                            }
                        }
                    }
                    itemIdToDbusId[timelineNotification.itemId] = notification.id
                    libPebble.sendNotification(timelineNotification)
                    log.info { "Notification queued for watch: ${notification.appName} – ${notification.summary}" }
                } catch (e: Exception) {
                    log.warn(e) { "Failed to send notification to watch" }
                }
            }
        }
    }
}

private object NoOpWebServices : WebServices {
    override suspend fun fetchLocker() = null
    // No Rebble account / remote locker in standalone mode: "removed from remote" is trivially
    // true, so Locker.removeApp() proceeds to delete the local entry instead of bailing out.
    override suspend fun removeFromLocker(id: Uuid) = true
    override suspend fun checkForFirmwareUpdate(watch: WatchInfo) =
        io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult.FoundNoUpdate
    override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {}
    override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) {}
}

private object NoOpTokenProvider : TokenProvider {
    override suspend fun getDevToken(): String? = null
}

private class DbusNotificationActionHandler(
    private val itemIdToDbusId: ConcurrentHashMap<Uuid, UInt32>,
    private val libPebbleRef: AtomicReference<LibPebble?>,
) : PlatformNotificationActionHandler {
    private val log = KotlinLogging.logger {}
    @Volatile private var dbusConn: DBusConnection? = null

    private fun notifService(): FreedesktopNotifications? = try {
        val existing = dbusConn
        val conn = if (existing != null && existing.isConnected()) existing else {
            existing?.disconnect()
            (DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection)
                .also { dbusConn = it }
        }
        conn.getRemoteObject(
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            FreedesktopNotifications::class.java,
        )
    } catch (e: Exception) {
        log.warn { "Cannot reach D-Bus notification service: ${e.message}" }
        dbusConn = null
        null
    }

    override suspend fun invoke(
        itemId: Uuid,
        action: BaseAction,
        attributes: List<TimelineItem.Attribute>,
    ): TimelineActionResult {
        log.info { "Watch action: type=${action.type} itemId=$itemId" }
        return when (action.type) {
            TimelineItem.Action.Type.Dismiss,
            TimelineItem.Action.Type.AncsDismiss -> {
                // Remove from watch notification centre
                libPebbleRef.get()?.markNotificationRead(itemId)

                // Close the corresponding desktop notification
                val dbusId = itemIdToDbusId.remove(itemId)
                if (dbusId != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            notifService()?.CloseNotification(dbusId)
                            log.info { "Closed D-Bus notification $dbusId for watch item $itemId" }
                        } catch (e: Exception) {
                            log.warn { "CloseNotification($dbusId) failed: ${e.message}" }
                            dbusConn = null
                        }
                    }
                } else {
                    log.warn { "No D-Bus ID found for watch item $itemId — desktop notification not closed" }
                }
                TimelineActionResult(true, TimelineIcon.ResultDismissed, "Dismissed")
            }
            else -> {
                log.info { "Unhandled watch action type ${action.type} on $itemId" }
                TimelineActionResult(false, TimelineIcon.ResultFailed, "Not supported")
            }
        }
    }
}

private val appIconMappings: List<Pair<String, TimelineIcon>> = listOf(
    // Messaging — specific compound names before their components
    "facebook messenger" to TimelineIcon.NotificationFacebookMessenger,
    "google hangouts" to TimelineIcon.NotificationGoogleHangouts,
    "google messenger" to TimelineIcon.NotificationGoogleMessenger,
    "google chat" to TimelineIcon.NotificationGoogleChat,
    "google inbox" to TimelineIcon.NotificationGoogleInbox,
    "google maps" to TimelineIcon.NotificationGoogleMaps,
    "google tasks" to TimelineIcon.NotificationGoogleTasks,
    "google photos" to TimelineIcon.NotificationGooglePhotos,
    "home assistant" to TimelineIcon.NotificationHomeAssistant,
    "kakaotalk" to TimelineIcon.NotificationKakaoTalk,
    "blackberry" to TimelineIcon.NotificationBlackberryMessenger,
    "unifi" to TimelineIcon.NotificationUnifiProtect,
    "yahoo mail" to TimelineIcon.NotificationYahooMail,
    // Individual apps
    "signal" to TimelineIcon.NotificationSignal,
    "discord" to TimelineIcon.NotificationDiscord,
    "telegram" to TimelineIcon.NotificationTelegram,
    "slack" to TimelineIcon.NotificationSlack,
    "whatsapp" to TimelineIcon.NotificationWhatsapp,
    "skype" to TimelineIcon.NotificationSkype,
    "zoom" to TimelineIcon.NotificationZoom,
    "teams" to TimelineIcon.NotificationTeams,
    "element" to TimelineIcon.NotificationElement,
    "bluesky" to TimelineIcon.NotificationBluesky,
    "twitter" to TimelineIcon.NotificationTwitter,
    "instagram" to TimelineIcon.NotificationInstagram,
    "facebook" to TimelineIcon.NotificationFacebook,
    "linkedin" to TimelineIcon.NotificationLinkedIn,
    "snapchat" to TimelineIcon.NotificationSnapchat,
    "viber" to TimelineIcon.NotificationViber,
    "wechat" to TimelineIcon.NotificationWeChat,
    "hipchat" to TimelineIcon.NotificationHipChat,
    "hangouts" to TimelineIcon.NotificationGoogleHangouts,
    "kik" to TimelineIcon.NotificationKik,
    "kakao" to TimelineIcon.NotificationKakaoTalk,
    "line" to TimelineIcon.NotificationLine,
    "gmail" to TimelineIcon.NotificationGmail,
    "outlook" to TimelineIcon.NotificationOutlook,
    "yahoo" to TimelineIcon.NotificationYahooMail,
    "steam" to TimelineIcon.NotificationSteam,
    "twitch" to TimelineIcon.NotificationTwitch,
    "youtube" to TimelineIcon.NotificationYoutube,
    "duolingo" to TimelineIcon.NotificationDuolingo,
    "threads" to TimelineIcon.NotificationThreads,
    "amazon" to TimelineIcon.NotificationAmazon,
    "ebay" to TimelineIcon.NotificationEbay,
    "beeper" to TimelineIcon.NotificationBeeper,
    "lighthouse" to TimelineIcon.NotificationLighthouse,
    // Generic categories
    "calendar" to TimelineIcon.TimelineCalendar,
    "alarm" to TimelineIcon.AlarmClock,
    "clock" to TimelineIcon.AlarmClock,
    "mail" to TimelineIcon.GenericEmail,
    "email" to TimelineIcon.GenericEmail,
    "thunderbird" to TimelineIcon.GenericEmail,
    "evolution" to TimelineIcon.GenericEmail,
    "geary" to TimelineIcon.GenericEmail,
    "kmail" to TimelineIcon.GenericEmail,
    "sms" to TimelineIcon.GenericSms,
    "messages" to TimelineIcon.GenericSms,
    "phone" to TimelineIcon.IncomingPhoneCall,
)

private fun iconForApp(appName: String): TimelineIcon {
    val lower = appName.lowercase()
    return appIconMappings.firstOrNull { (keyword, _) -> lower.contains(keyword) }?.second
        ?: TimelineIcon.NotificationGeneric
}

/** Snapshot of the whole locker (watchfaces first, then watchapps). */
private fun allApps(lp: LibPebble): List<LockerWrapper> = runBlocking {
    withTimeoutOrNull(5_000) {
        val faces = lp.getLocker(AppType.Watchface, null, 1000).first()
        val apps = lp.getLocker(AppType.Watchapp, null, 1000).first()
        faces + apps
    } ?: emptyList()
}

/** Resolve [query] to locker apps: by exact UUID, else exact (case-insensitive) title,
 *  else title substring. May return 0, 1 or many. */
private fun resolve(lp: LibPebble, query: String): List<LockerWrapper> {
    val all = allApps(lp)
    val asUuid = runCatching { Uuid.parse(query) }.getOrNull()
    if (asUuid != null) return all.filter { it.properties.id == asUuid }
    val exact = all.filter { it.properties.title.equals(query, ignoreCase = true) }
    if (exact.isNotEmpty()) return exact
    return all.filter { it.properties.title.contains(query, ignoreCase = true) }
}

@DBusInterfaceName("org.bluez.Adapter1")
private interface BluezAdapter1Ctl : DBusInterface {
    @Suppress("FunctionName")
    fun RemoveDevice(device: DBusPath)
}

@DBusInterfaceName("org.bluez.Device1")
private interface BluezDevice1Ctl : DBusInterface {
    @Suppress("FunctionName")
    fun Disconnect()
}

private const val ORG_BLUEZ = "org.bluez"
private const val BLUEZ_DEVICE1 = "org.bluez.Device1"
// Pebble BLE manufacturer-data company IDs (mirrors libpebble3's BluezBleScanner).
private val PEBBLE_VENDOR_IDS = setOf(0x0154, 0x0EEA)

private fun variantValue(v: Any?): Any? = if (v is Variant<*>) v.value else v

/**
 * Removes stale BlueZ bonds for Pebble watches that are bonded but NOT currently connected.
 *
 * When the watch side is wiped (factory reset / "forget" on the watch) but BlueZ still holds the
 * old bond, `Device1.Pair()` short-circuits with "Already Paired" and the watch refuses the stale
 * encryption — so a fresh pairing never completes and re-pairing silently fails. Restarting
 * Bluetooth does NOT help: bonds live in /var/lib/bluetooth and survive a BT restart. The only fix
 * is to delete BlueZ's side of the bond so the next pairing runs a genuine SMP exchange.
 *
 * Called at the start of an explicit `stoandl pair`, where a non-connected bonded Pebble can only be
 * a leftover. Connected devices are never touched. Returns the display names of the bonds cleared.
 */
private fun clearStalePebbleBonds(): List<String> {
    val removed = mutableListOf<String>()
    val conn = try {
        DBusConnectionBuilder.forSystemBus().withShared(false).build()
    } catch (e: Exception) {
        log.warn { "clearStalePebbleBonds: cannot reach system bus: ${e.message}" }
        return removed
    }
    try {
        val objMgr = conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)
        for ((path, ifaces) in objMgr.GetManagedObjects()) {
            val props = ifaces[BLUEZ_DEVICE1] ?: continue
            if (variantValue(props["Paired"]) as? Boolean != true) continue
            if (variantValue(props["Connected"]) as? Boolean == true) continue // never disturb a live link
            val name = (variantValue(props["Name"]) as? String)
                ?: (variantValue(props["Alias"]) as? String) ?: ""
            val md = variantValue(props["ManufacturerData"]) as? Map<*, *>
            val isPebble = name.startsWith("Pebble", ignoreCase = true) ||
                md?.keys?.any { (it as? Number)?.toInt() in PEBBLE_VENDOR_IDS } == true
            if (!isPebble) continue
            val adapterPath = path.toString().substringBeforeLast("/dev_") // -> /org/bluez/hciN
            try {
                conn.getRemoteObject(ORG_BLUEZ, adapterPath, BluezAdapter1Ctl::class.java)
                    .RemoveDevice(DBusPath(path.toString()))
                val label = name.ifEmpty { path.toString() }
                log.info { "Cleared stale Pebble bond: $label ($path)" }
                removed += label
            } catch (e: Exception) {
                log.warn { "RemoveDevice($path) failed: ${e.message}" }
            }
        }
    } catch (e: Exception) {
        log.warn { "clearStalePebbleBonds failed: ${e.message}" }
    } finally {
        conn.disconnect()
    }
    return removed
}

/** Extracts /org/bluez/hciN/dev_XX… from a PebbleBleIdentifier asString ({"object_path":"…"}). */
private fun bluezObjectPath(identifierAsString: String): String? =
    Regex(""""object_path"\s*:\s*"([^"]+)"""").find(identifierAsString)?.groupValues?.get(1)

/** Removes a single BlueZ bond by device object path via Adapter1.RemoveDevice. Returns true on success. */
private fun removeBluezBond(devicePath: String): Boolean {
    val adapterPath = devicePath.substringBeforeLast("/dev_") // -> /org/bluez/hciN
    val conn = try {
        DBusConnectionBuilder.forSystemBus().withShared(false).build()
    } catch (e: Exception) {
        log.warn { "removeBluezBond: cannot reach system bus: ${e.message}" }
        return false
    }
    return try {
        conn.getRemoteObject(ORG_BLUEZ, adapterPath, BluezAdapter1Ctl::class.java)
            .RemoveDevice(DBusPath(devicePath))
        log.info { "Removed stale BlueZ bond: $devicePath" }
        true
    } catch (e: Exception) {
        log.warn { "removeBluezBond($devicePath) failed: ${e.message}" }
        false
    } finally {
        conn.disconnect()
    }
}

/**
 * Drops the BLE link to a watch via BlueZ Device1.Disconnect, WITHOUT touching libpebble3's
 * connectGoal. Used on shutdown so the watch sees us go down and re-advertises (otherwise it stays
 * believing it's connected and ignores the next session's connection attempts) — but, unlike
 * watchConnector.requestDisconnection(), it leaves connectGoal=true so the next launch reconnects.
 */
private fun disconnectBluezDevice(devicePath: String) {
    val conn = try {
        DBusConnectionBuilder.forSystemBus().withShared(false).build()
    } catch (e: Exception) {
        log.warn { "disconnectBluezDevice: cannot reach system bus: ${e.message}" }
        return
    }
    try {
        conn.getRemoteObject(ORG_BLUEZ, devicePath, BluezDevice1Ctl::class.java).Disconnect()
        log.info { "Disconnected BLE link on shutdown: $devicePath" }
    } catch (e: Exception) {
        log.warn { "disconnectBluezDevice($devicePath) failed: ${e.message}" }
    } finally {
        conn.disconnect()
    }
}

/** Posts a desktop notification on the session bus (best-effort). */
private fun sendDesktopNotification(summary: String, body: String) {
    try {
        val conn = DBusConnectionBuilder.forSessionBus().withShared(false).build()
        try {
            conn.getRemoteObject(
                "org.freedesktop.Notifications",
                "/org/freedesktop/Notifications",
                FreedesktopNotifications::class.java,
            ).Notify("stoandl", UInt32(0), "phone", summary, body, emptyList(), emptyMap(), 15_000)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        log.warn { "sendDesktopNotification failed: ${e.message}" }
    }
}

private class StoandlControlImpl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val weatherSyncRef: AtomicReference<WeatherSync?>,
    private val watchPrefsControlRef: AtomicReference<WatchPrefsControl?>,
    private val scope: CoroutineScope,
    private val pairingGate: PairingGate,
    private val pairingState: AtomicReference<String>,
    private val bondCache: ConcurrentHashMap<String, Boolean>,
    // True only when Bluetooth is actually usable (libpebble3 state AND adapter Powered/GattManager1).
    private val btOn: () -> Boolean,
) : StoandlControl {
    private val log = KotlinLogging.logger {}

    override fun isRemote() = false
    override fun getObjectPath() = STOANDL_OBJECT_PATH

    override fun Version(): String = de.yoxcu.stoandl.BuildInfo.version

    override fun ListWatchPrefs(): List<String> =
        watchPrefsControlRef.get()?.list() ?: emptyList()

    override fun SetWatchPref(id: String, value: String): String {
        val control = watchPrefsControlRef.get() ?: return "notready:libPebble not ready"
        return control.setOne(id, value)
    }

    override fun SyncWeather(): String {
        val ws = weatherSyncRef.get()
            ?: return "error:Weather sync not enabled (set weather.locations in stoandl.conf)"
        return try {
            val n = runBlocking { ws.syncNow() }
            "ok:Weather synced ($n location(s) updated)"
        } catch (e: Exception) {
            log.warn(e) { "SyncWeather failed" }
            "error:${e.message ?: "weather sync failed"}"
        }
    }

    override fun SideloadApp(path: String): String {
        val lp = libPebbleRef.get() ?: run {
            log.warn { "SideloadApp($path): libPebble not ready" }
            return "notready:libPebble not ready"
        }
        log.info { "SideloadApp: $path" }
        // The daemon's cwd differs from the caller's, so a relative path resolves wrong here; reject
        // it with a clear message rather than the misleading "Pbw does not contain manifest" that a
        // missing file would otherwise produce. (The CLI already sends an absolute path.)
        if (!java.io.File(path).isFile) {
            log.warn { "SideloadApp($path): no such file (paths must be absolute on the daemon side)" }
            return "error:No such file: $path"
        }
        return try {
            val ok = runBlocking { lp.sideloadApp(Path(path)) }
            if (ok) "ok:Sideloaded ${Path(path).name}"
            else "error:Sideload rejected (not a valid .pbw, or no watch connected)"
        } catch (e: Exception) {
            log.warn(e) { "SideloadApp($path) failed" }
            "error:${e.message ?: "sideload failed"}"
        }
    }

    override fun ListApps(): List<String> {
        val lp = libPebbleRef.get() ?: return emptyList()
        val active = lp.activeWatchface.value?.properties?.id
        return allApps(lp).map { w ->
            val p = w.properties
            val flags = buildList {
                if (p.id == active) add("active")
                when (w) {
                    is LockerWrapper.NormalApp -> {
                        if (w.sideloaded) add("sideloaded")
                        if (w.configurable) add("config")
                    }
                    is LockerWrapper.SystemApp -> add("system")
                }
            }.joinToString(",")
            listOf(p.id.toString(), p.type.code, p.order.toString(), flags, p.title, p.developerName)
                .joinToString("\t")
        }
    }

    override fun LaunchApp(query: String): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        val matches = resolve(lp, query)
        when {
            matches.isEmpty() -> return "notfound:No app matching '$query'"
            matches.size > 1 -> return "ambiguous:" +
                matches.joinToString("; ") { "${it.properties.title} (${it.properties.id})" }
        }
        val app = matches.first()
        log.info { "LaunchApp: ${app.properties.title} (${app.properties.id})" }
        return try {
            runBlocking { lp.launchApp(app.properties.id) }
            "ok:Launched ${app.properties.title}"
        } catch (e: Exception) {
            log.warn(e) { "LaunchApp(${app.properties.id}) failed" }
            "error:${e.message ?: "launch failed"}"
        }
    }

    override fun RemoveApp(query: String): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        val matches = resolve(lp, query)
        when {
            matches.isEmpty() -> return "notfound:No app matching '$query'"
            matches.size > 1 -> return "ambiguous:" +
                matches.joinToString("; ") { "${it.properties.title} (${it.properties.id})" }
        }
        val app = matches.first()
        if (app is LockerWrapper.SystemApp) {
            return "error:Refusing to remove system app ${app.properties.title}"
        }
        log.info { "RemoveApp: ${app.properties.title} (${app.properties.id})" }
        return try {
            val ok = runBlocking { lp.removeApp(app.properties.id) }
            if (ok) "ok:Removed ${app.properties.title}"
            else "error:Failed to remove ${app.properties.title}"
        } catch (e: Exception) {
            log.warn(e) { "RemoveApp(${app.properties.id}) failed" }
            "error:${e.message ?: "remove failed"}"
        }
    }

    override fun OpenConfig(app: String): String {
        val lp = libPebbleRef.get() ?: run {
            log.warn { "OpenConfig($app): libPebble not ready" }
            return ""
        }
        // Run the whole flow (launch-if-needed → await bridge → request URL) in the integration
        // scope so coroutine exceptions never reach the D-Bus thread.
        val future = java.util.concurrent.CompletableFuture<String>()
        scope.launch {
            try {
                future.complete(resolveConfigUrl(lp, app))
            } catch (e: Throwable) {
                log.warn(e) { "OpenConfig($app) failed: ${e.message}" }
                future.complete("")
            }
        }
        return try {
            // Bounded so the daemon always replies inside the D-Bus reply window (the launch path's
            // await-bridge + URL sub-timeouts below sum to less than this).
            future.get(20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.warn(e) { "OpenConfig($app) future timed out" }
            ""
        }
    }

    /** Find the running PKJS session for [app], or — if it isn't running — launch the matching
     *  configurable locker app and wait for its bridge to come up, then return its config URL.
     *  Returns "" (no config page) on any miss: not found, ambiguous, not configurable, launch/bridge
     *  timeout, or URL timeout. */
    private suspend fun resolveConfigUrl(lp: LibPebble, app: String): String {
        var pkjsApp = findPkjsApp(app)
        if (pkjsApp == null) {
            if (app.isEmpty()) {
                log.warn { "OpenConfig: no PKJS app running and no app specified" }
                return ""
            }
            // Only configurable watchapps/watchfaces have a config page worth launching for.
            val candidates = resolve(lp, app)
                .filterIsInstance<LockerWrapper.NormalApp>()
                .filter { it.configurable }
            val target = when {
                candidates.isEmpty() -> {
                    log.warn { "OpenConfig: no configurable app matching '$app'" }
                    return ""
                }
                candidates.size > 1 -> {
                    log.warn { "OpenConfig: '$app' is ambiguous: ${candidates.joinToString { it.properties.title }}" }
                    return ""
                }
                else -> candidates.first()
            }
            log.info { "OpenConfig: ${target.properties.title} not running — launching it for its config page" }
            lp.launchApp(target.properties.id)
            pkjsApp = awaitPkjsApp(target.properties.id, 9_000)
            if (pkjsApp == null) {
                log.warn { "OpenConfig: PKJS bridge for ${target.properties.title} didn't come up in time" }
                return ""
            }
        }
        if (!pkjsApp.lockerEntry.configurable) {
            log.warn { "OpenConfig: ${pkjsApp.appInfo.shortName} is not configurable" }
            return ""
        }
        log.info { "OpenConfig: requesting config URL from ${pkjsApp.appInfo.shortName}" }
        val url = withTimeoutOrNull(9_000) { pkjsApp.requestConfigurationUrl() }
        return if (url == null) {
            log.warn { "OpenConfig: timed out waiting for URL from ${pkjsApp.appInfo.shortName}" }
            ""
        } else {
            log.info { "OpenConfig: got URL: $url" }
            url
        }
    }

    /** Poll for the PKJS session of the app with [uuid] (it appears a few seconds after launch, once
     *  the JS bridge initialises), up to [timeoutMs]. */
    private suspend fun awaitPkjsApp(uuid: Uuid, timeoutMs: Long): PKJSApp? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val match = libPebbleRef.get()?.watches?.value
                ?.filterIsInstance<ConnectedPebbleDevice>()
                ?.flatMap { it.currentCompanionAppSessions.value }?.filterIsInstance<PKJSApp>()
                ?.firstOrNull { it.appInfo.uuid.equals(uuid.toString(), ignoreCase = true) }
            if (match != null) return@withTimeoutOrNull match
            delay(300)
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    override fun WebviewClose(data: String) {
        val lp = libPebbleRef.get() ?: return
        lp.watches.value
            .filterIsInstance<ConnectedPebbleDevice>()
            .flatMap { it.currentCompanionAppSessions.value }.filterIsInstance<PKJSApp>()
            .forEach { it.triggerOnWebviewClosed(data) }
    }

    override fun FakeCallRing(name: String, number: String): Boolean {
        val lp = libPebbleRef.get() ?: run {
            log.warn { "FakeCallRing: libPebble not ready" }
            return false
        }
        val cookie = Random.nextUInt()
        val contactName = name.ifEmpty { null }
        log.info { "[fakecall] ringing: name=$name number=$number cookie=$cookie" }
        lp.currentCall.value = Call.RingingCall(
            contactName = contactName,
            contactNumber = number,
            cookie = cookie,
            onCallEnd = {
                log.info { "[fakecall] watch declined/ended ringing call (cookie=$cookie)" }
                lp.currentCall.value = null
            },
            onCallAnswer = {
                log.info { "[fakecall] watch answered (cookie=$cookie) → active call" }
                lp.currentCall.value = Call.ActiveCall(
                    contactName = contactName,
                    contactNumber = number,
                    cookie = cookie,
                    onCallEnd = {
                        log.info { "[fakecall] watch ended active call (cookie=$cookie)" }
                        lp.currentCall.value = null
                    },
                )
            },
        )
        return true
    }

    override fun FakeCallEnd(): Boolean {
        val lp = libPebbleRef.get() ?: return false
        log.info { "[fakecall] remote ended call" }
        lp.currentCall.value = null
        return true
    }

    private fun findPkjsApp(query: String): PKJSApp? {
        val lp = libPebbleRef.get() ?: return null
        return lp.watches.value
            .filterIsInstance<ConnectedPebbleDevice>()
            .flatMap { it.currentCompanionAppSessions.value }.filterIsInstance<PKJSApp>()
            .firstOrNull { app ->
                query.isEmpty() ||
                app.appInfo.shortName.contains(query, ignoreCase = true) ||
                app.appInfo.uuid.equals(query, ignoreCase = true)
            }
    }

    override fun Pair(): String {
        val lp = libPebbleRef.get() ?: return "error:Daemon not ready"
        // Already connected — report immediately without a pairing window.
        if (lp.watches.value.any { it is ConnectedPebbleDevice }) {
            pairingState.set("ok:Watch already connected")
            return "ok:Pairing started"
        }
        // Clear stale BlueZ bonds before pairing. If the watch was wiped but BlueZ still holds the
        // old bond, Pair() returns "Already Paired" and the watch refuses the stale encryption, so
        // re-pairing never completes (and a BT restart won't fix it — bonds persist on disk). We've
        // already returned above if a watch is connected, so any bonded-but-disconnected Pebble here
        // is leftover and safe to forget. Notify so the user knows to put the watch in pairing mode.
        val cleared = clearStalePebbleBonds()
        if (cleared.isNotEmpty()) {
            sendDesktopNotification(
                "Pebble pairing reset",
                "Cleared a stale pairing (${cleared.joinToString(", ")}). " +
                    "Put your watch in pairing mode to reconnect.",
            )
        }
        // Snapshot which devices are already bonded so the bond-poll job only fires on NEW bonds.
        val alreadyBonded = lp.watches.value
            .mapNotNull { it.identifier as? PebbleBleIdentifier }
            .filter { isBonded(it) }
            .map { it.asString }
            .toSet()
        pairingGate.open()
        pairingState.set("pending:")
        log.info { "Pairing mode opened (${PAIRING_WINDOW_MS / 1000}s window)" }
        scope.launch {
            val result = CompletableDeferred<String>()
            // Fast path: detect full connection via StateFlow collection.
            val connectedJob = launch {
                lp.watches.first { devices -> devices.any { it is ConnectedPebbleDevice } }
                result.complete("ok:Paired and connected")
            }
            // Bond-poll path: detect bonding every 2 s so we return as soon as the BLE bond
            // completes, even if the PPoG negotiation is still in progress or keeps failing.
            val bondedJob = launch {
                while (!result.isCompleted) {
                    delay(2_000)
                    // Skip the poll while Bluetooth is off: BlueZ removes the device object when the
                    // adapter is disabled, so isBonded() can only throw ("Method Get ... doesn't
                    // exist") and would spam the log every 2 s for the whole pairing window. The
                    // window stays open, so polling resumes once BT comes back.
                    if (!btOn()) continue
                    val newlyBonded = withContext(Dispatchers.IO) {
                        lp.watches.value
                            .mapNotNull { it.identifier as? PebbleBleIdentifier }
                            .filter { it.asString !in alreadyBonded }
                            .any { bleId -> isBonded(bleId).also { b -> if (b) bondCache[bleId.asString] = true } }
                    }
                    if (newlyBonded) result.complete("ok:Paired")
                }
            }
            // Overall timeout.
            val timeoutJob = launch {
                delay(PAIRING_WINDOW_MS + 10_000L)
                result.complete("timeout:Pairing timed out")
            }
            val newState = result.await()
            connectedJob.cancel()
            bondedJob.cancel()
            timeoutJob.cancel()
            pairingState.set(newState)
            pairingGate.close()
            log.info { "Pairing result: $newState" }
        }
        return "ok:Pairing started"
    }

    override fun PairStatus(): String =
        pairingState.get().ifEmpty { "error:No pairing in progress" }

    override fun Unpair(): String {
        val lp = libPebbleRef.get() ?: return "error:Daemon not ready"
        val known = lp.watches.value.filterIsInstance<KnownPebbleDevice>()
        // forget() each known watch (stops libpebble3 auto-connect) and clear its BlueZ bond.
        val names = known.map { d ->
            (d.identifier as? PebbleBleIdentifier)?.let { id -> bluezObjectPath(id.asString)?.let(::removeBluezBond) }
            d.forget()
            d.displayName()
        }
        // Also sweep any leftover bonded Pebble in BlueZ that libpebble3 no longer tracks.
        val swept = clearStalePebbleBonds()
        return when {
            names.isNotEmpty() -> "ok:Unpaired ${names.joinToString(", ")}"
            swept.isNotEmpty() -> "ok:Cleared ${swept.size} stale bond(s)"
            else -> "ok:No paired watch"
        }
    }
}

private object NoOpTranscriptionProvider : TranscriptionProvider {
    override suspend fun transcribe(
        encoderInfo: VoiceEncoderInfo,
        audioFrames: Flow<UByteArray>,
        isNotificationReply: Boolean,
    ): TranscriptionResult = TranscriptionResult.Failed

    override suspend fun canServeSession(): Boolean = false
}
