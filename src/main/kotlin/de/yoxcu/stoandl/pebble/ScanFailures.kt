package de.yoxcu.stoandl.pebble

/** PairStatus prefix of the note an open pairing window shows while its scan keeps failing. */
internal const val PAIRING_SCAN_FAILED = "pending:Scan failed, retrying — "

/**
 * libpebble3's scan errors (`UserFacingError.FailedToScan`) as the pairing window sees them. A scan that
 * can't start fails again on every scan-loop tick, so each consumer takes them at its own rate: the log
 * once per message and minute, the PairStatus note while failures keep coming, the desktop alert once per
 * window and only for a failure that repeats, and the window's timeout result names the last one.
 */
internal class ScanFailures(
    private val freshMs: Long = 10_000,
    private val relogMs: Long = 60_000,
    private val timeoutWithinMs: Long = 30_000,
) {
    data class Recorded(val logWarn: Boolean, val alert: Boolean)

    private var lastMessage: String? = null
    private var lastAtMs = 0L
    private var warnedMessage: String? = null
    private var warnedAtMs = 0L
    private var inWindow = 0
    private var alerted = false

    @Synchronized
    fun windowOpened() {
        lastMessage = null
        inWindow = 0
        alerted = false
    }

    @Synchronized
    fun record(message: String, nowMs: Long, windowOpen: Boolean): Recorded {
        lastMessage = message
        lastAtMs = nowMs
        val logWarn = message != warnedMessage || nowMs - warnedAtMs >= relogMs
        if (logWarn) { warnedMessage = message; warnedAtMs = nowMs }
        var alert = false
        if (windowOpen) {
            inWindow++
            if (inWindow >= 2 && !alerted) { alerted = true; alert = true }
        }
        return Recorded(logWarn, alert)
    }

    /** The PairStatus note while the scan is failing, or null once it has run [freshMs] without failing. */
    @Synchronized
    fun note(nowMs: Long): String? =
        lastMessage?.takeIf { nowMs - lastAtMs < freshMs }?.let { "$PAIRING_SCAN_FAILED$it" }

    /** The result of a pairing window that timed out, naming a scan failure from its last stretch. */
    @Synchronized
    fun timedOut(nowMs: Long): String =
        lastMessage?.takeIf { nowMs - lastAtMs < timeoutWithinMs }
            ?.let { "timeout:Pairing timed out — ${it.replaceFirstChar(Char::lowercase)}" }
            ?: "timeout:Pairing timed out"
}
