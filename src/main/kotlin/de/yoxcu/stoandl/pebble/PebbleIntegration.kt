@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.dbus.FreedesktopNotifications
import de.yoxcu.stoandl.dbus.IncomingNotification
import de.yoxcu.stoandl.dbus.ModemManagerCallMonitor
import de.yoxcu.stoandl.dbus.MprisMusicControl
import de.yoxcu.stoandl.dbus.SystemVolume
import de.yoxcu.stoandl.dbus.TimedateTimeChanged
import de.yoxcu.stoandl.calls.MissedCallLog
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.config.StoandlConfig.WeatherLocationSource
import de.yoxcu.stoandl.debug.DebugControl
import de.yoxcu.stoandl.developer.DeveloperControl
import de.yoxcu.stoandl.firmware.FirmwareControl
import de.yoxcu.stoandl.language.LanguageControl
import de.yoxcu.stoandl.notification.NotificationAppsControl
import de.yoxcu.stoandl.screenshot.ScreenshotControl
import de.yoxcu.stoandl.support.LogsControl
import de.yoxcu.stoandl.datalog.DatalogStore
import de.yoxcu.stoandl.health.HealthExporter
import de.yoxcu.stoandl.weather.DeLocationSource
import de.yoxcu.stoandl.location.GeoClueLocationProvider
import de.yoxcu.stoandl.location.GeoClueSystemGeolocation
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
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.ConnectingPebbleDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.connection.WatchConnector
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.di.PlatformConfig
import io.rebble.libpebblecommon.di.initKoin
import io.rebble.libpebblecommon.timeline.TimelineColor
import io.rebble.libpebblecommon.timeline.toPebbleColor
import io.rebble.libpebblecommon.js.InjectedPKJSHttpInterceptors
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.time.TimeChanged
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
import kotlin.uuid.Uuid
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import de.yoxcu.stoandl.calendar.CalDavSource
import de.yoxcu.stoandl.calendar.CalendarSource
import de.yoxcu.stoandl.calendar.DiscoverySource
import de.yoxcu.stoandl.calendar.IcalUrlSource
import de.yoxcu.stoandl.calendar.IcsPathSource
import de.yoxcu.stoandl.calendar.LinuxSystemCalendar
import de.yoxcu.stoandl.calendar.calendarDiscoveryDirs
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.util.SystemGeolocation
import java.io.File

private const val MAX_CONNECTION_ATTEMPTS = 5
private const val PAIRING_WINDOW_MS = 120_000L  // 2 minutes

/** Allows pairing with unbonded watches only while the window is open. */
private class PairingGate {
    private val expiryMs = java.util.concurrent.atomic.AtomicLong(0L)
    fun open() { expiryMs.set(System.currentTimeMillis() + PAIRING_WINDOW_MS) }
    fun close() { expiryMs.set(0L) }
    fun isOpen(): Boolean = System.currentTimeMillis() < expiryMs.get()
}

// Reconnection is delegated to BlueZ's kernel background auto-connect (a bonded watch is marked
// Trusted and a single standing Device1.Connect() intent is left in place — BlueZ reconnects it the
// instant it advertises). There is therefore no reconnect poll to stall, and no process-restart
// watchdog: a process restart can't fix a wedged bluetoothd anyway (we're a --user service; the only
// real remedy is `systemctl restart bluetooth` or an adapter reset), and the empirical record showed
// such restarts looping uselessly against an out-of-range watch. Genuine crashes are still caught by
// systemd Restart=on-failure; stale bonds are handled by the reaper below.

// Stale-bond reaper: a bonded watch that is in range but keeps failing to connect (the watch no
// longer honours BlueZ's bond — wiped, re-paired elsewhere, or a phantom object). Acts only on
// FailedToConnect (link established/attempted then rejected = watch present); ConnectTimeout
// (out of range) is ignored, so an away watch is never disturbed.
private val REAPER_INTERVAL = 30.seconds
private const val STALE_FAILS_THRESHOLD = 5      // consecutive present-but-failed connects before clearing
private val REPAIR_GRACE = 90.seconds            // after clearing, allow auto re-pair before forgetting

// How often to check whether an external process's Bluetooth discovery is blocking reconnection.
private val DISCOVERY_WARN_INTERVAL = 60.seconds

// Broken-bond / one-sided-bond churn: after the watch is unpaired ON THE WATCH, the host still holds
// the bond (and Trusted), so BlueZ reconnects then immediately tears the link down with an
// authentication failure (the watch no longer honours the bond), every few seconds. BlueZ surfaces
// each as `org.bluez.Device1.Disconnected(reason=org.bluez.Reason.Authentication)` — distinct from an
// out-of-range drop, which carries `org.bluez.Reason.Timeout`. Count those auth-failure disconnects
// and, past a threshold, notify with a one-tap re-pair action. This is proximity-independent: a far
// (out-of-range) watch only ever produces Reason.Timeout, so it is never flagged. Requires BlueZ
// >= 5.83 (when the Device1.Disconnected signal landed); on older BlueZ no signal arrives and the
// detector simply never fires — safe, just no auto-detection.
private val BROKEN_BOND_WINDOW = 3.minutes
private const val BROKEN_BOND_FLAPS = 5   // auth-failure (Reason.Authentication) disconnects → notify
private val RENOTIFY_INTERVAL = 10.minutes // re-show the churn notification this often while it persists
                                           // (in place, via replaces_id — it updates, never stacks)
// The OTHER broken-bond direction: the host lost the BlueZ pairing (e.g. `bluetoothctl remove`) while
// libpebble still wants the watch. It can never reconnect without a fresh pair, so once it's been
// unbonded this long (fires on the 2nd consecutive poll), we forget it + notify. Short, but long enough
// to outlast a `systemctl restart bluetooth` (BlueZ reloads bonds from disk in a few seconds).
private val HOST_BOND_LOST_GRACE = 10.seconds

private val log = KotlinLogging.logger {}

