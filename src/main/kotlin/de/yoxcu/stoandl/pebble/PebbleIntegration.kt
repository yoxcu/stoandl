@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.dbus.FreedesktopNotifications
import de.yoxcu.stoandl.dbus.IncomingNotification
import de.yoxcu.stoandl.dbus.ModemManagerCallMonitor
import de.yoxcu.stoandl.dbus.MprisMusicControl
import de.yoxcu.stoandl.dbus.TimedateTimeChanged
import de.yoxcu.stoandl.calls.MissedCallLog
import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.GUI_CONFIG_FIELDS
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.config.applyGuiConfig
import de.yoxcu.stoandl.config.StoandlConfig.WeatherLocationSource
import de.yoxcu.stoandl.notification.NotificationFilters
import de.yoxcu.stoandl.util.ConfFile
import de.yoxcu.stoandl.debug.DebugControl
import de.yoxcu.stoandl.developer.DeveloperControl
import de.yoxcu.stoandl.dnd.DndSync
import de.yoxcu.stoandl.dnd.detectHostDnd
import de.yoxcu.stoandl.ext.ExtensionManager
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import de.yoxcu.stoandl.firmware.FirmwareControl
import de.yoxcu.stoandl.language.LanguageControl
import de.yoxcu.stoandl.notification.NotificationAppsControl
import de.yoxcu.stoandl.icons.AppIconExtractor
import de.yoxcu.stoandl.screenshot.ScreenshotControl
import de.yoxcu.stoandl.support.LogsControl
import de.yoxcu.stoandl.battery.BatteryStore
import de.yoxcu.stoandl.battery.HeartbeatStore
import de.yoxcu.stoandl.datalog.DatalogStore
import de.yoxcu.stoandl.health.HealthExporter
import de.yoxcu.stoandl.weather.DeLocationSource
import de.yoxcu.stoandl.location.GeoClueLocationProvider
import de.yoxcu.stoandl.location.LiveGeolocation
import de.yoxcu.stoandl.weather.WeatherSync
import de.yoxcu.stoandl.contacts.ContactResolver
import de.yoxcu.stoandl.contacts.DialerNameCache
import io.rebble.libpebblecommon.calls.SystemCallLog
import de.yoxcu.stoandl.dbus.STOANDL_DESKTOP_ONLY_APP
import de.yoxcu.stoandl.dbus.STOANDL_OBJECT_PATH
import de.yoxcu.stoandl.dbus.StoandlControl
import de.yoxcu.stoandl.dbus.sendDesktopNotification
import de.yoxcu.stoandl.util.openSessionBus
import de.yoxcu.stoandl.util.unwrapVariant
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
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.ConnectingPebbleDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.bt.createBondClassic
import io.rebble.libpebblecommon.connection.bt.isBondedClassic
import io.rebble.libpebblecommon.connection.WatchConnector
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.di.PlatformConfig
import io.rebble.libpebblecommon.di.initKoin
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.timeline.TimelineColor
import io.rebble.libpebblecommon.js.InjectedPKJSHttpInterceptors
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.metadata.supportsHrm
import io.rebble.libpebblecommon.services.DailySleep
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.time.TimeChanged
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import de.yoxcu.stoandl.calendar.CalDavSource
import de.yoxcu.stoandl.calendar.CalendarSource
import de.yoxcu.stoandl.calendar.CalendarSources
import de.yoxcu.stoandl.calendar.DiscoverySource
import de.yoxcu.stoandl.calendar.IcalUrlSource
import de.yoxcu.stoandl.calendar.IcsPathSource
import de.yoxcu.stoandl.calendar.LinuxSystemCalendar
import de.yoxcu.stoandl.calendar.calendarDiscoveryDirs
import de.yoxcu.stoandl.secret.FileSecretStore
import de.yoxcu.stoandl.secret.LayeredSecretStore
import de.yoxcu.stoandl.secret.SecretServiceStore
import de.yoxcu.stoandl.secret.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.util.SystemGeolocation
import java.io.File

