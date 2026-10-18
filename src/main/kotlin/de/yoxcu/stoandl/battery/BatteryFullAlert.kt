package de.yoxcu.stoandl.battery

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.PebbleDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.abs

private val log = KotlinLogging.logger {}

/**
 * The official app's fully-charged rule, per watch: alert when the level rises to 100 %, then stay
 * latched until it falls to 97 % or below, so topping up on the charger doesn't re-alert on every blip.
 * A watch's first level after it (re)connects only seeds the rule — a watch already full when it
 * connects is not news. A null level (disconnected) forgets the previous level, not the latch.
 */
internal class BatteryFullLatch {
    private val last = HashMap<String, Int>()
    private val notified = HashSet<String>()

    /** Feed [level] for [key]; true when this sample is the one to alert on. */
    @Synchronized
    fun onLevel(key: String, level: Int?): Boolean {
        if (level == null) {
            last.remove(key)
            return false
        }
        val previous = last.put(key, level)
        if (level <= RENOTIFY_LEVEL) notified.remove(key)
        if (previous == null || previous >= FULL_LEVEL || level < FULL_LEVEL) return false
        return notified.add(key)
    }

    companion object {
        const val FULL_LEVEL = 100
        const val RENOTIFY_LEVEL = 97
    }
}

/**
 * The state of charge an analytics heartbeat reports, whole percent rounded down (99.6 % is not full),
 * or null when it doesn't decode or is not current: a reconnecting watch replays the hours it buffered,
 * and an old frame must not count as the latest level.
 */
internal fun heartbeatLevel(payload: ByteArray, nowSec: Long): Int? {
    val snap = HeartbeatStore.decode(payload, nowSec) ?: return null
    if (abs(nowSec - snap.watchTs) > HEARTBEAT_MAX_AGE_S) return null
    return snap.socPct.toInt()
}

/** One heartbeat period plus slack for a frame that waited in the watch's queue. */
private const val HEARTBEAT_MAX_AGE_S = 2L * 3600

/**
 * `alerts.battery_full`: tells the user when a connected watch has finished charging. BLE watches report
 * their level over GATT (`batteryLevel`); Classic watches have no GATT battery, so their level comes from
 * the hourly analytics heartbeat ([onHeartbeat], fed whether or not `battery.heartbeat` stores it) and
 * the alert can come up to an hour late. [notify] gets the watch's display name; it does the config check,
 * so the latch keeps tracking while the alert is off and turning it on at 100 % doesn't alert.
 */
class BatteryFullAlert(private val notify: (watchName: String) -> Unit) {
    private val latch = BatteryFullLatch()
    @Volatile private var connected: Map<String, CommonConnectedDevice> = emptyMap()

    fun start(watches: Flow<List<PebbleDevice>>, scope: CoroutineScope): Job =
        watches.onEach { runCatching { onWatches(it) }.onFailure { e -> log.warn(e) { "battery-full check failed" } } }
            .launchIn(scope)

    private fun onWatches(devices: List<PebbleDevice>) {
        val now = devices.filterIsInstance<CommonConnectedDevice>().associateBy { it.serial }
        for (gone in connected.keys - now.keys) latch.onLevel(gone, null)
        connected = now
        for (dev in now.values) {
            if (dev.usingBtClassic) continue
            if (latch.onLevel(dev.serial, dev.batteryLevel)) fire(dev)
        }
    }

    /** An analytics heartbeat from the watch with [serial]; only a Classic connection's counts. */
    fun onHeartbeat(payload: ByteArray, serial: String) {
        val dev = connected[serial]?.takeIf { it.usingBtClassic } ?: return
        val level = runCatching { heartbeatLevel(payload, System.currentTimeMillis() / 1000) }.getOrNull() ?: return
        if (latch.onLevel(serial, level)) fire(dev)
    }

    private fun fire(dev: CommonConnectedDevice) {
        log.info { "${dev.displayName()} is fully charged" }
        notify(dev.displayName())
    }
}