class PebbleIntegration(
    private val notificationFlow: Flow<IncomingNotification>,
    private val scope: CoroutineScope,
    private val serviceConn: DBusConnection,
) {
    private lateinit var libPebble: LibPebble
    private lateinit var watchConnector: WatchConnector
    private lateinit var firmwareControl: FirmwareControl
    private lateinit var languageControl: LanguageControl
    private lateinit var screenshotControl: ScreenshotControl
    private lateinit var logsControl: LogsControl
    private lateinit var debugControl: DebugControl
    private lateinit var developerControl: DeveloperControl
    // Null when notification.per_app is off (no store to manage).
    private var notificationAppsControl: NotificationAppsControl? = null
    private val libPebbleRef = AtomicReference<LibPebble?>(null)
    private val weatherSyncRef = AtomicReference<WeatherSync?>(null)
    private val healthExporterRef = AtomicReference<HealthExporter?>(null)
    private val watchPrefsControlRef = AtomicReference<WatchPrefsControl?>(null)
    private val calendarSyncRef = AtomicReference<LinuxSystemCalendar?>(null)
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
    // Long-lived session-bus connection for notifications that carry an action button, plus a map of
    // notification id → callback to run when the user taps it (org.freedesktop.Notifications.ActionInvoked).
    @Volatile private var notifConn: DBusConnection? = null
    private val notificationActions = ConcurrentHashMap<UInt32, () -> Unit>()

    fun init() {
        // Register the pairing agent before any connection so MITM pairing has an answerer.
        pairingAgent.register()

        // reversedPPoG=false → the phone acts as the BLE peripheral. lanDevConnection=true → the
        // developer connection (started on demand) uses the LAN WebSocket server on port 9000 rather
        // than the CloudPebble proxy (stoandl has no Rebble token, so the proxy can't authenticate).
        // Both the Ble- and WatchConfigFlow are pinned in the override module below so a persisted
        // Java Preferences value (LibPebbleConfigHolder loads storage over our default) can't undo them.
        val libPebbleConfig = LibPebbleConfig(
            bleConfig = BleConfig(reversedPPoG = false),
            watchConfig = WatchConfig(lanDevConnection = true),
        )
        val koin = initKoin(
            defaultConfig = libPebbleConfig,
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
        // watch-item UUID → app package, shared by the listener (populated when a notification with a
        // "Mute" action is sent) and the action handler (consumed when the watch invokes that action).
        val itemIdToMutePkg = ConcurrentHashMap<Uuid, String>()

        // Build the Linux calendar source from config (null if nothing is configured). libpebble3's
        // PhoneCalendarSyncer reads it and handles all pin creation/diffing/deletion itself.
        val calendarSync = buildCalendarSync()
        calendarSyncRef.set(calendarSync)

        // Override modules: use our DBus notification bridge, and pin Ble-/WatchConfigFlow so any
        // persisted Java Preferences value cannot override reversedPPoG=false / lanDevConnection=true.
        koin.loadModules(listOf(module {
            single<NotificationListenerConnection> {
                DbusNotificationListenerConnection(
                    notificationFlow, scope, itemIdToDbusId, itemIdToMutePkg,
                    dialerApps = config.dialerApps,
                    dialerNameCache = dialerNameCache,
                    notifAppDao = if (config.notificationPerApp) get<NotificationAppRealDao>() else null,
                    defaultMute = parseMuteState(config.notificationDefaultMute),
                )
            }
            // Sync the per-app list + mute states to the watch (Notifications settings menu + wrist
            // write-back). Overrides the JVM module's PlatformConfig(syncNotificationApps = false),
            // which otherwise keeps notificationAppRealDao out of the BlobDB sync set. BLE-only.
            if (config.notificationSyncToWatch) single { PlatformConfig(syncNotificationApps = true) }
            single { BleConfigFlow(MutableStateFlow(libPebbleConfig)) }
            // Pin lanDevConnection=true so the developer connection uses the LAN server (port 9000),
            // not the CloudPebble proxy. Overrides the JVM module's storage-backed WatchConfigFlow.
            single { WatchConfigFlow(MutableStateFlow(libPebbleConfig)) }
            // Handle watch-side notification actions: a Dismiss on the watch marks the item read AND
            // closes the originating desktop notification over D-Bus (CloseNotification). Without this
            // binding the JVM module's no-op handler runs and watch→desktop dismiss silently does
            // nothing. (Don't drop this line — it was once clobbered by an unrelated refactor.)
            single<PlatformNotificationActionHandler> {
                DbusNotificationActionHandler(
                    itemIdToDbusId, itemIdToMutePkg, libPebbleRef,
                    notifAppDao = if (config.notificationPerApp) get<NotificationAppRealDao>() else null,
                )
            }
            // Replace the no-op JVM TimeChanged with a systemd-timedated watcher so a host timezone
            // change while the watch stays connected re-pushes the clock (SetUTC). Without this the
            // watch only learns the time at connect (the negotiator's updateTime()).
            single<TimeChanged> { TimedateTimeChanged() }
            // Identify to the watch as an Android phone in the PhoneAppVersion handshake. The firmware
            // branches music handling on the phone's OS type (iOS → AMS; Android → the legacy
            // MUSIC_CONTROL endpoint that libpebble3 drives); the JVM module's default OSType.Unknown
            // makes the firmware never engage that path — the Music app shows no now-playing, never
            // sends GetCurrentTrack, and ignores its buttons, even though it PPoG-acks our packets.
            // A btmon capture confirmed: same watch+firmware works instantly on Android, where the only
            // wire difference is platformFlags carrying OSType.Android (2) vs our 0. We already mimic
            // Android elsewhere (ANDROID_NOTIFICATIONS_UUID), so this is consistent.
            single { PlatformFlags(PhoneAppVersion.PlatformFlag.makeFlags(PhoneAppVersion.OSType.Android, emptyList())) }
            // Back MissedCallSyncer with our ModemManager-fed log so missed calls become timeline pins.
            single<SystemCallLog> { missedCallLog }
            // Replace the no-op JVM SystemMusicControl with the MPRIS bridge (unless disabled), so the
            // watch's Music app shows now-playing and its buttons drive the desktop player. Volume buttons
            // drive the system/master output (config.musicVolume == SYSTEM, the default) via an auto-
            // detected backend, else the active player's own MPRIS volume.
            if (config.musicControl) single<SystemMusicControl> {
                val systemVol = if (config.musicVolume == StoandlConfig.MusicVolumeMode.SYSTEM) {
                    SystemVolume.resolve(config.musicVolumeUpCommand, config.musicVolumeDownCommand)
                } else null
                MprisMusicControl(scope, systemVol)
            }
            // Replace the no-op JVM SystemCalendar with the Linux reader (when sources are configured),
            // so PhoneCalendarSyncer turns desktop calendar events into watch timeline pins.
            calendarSync?.let { cs -> single<SystemCalendar> { cs } }
            // Replace the no-op JVM SystemGeolocation with a GeoClue2-backed one so watchapps'
            // navigator.geolocation (PKJS) and location-aware sports/GPS apps get a real fix. Lazy:
            // the GeoClue client is only created when a watchapp first asks for location. Reuses the
            // weather GeoClue identity (its own client; GeoClue is per-sender multi-client). Off
            // unless opted in — leaving the no-op binding (returns "Not supported on Linux").
            if (config.geolocation) single<SystemGeolocation> {
                GeoClueSystemGeolocation(GeoClueLocationProvider(config.weatherGpsDesktopId))
            }
        }), allowOverride = true)

        libPebble = koin.get()
        libPebbleRef.set(libPebble)
        firmwareControl = FirmwareControl(libPebbleRef, scope, config)
        languageControl = LanguageControl(libPebbleRef, config)
        screenshotControl = ScreenshotControl(libPebbleRef)
        logsControl = LogsControl(libPebbleRef)
        debugControl = DebugControl(libPebbleRef)
        developerControl = DeveloperControl(libPebbleRef)
        if (config.notificationPerApp) notificationAppsControl = NotificationAppsControl(koin.get())
        watchConnector = koin.get()
        watchBluetoothPowerState()
        libPebble.init()
        startScanLoop()
        startAutoConnect()
        startStaleBondReaper()
        startDiscoveryInterferenceWarning()
        startNotificationActionListener()
        startChurnDetector()
        registerControlService()
        startCallMonitor()
        startWeatherSync()
        startWatchPrefsSync()
        startFirmwareNotifier()
        startDeveloperAutostart()
        // Persist custom-watchapp datalog frames (PebbleKit DataLogging) to NDJSON. The fork re-emits
        // them on Datalogging.records; without a subscriber they're simply dropped (as before).
        if (config.datalog) {
            DatalogStore(koin.get(), scope).start()
        } else {
            log.info { "Datalog capture disabled (datalog.enabled=false)" }
        }
        // Health/activity: ask the watch for fresh data on connect (libpebble3 ingests it into the
        // shared DB; nothing requests it on its own), and project that DB to readable NDJSON files.
        startHealthSync()
        // The MPRIS SystemMusicControl is installed via Koin override above and self-starts when first
        // injected (on the first watch connect); nothing to start here, just report the state.
        log.info {
            if (config.musicControl) "Music control enabled (MPRIS → watch Music app; volume: ${config.musicVolume.name.lowercase()})"
            else "Music control disabled (music.enabled=false)"
        }
        log.info {
            if (calendarSync != null)
                "Calendar sync enabled (timeline pins; refresh every ${config.calendarSyncIntervalMinutes}m + on .ics change)"
            else "Calendar sync disabled (set calendar.ics_paths / discover / ical_urls / caldav in stoandl.conf)"
        }

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
            // EXPERIMENTAL active-rescan (config `reconnect.active_rescan`): when a bonded watch first
            // went (and stayed) down, so we only start rescanning after a grace — long enough that the
            // BLE-native Time 2 / Pebble 2, which reconnect via BlueZ's fast kernel path in a few
            // seconds, never trigger a rescan.
            var bondedDownSince: Long? = null
            val rescanGraceMs = 20_000L
            while (true) {
                // Suspend when BT is disabled — from libpebble3's state OR from our Powered watcher
                // (covers rfkill / airplane mode where GattServerManager may still report Enabled).
                // Wait for BOTH sources to agree BT is on before attempting anything.
                val btOn = libPebble.bluetoothEnabled.value.enabled() && btAdapterPowered.value
                if (!btOn) {
                    if (libPebble.isScanningBle.value) libPebble.stopBleScan()
                    combine(libPebble.bluetoothEnabled, btAdapterPowered) { bt, powered ->
                        bt.enabled() && powered
                    }.first { it }
                    delay(2.seconds) // brief settle so BlueZ is ready before we scan
                    continue
                }

                // Scanning is normally only needed to discover an *unbonded* watch during pairing — a
                // bonded watch reconnects via BlueZ-native background auto-connect, no app scan. So scan
                // while a pairing window is open. This keeps the radio quiet in every idle state — no
                // watch, post-unpair, or a host-bond-lost watch we just forgot — instead of flooding
                // advertising reports (a btmon "storm") whenever nothing happens to be connecting. Also
                // don't scan while connected/connecting: a scan collides with a connect on BlueZ's single
                // discovery slot (org.bluez.Error.InProgress).
                val devices = libPebble.watches.value
                val busy = devices.any {
                    it is ConnectedPebbleDevice || it is ConnectingPebbleDevice
                }
                // EXPERIMENTAL: also scan when a bonded watch is down and the kernel auto-connect isn't
                // recovering it. A KnownPebbleDevice that is neither connected nor connecting is a bonded
                // watch that's away/silent. Re-discovering it via an active scan reconnects it under its
                // *current* object path (the working initial-connect path) instead of the stale
                // `dev_<oldRPA>` path the connector is pinned to. Gated behind `reconnect.active_rescan`
                // + a grace so the BLE-native watches (fast kernel reconnect) never reach it.
                val bondedDown = !busy && devices.any { it is KnownPebbleDevice }
                val nowMs = System.currentTimeMillis()
                if (bondedDown) {
                    if (bondedDownSince == null) bondedDownSince = nowMs
                } else {
                    bondedDownSince = null
                }
                val downSince = bondedDownSince
                val rescanWanted = config.bleActiveRescan && downSince != null &&
                    nowMs - downSince >= rescanGraceMs

                if ((!pairingGate.isOpen() && !rescanWanted) || busy) {
                    // Not pairing, no rescan wanted, or already connected/connecting → stop any scan.
                    if (libPebble.isScanningBle.value) libPebble.stopBleScan()
                } else {
                    // Stop an in-flight scan before restarting it. Calling startBleScan() while BlueZ
                    // already has a discovery running returns org.bluez.Error.InProgress and the scan
                    // then never refreshes, so a watch that comes back into range is never re-discovered.
                    if (libPebble.isScanningBle.value) {
                        libPebble.stopBleScan()
                        delay(1.seconds)
                    }
                    log.info {
                        if (rescanWanted && !pairingGate.isOpen())
                            "Starting BLE scan (active reconnect rescan — bonded watch down; if it never " +
                                "appears below, it isn't advertising and only BT Classic can recover it)"
                        else "Starting BLE scan"
                    }
                    libPebble.startBleScan()
                }
                // Sleep ~35s (a bit longer than the 30s scan timeout), but re-check every second so a
                // freshly-opened pairing window kicks discovery within ~1s, and so we stop scanning
                // promptly once a (re)connect begins — freeing the discovery slot the connect needs.
                val until = System.currentTimeMillis() + 35_000
                while (System.currentTimeMillis() < until) {
                    delay(1.seconds)
                    val nowBusy = libPebble.watches.value.any {
                        it is ConnectedPebbleDevice || it is ConnectingPebbleDevice
                    }
                    if (nowBusy && libPebble.isScanningBle.value) break
                    if (pairingGate.isOpen() && !libPebble.isScanningBle.value) break
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

    /**
     * Diagnostic: warn (once) when an *external* process is running Bluetooth discovery while a bonded
     * watch is trying to reconnect. A discovery monopolizes the controller's single LE scanner, so
     * BlueZ cannot issue our standing `Device1.Connect()` — the watch then can't reconnect even though
     * everything on our side is correct. This is invisible without a btmon snoop and once cost a long
     * debugging session; surfacing it turns a multi-hour mystery into a one-line hint. It cannot be
     * *fixed* here (each client owns its own discovery session — we can't stop another process's scan),
     * only reported. `Adapter1.Discovering` is true whenever ANY client (including us) holds discovery,
     * so we only flag it when stoandl itself is NOT scanning.
     */
    private fun startDiscoveryInterferenceWarning() {
        scope.launch(Dispatchers.IO) {
            val conn = try {
                DBusConnectionBuilder.forSystemBus().withShared(false).build()
            } catch (e: Exception) {
                log.debug { "Discovery-interference monitor unavailable: ${e.message}" }
                return@launch
            }
            val hciAdapter = Regex("/org/bluez/hci\\d+$")
            fun externalDiscoveryActive(): Boolean {
                if (libPebble.isScanningBle.value) return false // our own scan, not interference
                return try {
                    val objMgr = conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)
                    objMgr.GetManagedObjects().any { (path, ifaces) ->
                        hciAdapter.matches(path.toString()) &&
                            variantValue(ifaces["org.bluez.Adapter1"]?.get("Discovering")) as? Boolean == true
                    }
                } catch (_: Exception) { false }
            }

            var warned = false
            try {
                while (true) {
                    delay(DISCOVERY_WARN_INTERVAL)
                    val devices = libPebble.watches.value
                    if (devices.any { it is ConnectedPebbleDevice }) { warned = false; continue }
                    val haveBonded = devices.filterIsInstance<KnownPebbleDevice>().any { it !is ConnectedPebbleDevice }
                    val btOn = libPebble.bluetoothEnabled.value.enabled() && btAdapterPowered.value
                    if (haveBonded && btOn && externalDiscoveryActive()) {
                        if (!warned) {
                            warned = true
                            log.warn {
                                "Another process is running Bluetooth discovery (Adapter1.Discovering=true " +
                                    "and stoandl is not scanning) — it monopolizes the controller's scanner, " +
                                    "so the watch cannot reconnect. Close it (e.g. an open Bluetooth settings " +
                                    "or pairing window) or stop the scan."
                            }
                            sendDesktopNotification(
                                "Pebble blocked by a Bluetooth scan",
                                "Another app is scanning for Bluetooth devices, which blocks your Pebble " +
                                    "from reconnecting. Close any open Bluetooth settings or pairing window.",
                            )
                        }
                    } else {
                        warned = false
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Watches for the two broken-bond directions, both INVISIBLE to libpebble's normal device state:
     *
     * 1. Unpaired ON THE WATCH (host still bonded): the still-Trusted bond makes BlueZ reconnect then
     *    immediately disconnect with an authentication failure (the watch rejects the bond), every few
     *    seconds — INVISIBLE to libPebble.watches. BlueZ reports each as a `org.bluez.Device1.Disconnected`
     *    signal with reason `org.bluez.Reason.Authentication`; an out-of-range drop instead carries
     *    `org.bluez.Reason.Timeout` and is ignored. After [BROKEN_BOND_FLAPS] auth-failure disconnects
     *    within [BROKEN_BOND_WINDOW] for a known not-fully-connected watch, NOTIFY with a one-tap Re-pair
     *    action. Never auto-forgets here — the user may just re-pair — and a watch that reaches a full
     *    session (ConnectedPebbleDevice) has its count cleared. Requires BlueZ >= 5.83 for the signal.
     *
     * 2. Pairing removed ON THE HOST (e.g. `bluetoothctl remove`): `isBonded` goes false. The watch can
     *    NEVER reconnect without a fresh pair, so after [HOST_BOND_LOST_GRACE] (and not mid-pairing) we
     *    DO forget it — stopping libpebble's futile connect loop — and notify the user to unpair on the
     *    watch + re-pair. Safe to forget because, unlike (1), the bond is already gone, not maybe-good.
     */
    private fun startChurnDetector() {
        val drops = ConcurrentHashMap<String, MutableList<Long>>() // device object path -> drop timestamps
        val notifiedAt = ConcurrentHashMap<String, Long>()         // path -> when we last (re-)notified
        val notifId = ConcurrentHashMap<String, UInt32>()          // path -> live notification id (replace/close)
        val unbondedSince = ConcurrentHashMap<String, Long>()      // path -> first poll the host bond was gone
        scope.launch(Dispatchers.IO) {
            val conn = try {
                DBusConnectionBuilder.forSystemBus().withShared(false).build()
            } catch (e: Exception) {
                log.debug { "Churn detector unavailable: ${e.message}" }
                return@launch
            }
            try {
                // Record every org.bluez.Device1 Disconnected(reason=Authentication), keyed by device path:
                // a host-side teardown because the watch rejected the bond (unpaired ON THE WATCH). This is
                // the precise broken-bond signal — out-of-range drops carry Reason.Timeout instead and are
                // NOT recorded, so a far watch never trips. (BlueZ >= 5.83; older BlueZ never emits it.)
                val rule = DBusMatchRuleBuilder.create()
                    .withType("signal").withInterface("org.bluez.Device1")
                    .withMember("Disconnected").build()
                conn.addGenericSigHandler(rule) { msg: DBusSignal ->
                    try {
                        val path = msg.getPath() ?: return@addGenericSigHandler
                        if (!path.contains("/dev_")) return@addGenericSigHandler
                        val params = msg.getParameters() ?: return@addGenericSigHandler
                        val reason = params.getOrNull(0) as? String ?: return@addGenericSigHandler
                        if (reason == "org.bluez.Reason.Authentication") {
                            drops.getOrPut(path) { java.util.Collections.synchronizedList(ArrayList()) }
                                .add(System.currentTimeMillis())
                        }
                    } catch (_: Exception) {}
                }
                // A full Pebble session (ConnectedPebbleDevice) PROVES the bond is intact, so clear that
                // watch's auth-failure history and any active alert the instant one happens. The reason
                // string already discriminates broken-bond (Authentication) from out-of-range (Timeout);
                // this is the belt-and-suspenders clear for the edge case where a transient auth-failure
                // is followed by a successful reconnect. Done continuously (not just at the 15s poll).
                scope.launch {
                    libPebble.watches.collect { list ->
                        list.filterIsInstance<ConnectedPebbleDevice>().forEach { w ->
                            (w.identifier as? PebbleBleIdentifier)?.asString
                                ?.let { bluezObjectPath(it) }?.let { p ->
                                    drops.remove(p)
                                    notifId.remove(p)?.let { closeChurnNotification(it) }
                                    notifiedAt.remove(p)
                                }
                        }
                    }
                }
                // Poll: a known watch that's NOT fully connected yet keeps dropping = the broken-bond churn.
                while (true) {
                    delay(15.seconds)
                    val now = System.currentTimeMillis()
                    val watchByPath = libPebble.watches.value.filterIsInstance<KnownPebbleDevice>()
                        .filter { it !is ConnectedPebbleDevice }
                        .mapNotNull { w ->
                            (w.identifier as? PebbleBleIdentifier)?.asString
                                ?.let { bluezObjectPath(it) }?.let { p -> p to w }
                        }.toMap()
                    drops.keys.retainAll(watchByPath.keys) // forget paths now fully connected / unknown
                    unbondedSince.keys.retainAll(watchByPath.keys)
                    // A watch that left the not-connected set (fully connected now, or gone): clear its alert.
                    for (path in notifId.keys - watchByPath.keys) {
                        notifId.remove(path)?.let { closeChurnNotification(it) }
                        notifiedAt.remove(path)
                    }
                    for ((path, w) in watchByPath) {
                        // The OTHER direction: the host has NO BlueZ pairing for this known watch (isBonded
                        // checks Device1.Paired; false also when `bluetoothctl remove` deleted the object).
                        // It can't reconnect without a fresh pair, so once that's held past the grace (and
                        // we're not mid-pairing, when the bond is briefly absent by design) forget it — which
                        // stops libpebble's futile connect loop — and tell the user how to bring it back.
                        val id = w.identifier as? PebbleBleIdentifier
                        if (id != null && !pairingGate.isOpen() && !isBonded(id)) {
                            val since = unbondedSince.getOrPut(path) { now }
                            if (now - since >= HOST_BOND_LOST_GRACE.inWholeMilliseconds) {
                                notifyHostBondLost(w)
                                bluezObjectPath(id.asString)?.let(::removeBluezBond)
                                bondCache.remove(id.asString)
                                w.forget()
                                unbondedSince.remove(path); drops.remove(path)
                                notifId.remove(path)?.let { closeChurnNotification(it) }; notifiedAt.remove(path)
                            }
                            continue // unbonded → not the flap case
                        }
                        unbondedSince.remove(path) // host bond present again → reset the grace timer

                        val list = drops[path] ?: continue
                        val recent = synchronized(list) {
                            list.removeAll { now - it > BROKEN_BOND_WINDOW.inWholeMilliseconds }; list.size
                        }
                        if (recent >= BROKEN_BOND_FLAPS) {
                            // Re-show on first detection, then every RENOTIFY_INTERVAL while it persists.
                            // replaces_id updates the SAME notification in place — it alerts but never stacks.
                            val last = notifiedAt[path]
                            if (last == null || now - last >= RENOTIFY_INTERVAL.inWholeMilliseconds) {
                                notifiedAt[path] = now
                                notifId[path] = notifyBrokenBond(w, notifId[path] ?: UInt32(0))
                            }
                        } else if (recent == 0) {
                            // Churn stopped (watch went out of range / quiet) — clear the alert.
                            notifId.remove(path)?.let { closeChurnNotification(it) }
                            notifiedAt.remove(path)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** (Re-)post the broken-bond alert. [replacesId] overwrites the existing one in place (0 = new). Returns its id. */
    private fun notifyBrokenBond(device: KnownPebbleDevice, replacesId: UInt32): UInt32 {
        val name = device.displayName()
        log.warn {
            "Broken-bond detector: $name — BlueZ reports repeated authentication-failure disconnects " +
                "(Reason.Authentication); the watch has unpaired on its side. Notifying; not forgetting it on its own."
        }
        return sendActionableNotification(
            "Pebble won't stay connected",
            "$name keeps connecting then dropping without finishing — if you unpaired it on the watch, " +
                "tap Re-pair (or run 'stoandl repair $name') and put the watch in pairing mode.",
            actionLabel = "Re-pair",
            replacesId = replacesId,
        ) { repairByName(name) }
    }

    /** Host has no BlueZ pairing for this watch (e.g. `bluetoothctl remove`). We've already forgotten it
     *  (it could never reconnect as-is); tell the user the one path back. The watch still holds its own
     *  bond, so it must be unpaired there first — only then does Pair (or 'stoandl pair') take. */
    private fun notifyHostBondLost(device: KnownPebbleDevice) {
        val name = device.displayName()
        log.warn {
            "Host bond lost for $name — BlueZ has no pairing (removed on this computer). Forgetting it; " +
                "the watch must be unpaired on its side and re-paired to reconnect."
        }
        sendActionableNotification(
            "Pebble pairing removed",
            "$name's pairing was removed on this computer. To reconnect, unpair it on the watch " +
                "(Settings → Bluetooth), then tap Pair (or run 'stoandl pair').",
            actionLabel = "Pair",
        ) { openPairingWindow() }
    }

    /** Close a churn notification once the watch reconnects / goes away (session bus, same conn as Notify). */
    private fun closeChurnNotification(id: UInt32) {
        if (id == UInt32(0)) return
        try {
            notifConn?.getRemoteObject(
                "org.freedesktop.Notifications", "/org/freedesktop/Notifications",
                FreedesktopNotifications::class.java,
            )?.CloseNotification(id)
        } catch (e: Exception) {
            log.debug { "closeChurnNotification failed: ${e.message}" }
        }
    }

    /** Re-pair ONE specific watch by name: forget just it (state + Trusted intent + BlueZ bond) and
     *  open the pairing window — used by the broken-bond notification's Re-pair button. Multi-watch safe. */
    private fun repairByName(name: String) {
        val match = libPebble.watches.value.filterIsInstance<KnownPebbleDevice>()
            .firstOrNull { it.displayName().equals(name, ignoreCase = true) } ?: return
        (match.identifier as? PebbleBleIdentifier)?.let { id ->
            bluezObjectPath(id.asString)?.let(::removeBluezBond)
            bondCache.remove(id.asString)
        }
        match.forget()
        openPairingWindow()
    }

    /** Opens the pairing window (the same gate 'stoandl pair' uses) so a watch in pairing mode re-pairs. */
    private fun openPairingWindow() {
        pairingGate.open()
        pairingState.set("pending:")
        log.info { "Pairing window opened via notification action (${PAIRING_WINDOW_MS / 1000}s)" }
    }

    /**
     * Long-lived session-bus listener for org.freedesktop.Notifications.ActionInvoked, so a
     * notification we send with an action button (see [sendActionableNotification]) can run a callback
     * when the user taps it.
     */
    private fun startNotificationActionListener() {
        scope.launch(Dispatchers.IO) {
            val conn = try {
                DBusConnectionBuilder.forSessionBus().withShared(false).build()
            } catch (e: Exception) {
                log.debug { "Notification action listener unavailable: ${e.message}" }
                return@launch
            }
            try {
                conn.addSigHandler(FreedesktopNotifications.ActionInvoked::class.java) { sig ->
                    notificationActions.remove(sig.id)?.let { cb ->
                        log.info { "Notification action '${sig.action_key}' invoked (id=${sig.id})" }
                        try { cb() } catch (e: Exception) { log.warn { "notification action handler failed: ${e.message}" } }
                    }
                }
                notifConn = conn
                awaitCancellation()
            } finally {
                notifConn = null
                conn.disconnect()
            }
        }
    }

    /**
     * Posts a desktop notification carrying a single action button; [onInvoke] runs when it's tapped.
     * timeout=0 so it persists until acted on/dismissed. Falls back to a plain notification if the
     * action listener isn't up — the body's "run 'stoandl pair'" hint is then the recovery path.
     */
    private fun sendActionableNotification(
        summary: String,
        body: String,
        actionLabel: String,
        replacesId: UInt32 = UInt32(0),
        onInvoke: () -> Unit,
    ): UInt32 {
        val conn = notifConn ?: run { sendDesktopNotification(summary, body); return UInt32(0) }
        return try {
            val id = conn.getRemoteObject(
                "org.freedesktop.Notifications", "/org/freedesktop/Notifications",
                FreedesktopNotifications::class.java,
            ).Notify("stoandl", replacesId, "phone", summary, body, listOf("repair", actionLabel), emptyMap(), 0)
            notificationActions[id] = onInvoke
            id
        } catch (e: Exception) {
            log.warn { "sendActionableNotification failed: ${e.message}" }
            sendDesktopNotification(summary, body)
            UInt32(0)
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
            weatherPins = config.weatherPins,
        )
        ws.start()
        weatherSyncRef.set(ws)
        log.info {
            "Weather sync started: ${config.weatherLocations.size} manual location(s)" +
                (if (hasSource) " + ${config.weatherLocationSource} source" else "") +
                (if (config.weatherGps) " + GPS current location" else "") +
                ", units=${config.weatherUnits}, every ${config.weatherIntervalMinutes}m" +
                (if (config.weatherPins) ", timeline pins on" else ", timeline pins off")
        }
    }

    /** Assemble the Linux calendar reader from config, or null when nothing is configured (then the
     *  no-op SystemCalendar binding stays and nothing syncs) — mirrors startWeatherSync's gate.
     *  Local .ics/discovery are egress-free; iCal URLs and CalDAV are opt-in egress. */
    private fun buildCalendarSync(): LinuxSystemCalendar? {
        val sources = mutableListOf<CalendarSource>()
        if (config.calendarIcsPaths.isNotEmpty()) sources += IcsPathSource(config.calendarIcsPaths)
        if (config.calendarDiscover) sources += DiscoverySource()
        val needsHttp = config.calendarIcalUrls.isNotEmpty() || config.calendarCalDav.isNotEmpty()
        val httpClient = if (needsHttp) HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
        } else null
        if (config.calendarIcalUrls.isNotEmpty()) {
            sources += IcalUrlSource(config.calendarIcalUrls, httpClient!!)
        }
        if (config.calendarCalDav.isNotEmpty()) {
            sources += CalDavSource(
                config.calendarCalDav.map { CalDavSource.Entry(it.url, it.username, it.password) },
                httpClient!!,
            )
        }
        if (sources.isEmpty()) return null
        // Directories to watch for near-instant updates: each ics_paths dir, the parent dir of each
        // ics_paths file, plus the discovery dirs. Network sources rely on the periodic ticker.
        val watchDirs = buildList {
            config.calendarIcsPaths.map(::File).forEach { f -> add(if (f.isDirectory) f else f.parentFile) }
            if (config.calendarDiscover) addAll(calendarDiscoveryDirs())
        }.filterNotNull().distinct()
        return LinuxSystemCalendar(sources, config.calendarSyncIntervalMinutes, watchDirs)
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

    /** Proactively check GitHub for newer firmware on each watch connect (throttled to once a day) and,
     *  when found, push a watch notification with an "Update" button. Off unless firmware.github +
     *  firmware.notify are both enabled (opt-in egress). The local `firmware <file.pbz>` sideload and
     *  the `firmware check`/`update` CLI commands work regardless of this. */
    private fun startFirmwareNotifier() {
        if (!config.firmwareGithub || !config.firmwareNotify) {
            log.info { "Firmware update notifications off (needs firmware.github=true and firmware.notify=true)" }
            return
        }
        val dailyMs = 24L * 60 * 60 * 1000
        log.info { "Firmware update notifications on (check on connect, at most once/day; repo=${config.firmwareGithubRepo})" }
        // Check on each fresh connect; maybeNotify() self-throttles to once per day.
        libPebble.watches
            .map { devices -> devices.any { it is ConnectedPebbleDevice } }
            .distinctUntilChanged()
            .onEach { connected -> if (connected) scope.launch { firmwareControl.maybeNotify(dailyMs) } }
            .launchIn(scope)
        // And re-check daily while a watch stays connected.
        scope.launch {
            while (true) {
                delay(dailyMs)
                if (libPebble.watches.value.any { it is ConnectedPebbleDevice }) {
                    firmwareControl.maybeNotify(dailyMs)
                }
            }
        }
    }

    /** Auto-start the developer connection (LAN server, port 9000) on every fresh watch connect when
     *  `developer.autostart` is on. The server lives in the watch's per-connection scope and dies on
     *  disconnect, so re-arming it on each reconnect is what makes autostart actually persistent. Off
     *  by default — the server is an unauthenticated LAN listener (see [DeveloperControl]). */
    private fun startDeveloperAutostart() {
        if (!config.developerAutostart) return
        log.info { "Developer connection autostart on (LAN server :9000 on every watch connect)" }
        libPebble.watches
            .map { devices -> devices.any { it is ConnectedPebbleDevice } }
            .distinctUntilChanged()
            .onEach { connected -> if (connected) scope.launch { developerControl.start() } }
            .launchIn(scope)
    }

    /**
     * Health/activity sync + export. The watch only sends health data when asked (nothing in
     * libpebble3 requests it automatically), so when `health.sync` is on we fire
     * [LibPebble.requestHealthData] on each fresh connect — incremental, since it asks for data newer
     * than the latest row already stored (the first run, with an empty DB, is therefore a full pull).
     * The data is ingested into the shared DB by libpebble3's HealthDataProcessor; when `health.export`
     * is on, [HealthExporter] projects it to NDJSON whenever new data lands.
     */
    private fun startHealthSync() {
        if (config.healthExport) {
            HealthExporter(
                libPebble = libPebble,
                scope = scope,
                exportDays = config.healthExportDays,
                exportSamples = config.healthExportSamples,
            ).also { healthExporterRef.set(it); it.start() }
        } else {
            log.info { "Health export disabled (health.export=false)" }
        }
        if (!config.healthSync) {
            log.info { "Health sync on connect disabled (health.sync=false)" }
            return
        }
        log.info { "Health sync on (request steps/sleep/HR/workouts from the watch on each connect)" }
        libPebble.watches
            .map { devices -> devices.any { it is ConnectedPebbleDevice } }
            .distinctUntilChanged()
            .onEach { connected -> if (connected) libPebble.requestHealthData(fullSync = false) }
            .launchIn(scope)
    }

    private fun registerControlService() {
        try {
            serviceConn.exportObject(STOANDL_OBJECT_PATH, StoandlControlImpl(libPebbleRef, weatherSyncRef, watchPrefsControlRef, calendarSyncRef, firmwareControl, languageControl, screenshotControl, logsControl, debugControl, developerControl, notificationAppsControl, healthExporterRef, scope, pairingGate, pairingState, bondCache) { libPebble.bluetoothEnabled.value.enabled() && btAdapterPowered.value })
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
    // watch-item UUID → app package, so a wrist "Mute" action knows which app to mute.
    private val itemIdToMutePkg: ConcurrentHashMap<Uuid, String>,
    private val dialerApps: List<String>,
    private val dialerNameCache: DialerNameCache,
    // Per-app mute store. Apps are lazy-added the first time they notify; mute state is enforced
    // host-side (drop before send) here, and each forwarded notification carries a "Mute" action so
    // it can also be muted from the wrist (write-back into this same DAO).
    private val notifAppDao: NotificationAppRealDao?,
    private val defaultMute: MuteState,
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
                // Per-app store: lazy-add the app, refresh lastNotified, and enforce its mute state
                // host-side. The appName is the app identity on Linux (no package ids).
                val dao = notifAppDao
                var trackedApp: NotificationAppItem? = null
                if (dao != null && notification.appName.isNotBlank()) {
                    val pkg = notification.appName
                    val now = Clock.System.now()
                    val existing = dao.getEntry(pkg)
                    val entry = if (existing == null) {
                        val created = NotificationAppItem(
                            packageName = pkg,
                            name = notification.appName,
                            muteState = defaultMute,
                            channelGroups = emptyList(),
                            stateUpdated = now.asMillisecond(),
                            lastNotified = now.asMillisecond(),
                            muteExpiration = null,
                            vibePatternName = null,
                            colorName = null,
                            iconCode = null,
                        )
                        dao.insertOrReplace(created)
                        log.info { "Tracking new notification app '${notification.appName}' (default mute=${defaultMute.name.lowercase()})" }
                        created
                    } else {
                        dao.insertOrReplace(existing.copy(lastNotified = now.asMillisecond()))
                        existing
                    }
                    if (isMutedNow(entry, now)) {
                        log.info { "Muted notification from ${notification.appName} (${entry.muteState.name.lowercase()})" }
                        return@collect
                    }
                    trackedApp = entry
                }
                try {
                    // Captured as a val so the per-app overrides + the Mute action smart-cast inside
                    // the builder lambdas. Null when per-app tracking is off.
                    val app = trackedApp
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
                            // Per-app overrides (set via `notif style`) fall back to the app-name icon.
                            tinyIcon { perAppIcon(app?.iconCode, notification.appName) }
                            perAppColor(app?.colorName)?.let { c -> backgroundColor { c.toPebbleColor() } }
                            perAppVibe(app?.vibePatternName)?.let { v -> vibrationPattern { v } }
                        }
                        actions {
                            action(TimelineItem.Action.Type.Dismiss) {
                                attributes { title { "Dismiss" } }
                            }
                            // "Mute <app>" — same mechanism the official Android app uses: a Generic
                            // timeline action the watch surfaces in the notification's action menu.
                            // On press it round-trips back (handled in DbusNotificationActionHandler)
                            // and mutes host-side. Needs no BlobDB app-sync.
                            if (app != null) {
                                action(TimelineItem.Action.Type.Generic) {
                                    attributes { title { "Mute ${app.name}" } }
                                }
                            }
                        }
                    }
                    itemIdToDbusId[timelineNotification.itemId] = notification.id
                    if (app != null) itemIdToMutePkg[timelineNotification.itemId] = app.packageName
                    libPebble.sendNotification(timelineNotification)
                    log.info { "Notification queued for watch: ${notification.appName} – ${notification.summary}" }
                } catch (e: Exception) {
                    log.warn(e) { "Failed to send notification to watch" }
                }
            }
        }
    }
}

/**
 * Host-side mute decision for a tracked app, mirroring the relevant parts of libpebble3's
 * Android `decideNotification`. A temporary mute ([NotificationAppItem.muteExpiration], set by
 * `notif mute <app> 1h`) takes precedence: muted until it expires, then delivered. Otherwise the
 * [MuteState] applies — `Always` mutes, `Never`/`Exempt` deliver, and `Weekdays`/`Weekends` are
 * day-of-week bitmasks (bit i = day, Sunday=0) tested against the host's current local day.
 */
// Per-app notification styling (set via `stoandl notif style`), applied to the outgoing notification
// at send time — host-side, watch-visible, independent of any BlobDB app-sync.
// Named vibration presets (shared with the `notif styles` CLI listing). Each is an on/off-millisecond
// pattern; perAppVibe also accepts a raw CSV of the same.
internal val VIBE_PRESETS: Map<String, List<UInt>> = mapOf(
    "short" to listOf(120u),
    "long" to listOf(500u),
    "double" to listOf(100u, 100u, 100u),
    "triple" to listOf(80u, 80u, 80u, 80u, 80u),
    "pulse" to listOf(60u, 140u, 60u, 140u, 60u),
)

private fun perAppColor(name: String?): TimelineColor? = name?.let { TimelineColor.findByName(it) }

private fun perAppIcon(code: String?, appName: String): TimelineIcon {
    if (code != null) {
        TimelineIcon.entries.firstOrNull { it.name.equals(code, ignoreCase = true) }?.let { return it }
        TimelineIcon.fromCode(code)?.let { return it }
    }
    return iconForApp(appName)
}

/** A named preset (`short`/`double`/…) or a raw CSV of on/off millisecond durations (`100,50,100`). */
private fun perAppVibe(spec: String?): List<UInt>? {
    if (spec == null) return null
    VIBE_PRESETS[spec.lowercase()]?.let { return it }
    return spec.split(',').mapNotNull { it.trim().toUIntOrNull() }.takeIf { it.isNotEmpty() }
}

private fun parseMuteState(s: String): MuteState = when (s.lowercase()) {
    "always" -> MuteState.Always
    "weekdays" -> MuteState.Weekdays
    "weekends" -> MuteState.Weekends
    else -> MuteState.Never
}

private fun isMutedNow(item: NotificationAppItem, now: kotlin.time.Instant): Boolean {
    item.muteExpiration?.let { return it.instant > now }
    return when (item.muteState) {
        MuteState.Always -> true
        MuteState.Never, MuteState.Exempt -> false
        MuteState.Weekdays, MuteState.Weekends -> {
            // java.time DayOfWeek: Mon=1..Sun=7; %7 maps to the firmware's Sunday=0 indexing.
            val sundayZero = java.time.LocalDate.now().dayOfWeek.value % 7
            (item.muteState.value.toInt() and (1 shl sundayZero)) != 0
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
    private val itemIdToMutePkg: ConcurrentHashMap<Uuid, String>,
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val notifAppDao: NotificationAppRealDao?,
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
            // "Mute <app>" — the only Generic action we attach. Mute the app host-side; the desktop
            // notification is left alone (mute ≠ dismiss). `stoandl notif unmute <app>` reverses it.
            TimelineItem.Action.Type.Generic -> {
                val pkg = itemIdToMutePkg[itemId]
                val dao = notifAppDao
                if (pkg != null && dao != null) {
                    dao.updateAppMuteState(pkg, MuteState.Always)
                    log.info { "Muted '$pkg' from watch action" }
                    TimelineActionResult(true, TimelineIcon.ResultMute, "Muted")
                } else {
                    log.info { "Generic action on $itemId with no mute mapping" }
                    TimelineActionResult(false, TimelineIcon.ResultFailed, "Not supported")
                }
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
        val m = e.message ?: ""
        // The device object being already gone (e.g. `bluetoothctl remove`, or the host-bond-lost path
        // where isBonded was already false) is the desired end state, not a failure — don't cry WARN.
        if (m.contains("Does Not Exist") || m.contains("doesn't exist") || m.contains("UnknownObject")) {
            log.debug { "removeBluezBond($devicePath): bond already gone" }
            true
        } else {
            log.warn { "removeBluezBond($devicePath) failed: $m" }
            false
        }
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
    private val calendarSyncRef: AtomicReference<LinuxSystemCalendar?>,
    private val firmwareControl: FirmwareControl,
    private val languageControl: LanguageControl,
    private val screenshotControl: ScreenshotControl,
    private val logsControl: LogsControl,
    private val debugControl: DebugControl,
    private val developerControl: DeveloperControl,
    // Null when notification.per_app is off.
    private val notificationAppsControl: NotificationAppsControl?,
    // Null when health.export is off (no exporter to re-project on demand).
    private val healthExporterRef: AtomicReference<HealthExporter?>,
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

    override fun SyncCalendar(): String {
        val cal = calendarSyncRef.get()
            ?: return "error:Calendar sync not enabled (set calendar.* in stoandl.conf)"
        cal.requestRefresh()
        return "ok:Calendar re-sync requested"
    }

    override fun ListCalendars(): List<String> {
        val lp = libPebbleRef.get() ?: return emptyList()
        return try {
            runBlocking { lp.calendars().first() }
                .map { "${it.id}\t${it.name}\t${if (it.enabled) "enabled" else "disabled"}" }
        } catch (e: Exception) {
            log.warn(e) { "ListCalendars failed" }
            emptyList()
        }
    }

    override fun SetCalendarEnabled(query: String, enabled: Boolean): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        return try {
            val cals = runBlocking { lp.calendars().first() }
            // Exact id wins, then an exact (case-insensitive) name — so a calendar whose name is a
            // substring of another (e.g. "Work" vs "Workout") can be picked in full — then substring.
            val matches = query.toIntOrNull()?.let { id -> cals.filter { it.id == id } }?.takeIf { it.isNotEmpty() }
                ?: cals.filter { it.name.equals(query, ignoreCase = true) }.takeIf { it.isNotEmpty() }
                ?: cals.filter { it.name.contains(query, ignoreCase = true) }
            when {
                matches.isEmpty() -> "notfound:No calendar matching '$query'"
                matches.size > 1 -> "ambiguous:" + matches.joinToString("; ") { "${it.id}:${it.name}" } +
                    " — type the exact name or the id to pick just one"
                else -> {
                    lp.updateCalendarEnabled(matches.first().id, enabled)
                    calendarSyncRef.get()?.requestRefresh()
                    "ok:${if (enabled) "Enabled" else "Disabled"} ${matches.first().name}"
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "SetCalendarEnabled failed" }
            "error:${e.message ?: "failed"}"
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

    override fun SideloadFirmware(path: String): String {
        log.info { "SideloadFirmware: $path" }
        return firmwareControl.sideload(path)
    }

    override fun FirmwareStatus(): String = firmwareControl.status()

    override fun CheckFirmware(): String = try {
        runBlocking { firmwareControl.check() }
    } catch (e: Exception) {
        log.warn(e) { "CheckFirmware failed" }
        "error:${e.message ?: "firmware check failed"}"
    }

    override fun UpdateFirmware(): String = try {
        runBlocking { firmwareControl.update() }
    } catch (e: Exception) {
        log.warn(e) { "UpdateFirmware failed" }
        "error:${e.message ?: "firmware update failed"}"
    }

    override fun SideloadLanguage(path: String): String {
        log.info { "SideloadLanguage: $path" }
        return languageControl.sideload(path)
    }

    override fun ListLanguages(): List<String> = languageControl.list()

    override fun InstallLanguage(query: String): String {
        log.info { "InstallLanguage: '$query'" }
        return languageControl.install(query)
    }

    override fun LanguageStatus(): String = languageControl.status()

    override fun TakeScreenshot(path: String): String {
        log.info { "TakeScreenshot: $path" }
        return screenshotControl.capture(path)
    }

    override fun GatherLogs(path: String): String {
        log.info { "GatherLogs: $path" }
        return logsControl.gatherLogs(path)
    }

    override fun GetCoreDump(path: String): String {
        log.info { "GetCoreDump: $path" }
        return logsControl.getCoreDump(path)
    }

    override fun Battery(): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = lp.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()
            ?: return "notready:No watch connected"
        val level = dev.batteryLevel ?: return "unknown:${dev.displayName()}"
        return "ok:${dev.displayName()}\t$level"
    }

    override fun SyncHealth(): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = lp.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()
            ?: return "notready:No watch connected"
        // Fire-and-forget: the watch streams data back asynchronously (ingested into the DB by
        // libpebble3), which fires healthDataUpdated → the exporter re-projects. Re-project once now too
        // so any data already in the DB is written even if this sync turns up nothing new.
        lp.requestHealthData(fullSync = false)
        healthExporterRef.get()?.let { exporter -> scope.launch { exporter.exportNow() } }
        return "ok:Requested health sync from ${dev.displayName()}"
    }

    override fun WatchInfoText(): String = logsControl.watchInfoText()

    override fun FactoryReset(): String {
        log.info { "FactoryReset requested" }
        return debugControl.factoryReset()
    }

    override fun ResetIntoRecovery(): String {
        log.info { "ResetIntoRecovery requested" }
        return debugControl.resetIntoRecovery()
    }

    override fun StartDevConnection(): String {
        log.info { "StartDevConnection requested" }
        return developerControl.start()
    }

    override fun StopDevConnection(): String {
        log.info { "StopDevConnection requested" }
        return developerControl.stop()
    }

    override fun DevConnectionStatus(): String = developerControl.status()

    override fun NotifList(): List<String> =
        notificationAppsControl?.list() ?: emptyList()

    override fun NotifSetMute(query: String, spec: String): String =
        notificationAppsControl?.setMute(query, spec)
            ?: "error:Per-app notifications are disabled (set notification.per_app = true)"

    override fun NotifSetMuteAll(spec: String): String =
        notificationAppsControl?.setMuteAll(spec)
            ?: "error:Per-app notifications are disabled (set notification.per_app = true)"

    override fun NotifSetStyle(query: String, color: String, icon: String, vibe: String): String =
        notificationAppsControl?.setStyle(query, color, icon, vibe)
            ?: "error:Per-app notifications are disabled (set notification.per_app = true)"

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

    override fun FindWatch(): Boolean {
        val lp = libPebbleRef.get() ?: run {
            log.warn { "FindWatch: libPebble not ready" }
            return false
        }
        val cookie = Random.nextUInt()
        log.info { "[findwatch] ringing watch (cookie=$cookie)" }
        // Reuse the incoming-call path: the watch rings continuously like a real call until the
        // user presses a button on the call screen. There is no call to hold, so Answer and
        // Decline both just silence the ring.
        lp.currentCall.value = Call.RingingCall(
            contactName = "Find My Watch",
            contactNumber = "",
            cookie = cookie,
            onCallEnd = {
                log.info { "[findwatch] watch declined — ring silenced (cookie=$cookie)" }
                lp.currentCall.value = null
            },
            onCallAnswer = {
                log.info { "[findwatch] watch answered — ring silenced (cookie=$cookie)" }
                lp.currentCall.value = null
            },
        )
        return true
    }

    private fun findPkjsApp(query: String): PKJSApp? {
        val lp = libPebbleRef.get() ?: return null
        val apps = lp.watches.value
            .filterIsInstance<ConnectedPebbleDevice>()
            .flatMap { it.currentCompanionAppSessions.value }.filterIsInstance<PKJSApp>()
        if (query.isEmpty()) return apps.firstOrNull()
        // Prefer an exact shortName / uuid match before a substring hit, so "whatsapp" doesn't pick a
        // running "whatsapps".
        return apps.firstOrNull {
            it.appInfo.shortName.equals(query, ignoreCase = true) || it.appInfo.uuid.equals(query, ignoreCase = true)
        } ?: apps.firstOrNull { it.appInfo.shortName.contains(query, ignoreCase = true) }
    }

    override fun Pair(): String {
        val lp = libPebbleRef.get() ?: return "error:Daemon not ready"
        // Already connected — report immediately without a pairing window.
        if (lp.watches.value.any { it is ConnectedPebbleDevice }) {
            pairingState.set("ok:Watch already connected")
            return "ok:Pairing started"
        }
        // NB: 'pair' deliberately does NOT touch existing known watches or their bonds — that would
        // break multi-watch (it'd nuke a second watch that's merely out of range). It just opens the
        // pairing window to discover + pair whatever watch is in pairing mode. To re-pair a specific
        // known-but-broken watch (e.g. one unpaired on the watch), use 'stoandl repair <name>', which
        // forgets only that watch first. The broken-bond detector triggers the same targeted recovery
        // automatically + offers a one-tap re-pair notification.
        log.info { "Pairing mode opened (${PAIRING_WINDOW_MS / 1000}s window)" }
        openPairingWindowAndMonitor(lp)
        return "ok:Pairing started"
    }

    /** Opens the pairing window and launches the monitor that resolves [pairingState] to `ok:`/`timeout:`
     *  when the watch connects or bonds. Shared by Pair() and Repair() — otherwise Repair would leave
     *  PairStatus() stuck on `pending:` and the CLI would hang until its own timeout even though the
     *  watch re-paired fine. */
    private fun openPairingWindowAndMonitor(lp: LibPebble) {
        // Snapshot which devices are already bonded so the bond-poll job only fires on NEW bonds.
        val alreadyBonded = lp.watches.value
            .mapNotNull { it.identifier as? PebbleBleIdentifier }
            .filter { isBonded(it) }
            .map { it.asString }
            .toSet()
        pairingGate.open()
        pairingState.set("pending:")
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
    }

    override fun PairStatus(): String =
        pairingState.get().ifEmpty { "error:No pairing in progress" }

    override fun Unpair(): String {
        val lp = libPebbleRef.get() ?: return "error:Daemon not ready"
        val known = lp.watches.value.filterIsInstance<KnownPebbleDevice>()
        // forget() each known watch (stops libpebble3 auto-connect) and clear its BlueZ bond. Only
        // *report* watches that were genuinely still bonded: forget() can leave a wedged
        // KnownPebbleDevice in the list (a stuck standing-connect attempt to a moved/out-of-range
        // watch keeps hasConnectionAttempt true, which blocks WatchManager from evicting it), so the
        // same entry can reappear here on a later 'unpair' even though it's already unpaired. The
        // BlueZ Paired property (isBonded) is the real "paired" state — gate the message on it so a
        // repeat unpair on an already-unpaired watch honestly reports nothing left to do.
        val names = known.mapNotNull { d ->
            val id = d.identifier as? PebbleBleIdentifier
            val wasBonded = id != null && isBonded(id)
            id?.let { bluezObjectPath(it.asString)?.let(::removeBluezBond) }
            d.forget()
            if (wasBonded) d.displayName() else null
        }
        // Also sweep any leftover bonded Pebble in BlueZ that libpebble3 no longer tracks.
        val swept = clearStalePebbleBonds()
        return when {
            names.isNotEmpty() -> "ok:Unpaired ${names.joinToString(", ")}"
            swept.isNotEmpty() -> "ok:Cleared ${swept.size} stale bond(s)"
            else -> "ok:No paired watch"
        }
    }

    override fun Repair(watch: String): String {
        val lp = libPebbleRef.get() ?: return "error:Daemon not ready"
        val known = lp.watches.value.filterIsInstance<KnownPebbleDevice>()
        if (known.isEmpty()) return "error:No known watches — use 'stoandl pair' to add one"
        // Substring match (case-insensitive) so 'repair B349' matches "Pebble B349" — no need to type
        // (and shell-escape) the full name. Prefer an exact match if one exists; else require a unique
        // substring hit so we never re-pair the wrong watch.
        val matches = known.filter { it.displayName().equals(watch, ignoreCase = true) }
            .ifEmpty { known.filter { it.displayName().contains(watch, ignoreCase = true) } }
        val match = when {
            matches.size == 1 -> matches[0]
            matches.isEmpty() -> return "error:No known watch matching '$watch'. Known: " +
                known.joinToString(", ") { it.displayName() }
            else -> return "error:'$watch' matches multiple watches (${matches.joinToString(", ") { it.displayName() }}) — be more specific"
        }
        // Forget ONLY this watch — its libpebble3 state, standing (Trusted) connect intent and BlueZ
        // bond — leaving any other watches untouched (multi-watch safe). Then open the pairing window
        // so the watch (put in pairing mode) re-pairs fresh.
        (match.identifier as? PebbleBleIdentifier)?.let { id ->
            bluezObjectPath(id.asString)?.let(::removeBluezBond)
            bondCache.remove(id.asString)
        }
        match.forget()
        log.info { "Re-pairing ${match.displayName()}: forgot the stale bond, opening pairing window (${PAIRING_WINDOW_MS / 1000}s)" }
        openPairingWindowAndMonitor(lp)
        return "ok:Re-pairing ${match.displayName()} — put the watch in pairing mode"
    }

    override fun ListWatches(): List<String> {
        val lp = libPebbleRef.get() ?: return emptyList()
        return lp.watches.value.filterIsInstance<KnownPebbleDevice>().map { d ->
            val state = when (d) {
                is ConnectedPebbleDevice -> "connected"
                is ConnectingPebbleDevice -> "connecting"
                else -> "disconnected"
            }
            // batteryLevel is only meaningful (and reachable) on a live connection.
            val battery = (d as? ConnectedPebbleDevice)?.batteryLevel?.toString() ?: ""
            "${d.displayName()}\t$state\t$battery"
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
