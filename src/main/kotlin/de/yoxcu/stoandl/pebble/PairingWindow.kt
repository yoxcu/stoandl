package de.yoxcu.stoandl.pebble

/**
 * The pairing window's decisions that don't need a radio, so a second watch can be paired while the
 * first stays connected or holds the connect goal (QOL-G3), and the rules are testable.
 *
 * One owner of the connect goal at a time: during a window the pairing flow hands it to the NEW watch
 * once; known watches reconnect only through libpebble3's connectGoal, which `watch connect` moves.
 */
internal object PairingWindow {
    /** What the BT Classic handler does with a discovered Classic watch (see [classicCandidate]). */
    enum class ClassicStep { NONE, HINT, PAIR_THEN_CONNECT, CONNECT }

    /** Whether the Classic handler looks at this watch at all: only an unknown one with no connection
     *  under way and Bluetooth on. A known watch is libpebble3's to reconnect (and `watch connect`'s to pick);
     *  giving it the goal from here as well made two owners that fought (the airplane-mode flip-flop). */
    fun classicCandidate(known: Boolean, active: Boolean, btOn: Boolean): Boolean = btOn && !known && !active

    /** The step for a [classicCandidate], once per window ([bonded] null: BlueZ can't tell, e.g. no device
     *  object — then don't guess, or a pairing that can only fail starts). Outside a window an unbonded
     *  watch only gets a hint; a bonded one waits for `watch pair` (or `watch connect`). */
    fun classicStep(windowOpen: Boolean, bonded: Boolean?): ClassicStep = when {
        bonded == null -> ClassicStep.NONE
        !windowOpen -> if (bonded) ClassicStep.NONE else ClassicStep.HINT
        bonded -> ClassicStep.CONNECT
        else -> ClassicStep.PAIR_THEN_CONNECT
    }

    /** A watch whose connection is under way, as the discovery loops see it: [classic] transport, and
     *  whether its radio link is already [linkUp] (a BLE PPoG handshake or a protocol negotiation). */
    data class Connecting(val classic: Boolean, val linkUp: Boolean)

    /** Whether a discovery would disturb a connection under way — the one predicate both loops use. The BLE
     *  scan steps aside for any connecting watch: an LE scan and an LE connect share BlueZ's discovery slot
     *  (org.bluez.Error.InProgress), and one during the PPoG handshake starves its traffic. The BR/EDR
     *  [inquiry] steps aside for a Classic page/SDP and for any handshake on an up link, but not for a BLE
     *  watch that is only arming for a watch that is away: that would hold the inquiry for as long as the
     *  away watch keeps the goal, and a Classic watch could then never be paired. */
    fun discoveryDisturbs(inquiry: Boolean, connecting: Collection<Connecting>): Boolean =
        if (inquiry) connecting.any { it.classic || it.linkUp } else connecting.isNotEmpty()

    /** Whether the window's scan or inquiry may run: never while it would disturb a connection under way
     *  ([discoveryDisturbs]) nor while the host is suspending. A watch that is already connected doesn't
     *  stop it. */
    fun scanAllowed(windowOpen: Boolean, disturbs: Boolean, hostSuspending: Boolean): Boolean =
        windowOpen && !disturbs && !hostSuspending

    /**
     * Tells when a monitored window has found its watch: one that was not known when the window opened
     * connects. A known watch coming back meanwhile (in range again, or re-armed) is not it. The watch a
     * Repair is re-pairing ([repairing]) counts, but only once it has been seen without a link: Repair
     * forgets it and drops the link asynchronously, so for a moment it can still show as connected.
     */
    class NewWatch(knownAtOpen: Set<String>, private val repairing: String?, connectedAtOpen: Set<String>) {
        private val known = knownAtOpen - setOfNotNull(repairing)
        @Volatile private var repairingWentDown = repairing !in connectedAtOpen

        fun connected(connectedNow: Collection<String>): Boolean {
            if (repairing != null && repairing !in connectedNow) repairingWentDown = true
            return connectedNow.any { it !in known && (it != repairing || repairingWentDown) }
        }
    }

    /** The watch to give the goal back to when the window ends without its new watch connected ([result]
     *  not `ok:`): the one that held it at the start ([goalHolderAtOpen]), which the new watch's connect may
     *  have taken it from (decline, timeout). Null when nothing needs restoring. */
    fun restoreGoalTo(result: String, goalHolderAtOpen: String?): String? =
        goalHolderAtOpen.takeIf { !result.startsWith("ok:") }
}
