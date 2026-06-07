@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.dbus.FreedesktopNotifications
import de.yoxcu.stoandl.dbus.IncomingNotification
import de.yoxcu.stoandl.dbus.ModemManagerCallMonitor
import de.yoxcu.stoandl.calls.MissedCallLog
import de.yoxcu.stoandl.config.StoandlConfig
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
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlinx.io.files.Path
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private const val MAX_CONNECTION_ATTEMPTS = 5

private val log = KotlinLogging.logger {}

class PebbleIntegration(
    private val notificationFlow: Flow<IncomingNotification>,
    private val scope: CoroutineScope,
    private val serviceConn: DBusConnection,
) {
    private lateinit var libPebble: LibPebble
    private lateinit var watchConnector: WatchConnector
    private val libPebbleRef = AtomicReference<LibPebble?>(null)
    private val config = StoandlConfig.load()
    private val contactResolver = ContactResolver(config.vcardPaths)
    private val dialerNameCache = DialerNameCache()
    private val missedCallLog = MissedCallLog()
    // Headless BlueZ pairing agent so MITM/Secure-Connections pairing (newer Pebble firmware,
    // e.g. Time 2) can complete without a desktop UI — the user just confirms the code on the watch.
    private val pairingAgent = BluezPairingAgent()

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
        libPebble.init()
        startScanLoop()
        startAutoConnect()
        registerControlService()
        startCallMonitor()

        log.info { "libpebble3 initialized" }
    }

    fun gracefulShutdown() {
        if (!::libPebble.isInitialized) return
        log.info { "graceful BLE shutdown: disconnecting watches" }
        runBlocking {
            libPebble.watches.value.forEach { device ->
                watchConnector.requestDisconnection(device.identifier)
            }
            delay(1.5.seconds)
        }
    }

    private fun startScanLoop() {
        scope.launch {
            while (true) {
                log.info { "Starting BLE scan" }
                libPebble.startBleScan()
                delay(35.seconds) // slightly longer than the 30s scan timeout
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

    private fun registerControlService() {
        try {
            serviceConn.exportObject(STOANDL_OBJECT_PATH, StoandlControlImpl(libPebbleRef, scope))
            log.info { "D-Bus control service registered at $STOANDL_OBJECT_PATH" }
        } catch (e: Exception) {
            log.warn(e) { "Failed to register D-Bus control service" }
        }
    }

    private fun startAutoConnect() {
        libPebble.watches.onEach { devices ->
            devices
                .filterIsInstance<BleDiscoveredPebbleDevice>()
                .forEach { device ->
                    val failures = device.connectionFailureInfo
                    if (failures != null && failures.times >= MAX_CONNECTION_ATTEMPTS) {
                        log.warn { "Giving up on ${device.identifier} after ${failures.times} attempts (${failures.reason})" }
                        watchConnector.requestDisconnection(device.identifier)
                        return@forEach
                    }
                    log.info { "Requesting connection to ${device.identifier}" }
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

private class StoandlControlImpl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val scope: CoroutineScope,
) : StoandlControl {
    private val log = KotlinLogging.logger {}

    override fun isRemote() = false
    override fun getObjectPath() = STOANDL_OBJECT_PATH

    override fun SideloadApp(path: String): Boolean {
        val lp = libPebbleRef.get() ?: run {
            log.warn { "SideloadApp($path): libPebble not ready" }
            return false
        }
        log.info { "SideloadApp: $path" }
        return try {
            runBlocking { lp.sideloadApp(Path(path)) }
        } catch (e: Exception) {
            log.warn(e) { "SideloadApp($path) failed" }
            false
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

    override fun OpenConfig(app: String): String {
        val pkjsApp = findPkjsApp(app) ?: run {
            log.warn { "OpenConfig($app): no matching PKJS app running" }
            return ""
        }
        if (!pkjsApp.lockerEntry.configurable) {
            log.warn { "OpenConfig: ${pkjsApp.appInfo.shortName} is not configurable" }
            return ""
        }
        log.info { "OpenConfig: requesting config URL from ${pkjsApp.appInfo.shortName}" }
        // Run in the integration scope so coroutine exceptions never reach the D-Bus thread.
        val future = java.util.concurrent.CompletableFuture<String>()
        scope.launch {
            try {
                val url = withTimeoutOrNull(10_000) { pkjsApp.requestConfigurationUrl() }
                if (url == null) {
                    log.warn { "OpenConfig: timed out waiting for URL from ${pkjsApp.appInfo.shortName}" }
                    future.complete("")
                } else {
                    log.info { "OpenConfig: got URL: $url" }
                    future.complete(url)
                }
            } catch (e: Throwable) {
                log.warn(e) { "OpenConfig(${pkjsApp.appInfo.shortName}) coroutine failed: ${e.message}" }
                future.complete("")
            }
        }
        return try {
            future.get(20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.warn(e) { "OpenConfig(${pkjsApp.appInfo.shortName}) future timed out" }
            ""
        }
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
}

private object NoOpTranscriptionProvider : TranscriptionProvider {
    override suspend fun transcribe(
        encoderInfo: VoiceEncoderInfo,
        audioFrames: Flow<UByteArray>,
        isNotificationReply: Boolean,
    ): TranscriptionResult = TranscriptionResult.Failed

    override suspend fun canServeSession(): Boolean = false
}
