package de.yoxcu.stoandl.pebble

import kotlin.test.Test
import kotlin.test.assertEquals

/** Field finding 3: a watch that just dropped after a resume, or torn down by this host, gets time to
 *  re-link before the follower rotates the goal away from it. */
class WristFollowTest {
    private val t = 1_000_000L

    @Test
    fun `a watch armed fresh or by a rotation gets the normal grace`() {
        assertEquals(WRIST_ARM_GRACE, wristRotateGrace(dropAtMs = null, lastResumeMs = t, dropReason = "org.bluez.Reason.Local", reasonAtMs = t))
    }

    @Test
    fun `a drop right after a resume waits longer, in either order`() {
        assertEquals(WRIST_RELINK_GRACE, wristRotateGrace(dropAtMs = t + 2_000, lastResumeMs = t, dropReason = null, reasonAtMs = 0))
        assertEquals(WRIST_RELINK_GRACE, wristRotateGrace(dropAtMs = t, lastResumeMs = t + 1_000, dropReason = null, reasonAtMs = 0))
    }

    @Test
    fun `a host-side teardown waits longer`() {
        assertEquals(WRIST_RELINK_GRACE, wristRotateGrace(dropAtMs = t, lastResumeMs = 0, dropReason = "org.bluez.Reason.Local", reasonAtMs = t - 100))
        assertEquals(WRIST_RELINK_GRACE, wristRotateGrace(dropAtMs = t, lastResumeMs = 0, dropReason = "org.bluez.Reason.Suspend", reasonAtMs = t))
    }

    @Test
    fun `an out-of-range drop, or a stale reason or resume, gets the normal grace`() {
        assertEquals(WRIST_ARM_GRACE, wristRotateGrace(dropAtMs = t, lastResumeMs = 0, dropReason = "org.bluez.Reason.Timeout", reasonAtMs = t))
        assertEquals(WRIST_ARM_GRACE, wristRotateGrace(dropAtMs = t, lastResumeMs = t - 600_000, dropReason = "org.bluez.Reason.Local", reasonAtMs = t - 600_000))
    }
}
