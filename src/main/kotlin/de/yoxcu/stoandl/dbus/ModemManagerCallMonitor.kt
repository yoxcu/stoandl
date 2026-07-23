@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.dbus

import de.yoxcu.stoandl.calls.MissedCallLog
import de.yoxcu.stoandl.contacts.ContactResolver
import de.yoxcu.stoandl.contacts.DialerNameCache
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.yoxcu.stoandl.util.openSystemBus
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.random.nextUInt
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
 * call at a time) and translate state transitions into [Call] instances.
 *
 * Answer/Hangup from the watch are wired back through the [Call] callbacks to `Accept()`/`Hangup()`
 * on the matching `org.freedesktop.ModemManager1.Call` object.
 *
 * Note: ModemManager exposes only the caller number. The caller name is resolved host-side —
 * vCard files via [ContactResolver] then the recent dialer-notification title via [DialerNameCache]
 * — and passed as [Call.contactName]; PhoneControlManager falls back to the number only when it
 * stays unresolved.
 */
class ModemManagerCallMonitor(
    private val libPebble: LibPebble,
    private val scope: CoroutineScope,
    private val contactResolver: ContactResolver,
    private val dialerNameCache: DialerNameCache,
    private val missedCallLog: MissedCallLog,
) {
    private val log = KotlinLogging.logger {}

    @Volatile private var conn: DBusConnection? = null
    // Stable per-call cookie for the PhoneControl protocol, keyed by call object path.
    private val cookies = ConcurrentHashMap<String, UInt>()
    @Volatile private var activePath: String? = null
    // Per-call bookkeeping for missed-call detection (incoming + never answered).
    private val tracks = ConcurrentHashMap<String, CallTrack>()

    private class CallTrack(val number: String, val name: String?, val incoming: Boolean) {
        @Volatile var answered: Boolean = false
    }

    fun start() {
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
        val c = withContext(Dispatchers.IO) { openSystemBus() }
        conn = c
        log.info { "ModemManager call monitor: connected to system bus" }

        c.addSigHandler(ModemVoice.CallAdded::class.java) { sig ->
            val callPath = sig.call.path
            log.info { "ModemManager: CallAdded $callPath" }
            scope.launch(Dispatchers.IO) { handleState(callPath, readState(callPath)) }
        }
        c.addSigHandler(ModemVoice.CallDeleted::class.java) { sig ->
            val callPath = sig.call.path
            log.info { "ModemManager: CallDeleted $callPath" }
            scope.launch { endCall(callPath) }
        }
        c.addSigHandler(ModemCall.StateChanged::class.java) { sig ->
            val callPath = sig.path
            log.info { "ModemManager: StateChanged $callPath ${sig.oldState}→${sig.newState}" }
            scope.launch(Dispatchers.IO) { handleState(callPath, sig.newState) }
        }

        // Pick up a call already in progress (e.g. the daemon restarted mid-call).
        scanExisting(c)

        while (coroutineContext.isActive && c.isConnected) {
            delay(1.seconds)
        }
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
                        scope.launch(Dispatchers.IO) { handleState(callPath.path, readState(callPath.path)) }
                    }
                }
            }
        } catch (e: Exception) {
            log.warn { "ModemManager: could not enumerate existing calls: ${e.message}" }
        }
    }

    private fun handleState(callPath: String, state: Int) {
        if (state == MMCallState.TERMINATED) {
            endCall(callPath)
            return
        }
        val number = readNumber(callPath)
        val name = resolveName(number)
        val cookie = cookieFor(callPath)
        // Track for missed-call detection. RINGING_IN/WAITING = incoming; DIALING/RINGING_OUT =
        // outgoing; ACTIVE marks the call answered (whichever direction).
        when (state) {
            MMCallState.RINGING_IN, MMCallState.WAITING ->
                tracks.computeIfAbsent(callPath) { _ -> CallTrack(number, name, incoming = true) }
            MMCallState.DIALING, MMCallState.RINGING_OUT ->
                tracks.computeIfAbsent(callPath) { _ -> CallTrack(number, name, incoming = false) }
            MMCallState.ACTIVE ->
                tracks.computeIfAbsent(callPath) { _ -> CallTrack(number, name, incoming = false) }.answered = true
        }
        val call = when (state) {
            MMCallState.RINGING_IN, MMCallState.WAITING -> Call.RingingCall(
                contactName = name, contactNumber = number, cookie = cookie,
                onCallEnd = { hangup(callPath) },
                onCallAnswer = { accept(callPath) },
            )
            MMCallState.DIALING, MMCallState.RINGING_OUT -> Call.DialingCall(
                contactName = name, contactNumber = number, cookie = cookie,
                onCallEnd = { hangup(callPath) },
            )
            MMCallState.ACTIVE -> Call.ActiveCall(
                contactName = name, contactNumber = number, cookie = cookie,
                onCallEnd = { hangup(callPath) },
            )
            MMCallState.HELD -> Call.HoldingCall(
                contactName = name, contactNumber = number, cookie = cookie,
                onCallEnd = { hangup(callPath) },
            )
            else -> return // UNKNOWN — leave current state untouched
        }
        activePath = callPath
        libPebble.currentCall.value = call
        log.info { "Call ${callPath.substringAfterLast('/')}: state=$state name=${name ?: "?"} number=$number" }
    }

    /** vCard lookup first; fall back to a recent dialer-notification title (if it isn't just the
     *  number again). Null → PhoneControlManager shows the raw number. */
    private fun resolveName(number: String): String? {
        contactResolver.resolve(number)?.let { return it }
        val fallback = dialerNameCache.recent() ?: return null
        return fallback.takeIf { it.isNotBlank() && it.filter(Char::isDigit) != number.filter(Char::isDigit) }
    }

    private fun endCall(callPath: String) {
        cookies.remove(callPath)
        // An incoming call that ended without ever being answered is a missed call.
        tracks.remove(callPath)?.let { t ->
            if (t.incoming && !t.answered) missedCallLog.record(t.number, t.name)
        }
        // Only clear if this is the call we're currently showing (a stale TERMINATED for an
        // already-replaced call must not wipe a newer ringing call).
        if (activePath == null || activePath == callPath) {
            activePath = null
            libPebble.currentCall.value = null
        }
    }

    private fun cookieFor(callPath: String): UInt =
        cookies.computeIfAbsent(callPath) { _ -> Random.nextUInt() }

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
            try { callObj(callPath)?.Hangup() } catch (e: Exception) {
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
