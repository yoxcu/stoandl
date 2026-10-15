package de.yoxcu.stoandl.dbus

import io.rebble.libpebblecommon.calls.Call
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The call monitor's bookkeeping, without a bus: what the watch shows for a given signal order. */
class ModemCallStateTest {
    private val modemStates = Collections.synchronizedMap(HashMap<String, Int>())
    private val shown = Collections.synchronizedList(mutableListOf<Call?>())
    private val missed = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var lookupDelayMs = 0L

    private val state = ModemCallState(
        readState = { path -> Thread.sleep(lookupDelayMs); modemStates[path] ?: MMCallState.UNKNOWN },
        readNumber = { "+4915123456789" },
        // the first contact-index load is the slow part
        resolveName = { Thread.sleep(lookupDelayMs); "Alice" },
        publish = { shown += it },
        recordMissed = { number, _ -> missed += number },
        accept = {},
        hangup = {},
    )

    /** Post [events] back to back, as the signal thread does, and wait until they are all handled. */
    private fun deliver(vararg events: CallEvent) = runBlocking {
        val consumer = launch(Dispatchers.IO) { state.run() }
        events.forEach(state::post)
        state.close()
        withTimeout(5_000) { consumer.join() }
    }

    @Test
    fun `a call withdrawn while its lookup is slow ends on the watch`() {
        // MM reads the call as ringing; it terminates and disappears before the lookup is done.
        modemStates[CALL0] = MMCallState.RINGING_IN
        lookupDelayMs = 300
        deliver(
            CallEvent.Added(CALL0),
            CallEvent.StateChanged(CALL0, MMCallState.TERMINATED),
            CallEvent.Deleted(CALL0),
        )
        assertNull(shown.last())
        assertEquals(listOf("+4915123456789"), missed)
    }

    @Test
    fun `an answered call is not a missed call`() {
        modemStates[CALL0] = MMCallState.RINGING_IN
        deliver(
            CallEvent.Added(CALL0),
            CallEvent.StateChanged(CALL0, MMCallState.ACTIVE),
            CallEvent.StateChanged(CALL0, MMCallState.TERMINATED),
            CallEvent.Deleted(CALL0),
        )
        assertIs<Call.RingingCall>(shown[0])
        assertIs<Call.ActiveCall>(shown[1])
        assertNull(shown.last())
        assertEquals(emptyList(), missed)
    }

    @Test
    fun `the end of an older call leaves a newer ringing call alone`() {
        modemStates[CALL0] = MMCallState.ACTIVE
        modemStates[CALL1] = MMCallState.WAITING
        deliver(
            CallEvent.Added(CALL0),
            CallEvent.Added(CALL1),
            CallEvent.StateChanged(CALL0, MMCallState.TERMINATED),
            CallEvent.Deleted(CALL0),
        )
        assertIs<Call.RingingCall>(shown.last())
    }

    @Test
    fun `a call that is gone when it is read changes nothing`() {
        // readState can't read a deleted call: UNKNOWN
        deliver(CallEvent.Added(CALL0), CallEvent.Deleted(CALL0))
        assertEquals(listOf<Call?>(null), shown)
        assertEquals(emptyList(), missed)
    }

    private companion object {
        const val CALL0 = "/org/freedesktop/ModemManager1/Call/0"
        const val CALL1 = "/org/freedesktop/ModemManager1/Call/1"
    }
}
