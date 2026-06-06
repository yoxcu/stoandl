package de.yoxcu.stoandl.calls

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.calls.BlockedReason
import io.rebble.libpebblecommon.calls.MissedCall
import io.rebble.libpebblecommon.calls.SystemCallLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * In-memory missed-call log backing libpebble3's `MissedCallSyncer`, which turns entries into
 * timeline pins on the watch. ModemManager keeps no persistent call log (it drops `Call` objects
 * when they end), so [de.yoxcu.stoandl.dbus.ModemManagerCallMonitor] detects misses — an incoming
 * call that terminated without ever going ACTIVE — and calls [record].
 *
 * Entries are intentionally ephemeral (lost on restart): the pin is created in real time and then
 * persists in the watch's blob DB, so there's nothing to re-sync. Each miss is timestamped at
 * detection time, not ring-start, because `MissedCallSyncer` only pulls calls from the last ~10 s
 * after a change fires — and a call can ring longer than that.
 */
class MissedCallLog : SystemCallLog {
    private val log = KotlinLogging.logger {}
    private val entries = CopyOnWriteArrayList<MissedCall>()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    fun record(callerNumber: String, callerName: String?) {
        entries.add(
            MissedCall(
                callerNumber = callerNumber,
                callerName = callerName,
                blockedReason = BlockedReason.NotBlocked,
                timestamp = Clock.System.now(),
                duration = Duration.ZERO,
            )
        )
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        log.info { "Missed call from ${callerName ?: callerNumber} recorded" }
        changes.tryEmit(Unit)
    }

    override suspend fun getMissedCalls(start: Instant): List<MissedCall> =
        entries.filter { it.timestamp >= start }.sortedByDescending { it.timestamp }

    override fun registerForMissedCallChanges(): Flow<Unit> = changes

    override fun hasPermission(): Boolean = true

    companion object {
        private const val MAX_ENTRIES = 50
    }
}
