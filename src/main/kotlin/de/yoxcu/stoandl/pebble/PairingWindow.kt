package de.yoxcu.stoandl.pebble

/**
 * The pairing window's decisions that don't need a radio, so a second watch can be paired while the
 * first stays connected (QOL-G3) and the rules are testable.
 */
internal object PairingWindow {
    /** The window has done its job: a watch is connected that wasn't when it opened (identifiers). A watch
     *  that was already connected stays out of it, so pairing a second watch waits for that one, while
     *  with nothing connected at the start any watch that comes up counts, as it always did. */
    fun newWatchConnected(connectedNow: Collection<String>, connectedAtOpen: Set<String>): Boolean =
        connectedNow.any { it !in connectedAtOpen }

    /** Whether the window's scan (BLE, or Classic's BR/EDR inquiry, each judged on its own transport's
     *  watches) may run. Never while a connection is being set up (a scan shares BlueZ's discovery slot with
     *  it, and one during the handshake starves its traffic), nor while the host is suspending; a watch
     *  that is already connected doesn't stop it. */
    fun scanAllowed(windowOpen: Boolean, connecting: Boolean, hostSuspending: Boolean): Boolean =
        windowOpen && !connecting && !hostSuspending
}
