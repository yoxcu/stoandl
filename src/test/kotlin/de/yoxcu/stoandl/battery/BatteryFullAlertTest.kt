package de.yoxcu.stoandl.battery

import de.yoxcu.stoandl.battery.HeartbeatLayoutTest.Companion.synthetic567
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fully-charged rule (official app's: alert on reaching 100, latch until 97) and the Classic heartbeat feed. */
class BatteryFullAlertTest {
    private fun alerts(levels: List<Int?>, latch: BatteryFullLatch = BatteryFullLatch(), key: String = "W"): List<Int> =
        levels.mapIndexedNotNull { i, l -> i.takeIf { latch.onLevel(key, l) } }

    @Test
    fun alertsOnceWhenReachingFull() {
        assertEquals(listOf(2), alerts(listOf(98, 99, 100, 100, 100)))
    }

    @Test
    fun firstSampleOnlySeeds() {
        assertEquals(emptyList(), alerts(listOf(100, 100)))
    }

    @Test
    fun topUpBlipsAboveRearmDoNotRealert() {
        assertEquals(listOf(1), alerts(listOf(99, 100, 98, 100, 99, 100)))
    }

    @Test
    fun rearmsAtNinetySeven() {
        assertEquals(listOf(1, 4), alerts(listOf(99, 100, 97, 99, 100)))
    }

    @Test
    fun disconnectForgetsTheLevelButKeepsTheLatch() {
        val latch = BatteryFullLatch()
        assertEquals(listOf(1), alerts(listOf(99, 100), latch))
        // Reconnects already full: seeds only. Reconnects at 99 and fills: still latched from the last charge.
        assertEquals(emptyList(), alerts(listOf(null, 100, null, 99, 100), latch))
        assertEquals(listOf(3), alerts(listOf(null, 90, 97, 100), latch))
    }

    @Test
    fun watchesAreIndependent() {
        val latch = BatteryFullLatch()
        assertFalse(latch.onLevel("A", 99))
        assertFalse(latch.onLevel("B", 99))
        assertTrue(latch.onLevel("A", 100))
        assertTrue(latch.onLevel("B", 100))
    }

    private fun heartbeat(socHundredths: Long) = synthetic567(overrides = mapOf(
        "battery_soc_pct" to socHundredths,
        "battery_voltage" to 4180L,
        "battery_charge_time_ms" to 3_600_000L,
        "battery_discharge_duration_ms" to 0L,
    ))

    @Test
    fun heartbeatLevelRoundsDownSoNearlyFullIsNotFull() {
        val now = 1_790_000_000L + 60
        assertEquals(100, heartbeatLevel(heartbeat(10_000), now))
        assertEquals(99, heartbeatLevel(heartbeat(9_960), now))
    }

    @Test
    fun replayedHeartbeatIsNotTheCurrentLevel() {
        assertNull(heartbeatLevel(heartbeat(10_000), 1_790_000_000L + 3 * 3600))
    }
}