private const val MAX_CONNECTION_ATTEMPTS = 5
private const val PAIRING_WINDOW_MS = 120_000L  // 2 minutes
// How long the phone-side numeric-comparison confirmation waits for the user (ConfirmPairing) before
// declining. BlueZ agent calls are user-interactive, so it tolerates this human-scale wait.
private const val PAIRING_CONFIRM_TIMEOUT_MS = 60_000L

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
// A watch that merely drifts out of range reconnects within a minute or two via BlueZ's kernel
// accept-list passive scan — which is invisible to D-Bus and never sets Adapter1.Discovering, so it
// is NOT what this monitor detects. Only a genuinely stuck reconnect — an external client holding an
// ACTIVE discovery session that monopolises the single controller scanner — keeps the watch down
// indefinitely. Require the (bonded watch disconnected + external discovery active) condition to
// persist this long before alarming, so a benign out-of-range drop that merely coincides with a
// brief external scan never fires a false "blocked by a Bluetooth scan" notification.
private val DISCOVERY_WARN_GRACE = 5.minutes

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
    // Battery insights (see docs). Heartbeat is the primary source and is fed straight from the
    // WebServices override, so its ref is a plain field (initialised before init() wires initKoin);
    // the GATT-level series is a fallback started/stopped by applyBattery. Both null when disabled.
    private val batteryStoreRef = AtomicReference<BatteryStore?>(null)
    private val heartbeatStoreRef = AtomicReference<HeartbeatStore?>(null)
    private val watchPrefsControlRef = AtomicReference<WatchPrefsControl?>(null)
    private val calendarSyncRef = AtomicReference<LinuxSystemCalendar?>(null)
    // Held for the daemon lifetime so its host-DND backend (bus connection / gsettings monitor) and
    // sync coroutines aren't dropped; null when dnd.sync=off or no host backend was detected.
    private var dndSync: DndSync? = null
    // The extension supervisor (companion apps). Constructed in init() before the control service so
    // the `stoandl ext` D-Bus methods can drive it; started after libPebble is up.
    private lateinit var extensionManager: ExtensionManager
    // Live config: a holder reloaded on every SetConfig/SetSyncEnabled write. `config` is a getter over
    // it, so existing `config.x` reads (and the new apply*/reconcile paths) see the current value rather
    // than a frozen startup snapshot — that's what makes settings apply without a restart.
    private val configStore = ConfigStore()
    private val config: StoandlConfig get() = configStore.current()
    // Global allow/block notification filters (own file under configDir; live-mutable via D-Bus).
    private val notificationFilters = NotificationFilters(File(StoandlConfig.configDir(), "notification-filters"))
    // Secret store for CalDAV passwords: the system keyring (org.freedesktop.secrets) when it's unlocked,
    // else a 0600 file under the config dir (kept OUT of backups). So a password never sits in
    // stoandl.conf or a backup tarball. Used by the live calendar sources + the source-CRUD methods.
    private val secretStore: SecretStore =
        LayeredSecretStore(SecretServiceStore(), FileSecretStore(File(StoandlConfig.configDir(), "secrets")))
    // One HTTP client for the network calendar sources (iCal feeds + CalDAV), built lazily the first
    // time a network source exists and reused across live source rebuilds.
    private val calendarHttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
        }
    }
    // The single MPRIS music bridge (Koin-bound once; gated live on music.enabled). Set in init().
    private var mprisMusicControl: MprisMusicControl? = null
    // The health on-connect-request collector (when health.sync is on), held so applyHealth can cancel it.
    private var healthRequestJob: Job? = null
    // Last successful sync time (epoch seconds) per discrete-sync service (weather/calendar/health), for
    // GetSyncStatus's lastSync column; continuous services (notif/music/dnd) report live/mode instead.
    private val lastSyncAt = ConcurrentHashMap<String, Long>()
    private fun stampSync(service: String) { lastSyncAt[service] = System.currentTimeMillis() / 1000 }
    private val contactResolver = ContactResolver(config.vcardPaths)
    private val dialerNameCache = DialerNameCache()
    private val missedCallLog = MissedCallLog()
    // Headless BlueZ pairing agent so MITM/Secure-Connections pairing (newer Pebble firmware,
    // e.g. Time 2) can complete without a desktop UI — the user just confirms the code on the watch.
    private val pairingAgent = BluezPairingAgent()
    private val pairingGate = PairingGate()
    // "pending:" while pairing is in progress; "confirm:<code>" while awaiting the user's numeric-
    // comparison decision; "ok:…" / "error:…" / "timeout:…" when done.
    private val pairingState = AtomicReference<String>("")
    // Numeric-comparison confirmation: the BlueZ agent parks here until ConfirmPairing answers.
    private val pairingConfirmation = PairingConfirmation()
    // True only for a client-initiated (Pair/Repair) window: require an explicit ConfirmPairing before
    // bonding. The notification re-pair path leaves it false (auto-accept — the user already consented).
    // Shared (mutable) with StoandlControlImpl, which flips it around the monitored window.
    private val requireConfirm = AtomicBoolean(false)
    // The in-flight monitored pairing's result, so a decline/timeout resolves PairStatus immediately.
    private val pairingResult = AtomicReference<CompletableDeferred<String>?>(null)
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
        //  - onConfirm: numeric-comparison decision (see onPairingConfirm) — block for the user on a
        //    client-initiated window, auto-accept otherwise.
        //  - onPairingCode: display-only passkey path — just surface the code.
        pairingAgent.register(
            onPairingCode = { code ->
                if (pairingGate.isOpen()) pairingState.set("pending:Confirm code $code on the watch")
            },
            onConfirm = ::onPairingConfirm,
        )

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
            webServices = StoandlWebServices(heartbeatStoreRef),
            appContext = AppContext(),
            tokenProvider = NoOpTokenProvider,
            proxyTokenProvider = MutableStateFlow(null),
            transcriptionProvider = NoOpTranscriptionProvider,
            injectedPKJSHttpInterceptors = InjectedPKJSHttpInterceptors(emptyList()),
        )

        // The single notification choke point + its action router (Phase 0 of the extension system):
        // both the desktop bridge and every extension push through `watchNotifier`, and every watch-side
        // action routes back through `watchActionRouter` via the shared `routeTable`. The desktop owner
        // holds the watch-item → D-Bus-id map so a wrist dismiss can CloseNotification the original.
        // Always built now — `notification.per_app` is toggled live (consulted per-push in WatchNotifier),
        // so the store must always exist; it's just not consulted while per_app is off.
        val notifDao = koin.get<NotificationAppRealDao>()
        val timelineNotifDao = koin.get<TimelineNotificationRealDao>()
        // In-memory route table: the firmware only offers the action menu on the live notification (not
        // from history), so a route never needs to outlive the daemon.
        val routeTable = NotifRouteTable()
        val notifOwners = NotifOwnerRegistry().apply { register(DesktopNotifOwner()) }
        val watchNotifier = WatchNotifier(libPebbleRef, routeTable, notifDao, parseMuteState(config.notificationDefaultMute), timelineNotifDao, configStore, notificationFilters)
        val watchActionRouter = WatchActionRouter(routeTable, notifOwners, notifDao, timelineNotifDao)
        // Extension supervisor: constructed now (so the control service can reach it); started below.
        extensionManager = ExtensionManager(
            extDir = File(StoandlConfig.configDir(), "ext"),
            confFile = StoandlConfig.configFile(),
            enabledAtStart = config.extensionsEnabled,
            extConfig = config.extensionConfig,
            watchNotifier = watchNotifier,
            owners = notifOwners,
            libPebbleRef = libPebbleRef,
            scope = scope,
        )

        // The Linux calendar reader — always built (reads its sources live from config), so a source
        // added later via the GUI/CLI syncs with no restart. libpebble3's PhoneCalendarSyncer reads it
        // and handles all pin creation/diffing/deletion itself.
        val calendarSync = buildCalendarSync()
        calendarSyncRef.set(calendarSync)

        // Override modules: use our DBus notification bridge, and pin Ble-/WatchConfigFlow so any
        // persisted Java Preferences value cannot override reversedPPoG=false / lanDevConnection=true.
        koin.loadModules(listOf(module {
            single<NotificationListenerConnection> {
                DbusNotificationListenerConnection(
                    notificationFlow, scope,
                    dialerApps = config.dialerApps,
                    dialerNameCache = dialerNameCache,
                    watchNotifier = watchNotifier,
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
            // Route every watch-side notification action to whoever sent it (desktop bridge or an
            // extension) via the shared routeTable: Dismiss marks the item read + closes the originating
            // desktop notification over D-Bus / fires the extension's onDismiss; the "Mute" action mutes
            // host-side; Reply/named actions reach the owner. Without this binding the JVM module's no-op
            // handler runs and watch→host actions silently do nothing. (Don't drop this line — it was
            // once clobbered by an unrelated refactor.)
            single<PlatformNotificationActionHandler> { watchActionRouter }
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
            // Replace the no-op JVM SystemMusicControl with the MPRIS bridge so the watch's Music app
            // shows now-playing and its buttons drive the desktop player. Bound once (Koin caches it) and
            // gated live on music.enabled — it reads the config store per-event, so turning music control
            // on/off (and switching the volume target) takes effect without a restart.
            single<SystemMusicControl> { MprisMusicControl(scope, configStore).also { mprisMusicControl = it } }
            // Replace the no-op JVM SystemCalendar with the Linux reader (always bound; it reads its
            // sources live), so PhoneCalendarSyncer turns desktop calendar events into watch timeline
            // pins. The reader gates on calendar.enabled live (no events while off → pins removed; refresh
            // on re-enable) and exposes nothing until a source is configured.
            single<SystemCalendar> { calendarSync }
            // Replace the no-op JVM SystemGeolocation with a GeoClue2-backed one so watchapps'
            // navigator.geolocation (PKJS) and location-aware sports/GPS apps get a real fix. Lazy:
            // the GeoClue client is only created when a watchapp first asks for location. Reuses the
            // weather GeoClue identity (its own client; GeoClue is per-sender multi-client). When off
            // (the default), bind DisabledGeolocation instead of leaving libpebble3's no-op so the
            // watchapp's error says it's disabled (opt-in), not the misleading "Not supported on Linux".
            single<SystemGeolocation> {
                LiveGeolocation(
                    enabled = { config.geolocation },
                    provider = { GeoClueLocationProvider(config.weatherGpsDesktopId) },
                )
            }
        }), allowOverride = true)

        libPebble = koin.get()
        libPebbleRef.set(libPebble)
        firmwareControl = FirmwareControl(libPebbleRef, scope, config, notifyDesktop = { summary, body, label, onAction ->
            // STOANDL_DESKTOP_ONLY_APP → not bridged to the watch (the watch gets a direct notif too).
            sendActionableNotification(summary, body, label, appName = STOANDL_DESKTOP_ONLY_APP, onInvoke = onAction)
        })
        languageControl = LanguageControl(libPebbleRef, config)
        screenshotControl = ScreenshotControl(libPebbleRef)
        logsControl = LogsControl(libPebbleRef)
        debugControl = DebugControl(libPebbleRef)
        developerControl = DeveloperControl(libPebbleRef)
        // Always built (the per-app store always exists now); per_app only gates enforcement in push().
        notificationAppsControl = NotificationAppsControl(koin.get())
        watchConnector = koin.get()
        watchBluetoothPowerState()
        libPebble.init()
        startScanLoop()
        startAutoConnect()
        startClassicWatch()
        startStaleBondReaper()
        startDiscoveryInterferenceWarning()
        startNotificationActionListener()
        startChurnDetector()
        registerControlService()
        startSignalEmitters()
        startCallMonitor()
        applyWeather()
        startWatchPrefsSync()
        applyDnd()
        // User extensions (companion apps): host-side child processes that drive watch notifications
        // (and reply/actions / watchapp AppMessages) over stdio JSON-RPC. They push through the same
        // watchNotifier choke point as desktop notifications, so per-app mute/style applies to them too.
        // (Constructed above; started here, after libPebble is up.) Managed live via `stoandl ext`.
        extensionManager.start()
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
        applyHealth()
        // Battery insights: capture the hourly analytics heartbeat (primary) + a GATT-level fallback.
        applyBattery()
        // The MPRIS SystemMusicControl is installed via Koin override above and self-starts when first
        // injected (on the first watch connect); nothing to start here, just report the state.
        log.info {
            if (config.musicControl) "Music control enabled (MPRIS → watch Music app; volume: ${config.musicVolume.name.lowercase()})"
            else "Music control disabled (music.enabled=false)"
        }
        log.info {
            if (currentCalendarSources().isNotEmpty())
                "Calendar sync enabled (timeline pins; refresh every ${config.calendarSyncIntervalMinutes}m + on .ics change)"
            else "Calendar sync ready (no source configured yet — add one in the GUI or `stoandl calendar add`)"
        }

        log.info { "libpebble3 initialized" }
    }

    fun gracefulShutdown() {
        if (!::libPebble.isInitialized) return
        // Stop extension child processes first so they don't orphan when the JVM exits.
        if (::extensionManager.isInitialized) extensionManager.shutdownAll()
        log.info { "graceful BLE shutdown: disconnecting watches" }
        // Release any discovery session we still hold (e.g. an open pairing-window scan) BEFORE we go.
        // BlueZ frees a client's discovery when its D-Bus connection drops on process exit, but the
        // scanner deliberately keeps a process-lifetime connection open, so an in-flight scan that was
        // never cleanly stopped could leave Adapter1.Discovering=true held on that connection until the
        // JVM fully exits — long enough to look like an external scanner is blocking reconnection.
        // Stopping explicitly here is deterministic and closes that window.
        if (libPebble.isScanningBle.value) libPebble.stopBleScan()
        if (libPebble.isScanningClassic.value) libPebble.stopClassicScan()
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
        // Tear down the BlueZ pairing agent registered in init() (no-op if it never registered).
        pairingAgent.unregister()
    }

    /**
     * EXPERIMENTAL Bluetooth Classic (BR/EDR) transport. Classic-era Pebbles (Time / Time Steel) use
     * RFCOMM/SPP as their reliable native transport, not BLE. When `classic.discover` is enabled,
     * discover these watches via a BR/EDR inquiry and auto-pair + connect them (see
     * [startClassicDiscovery]) — libpebble3 routes a PebbleBtClassicIdentifier to the
     * BluezBtClassicConnector (a secure RFCOMM socket). The BLE path is untouched — BLE-native watches
     * keep using BLE.
     */
    private fun startClassicWatch() {
        if (!config.classicDiscover) return
        scope.launch {
            // Wait for Bluetooth to be up so the BR/EDR inquiry can reach the watch.
            combine(libPebble.bluetoothEnabled, btAdapterPowered) { bt, powered ->
                bt.enabled() && powered
            }.first { it }
            delay(2.seconds)
            log.info { "BT Classic: discovering classic Pebbles (BR/EDR inquiry)" }
            startClassicDiscovery()
        }
    }

    /** Discover + connect classic Pebbles. The connector auto-pairs (during a pairing window) if needed. */
    private fun startClassicDiscovery() {
        // BR/EDR inquiry is ONLY needed to find a NEW watch's MAC — so, like the BLE scan, run it only
        // while a pairing window is open (`stoandl pair`). A KNOWN/bonded classic watch needs no scan:
        // it's loaded from the DB and WatchManager reconnects it via the connector paging its fixed MAC
        // (no advertising, no discovery). Inquiry is power/airtime-heavy, so we never leave it running.
        scope.launch {
            while (true) {
                val btOn = libPebble.bluetoothEnabled.value.enabled() && btAdapterPowered.value
                val classicConnected = libPebble.watches.value.any {
                    it.identifier is PebbleBtClassicIdentifier && it is ConnectedPebbleDevice
                }
                if (btOn && pairingGate.isOpen() && !classicConnected) {
                    if (!libPebble.isScanningClassic.value) {
                        log.info { "BT Classic: discovering (BR/EDR inquiry — pairing window open)" }
                        libPebble.startClassicScan()
                    }
                } else if (libPebble.isScanningClassic.value) {
                    libPebble.stopClassicScan()
                }
                delay(2.seconds)
            }
        }
        // For each discovered classic Pebble: a BONDED one auto-connects; an UNBONDED one is only paired
        // when a pairing window is open (`stoandl pair`) — honoring the same flow as BLE, so we never pop
        // an unsolicited pairing prompt on the watch. Pairing is a blocking ~10s Device1.Pair (confirm the
        // code on the watch) done OUTSIDE the connect attempt so it doesn't race the connection timeout.
        val connecting = ConcurrentHashMap.newKeySet<String>()
        val pairingInFlight = ConcurrentHashMap.newKeySet<String>()
        val hinted = ConcurrentHashMap.newKeySet<String>()
        libPebble.watches.onEach { devices ->
            for (d in devices) {
                val id = d.identifier as? PebbleBtClassicIdentifier ?: continue
                if (d is ConnectedPebbleDevice || d is ConnectingPebbleDevice) continue
                val mac = id.macAddress
                if (connecting.contains(mac)) continue
                scope.launch {
                    if (withContext(Dispatchers.IO) { isBondedClassic(id) }) {
                        if (connecting.add(mac)) {
                            log.info { "BT Classic: connecting $mac" }
                            watchConnector.requestConnection(id)
                        }
                        return@launch
                    }
                    // Unbonded — wait for an explicit pairing window.
                    if (!pairingGate.isOpen()) {
                        if (hinted.add(mac)) {
                            log.info { "BT Classic: discovered unpaired Pebble $mac — run 'stoandl pair' (then confirm the code on the watch) to pair it" }
                        }
                        return@launch
                    }
                    if (!pairingInFlight.add(mac)) return@launch
                    // Report the found watch in `stoandl pair`, same as the BLE flow.
                    if (pairingState.get() == "pending:") {
                        val label = d.displayName().takeIf { it.isNotBlank() } ?: "Pebble"
                        pairingState.set("pending:Found $label — pairing...")
                    }
                    val paired = try {
                        withContext(Dispatchers.IO) { createBondClassic(id) }
                    } finally {
                        pairingInFlight.remove(mac)
                    }
                    if (paired) {
                        hinted.remove(mac)
                        if (connecting.add(mac)) {
                            log.info { "BT Classic: connecting $mac" }
                            watchConnector.requestConnection(id)
                        }
                    } else {
                        log.warn { "BT Classic: pairing $mac failed — try `btmgmt pair -t bredr $mac` by hand" }
                    }
                }
            }
        }.launchIn(scope)
    }

    private fun startScanLoop() {
        scope.launch {
            // Wait for watchBluetoothPowerState() to complete its initial GetManagedObjects() check
            // before the first scan attempt so btAdapterPowered reflects reality from the start.
            delay(1.seconds)
            // startBleScan auto-stops after 30s, so we re-issue it while we still want to scan; tick
            // fast so we stop the scan within ~2s of a GATT link coming up (a scan running during the
            // PPoG handshake starves the watch's GATT traffic → the handshake times out and the link is
            // torn down — observed as ~33s connect/drop(reason Local) flapping).
            val tick = 2.seconds
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

                // Scan only to discover an *unbonded* watch while a pairing window is open. Stop once any
                // connect is in flight (connected or connecting, even link-less): a scan collides with the
                // connect on BlueZ's single discovery slot (org.bluez.Error.InProgress), and a scan during
                // the PPoG handshake starves the watch's GATT traffic → the handshake times out and the
                // link is torn down. A bonded watch needs no scan — it reconnects via the connector's
                // standing Device1.Connect() (BlueZ's kernel auto-connect).
                val devices = libPebble.watches.value
                val connectInFlight = devices.any {
                    it is ConnectedPebbleDevice || it is ConnectingPebbleDevice
                }
                val wantScan = pairingGate.isOpen() && !connectInFlight
                if (wantScan && !libPebble.isScanningBle.value) {
                    log.info { "Starting BLE scan" }
                    libPebble.startBleScan()
                } else if (!wantScan && libPebble.isScanningBle.value) {
                    libPebble.stopBleScan()
                }
                delay(tick)
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
     * Diagnostic: warn (once) when an *external* process is holding a *sustained* Bluetooth discovery
     * while a bonded watch is trying to reconnect. A continuously-held active discovery starves the
     * controller's single scanner duty cycle, squeezing the passive accept-list windows BlueZ uses for
     * our standing `Device1.Connect()` — so a nearby watch can't reconnect even though everything on our
     * side is correct. This is invisible without a btmon snoop and once cost a long debugging session;
     * surfacing it turns a multi-hour mystery into a one-line hint. It cannot be *fixed* here (each
     * client owns its own discovery session — we can't stop another process's scan), only reported.
     *
     * `Adapter1.Discovering` is true whenever ANY client holds an *active* `StartDiscovery` session —
     * NOT for our own standing reconnect: that runs as a kernel accept-list passive scan which never
     * sets the property (BlueZ `discovering_callback()` returns early when no D-Bus discovery client is
     * registered). So `Discovering=true` with no scan of ours genuinely means a third party is scanning.
     * But its mere presence does NOT prove reconnection is blocked — a watch that just drifted out of
     * range reconnects fine once back, regardless of a coincidental external scan. We therefore only
     * alarm when the condition *persists* past [DISCOVERY_WARN_GRACE], the signature of a scan that is
     * actually monopolising the scanner rather than a brief overlap with a normal out-of-range drop.
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
                // Any of OUR OWN pairing-window scans (BLE or classic BR/EDR inquiry) sets Discovering
                // too — exclude both so we never read our own scan as external interference.
                if (libPebble.isScanningBle.value || libPebble.isScanningClassic.value) return false
                return try {
                    val objMgr = conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)
                    objMgr.GetManagedObjects().any { (path, ifaces) ->
                        hciAdapter.matches(path.toString()) &&
                            variantValue(ifaces["org.bluez.Adapter1"]?.get("Discovering")) as? Boolean == true
                    }
                } catch (_: Exception) { false }
            }

            // Warn only after the (disconnected + external discovery) condition holds for the grace
            // window — a brief external scan overlapping a normal out-of-range drop never reaches it.
            val ticksToWarn = (DISCOVERY_WARN_GRACE / DISCOVERY_WARN_INTERVAL).toInt().coerceAtLeast(1)
            var warned = false
            var blockedTicks = 0
            try {
                while (true) {
                    delay(DISCOVERY_WARN_INTERVAL)
                    val devices = libPebble.watches.value
                    if (devices.any { it is ConnectedPebbleDevice }) { warned = false; blockedTicks = 0; continue }
                    val haveBonded = devices.filterIsInstance<KnownPebbleDevice>().any { it !is ConnectedPebbleDevice }
                    val btOn = libPebble.bluetoothEnabled.value.enabled() && btAdapterPowered.value
                    if (haveBonded && btOn && externalDiscoveryActive()) {
                        blockedTicks++
                        if (!warned && blockedTicks >= ticksToWarn) {
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
                        blockedTicks = 0
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
                        // Only forget on a CONFIRMED host-side removal (device present + Paired=false),
                        // not when isBonded merely threw because the BLE object is transiently absent
                        // (rotating RPA, or a Classic-driven watch) — that's away, not unpaired.
                        if (id != null && !pairingGate.isOpen() &&
                            bluezObjectPath(id.asString)?.let { bluezBondGenuinelyRemoved(it) } == true) {
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

    /** BlueZ numeric-comparison callback (runs on the agent's dispatch thread). On a client-initiated
     *  pairing window (Pair/Repair) we surface the code as `confirm:<code>` and block until the user
     *  accepts/declines via [StoandlControl.ConfirmPairing] (or the timeout declines); otherwise
     *  (notification re-pair / external pairing) we auto-accept and just surface the code for display. */
    private fun onPairingConfirm(code: String): Boolean {
        if (!(pairingGate.isOpen() && requireConfirm.get())) {
            if (pairingGate.isOpen()) pairingState.set("pending:Confirm code $code on the watch")
            return true
        }
        pairingState.set("confirm:$code")
        return when (pairingConfirmation.awaitDecision(code, PAIRING_CONFIRM_TIMEOUT_MS)) {
            PairingConfirmation.Decision.ACCEPT -> { pairingState.set("pending:Completing pairing…"); true }
            PairingConfirmation.Decision.DECLINE -> { pairingResult.get()?.complete("error:Pairing declined"); false }
            PairingConfirmation.Decision.TIMEOUT -> { pairingResult.get()?.complete("timeout:Pairing confirmation timed out"); false }
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
                openSessionBus()
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
        appName: String = "stoandl",
        onInvoke: () -> Unit,
    ): UInt32 {
        val conn = notifConn ?: run { sendDesktopNotification(summary, body, appName); return UInt32(0) }
        return try {
            val id = conn.getRemoteObject(
                "org.freedesktop.Notifications", "/org/freedesktop/Notifications",
                FreedesktopNotifications::class.java,
            ).Notify(appName, replacesId, "phone", summary, body, listOf("repair", actionLabel), emptyMap(), 0)
            notificationActions[id] = onInvoke
            id
        } catch (e: Exception) {
            log.warn { "sendActionableNotification failed: ${e.message}" }
            sendDesktopNotification(summary, body, appName)
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

    /** (Re)start or stop weather sync against the current config — at boot and on SetSyncEnabled/SetConfig.
     *  Idempotent: stops any running instance, then starts a fresh one when `weather.enabled` is on and a
     *  source is configured. A weather.* config change re-runs this (rebuilds with the new units/pins/etc). */
    private fun applyWeather() {
        weatherSyncRef.getAndSet(null)?.stop()
        val hasSource = config.weatherLocationSource != WeatherLocationSource.MANUAL
        if (!config.weatherEnabled) {
            log.info { "Weather sync off (weather.enabled=false)" }
            return
        }
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
            parentScope = scope,
            locations = config.weatherLocations,
            units = config.weatherUnits,
            intervalMinutes = config.weatherIntervalMinutes,
            gps = gps,
            gpsFallbackName = config.weatherGpsName,
            reverseGeocodeEnabled = config.weatherReverseGeocode,
            extraLocations = extraLocations,
            weatherPins = config.weatherPins,
            onSynced = { stampSync("weather") },
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

    /** The live Linux calendar reader, ALWAYS constructed (even with no source yet) and bound as the
     *  SystemCalendar — so adding the FIRST source via the GUI/CLI takes effect without a restart. Its
     *  source list + watch dirs are read live from config on each enumeration. */
    private fun buildCalendarSync(): LinuxSystemCalendar =
        LinuxSystemCalendar(
            sources = ::currentCalendarSources,
            intervalMinutes = config.calendarSyncIntervalMinutes,
            watchDirs = ::calendarWatchDirs,
            isEnabled = { config.calendarEnabled },
            onSync = { stampSync("calendar") },
        )

    /** Assemble the calendar sources from the CURRENT config (read live, so source CRUD takes effect on
     *  the next refresh). Local .ics/discovery are egress-free; iCal URLs and CalDAV are opt-in egress.
     *  CalDAV passwords are resolved per sync from [secretStore], never from config. */
    private fun currentCalendarSources(): List<CalendarSource> {
        val cfg = configStore.current()
        val sources = mutableListOf<CalendarSource>()
        if (cfg.calendarIcsPaths.isNotEmpty()) sources += IcsPathSource(cfg.calendarIcsPaths)
        if (cfg.calendarDiscover) sources += DiscoverySource()
        if (cfg.calendarIcalUrls.isNotEmpty()) sources += IcalUrlSource(cfg.calendarIcalUrls, calendarHttpClient)
        if (cfg.calendarCalDav.isNotEmpty()) {
            sources += CalDavSource(
                cfg.calendarCalDav.map { CalDavSource.Entry(it.id, it.url, it.username) },
                calendarHttpClient, secretStore,
            )
        }
        return sources
    }

    /** Directories file-watched for near-instant .ics updates: each ics_paths dir, the parent dir of each
     *  ics_paths file, plus the discovery dirs (from the CURRENT config). Network sources use the ticker. */
    private fun calendarWatchDirs(): List<File> {
        val cfg = configStore.current()
        return buildList {
            cfg.calendarIcsPaths.map(::File).forEach { f -> add(if (f.isDirectory) f else f.parentFile) }
            if (cfg.calendarDiscover) addAll(calendarDiscoveryDirs())
        }.filterNotNull().distinct()
    }

    /** Run [action] on each fresh watch connect — the false→true edge of "any device connected".
     *  Returns the collector [Job] so callers that need to stop it at runtime (e.g. applyHealth) can. */
    private fun onFreshConnect(action: suspend () -> Unit): Job =
        libPebble.watches
            .map { devices -> devices.any { it is ConnectedPebbleDevice } }
            .distinctUntilChanged()
            .onEach { connected -> if (connected) action() }
            .launchIn(scope)

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
        onFreshConnect { control.applyConfigured(config.watchPrefs) }
    }

    /** Mirror desktop Do Not Disturb ↔ watch Quiet Time (manual). Off unless `dnd.sync` is set; the
     *  GNOME/KDE host backend is auto-detected. The actual sync (and its loop avoidance) lives in
     *  [DndSync]; setWatchPref persists, so a host→watch change applied while disconnected still syncs
     *  on the next connect. */
    /** (Re)start or stop DND ↔ Quiet Time mirroring against the current `dnd.sync` mode. Idempotent:
     *  stops any running instance (cancelling its collector + closing the host backend), then starts a
     *  fresh one for the new mode. Called at boot and on SetSyncEnabled("dnd")/SetConfig("dnd.sync"). */
    private fun applyDnd() {
        dndSync?.stop()
        dndSync = null
        if (config.dndSync == StoandlConfig.DndSyncMode.OFF) {
            log.info { "DND ↔ Quiet Time sync disabled (dnd.sync=off)" }
            return
        }
        if (config.watchPrefs.containsKey(BoolWatchPref.QuietTimeManuallyEnabled.id)) {
            log.warn {
                "dnd.sync is on but watch.${BoolWatchPref.QuietTimeManuallyEnabled.id} is also configured — " +
                    "the configured value is re-applied on every connect and will fight DND sync; remove one"
            }
        }
        val backend = detectHostDnd(scope)
        if (backend == null) {
            log.warn {
                "dnd.sync=${config.dndSync.name.lowercase()} but no host DND backend detected " +
                    "(need GNOME's notification GSettings or a KDE/Plasma notification server) — disabled"
            }
            return
        }
        log.info { "DND ↔ Quiet Time sync on (mode=${config.dndSync.name.lowercase()}, host=${backend.name})" }
        dndSync = DndSync(libPebble, scope, config.dndSync, backend).also { it.start() }
    }

    /** Proactively check for newer firmware on each watch connect (throttled to once a day) and, when
     *  found, push a watch notification with an "Update" button. The source is picked per watch (GitHub
     *  for Core devices, cohorts.rebble.io for classic), so this runs when *either* firmware.github or
     *  firmware.cohorts is on, plus firmware.notify (opt-in egress). The local `firmware <file.pbz>`
     *  sideload and the `firmware check`/`update` CLI commands work regardless of this. */
    private fun startFirmwareNotifier() {
        val dailyMs = 1.days.inWholeMilliseconds
        // Always armed; whether it actually checks is gated live on (a source enabled) && firmware.notify,
        // so toggling firmware.notify takes effect without a restart. maybeNotify() self-throttles to once/day.
        fun shouldNotify() = (config.firmwareGithub || config.firmwareCohorts) && config.firmwareNotify
        log.info {
            if (shouldNotify()) "Firmware update notifications on (check on connect, at most once/day)"
            else "Firmware update notifications off (needs firmware.github or firmware.cohorts, plus firmware.notify=true)"
        }
        // Check on each fresh connect…
        onFreshConnect { if (shouldNotify()) scope.launch { firmwareControl.maybeNotify(dailyMs) } }
        // …and re-check daily while a watch stays connected.
        scope.launch {
            while (true) {
                delay(dailyMs)
                if (shouldNotify() && libPebble.watches.value.any { it is ConnectedPebbleDevice }) {
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
        onFreshConnect { scope.launch { developerControl.start() } }
    }

    /**
     * Health/activity sync + export. The watch only sends health data when asked (nothing in
     * libpebble3 requests it automatically), so when `health.sync` is on we fire
     * [LibPebble.requestHealthData] on each fresh connect — incremental, since it asks for data newer
     * than the latest row already stored (the first run, with an empty DB, is therefore a full pull).
     * The data is ingested into the shared DB by libpebble3's HealthDataProcessor; when `health.export`
     * is on, [HealthExporter] projects it to NDJSON whenever new data lands.
     */
    /** (Re)start or stop health export + the on-connect data request against the current config.
     *  Idempotent: stops the running exporter and cancels the on-connect collector first, then rebuilds
     *  per `health.export` / `health.sync`. Called at boot and on SetSyncEnabled("health")/SetConfig. */
    private fun applyHealth() {
        healthExporterRef.getAndSet(null)?.stop()
        if (config.healthExport) {
            HealthExporter(
                libPebble = libPebble,
                parentScope = scope,
                exportDays = config.healthExportDays,
                exportSamples = config.healthExportSamples,
            ).also { healthExporterRef.set(it); it.start() }
        } else {
            log.info { "Health export disabled (health.export=false)" }
        }
        healthRequestJob?.cancel()
        healthRequestJob = null
        if (!config.healthSync) {
            log.info { "Health sync on connect disabled (health.sync=false)" }
            return
        }
        log.info { "Health sync on (request steps/sleep/HR/workouts from the watch on each connect)" }
        healthRequestJob = onFreshConnect { libPebble.requestHealthData(fullSync = false) }
    }

    /** (Re)start or stop battery-insights capture against the current config. Idempotent: rebuilds the
     *  GATT-level fallback collector and (re)installs the heartbeat store. Called at boot and on
     *  SetConfig("battery.*"). The heartbeat store is fed live from [StoandlWebServices] via
     *  [heartbeatStoreRef] — nulling the ref stops decoding without touching the WebServices binding. */
    private fun applyBattery() {
        batteryStoreRef.getAndSet(null)?.stop()
        if (config.batteryHistory) {
            BatteryStore(libPebble, scope, config.batteryRetentionDays)
                .also { batteryStoreRef.set(it); it.start() }
        } else {
            log.info { "Battery level history disabled (battery.history=false)" }
        }
        if (config.batteryHeartbeat) {
            // Rebuild so a changed retention window takes effect; the store is passive (no scope).
            HeartbeatStore(config.batteryRetentionDays).also { heartbeatStoreRef.set(it); it.start() }
        } else {
            heartbeatStoreRef.set(null)
            log.info { "Battery analytics-heartbeat capture disabled (battery.heartbeat=false)" }
        }
    }

    /** Re-publish music now-playing after a music.* change (the bridge reads music.enabled/volume live;
     *  this nudges it so a just-flipped enable/disable takes effect at once rather than at the next track). */
    private fun applyMusic() {
        mprisMusicControl?.onConfigChanged()
        log.info {
            if (config.musicControl) "Music control on (MPRIS → watch; volume: ${config.musicVolume.name.lowercase()})"
            else "Music control off (music.enabled=false)"
        }
    }

    /** Re-evaluate calendar sync after a calendar.* change: the reader gates on calendar.enabled live, so
     *  requesting a refresh makes PhoneCalendarSyncer drop (disabled) or repopulate (enabled) the pins. */
    private fun applyCalendar() {
        val cal = calendarSyncRef.get()
        if (cal == null) { log.info { "Calendar sync unavailable (no calendar source configured)" }; return }
        cal.requestRefresh()
        log.info { if (config.calendarEnabled) "Calendar sync on (refresh requested)" else "Calendar sync off (pins being removed)" }
    }

    /** Re-apply a sync service against the (just-reloaded) config — the live half of SetSyncEnabled/SetConfig. */
    private fun reconcile(service: String) {
        when (service) {
            "weather" -> applyWeather()
            "calendar" -> applyCalendar()
            "music" -> applyMusic()
            "health" -> applyHealth()
            "battery" -> applyBattery()
            "dnd" -> applyDnd()
            "notifications" -> log.info { "Notification forwarding ${if (config.notificationForward) "on" else "paused"}" }
        }
    }

    /** Map a GUI config key to the sync subsystem that must be re-applied for it to take effect live, or
     *  null when the value is read live by its consumer with nothing to restart (notification.per_app →
     *  the push gate; geolocation.enabled → LiveGeolocation; firmware.notify → the notifier loop). */
    private fun serviceForConfigKey(key: String): String? = when {
        key.startsWith("weather.") -> "weather"
        key.startsWith("music.") -> "music"
        key.startsWith("health.") -> "health"
        key.startsWith("battery.") -> "battery"
        key == "dnd.sync" -> "dnd"
        key.startsWith("calendar.") -> "calendar"
        key == "notification.forward" -> "notifications"
        else -> null
    }

    private fun buildSyncStatus(): List<String> {
        val cfg = configStore.current()
        fun row(service: String, available: Boolean, enabled: Boolean, lastSync: String) =
            "$service\t${if (enabled) "enabled" else "disabled"}\t${if (available) "available" else "unavailable"}\t$lastSync"
        // Discrete syncers report a real relative age ("just now"/"5 min ago"/"never"); continuous ones
        // (notifications/music/dnd) report live/mode since they don't sync on a schedule.
        fun synced(service: String): String = relAge(lastSyncAt[service] ?: 0L)
        val weatherAvail = cfg.weatherLocations.isNotEmpty() || cfg.weatherGps ||
            cfg.weatherLocationSource != WeatherLocationSource.MANUAL
        val weatherOn = cfg.weatherEnabled && weatherSyncRef.get() != null
        // "Available" = at least one source is configured (mirrors weatherAvail). The reader is always
        // bound now, so we can't infer availability from its presence.
        val calAvail = cfg.calendarIcsPaths.isNotEmpty() || cfg.calendarDiscover ||
            cfg.calendarIcalUrls.isNotEmpty() || cfg.calendarCalDav.isNotEmpty()
        val calOn = cfg.calendarEnabled && calAvail
        // dnd reports its real running state (like weather/calendar): a non-off mode that failed to start
        // (no GNOME/KDE host DND backend — e.g. a headless host) is reported unavailable/"no backend", not
        // live, so the GUI doesn't show a toggle that isn't actually doing anything.
        val dndConfigured = cfg.dndSync != StoandlConfig.DndSyncMode.OFF
        val dndRunning = dndSync != null
        return listOf(
            row("notifications", available = true, enabled = cfg.notificationForward, lastSync = if (cfg.notificationForward) "live" else "off"),
            row("weather", available = weatherAvail, enabled = weatherOn, lastSync = if (!weatherAvail) "no source" else if (weatherOn) synced("weather") else "off"),
            row("calendar", available = calAvail, enabled = calOn, lastSync = if (!calAvail) "no source" else if (calOn) synced("calendar") else "off"),
            row("music", available = true, enabled = cfg.musicControl, lastSync = if (cfg.musicControl) "live" else "off"),
            row("health", available = true, enabled = cfg.healthSync, lastSync = if (cfg.healthSync) synced("health") else "off"),
            row("dnd", available = dndRunning || !dndConfigured, enabled = dndConfigured && dndRunning,
                lastSync = if (dndRunning) cfg.dndSync.name.lowercase() else if (dndConfigured) "no backend" else "off"),
        )
    }

    /** Persist + live-apply a Sync-screen toggle. The dnd boolean maps to its 4-way mode (true→both,
     *  false→off; the precise direction stays editable in Settings). Returns the GUI status string. */
    private fun setSyncEnabledLive(service: String, enabled: Boolean): String {
        val (key, token) = when (service) {
            "notifications" -> "notification.forward" to enabled.toString()
            "weather" -> "weather.enabled" to enabled.toString()
            "calendar" -> "calendar.enabled" to enabled.toString()
            "music" -> "music.enabled" to enabled.toString()
            "health" -> "health.sync" to enabled.toString()
            "dnd" -> "dnd.sync" to if (enabled) "both" else "off"
            else -> return "notfound:no sync service '$service'"
        }
        return try {
            ConfFile.upsert(StoandlConfig.configFile(), mapOf(key to token))
            configStore.reload()
            reconcile(service)
            "ok:$service ${if (enabled) "enabled" else "disabled"}"
        } catch (e: Exception) {
            log.warn(e) { "SetSyncEnabled($service=$enabled) failed" }
            "error:${e.message ?: "failed to update $service"}"
        }
    }

    /** Persist + live-apply a single GUI config key: validate+write (applyGuiConfig), reload the store,
     *  then re-reconcile the affected subsystem so the change takes effect without a restart. */
    private fun setConfigLive(key: String, value: String): String {
        val result = applyGuiConfig(key, value, StoandlConfig.configFile())
        if (!result.startsWith("ok:")) return result
        configStore.reload()
        serviceForConfigKey(key)?.let { reconcile(it) }
        return result
    }

    /** The live actuation behind the GUI Sync/Settings screens, delegated to by [StoandlControlImpl]. */
    private val syncControl = object : SyncControl {
        override fun status(): List<String> = buildSyncStatus()
        override fun setEnabled(service: String, enabled: Boolean): String = setSyncEnabledLive(service, enabled)
        override fun getConfig(): List<String> =
            GUI_CONFIG_FIELDS.map { "${it.key}\t${it.value(configStore.current())}" }
        override fun setConfig(key: String, value: String): String = setConfigLive(key, value)
        override fun listCalendarSources(): List<String> = CalendarSources.list(configStore.current())
        override fun addCalendarSource(type: String, url: String, username: String, password: String): String =
            crudCalendarSource { CalendarSources.add(type, url, username, password, configStore, StoandlConfig.configFile(), secretStore) }
        override fun updateCalendarSource(id: String, url: String, username: String, password: String): String =
            crudCalendarSource { CalendarSources.update(id, url, username, password, configStore, StoandlConfig.configFile(), secretStore) }
        override fun removeCalendarSource(id: String): String =
            crudCalendarSource { CalendarSources.remove(id, configStore, StoandlConfig.configFile(), secretStore) }
    }

    /** Run a [CalendarSources] CRUD op (it persists + reloads the store under the conf lock) then live-
     *  re-reconcile so the change reaches the watch with no restart: a successful write triggers a
     *  refresh (new account's calendars appear / removed account's pins drop within ~5 s). */
    private fun crudCalendarSource(op: () -> String): String {
        val result = try {
            op()
        } catch (e: Exception) {
            log.warn(e) { "Calendar source CRUD failed" }
            "error:${e.message ?: "failed to update calendar sources"}"
        }
        if (result.startsWith("ok:")) applyCalendar()
        return result
    }

    private fun registerControlService() {
        try {
            serviceConn.exportObject(STOANDL_OBJECT_PATH, StoandlControlImpl(libPebbleRef, weatherSyncRef, watchPrefsControlRef, calendarSyncRef, firmwareControl, languageControl, screenshotControl, logsControl, debugControl, developerControl, notificationAppsControl, healthExporterRef, batteryStoreRef, heartbeatStoreRef, extensionManager, scope, pairingGate, pairingState, pairingConfirmation, requireConfirm, pairingResult, bondCache, syncControl, notificationFilters) { libPebble.bluetoothEnabled.value.enabled() && btAdapterPowered.value })
            log.info { "D-Bus control service registered at $STOANDL_OBJECT_PATH" }
        } catch (e: Exception) {
            log.warn(e) { "Failed to register D-Bus control service" }
        }
    }

    /**
     * Emit the reactive D-Bus signals (WatchesChanged / FirmwareProgress / LockerChanged) as a layer ON
     * TOP of the poll methods — clients still re-fetch via the matching method (the source of truth) and
     * keep a slow fallback poll, since the daemon isn't D-Bus-activated and a late client can miss a
     * signal. Collectors run on the daemon [scope]; started after [registerControlService] so the object
     * is exported. Emits are best-effort. The watch/locker pokes `drop(1)` the initial state (the client
     * fetches once on connect, so the first emission would be a redundant poke); FirmwareProgress doesn't
     * drop — a fresh subscriber wants the current phase (and clients ignore `idle`/`notready`).
     */
    private fun startSignalEmitters() {
        fun emit(signal: DBusSignal) {
            runCatching { serviceConn.sendMessage(signal) }
                .onFailure { log.debug { "D-Bus signal emit failed: ${it.message}" } }
        }

        // WatchesChanged: poke when the watch list's name/state/battery/transport signature changes
        // (mirrors the fields ListWatches returns, so any GUI-visible change re-fetches).
        libPebble.watches
            .map { devices ->
                devices.joinToString("\n") { d ->
                    val state = when (d) {
                        is ConnectedPebbleDevice -> "connected"
                        is ConnectingPebbleDevice -> "connecting"
                        else -> "disconnected"
                    }
                    val battery = (d as? ConnectedPebbleDevice)?.batteryLevel?.toString() ?: ""
                    val transport = (d as? ConnectedPebbleDevice)?.let { if (it.usingBtClassic) "classic" else "ble" } ?: ""
                    "${d.displayName()}\t$state\t$battery\t$transport"
                }
            }
            .distinctUntilChanged()
            .drop(1)
            .onEach { emit(StoandlControl.WatchesChanged(STOANDL_OBJECT_PATH)) }
            .launchIn(scope)

        // FirmwareProgress / LanguageProgress: phase + percent (-1 unless the in-progress phase) + detail,
        // parsed from the single-sourced statusFlow string (already distinctUntilChanged, follows % ticks).
        fun parse(s: String, progressPhase: String): Triple<String, Int, String> {
            val idx = s.indexOf(':')
            val phase = if (idx >= 0) s.substring(0, idx) else s
            val rest = if (idx >= 0) s.substring(idx + 1) else ""
            val percent = if (phase == progressPhase) (rest.toIntOrNull() ?: -1) else -1
            val detail = if (phase == progressPhase) "" else rest
            return Triple(phase, percent, detail)
        }
        firmwareControl.statusFlow()
            .onEach { val (p, pct, d) = parse(it, "inprogress"); emit(StoandlControl.FirmwareProgress(STOANDL_OBJECT_PATH, p, pct, d)) }
            .launchIn(scope)
        languageControl.statusFlow()
            .onEach { val (p, pct, d) = parse(it, "installing"); emit(StoandlControl.LanguageProgress(STOANDL_OBJECT_PATH, p, pct, d)) }
            .launchIn(scope)

        // LockerChanged: poke when apps/faces are added/removed or the active watchface changes.
        combine(libPebble.getAllLockerUuids(), libPebble.activeWatchface) { uuids, active ->
            "${uuids.sortedBy { it.toString() }.joinToString(",")}|${active?.properties?.id}"
        }
            .distinctUntilChanged()
            .drop(1)
            .onEach { emit(StoandlControl.LockerChanged(STOANDL_OBJECT_PATH)) }
            .launchIn(scope)

        // CalendarsChanged: poke when a sync adds/drops calendars (after a source CRUD, or a periodic
        // re-read) so the GUI re-fetches exactly when the data is ready — not racing the async sync that
        // follows a CRUD call. The reader emits only on a real set change, so no drop(1) is needed.
        calendarSyncRef.get()?.calendarsChanged
            ?.onEach { emit(StoandlControl.CalendarsChanged(STOANDL_OBJECT_PATH)) }
            ?.launchIn(scope)

        // health lastSync: stamp when fresh health data lands (drives GetSyncStatus's health age).
        libPebble.healthDataUpdated
            .onEach { stampSync("health") }
            .launchIn(scope)

        // ExtensionsChanged: the manager pokes us on enable/disable/restart/install/uninstall (incl. CLI).
        extensionManager.onChanged = { emit(StoandlControl.ExtensionsChanged(STOANDL_OBJECT_PATH)) }
        // ExtensionStateChanged: per-extension runtime transitions (ready/exited/quarantined) — the
        // unsolicited crash/quarantine the poke can't catch.
        extensionManager.onExtensionState = { name, state ->
            emit(StoandlControl.ExtensionStateChanged(STOANDL_OBJECT_PATH, name, state))
        }
    }

    private fun startAutoConnect() {
        libPebble.watches.onEach { devices ->
            for (device in devices.filterIsInstance<BleDiscoveredPebbleDevice>()) {
                // Dual-mode pick: if this same watch is reachable over BT Classic (a classic Pebble with
                // the matching name suffix is present, e.g. "Pebble B349" on both), let Classic claim it
                // and skip the BLE bridge. Covers watches whose BLE advert name lacks the "LE" token the
                // scan-record filter keys on.
                val bleSuffix = device.name.substringAfterLast(' ').takeIf { it.isNotBlank() }
                if (bleSuffix != null && devices.any {
                        it.identifier is PebbleBtClassicIdentifier &&
                            it.name.substringAfterLast(' ').equals(bleSuffix, ignoreCase = true)
                    }
                ) continue
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
    private val dialerApps: List<String>,
    private val dialerNameCache: DialerNameCache,
    // The shared choke point: per-app tracking/mute/style + build + send + route now live here, used by
    // extensions too. This bridge just turns a desktop notification into a [NotifRequest].
    private val watchNotifier: WatchNotifier,
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
                // subtitle defaults to appName inside WatchNotifier; per-app mute/style + the "Mute"
                // action are applied there. The D-Bus id rides in the route as the owner token, so a
                // wrist dismiss closes the original desktop notification (even after a restart).
                watchNotifier.push(
                    NotifRequest(
                        appName = notification.appName,
                        title = notification.summary,
                        body = notification.body,
                    ),
                    ownerId = "desktop",
                    ownerToken = notification.id.toString(),
                )
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

internal fun perAppColor(name: String?): TimelineColor? = name?.let { TimelineColor.findByName(it) }

internal fun perAppIcon(code: String?, appName: String): TimelineIcon {
    if (code != null) {
        TimelineIcon.entries.firstOrNull { it.name.equals(code, ignoreCase = true) }?.let { return it }
        TimelineIcon.fromCode(code)?.let { return it }
    }
    return iconForApp(appName)
}

/** A named preset (`short`/`double`/…) or a raw CSV of on/off millisecond durations (`100,50,100`). */
internal fun perAppVibe(spec: String?): List<UInt>? {
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

/** Format an epoch-seconds timestamp as a short relative age ("just now"/"5 min ago"/"2h ago"/
 *  "yesterday"/"3d ago"); "never" for a non-positive (unset) timestamp. Used for the GUI's lastSync /
 *  last-connected strings. */
internal fun relAge(epochSec: Long): String {
    if (epochSec <= 0L) return "never"
    val d = System.currentTimeMillis() / 1000 - epochSec
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86_400 -> "${d / 3600}h ago"
        d < 172_800 -> "yesterday"
        else -> "${d / 86_400}d ago"
    }
}

internal fun isMutedNow(item: NotificationAppItem, now: kotlin.time.Instant): Boolean {
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

// No Rebble account / remote locker / cloud analytics in standalone mode — every remote method is a
// no-op EXCEPT uploadAnalyticsHeartbeat: the watch's hourly analytics heartbeat (the same blob the
// official app forwards to its cloud) reaches us here, and instead of dropping it we hand it to the
// local [HeartbeatStore] (when battery.heartbeat is on) to decode the battery fields — no egress.
private class StoandlWebServices(
    private val heartbeatStoreRef: AtomicReference<HeartbeatStore?>,
) : WebServices {
    override suspend fun fetchLocker() = null
    // "removed from remote" is trivially true, so Locker.removeApp() proceeds to delete the local entry.
    override suspend fun removeFromLocker(id: Uuid) = true
    override suspend fun checkForFirmwareUpdate(watch: WatchInfo) =
        io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult.FoundNoUpdate
    override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {}
    override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) {
        heartbeatStoreRef.get()?.record(payload, watchInfo.serial, watchInfo.runningFwVersion.stringVersion)
    }
}

private object NoOpTokenProvider : TokenProvider {
    override suspend fun getDevToken(): String? = null
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

private fun variantValue(v: Any?): Any? = unwrapVariant(v)

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

/**
 * True ONLY when the BlueZ device object EXISTS and reports Paired=false — a genuine host-side bond
 * removal (e.g. `bluetoothctl remove`). The fork's [isBonded] returns false even when the device object
 * is merely ABSENT (a transient/away BLE device whose rotating-RPA object BlueZ dropped, or a watch we
 * drive over BT Classic so its "Pebble Time LE" BLE object comes and goes) — that is NOT a removed bond
 * and must not trigger the host-bond-lost forget/notify (the spurious "Pebble pairing removed" alert).
 * Here an absent/unreachable object → false (away, not removed).
 */
private fun bluezBondGenuinelyRemoved(devicePath: String): Boolean {
    val conn = try {
        DBusConnectionBuilder.forSystemBus().withShared(false).build()
    } catch (e: Exception) {
        return false
    }
    return try {
        val props = conn.getRemoteObject(ORG_BLUEZ, devicePath, Properties::class.java)
        val paired = props.Get<Any>(BLUEZ_DEVICE1, "Paired")
        val isPaired = when (paired) {
            is Boolean -> paired
            is Variant<*> -> paired.value as? Boolean ?: true
            else -> true
        }
        !isPaired  // object present AND not paired = a real host-side removal
    } catch (e: Exception) {
        false  // object absent / unreachable → away, not a removed bond
    } finally {
        try { conn.disconnect() } catch (_: Exception) {}
    }
}

/** BlueZ object path of a BR/EDR device by MAC (any adapter), or null if BlueZ doesn't know it. */
private fun classicDevicePath(mac: String): String? {
    val suffix = "dev_" + mac.trim().uppercase().replace(":", "_")
    val conn = try {
        DBusConnectionBuilder.forSystemBus().withShared(false).build()
    } catch (e: Exception) {
        return null
    }
    return try {
        conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)
            .GetManagedObjects().keys
            .map { it.toString() }
            .firstOrNull { it.endsWith("/$suffix") }
    } catch (e: Exception) {
        null
    } finally {
        try { conn.disconnect() } catch (_: Exception) {}
    }
}

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

/**
 * The live actuation surface behind the GUI's Sync + Settings screens, implemented by
 * [PebbleIntegration] (which owns the service refs + the [ConfigStore]) and delegated to by
 * [StoandlControlImpl]. Every method persists to `stoandl.conf`, reloads the store, and re-reconciles
 * the affected subsystem, so changes take effect without a daemon restart.
 */
internal interface SyncControl {
    /** GetSyncStatus rows: `service\tenabled|disabled\tavailable|unavailable\tlastSync`. */
    fun status(): List<String>
    /** Persist + live-apply a Sync-screen toggle for one of the six services. */
    fun setEnabled(service: String, enabled: Boolean): String
    /** GetConfig rows: `key\tvalue` over the current (live) config. */
    fun getConfig(): List<String>
    /** Persist + live-apply a single GUI config key. */
    fun setConfig(key: String, value: String): String
    /** Editable calendar sources as `id\ttype\turl\tusername\tlabel` records (never the password). */
    fun listCalendarSources(): List<String>
    /** Add a calendar source ([type] ∈ caldav/ical/ics); persists + re-syncs. `ok:<id>\t<backend>`. */
    fun addCalendarSource(type: String, url: String, username: String, password: String): String
    /** Edit a calendar source by id (blank password keeps the stored one); persists + re-syncs. */
    fun updateCalendarSource(id: String, url: String, username: String, password: String): String
    /** Remove a calendar source by id (and its stored password); persists + re-syncs. */
    fun removeCalendarSource(id: String): String
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
    // Battery insights sources: heartbeat (primary) is preferred; the GATT-level series is the fallback.
    // Both null when their config switch is off.
    private val batteryStoreRef: AtomicReference<BatteryStore?>,
    private val heartbeatStoreRef: AtomicReference<HeartbeatStore?>,
    private val extensionManager: ExtensionManager,
    private val scope: CoroutineScope,
    private val pairingGate: PairingGate,
    private val pairingState: AtomicReference<String>,
    private val pairingConfirmation: PairingConfirmation,
    // Shared with the outer module's agent callback (onPairingConfirm): the monitor sets these around a
    // client-initiated window; the agent reads them to decide whether to block for ConfirmPairing.
    private val requireConfirm: AtomicBoolean,
    private val pairingResult: AtomicReference<CompletableDeferred<String>?>,
    private val bondCache: ConcurrentHashMap<String, Boolean>,
    // The live Sync/Settings actuation (GetSyncStatus/SetSyncEnabled/GetConfig/SetConfig delegate here),
    // owned by PebbleIntegration which holds the service refs + the live config store.
    private val syncControl: SyncControl,
    // Global allow/block notification filters (NotifListFilters/AddFilter/RemoveFilter).
    private val notificationFilters: NotificationFilters,
    // True only when Bluetooth is actually usable (libpebble3 state AND adapter Powered/GattManager1).
    private val btOn: () -> Boolean,
) : StoandlControl {
    private val log = KotlinLogging.logger {}

    // Local menu-icon extraction from cached .pbw files (no LibPebble dependency — pure disk).
    private val appIconExtractor = AppIconExtractor()

    override fun isRemote() = false
    override fun getObjectPath() = STOANDL_OBJECT_PATH

    override fun Version(): String = de.yoxcu.stoandl.BuildInfo.version

    override fun BluetoothStatus(): String = if (btOn()) "ok:on" else "ok:off"

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
        val cal = calendarSyncRef.get()
        return try {
            runBlocking { lp.calendars().first() }.map {
                // The 4th column is the owning source id (accountId) so the GUI can nest calendars under
                // their CalDAV account / iCal feed / .ics source; empty for an unknown/legacy calendar.
                "${it.id}\t${it.name}\t${if (it.enabled) "enabled" else "disabled"}\t${cal?.accountIdFor(it.platformId) ?: ""}"
            }
        } catch (e: Exception) {
            log.warn(e) { "ListCalendars failed" }
            emptyList()
        }
    }

    override fun ListCalendarSources(): List<String> = syncControl.listCalendarSources()

    override fun AddCalendarSource(type: String, url: String, username: String, password: String): String =
        syncControl.addCalendarSource(type, url, username, password)

    override fun UpdateCalendarSource(id: String, url: String, username: String, password: String): String =
        syncControl.updateCalendarSource(id, url, username, password)

    override fun RemoveCalendarSource(id: String): String = syncControl.removeCalendarSource(id)

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

    override fun BatteryHistory(watch: String, sinceEpoch: Long): String {
        val hb = heartbeatStoreRef.get()
        val gatt = batteryStoreRef.get()
        if (hb == null && gatt == null) return "notready:battery history disabled"
        val (name, serial) = resolveWatch(watch)
        // Heartbeat is the source of truth when it has decoded data for this watch; else GATT fallback.
        val points = if (serial != null && hb?.hasData(serial) == true) hb.history(serial, sinceEpoch)
        else gatt?.history(name.ifBlank { watch }, sinceEpoch) ?: emptyList()
        val body = points.joinToString("\n") {
            "${it.ts}\t${fmtNum(it.level)}\t${it.source}\t${it.voltage?.let { v -> String.format(Locale.ROOT, "%.3f", v) } ?: ""}"
        }
        return "ok:$body"
    }

    override fun BatteryInsights(watch: String): String {
        val hb = heartbeatStoreRef.get()
        val gatt = batteryStoreRef.get()
        if (hb == null && gatt == null) return "notready:battery insights disabled"
        val (name, serial) = resolveWatch(watch)
        val label = name.ifBlank { watch.ifBlank { "watch" } }
        val ins = (if (serial != null) hb?.insights(serial) else null)
            ?: gatt?.insights(name.ifBlank { watch })
            ?: return "unknown:$label"
        val volt = ins.voltage?.let { String.format(Locale.ROOT, "%.3f", it) } ?: ""
        return "ok:$label\t${fmtNum(ins.level)}\t${if (ins.charging) 1 else 0}\t${String.format(Locale.ROOT, "%.2f", ins.dischargePerHour)}\t" +
            "${fmtHours(ins.hoursRemaining)}\t${ins.chargeSessions7d}\t${ins.lastChargedEpoch}\t" +
            "${fmtNum(ins.min24h)}\t${fmtNum(ins.max24h)}\t${ins.sampleCount}\t$volt\t${ins.source}"
    }

    override fun BatteryActivity(watch: String, sinceEpoch: Long): String {
        val hb = heartbeatStoreRef.get()
        val gatt = batteryStoreRef.get()
        if (hb == null && gatt == null) return "notready:battery capture disabled"
        val (name, serial) = resolveWatch(watch)
        // Heartbeat is the source of truth when it has decoded data (firmware drop + notif counters);
        // else derive per-sample drops from the GATT level series (no notification counters there).
        val rows: List<BatteryActivityRow> = if (serial != null && hb?.hasData(serial) == true) {
            val acts = hb.activity(serial, sinceEpoch)
            acts.mapIndexed { i, a ->
                val drop = a.socDropPct ?: (if (i > 0) (acts[i - 1].socPct - a.socPct).coerceAtLeast(0.0) else 0.0)
                BatteryActivityRow(a.ts, drop, a.notifCount, a.notifDndCount)
            }
        } else {
            val pts = gatt?.history(name.ifBlank { watch }, sinceEpoch).orEmpty()
            pts.mapIndexed { i, p ->
                val drop = if (i > 0) (pts[i - 1].level - p.level).coerceAtLeast(0.0) else 0.0
                BatteryActivityRow(p.ts, drop, 0, 0)
            }
        }
        val body = rows.joinToString("\n") { "${it.ts}\t${String.format(Locale.ROOT, "%.2f", it.drop)}\t${it.notif}\t${it.notifDnd}" }
        return "ok:$body"
    }

    override fun BatteryPower(watch: String, sinceEpoch: Long): String {
        val hb = heartbeatStoreRef.get() ?: return "notready:battery capture disabled"
        val (_, serial) = resolveWatch(watch)
        // Power attribution comes only from the analytics heartbeat; a GATT-only watch has no breakdown.
        if (serial == null || !hb.hasData(serial)) return "ok:"
        val body = hb.power(serial, sinceEpoch).joinToString("\n") {
            "${it.category}\t${it.activityMs}\t${String.format(Locale.ROOT, "%.1f", it.sharePct)}"
        }
        return "ok:$body"
    }

    private data class BatteryActivityRow(val ts: Long, val drop: Double, val notif: Long, val notifDnd: Long)

    /** Resolve a watch query to `(displayName, serial-or-null)`. Blank query → the single connected
     *  watch. A non-blank query matches a connected watch by exact-then-substring display name; if it
     *  matches none (e.g. querying a disconnected watch's history), the serial is null and the raw query
     *  is used as the GATT-series key. */
    private fun resolveWatch(query: String): Pair<String, String?> {
        val devices = libPebbleRef.get()?.watches?.value?.filterIsInstance<ConnectedPebbleDevice>().orEmpty()
        val dev = if (query.isBlank()) devices.firstOrNull()
        else devices.firstOrNull { it.displayName().equals(query, ignoreCase = true) }
            ?: devices.firstOrNull { it.displayName().contains(query, ignoreCase = true) }
        return if (dev != null) dev.displayName() to dev.watchInfo.serial else query to null
    }

    /** Integer if whole, else two decimals (heartbeat soc carries fractional centi-percent). Locale.ROOT
     *  so the decimal point is always `.` — these strings are parsed as doubles by the CLI/GUI. */
    private fun fmtNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(Locale.ROOT, "%.2f", d)

    /** Hours-remaining for the wire: empty when unknown/charging (-1), else one decimal. */
    private fun fmtHours(h: Double): String = if (h < 0) "" else String.format(Locale.ROOT, "%.1f", h)

    override fun SyncHealth(): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = lp.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()
            ?: return "notready:No watch connected"
        // Full sync: ask the watch to replay ALL stored history, not just data newer than our latest
        // timestamp. Incremental (the on-connect path) keys off healthDao.getLatestTimestamp(), so once
        // today's step/HR minute samples land, the watermark sits at "now" and incremental never reaches
        // back to last night's sleep overlays we missed (e.g. recorded while paired to another phone).
        // An explicit "sync" is the user asking to backfill, so request everything; the processor upserts.
        // Fire-and-forget: the watch streams data back asynchronously (ingested into the DB by
        // libpebble3), which fires healthDataUpdated → the exporter re-projects. Re-project once now too
        // so any data already in the DB is written even if this sync turns up nothing new.
        log.info { "Full health sync requested from ${dev.displayName()} (replay all watch-retained history)" }
        lp.requestHealthData(fullSync = true)
        healthExporterRef.get()?.let { exporter -> scope.launch { exporter.exportNow() } }
        return "ok:Requested full health sync from ${dev.displayName()}"
    }

    override fun HeartRate(): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        // Read-only DB lookup (the latest non-zero HR sample), so it works whether or not a watch is
        // currently connected — the samples persist after the connect-time health.sync ingests them.
        val hr = runBlocking { lp.getLatestHeartRateReading() } ?: return "none:"
        return "ok:${hr.bpm}\t${hr.timestampEpochSec}"
    }

    /** The calendar days + epoch bounds + bar labels for a (periodType, offset) selection.
     *  `day`: one day (today − offset); `week`: 7 days ending today − offset·7; `month`: the calendar
     *  month offset months back (current month = 1st → today, a past month = the whole month). */
    private data class HealthWindow(
        val start: Long, val end: Long, val days: List<LocalDate>, val labels: List<String>,
    )

    private fun healthWindow(periodType: String, offset: Int, today: LocalDate, zone: ZoneId): HealthWindow {
        val off = offset.coerceAtLeast(0).toLong()
        fun dayStart(d: LocalDate) = d.atStartOfDay(zone).toEpochSecond()
        fun dow(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth}"
        return when (periodType) {
            "week" -> {
                val end = today.minusDays(off * 7)
                val start = end.minusDays(6)
                val days = (0L..6L).map { start.plusDays(it) }
                HealthWindow(dayStart(start), dayStart(end.plusDays(1)), days, days.map { dow(it) })
            }
            "month" -> {
                val anchor = today.minusMonths(off)
                val start = anchor.withDayOfMonth(1)
                val last = if (off == 0L) today else anchor.withDayOfMonth(anchor.lengthOfMonth())
                val n = (last.toEpochDay() - start.toEpochDay()).toInt()
                val days = (0..n).map { start.plusDays(it.toLong()) }
                HealthWindow(dayStart(start), dayStart(last.plusDays(1)), days, days.map { "${it.dayOfMonth}" })
            }
            else -> {  // "day"
                val d = today.minusDays(off)
                HealthWindow(dayStart(d), dayStart(d.plusDays(1)), listOf(d), listOf(dow(d)))
            }
        }
    }

    /** A night's sleep timeline as `startFraction\twidthFraction\tisDeep` rows over an 18 h window
     *  (6 PM → noon of that day); light intervals first, deep last (so the GUI draws deep over light). */
    private fun sleepTimelineRows(dayStart: Long, sleep: DailySleep?): List<String> {
        val windowStart = dayStart - 6 * 3600L
        val windowSpan = 18 * 3600.0
        fun seg(start: Long, end: Long, deep: Boolean): String? {
            val sf = ((start - windowStart) / windowSpan).coerceIn(0.0, 1.0)
            val ef = ((end - windowStart) / windowSpan).coerceIn(0.0, 1.0)
            if (ef <= sf) return null
            return String.format(Locale.ROOT, "%.4f\t%.4f\t%d", sf, ef - sf, if (deep) 1 else 0)
        }
        val intervals = sleep?.intervals ?: emptyList()
        return intervals.filter { !it.isDeep }.mapNotNull { seg(it.start, it.end, false) } +
            intervals.filter { it.isDeep }.mapNotNull { seg(it.start, it.end, true) }
    }

    /** How far back Today's sleep card looks for the most recent recorded night when today has none. */
    private val SLEEP_LOOKBACK_DAYS = 45

    /** The night to show for a `day` sleep view. Only **Today** (offset 0) falls back to the most recent
     *  recorded night (within [SLEEP_LOOKBACK_DAYS]) when today has none yet; a specific past day shows
     *  its own (possibly empty) night. stoandl's sleep data is inherently a night-or-more behind — the
     *  watch finalizes/hands off each night's overlay late and overlays are consume-once — so a strict
     *  today-only window leaves the launch view perpetually empty. The GUI dates the shown night from its
     *  wake epoch and labels it "Last recorded night", so no extra D-Bus field is needed to flag it. */
    private suspend fun resolveSleepDay(lp: LibPebble, offset: Int, today: LocalDate, zone: ZoneId): LocalDate {
        val requested = today.minusDays(offset.coerceAtLeast(0).toLong())
        val requestedHas = (lp.getDailySleepSession(requested.atStartOfDay(zone).toEpochSecond())?.totalSleep ?: 0L) > 0L
        if (offset != 0 || requestedHas) return requested
        for (back in 1..SLEEP_LOOKBACK_DAYS) {
            val d = requested.minusDays(back.toLong())
            if ((lp.getDailySleepSession(d.atStartOfDay(zone).toEpochSecond())?.totalSleep ?: 0L) > 0L) return d
        }
        return requested
    }

    override fun GetHealthSummary(periodType: String, offset: Int): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        return runBlocking {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val win = healthWindow(periodType, offset, today, zone)
            val isDay = periodType != "week" && periodType != "month"

            // Movement (libpebble3 stores cm + gram-calories; convert as the Core app does). For a
            // multi-day period the distance/cal/active tiles show the per-day average (= the day's
            // own total when `day`, since daysWithData = 1 then).
            val move = lp.getTotalHealthData(win.start, win.end)
            val daysWithData = lp.getDailyAggregates(win.start, win.end)
                .count { (it.steps ?: 0L) > 0L }.coerceAtLeast(1)
            val stepsTotal = (move?.steps ?: 0L).toInt()
            val stepsAvgPerDay = stepsTotal / daysWithData
            val distanceKm = String.format(Locale.ROOT, "%.1f",
                (move?.distanceCm ?: 0L) / 100.0 / 1000.0 / daysWithData)
            val kcal = ((move?.activeGramCalories ?: 0L) / 1000L / daysWithData).toInt()
            val activeMin = ((move?.activeMinutes ?: 0L) / daysWithData).toInt()
            // Typical daily steps = each day's weekday typical (getTypicalSteps is hourly → sum), averaged.
            // getTypicalSteps depends only on the weekday (it scans ~49 days each call), so memoize per
            // weekday — a month window has ≤7 distinct weekdays, not ~30 redundant DB scans.
            val typByDow = HashMap<Int, Long>()
            for (dow in win.days.map { it.dayOfWeek.ordinal }.distinct())
                typByDow[dow] = lp.getTypicalSteps(dow).sum()
            val typicals = win.days.mapNotNull { typByDow[it.dayOfWeek.ordinal]?.toInt()?.takeIf { t -> t > 0 } }
            val stepsTypical = if (typicals.isEmpty()) 0 else typicals.sum() / typicals.size

            // Sleep per night across the window. For `day` we report that night; for a period the
            // avg/night (light = total − deep; Pebble has no REM). bedtime/wakeup only for `day`.
            // For the Today card, fall back to the most recent recorded night when today has none yet
            // (see resolveSleepDay) so the launch view isn't perpetually empty; the GUI dates it. The
            // resolved night is reused for the sleep-derived resting HR below so the two always agree.
            val daySleepStart = if (isDay) resolveSleepDay(lp, offset, today, zone).atStartOfDay(zone).toEpochSecond()
                                else win.start
            val sleepTotalMin: Int; val sleepDeepMin: Int; val sleepBedtime: Long; val sleepWakeup: Long
            if (isDay) {
                val s = lp.getDailySleepSession(daySleepStart)?.takeIf { it.totalSleep > 0L }
                sleepTotalMin = ((s?.totalSleep ?: 0L) / 60L).toInt()
                sleepDeepMin = ((s?.deepSleep ?: 0L) / 60L).toInt()
                sleepBedtime = s?.firstStart ?: 0L
                sleepWakeup = s?.lastEnd ?: 0L
            } else {
                val nights = win.days.mapNotNull { lp.getDailySleepSession(it.atStartOfDay(zone).toEpochSecond()) }
                    .filter { it.totalSleep > 0L }
                val n = nights.size.coerceAtLeast(1)
                sleepTotalMin = (nights.sumOf { it.totalSleep } / 60L / n).toInt()
                sleepDeepMin = (nights.sumOf { it.deepSleep } / 60L / n).toInt()
                sleepBedtime = 0L; sleepWakeup = 0L
            }
            val sleepLightMin = (sleepTotalMin - sleepDeepMin).coerceAtLeast(0)
            val sleepTypicalMin = (lp.getTypicalSleepSeconds() / 60L).toInt()

            // Heart rate. avg over the window; for `day` also resting (sleep-derived), the live "now",
            // and the day's min/max. For a period min/max/now are 0 (the GUI shows the avg only).
            val hrAvg = lp.getAverageHeartRate(win.start, win.end)?.roundToInt() ?: 0
            val hrResting: Int; val hrCurrent: Int; val hrMin: Int; val hrMax: Int
            if (isDay) {
                hrResting = lp.getRestingHeartRate(daySleepStart) ?: 0
                hrCurrent = lp.getLatestHeartRateReading()?.bpm ?: 0
                val dayHr = lp.getHealthDataForRange(win.start, win.end).map { it.heartRate }.filter { it > 0 }
                hrMin = dayHr.minOrNull() ?: 0
                hrMax = dayHr.maxOrNull() ?: 0
            } else {
                hrResting = win.days.reversed()
                    .firstNotNullOfOrNull { lp.getRestingHeartRate(it.atStartOfDay(zone).toEpochSecond()) } ?: 0
                hrCurrent = 0; hrMin = 0; hrMax = 0
            }
            val hrAvailable = if (hrIsAvailable(lp, win.start, win.end)) "yes" else "no"
            val lastSync = relAge(lp.getLatestTimestamp() ?: 0L)

            "ok:" + listOf(
                stepsTotal, stepsAvgPerDay, stepsTypical, distanceKm, kcal, activeMin,
                sleepTotalMin, sleepDeepMin, sleepLightMin, sleepTypicalMin, sleepBedtime, sleepWakeup,
                hrAvg, hrResting, hrCurrent, hrMin, hrMax, hrAvailable, daysWithData, lastSync,
            ).joinToString("\t")
        }
    }

    override fun GetHealthSeries(metric: String, periodType: String, offset: Int): List<String> {
        val lp = libPebbleRef.get() ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val win = healthWindow(periodType, offset, today, zone)
        val isDay = periodType != "week" && periodType != "month"
        return runBlocking {
            when (metric) {
                "steps" -> if (isDay) {
                    // 24 hourly step buckets for the day, so the daily card shows an hourly bar graph.
                    val buckets = IntArray(24)
                    lp.getHealthDataForRange(win.start, win.end).forEach { e ->
                        buckets[((e.timestamp - win.start) / 3600L).toInt().coerceIn(0, 23)] += e.steps
                    }
                    (0..23).map { "$it\t${buckets[it]}" }
                } else {
                    // One bar per day: label \t steps \t typical(that weekday's typical daily total).
                    val byDay = lp.getDailyAggregates(win.start, win.end).associateBy { it.day }
                    val typByDow = HashMap<Int, Long>()   // memoize: typical depends only on the weekday
                    for (dow in win.days.map { it.dayOfWeek.ordinal }.distinct())
                        typByDow[dow] = lp.getTypicalSteps(dow).sum()
                    win.days.mapIndexed { i, d ->
                        val steps = byDay[d.toString()]?.steps
                        val typ = typByDow[d.dayOfWeek.ordinal] ?: 0L
                        "${win.labels[i]}\t${steps ?: ""}\t${if (typ > 0) typ else ""}"
                    }
                }
                "sleep" -> if (isDay) {
                    // Same Today→most-recent-night fallback as GetHealthSummary, so the timeline and the
                    // headline always describe the same night (see resolveSleepDay).
                    val ds = resolveSleepDay(lp, offset, today, zone).atStartOfDay(zone).toEpochSecond()
                    sleepTimelineRows(ds, lp.getDailySleepSession(ds))
                } else {
                    // One bar per night: label \t totalMin \t deepMin.
                    win.days.mapIndexed { i, d ->
                        val s = lp.getDailySleepSession(d.atStartOfDay(zone).toEpochSecond())
                        "${win.labels[i]}\t${((s?.totalSleep ?: 0L) / 60L).toInt()}\t${((s?.deepSleep ?: 0L) / 60L).toInt()}"
                    }
                }
                "heart" -> if (isDay) {
                    // Minute-level samples for the day (getHealthDataForRange is minute-resolution):
                    // one row per recorded minute `minuteOfDay\tbpm`. Empty = no HR that day.
                    lp.getHealthDataForRange(win.start, win.end)
                        .filter { it.heartRate > 0 }
                        .map { e ->
                            val minute = ((e.timestamp - win.start) / 60L).toInt().coerceIn(0, 1439)
                            "$minute\t${e.heartRate}"
                        }
                } else {
                    // One bar per day: label \t avgBpm (empty when no reading that day).
                    win.days.mapIndexed { i, d ->
                        val ds = d.atStartOfDay(zone).toEpochSecond()
                        val de = d.plusDays(1).atStartOfDay(zone).toEpochSecond()
                        val avg = lp.getAverageHeartRate(ds, de)?.roundToInt()
                        "${win.labels[i]}\t${avg ?: ""}"
                    }
                }
                else -> emptyList()
            }
        }
    }

    /** Whether to surface heart-rate UI: the connected watch has an HRM, or there's stored HR data.
     *  Shared by [GetHealthSummary] and [GetHealthSeries] so the summary flag and the series never disagree. */
    private suspend fun hrIsAvailable(lp: LibPebble, dayStart: Long, dayEnd: Long): Boolean {
        val color = lp.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()?.watchInfo?.color
        if (color != null && color.supportsHrm()) return true
        return lp.getAverageHeartRate(dayStart, dayEnd) != null || lp.getLatestHeartRateReading() != null
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

    override fun ExtList(): List<String> = extensionManager.list()
    override fun ExtInstall(path: String): String = extensionManager.install(path)
    override fun ExtUninstall(name: String, keepConfig: Boolean): String = extensionManager.uninstall(name, keepConfig)
    override fun ExtEnable(name: String): String = extensionManager.enable(name)
    override fun ExtDisable(name: String): String = extensionManager.disable(name)
    override fun ExtRestart(name: String): String = extensionManager.restart(name)
    override fun ExtGetConfig(name: String): String = extensionManager.getConfig(name)
    override fun ExtSetConfig(name: String, payloadJson: String): String =
        extensionManager.setConfig(name, payloadJson)
    override fun ExtConfigSchema(name: String): String = extensionManager.configSchema(name)
    override fun ExtOpenConfig(name: String): String = extensionManager.openConfig(name)

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
                        if (w.sync) add("synced")
                    }
                    // System apps are always on the watch — treat them as synced (they carry no sync flag).
                    is LockerWrapper.SystemApp -> {
                        add("system")
                        add("synced")
                    }
                }
            }.joinToString(",")
            listOf(p.id.toString(), p.type.code, p.order.toString(), flags, p.title, p.developerName, p.version ?: "")
                .joinToString("\t")
        }
    }

    override fun GetAppIcon(uuid: String): String {
        // libPebble readiness gates this only so a too-early GUI call reports notready rather than
        // none (the icon cache itself is a pure disk read, independent of a connected watch).
        libPebbleRef.get() ?: return "notready:libPebble not ready"
        return try {
            val path = appIconExtractor.iconPngPath(uuid.trim()) ?: return "none:"
            "ok:$path"
        } catch (e: Exception) {
            log.warn(e) { "GetAppIcon($uuid) failed" }
            "error:${e.message ?: "icon extraction failed"}"
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

    /** Every live PKJS session across all connected watches (empty when libPebble isn't ready). */
    private fun currentPkjsApps(): List<PKJSApp> =
        libPebbleRef.get()?.watches?.value
            ?.filterIsInstance<ConnectedPebbleDevice>()
            ?.flatMap { it.currentCompanionAppSessions.value }?.filterIsInstance<PKJSApp>()
            ?: emptyList()

    /** Poll for the PKJS session of the app with [uuid] (it appears a few seconds after launch, once
     *  the JS bridge initialises), up to [timeoutMs]. */
    private suspend fun awaitPkjsApp(uuid: Uuid, timeoutMs: Long): PKJSApp? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val match = currentPkjsApps().firstOrNull { it.appInfo.uuid.equals(uuid.toString(), ignoreCase = true) }
            if (match != null) return@withTimeoutOrNull match
            delay(300)
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    override fun WebviewClose(data: String) {
        currentPkjsApps().forEach { it.triggerOnWebviewClosed(data) }
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
        val apps = currentPkjsApps()
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
        // Client-initiated pairing → require an explicit ConfirmPairing before bonding (see onPairingConfirm).
        requireConfirm.set(true)
        scope.launch {
            val result = CompletableDeferred<String>()
            pairingResult.set(result)
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
            requireConfirm.set(false)
            pairingResult.compareAndSet(result, null)
            pairingConfirmation.decide(false)  // unblock any still-parked agent (no-op if none)
            pairingState.set(newState)
            pairingGate.close()
            log.info { "Pairing result: $newState" }
        }
    }

    override fun PairStatus(): String =
        pairingState.get().ifEmpty { "error:No pairing in progress" }

    override fun ConfirmPairing(accept: Boolean): String =
        if (pairingConfirmation.decide(accept)) "ok:${if (accept) "accepted" else "declined"}"
        else "error:No pairing confirmation pending"

    override fun Unpair(watch: String): String {
        val lp = libPebbleRef.get() ?: return "error:Daemon not ready"
        val known = lp.watches.value.filterIsInstance<KnownPebbleDevice>()

        // Targeted: `unpair <name>` forgets just the matching watch (exact-then-unique-substring, like
        // `repair`), leaving any other watches untouched (multi-watch safe).
        if (watch.isNotBlank()) {
            if (known.isEmpty()) return "error:No known watches"
            val match = when (val m = matchOneWatch(known, watch)) {
                is WatchMatch.One -> m.device
                is WatchMatch.Error -> return m.message
            }
            val name = match.displayName()
            unpairOne(match)
            return "ok:Unpaired $name"
        }

        // Blanket: forget() each known watch (stops libpebble3 auto-connect) and clear its BlueZ bond.
        // Only *report* watches that were genuinely still bonded: forget() can leave a wedged
        // KnownPebbleDevice in the list (a stuck standing-connect attempt to a moved/out-of-range watch
        // keeps hasConnectionAttempt true, which blocks WatchManager from evicting it), so the same entry
        // can reappear here on a later 'unpair' even though it's already unpaired.
        val names = known.mapNotNull { d ->
            val name = d.displayName()
            if (unpairOne(d)) name else null
        }
        // Also sweep any leftover bonded Pebble in BlueZ that libpebble3 no longer tracks.
        val swept = clearStalePebbleBonds()
        return when {
            names.isNotEmpty() -> "ok:Unpaired ${names.joinToString(", ")}"
            swept.isNotEmpty() -> "ok:Cleared ${swept.size} stale bond(s)"
            else -> "ok:No paired watch"
        }
    }

    /** Forget one watch's libpebble3 state and clear its BlueZ bond (BLE by object path, Classic by MAC
     *  — works even while connected). Returns whether it was genuinely bonded (for blanket-unpair noise
     *  filtering; a wedged BLE entry reports false). */
    private fun unpairOne(d: KnownPebbleDevice): Boolean {
        val wasBonded = when (val id = d.identifier) {
            is PebbleBleIdentifier -> {
                val bonded = isBonded(id)
                bluezObjectPath(id.asString)?.let(::removeBluezBond)
                d.forget()
                bonded
            }
            is PebbleBtClassicIdentifier -> {
                val path = classicDevicePath(id.macAddress)
                path?.let(::removeBluezBond)
                d.forget()
                path != null
            }
            else -> { d.forget(); false }
        }
        // A deliberate unpair: log it plainly (removeBluezBond's own line reads "stale", which fits the
        // reaper/repair paths but not a user-driven unpair). Only for genuinely-bonded watches so a
        // blanket `unpair` over wedged/non-bonded entries stays quiet.
        if (wasBonded) log.info { "Unpaired ${d.displayName()} — forgot it and cleared its BlueZ bond" }
        return wasBonded
    }

    private sealed interface WatchMatch {
        data class One(val device: KnownPebbleDevice) : WatchMatch
        data class Error(val message: String) : WatchMatch
    }

    /** Resolve [watch] against [known] (the shared repair/unpair/connect semantics): an exact
     *  case-insensitive displayName match wins, else a unique substring match; zero or several
     *  matches are an `error:` string. The empty-`known` guard is left to each caller (its wording
     *  differs). */
    private fun matchOneWatch(known: List<KnownPebbleDevice>, watch: String): WatchMatch {
        val matches = known.filter { it.displayName().equals(watch, ignoreCase = true) }
            .ifEmpty { known.filter { it.displayName().contains(watch, ignoreCase = true) } }
        return when {
            matches.size == 1 -> WatchMatch.One(matches[0])
            matches.isEmpty() -> WatchMatch.Error("error:No known watch matching '$watch'. Known: " +
                known.joinToString(", ") { it.displayName() })
            else -> WatchMatch.Error("error:'$watch' matches multiple watches (${matches.joinToString(", ") { it.displayName() }}) — be more specific")
        }
    }

    override fun Repair(watch: String): String {
        val lp = libPebbleRef.get() ?: return "error:Daemon not ready"
        val known = lp.watches.value.filterIsInstance<KnownPebbleDevice>()
        if (known.isEmpty()) return "error:No known watches — use 'stoandl pair' to add one"
        // Substring match (case-insensitive) so 'repair B349' matches "Pebble B349" — no need to type
        // (and shell-escape) the full name. Prefer an exact match if one exists; else require a unique
        // substring hit so we never re-pair the wrong watch.
        val match = when (val m = matchOneWatch(known, watch)) {
            is WatchMatch.One -> m.device
            is WatchMatch.Error -> return m.message
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

    override fun Connect(watch: String): String {
        val lp = libPebbleRef.get() ?: return "error:Daemon not ready"
        val known = lp.watches.value.filterIsInstance<KnownPebbleDevice>()
        if (known.isEmpty()) return "error:No known watches — use 'stoandl pair' to add one"
        // Same exact-then-unique-substring match as repair/unpair.
        val match = when (val m = matchOneWatch(known, watch)) {
            is WatchMatch.One -> m.device
            is WatchMatch.Error -> return m.message
        }
        if (match is ConnectedPebbleDevice) return "ok:${match.displayName()} already connected"
        // requestConnection: in single-watch mode this sets connectGoal on the chosen watch and clears
        // it on the others, handing it the one connection slot (it connects once in range).
        match.connect()
        log.info { "Connect: handing the connection slot to ${match.displayName()}" }
        return "ok:Connecting ${match.displayName()}"
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
            // Transport (ble|classic) is only known for a live connection (ActiveDevice.usingBtClassic);
            // empty for connecting/disconnected — matches the GUI contract.
            val transport = (d as? ConnectedPebbleDevice)?.let { if (it.usingBtClassic) "classic" else "ble" } ?: ""
            "${d.displayName()}\t$state\t$battery\t$transport"
        }
    }

    override fun WatchDetails(): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = lp.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()
            ?: return "notready:No watch connected"
        val info = dev.watchInfo
        // "code" has no libpebble3 source — use the BLE advert-name suffix ("Pebble … B349" → "B349"),
        // the per-watch token the daemon already keys on; empty when the name has no suffix.
        val code = dev.name.substringAfterLast(' ', "")
        val transport = if (dev.usingBtClassic) "Bluetooth Classic" else "Bluetooth LE"
        return "ok:" + listOf(
            dev.displayName(),
            code,
            info.color.uiDescription,
            info.platform.watchType.name,
            transport,
            info.runningFwVersion.stringVersion,
            info.serial,
            dev.batteryLevel?.toString() ?: "",
            relAge(dev.lastConnected.epochSeconds),
        ).joinToString("\t")
    }

    override fun SetWatchNickname(query: String, nickname: String): String {
        val lp = libPebbleRef.get() ?: return "notready:libPebble not ready"
        val known = lp.watches.value.filterIsInstance<KnownPebbleDevice>()
        if (known.isEmpty()) return "notfound:No known watches — use 'stoandl pair' to add one"
        val match = when (val m = matchOneWatch(known, query)) {
            is WatchMatch.One -> m.device
            is WatchMatch.Error -> return m.message.replaceFirst("error:", "notfound:")
        }
        val nick = nickname.trim()
        if (nick.isEmpty()) return "error:Nickname must not be empty"
        return try {
            log.info { "Renaming ${match.displayName()} → $nick" }
            match.setNickname(nick)
            "ok:Renamed to $nick"
        } catch (e: Exception) {
            log.warn(e) { "SetWatchNickname failed" }
            "error:${e.message ?: "rename failed"}"
        }
    }

    // The six sync services' live runtime state (enabled/available/lastSync), assembled by
    // PebbleIntegration from its service refs + the live config store — see SyncControl.
    override fun GetSyncStatus(): List<String> = syncControl.status()

    // Persist + live-apply a Sync-screen toggle (start/stop the service, no restart needed).
    override fun SetSyncEnabled(service: String, enabled: Boolean): String =
        syncControl.setEnabled(service, enabled)

    // Read off the live config store (reloaded on every write) so a just-set value sticks.
    override fun GetConfig(): List<String> = syncControl.getConfig()

    override fun GetConfigSchema(): List<String> =
        GUI_CONFIG_FIELDS.map { "${it.key}\t${it.type}\t${it.label}\t${it.options}\t${it.desc}" }

    // Persist + live-apply (validate, write, reload, reconcile the affected subsystem).
    override fun SetConfig(key: String, value: String): String = syncControl.setConfig(key, value)

    // --- Notification filters (global allow/block list, enforced in WatchNotifier.push) -------------
    override fun NotifListFilters(): List<String> =
        notificationFilters.list().map { "${it.pattern}\t${it.action.name.lowercase()}" }

    override fun NotifAddFilter(pattern: String, action: String): String =
        notificationFilters.add(pattern, action)

    override fun NotifRemoveFilter(pattern: String): String =
        notificationFilters.remove(pattern)
}

private object NoOpTranscriptionProvider : TranscriptionProvider {
    override suspend fun transcribe(
        encoderInfo: VoiceEncoderInfo,
        audioFrames: Flow<UByteArray>,
        isNotificationReply: Boolean,
    ): TranscriptionResult = TranscriptionResult.Failed

    override suspend fun canServeSession(): Boolean = false
}
