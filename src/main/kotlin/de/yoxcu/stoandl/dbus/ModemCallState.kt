@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.dbus

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.calls.Call
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlin.random.Random
import kotlin.random.nextUInt

/** A ModemManager call signal, in the order the bus delivered it. */
internal sealed interface CallEvent {
    val path: String

    /** `CallAdded`, or a call found already in progress: its state is read live when handled. */
    data class Added(override val path: String) : CallEvent
    data class StateChanged(override val path: String, val state: Int) : CallEvent
    data class Deleted(override val path: String) : CallEvent
}

/**
 * [ModemManagerCallMonitor]'s call bookkeeping, without a bus: turns [CallEvent]s into the [Call] the
 * watch shows, and incoming calls that end unanswered into missed calls.
 *
 * Events are handled one at a time, in the order they were [post]ed. Handling one can block (reading
 * the call's properties, the first contact-index load), and a call the network withdraws at once
 * (MultiSIM answered on another phone) sends `CallAdded`, `StateChanged`→terminated and `CallDeleted`
 * within a few milliseconds. Handled concurrently, the slow `CallAdded` finished last and left the
 * watch ringing for a call that no longer existed.
 */
internal class ModemCallState(
    /** The call's `State` (MMCallState); [MMCallState.UNKNOWN] if it can't be read. */
    private val readState: (path: String) -> Int,
    private val readNumber: (path: String) -> String,
    /** Caller name for the number; null → the watch shows the number. */
    private val resolveName: (number: String) -> String?,
    /** The call the watch shows (libPebble's `currentCall`); null ends the call screen. */
    private val publish: (Call?) -> Unit,
    private val recordMissed: (number: String, name: String?) -> Unit,
    private val accept: (path: String) -> Unit,
    private val hangup: (path: String) -> Unit,
) {
    private val log = KotlinLogging.logger {}
    private val queue = Channel<CallEvent>(Channel.UNLIMITED)

    // Only touched by [run]'s single consumer.
    // Stable per-call cookie for the PhoneControl protocol, keyed by call object path.
    private val cookies = HashMap<String, UInt>()
    private var activePath: String? = null
    // Per-call bookkeeping for missed-call detection (incoming + never answered).
    private val tracks = HashMap<String, CallTrack>()

    private class CallTrack(val number: String, val name: String?, val incoming: Boolean) {
        var answered: Boolean = false
    }

    /** Queue [event]; never blocks, so it's safe from a D-Bus signal handler. */
    fun post(event: CallEvent) {
        queue.trySend(event)
    }

    /** Handle posted events in order until [close]d or cancelled. */
    suspend fun run() {
        for (event in queue) {
            try {
                handle(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "ModemManager: failed to handle $event" }
            }
        }
    }

    /** Stop [run] once the queued events are handled. */
    internal fun close() {
        queue.close()
    }

    private fun handle(event: CallEvent) = when (event) {
        is CallEvent.Added -> handleState(event.path, readState(event.path))
        is CallEvent.StateChanged -> handleState(event.path, event.state)
        is CallEvent.Deleted -> endCall(event.path)
    }

    private fun handleState(callPath: String, state: Int) {
        if (state == MMCallState.TERMINATED) {
            endCall(callPath)
            return
        }
        val number = readNumber(callPath)
        val name = resolveName(number)
        val cookie = cookies.getOrPut(callPath) { Random.nextUInt() }
        // Track for missed-call detection. RINGING_IN/WAITING = incoming; DIALING/RINGING_OUT =
        // outgoing; ACTIVE marks the call answered (whichever direction).
        when (state) {
            MMCallState.RINGING_IN, MMCallState.WAITING ->
                tracks.getOrPut(callPath) { CallTrack(number, name, incoming = true) }
            MMCallState.DIALING, MMCallState.RINGING_OUT ->
                tracks.getOrPut(callPath) { CallTrack(number, name, incoming = false) }
            MMCallState.ACTIVE ->
                tracks.getOrPut(callPath) { CallTrack(number, name, incoming = false) }.answered = true
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
        publish(call)
        log.info { "Call ${callPath.substringAfterLast('/')}: state=$state name=${name ?: "?"} number=$number" }
    }

    private fun endCall(callPath: String) {
        cookies.remove(callPath)
        // An incoming call that ended without ever being answered is a missed call.
        tracks.remove(callPath)?.let { t ->
            if (t.incoming && !t.answered) recordMissed(t.number, t.name)
        }
        // Only clear if this is the call we're currently showing (a stale TERMINATED for an
        // already-replaced call must not wipe a newer ringing call).
        if (activePath == null || activePath == callPath) {
            activePath = null
            publish(null)
        }
    }
}
