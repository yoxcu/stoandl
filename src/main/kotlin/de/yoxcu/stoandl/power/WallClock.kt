package de.yoxcu.stoandl.power

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Suspend-aware waiting for periodic work (weather, calendar, firmware check).
 *
 * kotlinx `delay()` runs on CLOCK_MONOTONIC, which stops while the machine is suspended: a 30-minute
 * delay on a phone that is suspended 90 % of the time fires after roughly five hours of real time, and
 * the watch's weather goes stale. [delayWallClock] waits for a wall-clock deadline instead, in
 * monotonic slices of at most [WALL_CLOCK_SLICE], and re-checks at once on every resume ([wakeups] —
 * `SleepGuard.resumed`), so overdue work runs within a moment of the next wake and rides on it.
 *
 * It never arms an RTC alarm: a sleeping phone is not woken for a weather refresh. On a phone that
 * already wakes for push keepalives (and a safety RTC alarm) the work lands on one of those wakes.
 */
suspend fun delayWallClock(duration: Duration, wakeups: Flow<Unit>) =
    delayUntilWallClock(System.currentTimeMillis() + duration.inWholeMilliseconds, wakeups)

/** Wait until the wall clock reaches [deadlineMs] (epoch ms); see [delayWallClock]. */
suspend fun delayUntilWallClock(deadlineMs: Long, wakeups: Flow<Unit>) {
    while (true) {
        val left = deadlineMs - System.currentTimeMillis()
        if (left <= 0) return
        // A monotonic slice or the next resume, whichever comes first; then re-read the wall clock, so
        // time spent suspended counts in full. A resume that lands between two slices is only noticed
        // at the end of the next slice — fine for minute-scale work.
        withTimeoutOrNull(left.coerceAtMost(WALL_CLOCK_SLICE.inWholeMilliseconds)) { wakeups.first() }
    }
}

/** For callers without a resume signal: never emits (the slices alone still track the wall clock). */
val NoWakeups: Flow<Unit> = flow { awaitCancellation() }

private val WALL_CLOCK_SLICE = 1.minutes
