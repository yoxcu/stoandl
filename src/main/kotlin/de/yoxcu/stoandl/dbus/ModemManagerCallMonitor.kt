package de.yoxcu.stoandl.dbus

import de.yoxcu.stoandl.calls.MissedCallLog
import de.yoxcu.stoandl.contacts.ContactResolver
import de.yoxcu.stoandl.contacts.DialerNameCache
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.yoxcu.stoandl.util.openWatchedSystemBus
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.errors.UnknownObject
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * Bridges ModemManager telephony (system bus) to libPebble's [LibPebble.currentCall], so an
 * incoming/active call shows the native Pebble call screen and the watch's Answer/Hangup buttons
 * drive the modem.
 *
 * ModemManager runs on the **system bus** (the session-bus connections used elsewhere can't see
 * it), so this opens its own connection. Call objects appear under each modem's `…Modem.Voice`
 * interface; we subscribe to the `CallAdded`/`CallDeleted`/`Call.StateChanged` signals across all
 * modems (filtering by object path is unnecessary — a single watch can only sensibly surface one
 * call at a time) and hand them, in bus order, to [ModemCallState], which turns them into [Call]s.
 *
 * Answer/Hangup from the watch are wired back through the [Call] callbacks to `Accept()`/`Hangup()`
 * on the matching `org.freedesktop.ModemManager1.Call` object.
 *
 * Note: ModemManager exposes only the caller number. The caller name is resolved host-side —
 * vCard files via [ContactResolver] then the recent dialer-notification title via [DialerNameCache]
 * — and passed as [Call.contactName]; PhoneControlManager falls back to the number only when it
 * stays unresolved.
 */
internal class ModemManagerCallMonitor(
    private val libPebble: LibPebble,
    private val scope: CoroutineScope,
    private val contactResolver: ContactResolver,
    private val dialerNameCache: DialerNameCache,
    private val missedCallLog: MissedCallLog,
) {
    private val log = KotlinLogging.logger {}

    @Volatile private var conn: DBusConnection? = null

    private val calls = ModemCallState(
        readState = ::readState,
        readNumber = ::readNumber,
        resolveName = ::resolveName,
        publish = { libPebble.currentCall.value = it },
        recordMissed = missedCallLog::record,
        accept = ::accept,
        hangup = ::hangup,
    )

    fun start() {
        scope.launch(Dispatchers.IO) { calls.run() }
        // Load the contact index now, not on the first call's CallAdded, so the watch rings sooner.
        scope.launch(Dispatchers.IO) { contactResolver.preload() }
        scope.launch {
            while (coroutineContext.isActive) {
                try {
                    runMonitor()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "ModemManager monitor error: ${e.message}" }
                }
                try { conn?.disconnect() } catch (_: Exception) {}
                conn = null
                if (coroutineContext.isActive) delay(5.seconds)
            }
        }
    }

    private suspend fun runMonitor() {
        val bus = withContext(Dispatchers.IO) { openWatchedSystemBus() }
        val c = bus.conn
        conn = c
        log.info { "ModemManager call monitor: connected to system bus" }

        // dbus-java runs these on its one signal thread, in bus order; posting keeps that order.
        c.addSigHandler(ModemVoice.CallAdded::class.java) { sig ->
            val callPath = sig.call.path
            log.info { "ModemManager: CallAdded $callPath" }
            calls.post(CallEvent.Added(callPath))
        }
        c.addSigHandler(ModemVoice.CallDeleted::class.java) { sig ->
            val callPath = sig.call.path
            log.info { "ModemManager: CallDeleted $callPath" }
            calls.post(CallEvent.Deleted(callPath))
        }
        c.addSigHandler(ModemCall.StateChanged::class.java) { sig ->
            val callPath = sig.path
            log.info { "ModemManager: StateChanged $callPath ${sig.oldState}→${sig.newState}" }
            calls.post(CallEvent.StateChanged(callPath, sig.newState))
        }

        // Pick up a call already in progress (e.g. the daemon restarted mid-call).
        scanExisting(c)

        bus.awaitDisconnect()
        if (coroutineContext.isActive) log.warn { "ModemManager system-bus connection lost, reconnecting…" }
    }

    private fun scanExisting(c: DBusConnection) {
        try {
            // MM's root ObjectManager lists modems; ask each modem's Voice interface for its calls.
            val om = c.getRemoteObject(MM_BUS_NAME, MM_OBJECT_PATH, ObjectManager::class.java)
            om.GetManagedObjects().forEach { (modemPath, ifaces) ->
                if (ifaces.containsKey(MM_VOICE_IFACE)) {
                    val voice = c.getRemoteObject(MM_BUS_NAME, modemPath.path, ModemVoice::class.java)
                    voice.ListCalls().forEach { callPath ->
                        log.info { "ModemManager: existing call ${callPath.path}" }
                        calls.post(CallEvent.Added(callPath.path))
                    }
                }
            }
        } catch (e: Exception) {
            log.warn { "ModemManager: could not enumerate existing calls: ${e.message}" }
        }
    }

    /** vCard lookup first; fall back to a recent dialer-notification title (if it isn't just the
     *  number again). Null → PhoneControlManager shows the raw number. */
    private fun resolveName(number: String): String? {
        contactResolver.resolve(number)?.let { return it }
        val fallback = dialerNameCache.recent() ?: return null
        return fallback.takeIf { it.isNotBlank() && it.filter(Char::isDigit) != number.filter(Char::isDigit) }
    }

    private fun accept(callPath: String) {
        log.info { "[call] watch answered → ModemManager Accept($callPath)" }
        scope.launch(Dispatchers.IO) {
            try { callObj(callPath)?.Accept() } catch (e: Exception) {
                log.warn { "Accept($callPath) failed: ${e.message}" }
            }
        }
    }

    private fun hangup(callPath: String) {
        log.info { "[call] hangup → ModemManager Hangup($callPath)" }
        scope.launch(Dispatchers.IO) {
            try { callObj(callPath)?.Hangup() } catch (e: UnknownObject) {
                // The call already ended and we missed it; end it here too.
                log.info { "Hangup($callPath): the call is already gone" }
                calls.post(CallEvent.Deleted(callPath))
            } catch (e: Exception) {
                log.warn { "Hangup($callPath) failed: ${e.message}" }
            }
        }
    }

    private fun callObj(callPath: String): ModemCall? =
        conn?.getRemoteObject(MM_BUS_NAME, callPath, ModemCall::class.java)

    private fun props(callPath: String): Properties? =
        conn?.getRemoteObject(MM_BUS_NAME, callPath, Properties::class.java)

    private fun readNumber(callPath: String): String = try {
        props(callPath)?.Get<String>(MM_CALL_IFACE, "Number") ?: ""
    } catch (e: Exception) {
        log.warn { "Cannot read Number for $callPath: ${e.message}" }
        ""
    }

    private fun readState(callPath: String): Int = try {
        (props(callPath)?.Get<Any>(MM_CALL_IFACE, "State") as? Number)?.toInt() ?: MMCallState.UNKNOWN
    } catch (e: Exception) {
        log.warn { "Cannot read State for $callPath: ${e.message}" }
        MMCallState.UNKNOWN
    }
}
