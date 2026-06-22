package de.yoxcu.stoandl.pebble

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates a phone-side numeric-comparison pairing decision between the BlueZ pairing agent and the
 * D-Bus control surface. The agent thread parks in [awaitDecision] (surfacing the code for the user to
 * verify against the watch) until a client answers via [decide] — the `ConfirmPairing` D-Bus method —
 * or the timeout elapses. Only one decision can be outstanding at a time; a new request supersedes any
 * stale one. Thread-safe: [awaitDecision] runs on the agent connection's dispatch thread, [decide] on
 * the control connection's.
 */
class PairingConfirmation {
    enum class Decision { ACCEPT, DECLINE, TIMEOUT }

    private class Pending(val code: String, val latch: CountDownLatch, val accepted: AtomicBoolean)

    private val pending = AtomicReference<Pending?>(null)

    /** Park until the user accepts/declines [code], or [timeoutMs] elapses. */
    fun awaitDecision(code: String, timeoutMs: Long): Decision {
        val p = Pending(code, CountDownLatch(1), AtomicBoolean(false))
        pending.set(p)
        return try {
            when {
                !p.latch.await(timeoutMs, TimeUnit.MILLISECONDS) -> Decision.TIMEOUT
                p.accepted.get() -> Decision.ACCEPT
                else -> Decision.DECLINE
            }
        } finally {
            pending.compareAndSet(p, null)
        }
    }

    /** Resolve the outstanding decision. Returns false if none is pending. */
    fun decide(accept: Boolean): Boolean {
        val p = pending.get() ?: return false
        p.accepted.set(accept)
        p.latch.countDown()
        return true
    }
}
