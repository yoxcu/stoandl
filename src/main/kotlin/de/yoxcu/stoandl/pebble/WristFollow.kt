package de.yoxcu.stoandl.pebble

import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long the wrist follower waits for the watch it armed before rotating the goal to the next one. */
internal val WRIST_ARM_GRACE = 20.seconds

/** The longer wait for a watch that was connected and just dropped in a way that re-links slowly: right
 *  after a resume, or torn down by this host (BlueZ reason Local or Suspend). A post-resume BLE re-link
 *  can take longer than [WRIST_ARM_GRACE]; rotating away then hands the goal to a watch that may be away
 *  and keeps the worn one disconnected for minutes (field, 2026-10-20). */
internal val WRIST_RELINK_GRACE = 90.seconds

/** How close to a resume (either side: the drop and logind's resume signal arrive in either order) a drop
 *  counts as caused by it, and how close to the drop BlueZ's Disconnected reason must be to be its. */
private const val DROP_MATCH_WINDOW_MS = 30_000L

private val SLOW_RELINK_REASONS = setOf("org.bluez.Reason.Local", "org.bluez.Reason.Suspend")

/**
 * The grace before the follower rotates off the armed watch. [dropAtMs] is when the armed watch dropped
 * from connected (null: it was armed fresh or by a rotation, not dropped); [lastResumeMs] the last resume
 * (0: none); [dropReason] BlueZ's last Disconnected reason for that watch and [reasonAtMs] when it came.
 * [freshArm]: the follower armed the most recent watch on a fresh start (daemon start, autoswitch turned
 * on). Its first BlueZ Connect() can run about 20 s before it fails and retries, so it gets the longer
 * grace too; with [WRIST_ARM_GRACE] a restart rotated to the other watch before the worn one's retry.
 */
internal fun wristRotateGrace(
    dropAtMs: Long?, lastResumeMs: Long, dropReason: String?, reasonAtMs: Long, freshArm: Boolean = false,
): Duration {
    if (freshArm) return WRIST_RELINK_GRACE
    if (dropAtMs == null) return WRIST_ARM_GRACE
    val afterResume = lastResumeMs > 0 && abs(dropAtMs - lastResumeMs) <= DROP_MATCH_WINDOW_MS
    val slowReason = dropReason in SLOW_RELINK_REASONS && abs(dropAtMs - reasonAtMs) <= DROP_MATCH_WINDOW_MS
    return if (afterResume || slowReason) WRIST_RELINK_GRACE else WRIST_ARM_GRACE
}
