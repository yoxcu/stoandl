package de.yoxcu.stoandl.dnd

import de.yoxcu.stoandl.config.StoandlConfig.DndSyncMode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private val log = KotlinLogging.logger {}

/**
 * Mirrors the desktop's Do Not Disturb state ([HostDndBackend]) to/from the watch's manual Quiet Time
 * ([BoolWatchPref.QuietTimeManuallyEnabled], which libpebble3 syncs over the WatchPrefs BlobDB in both
 * directions). The [mode] picks which way(s) to propagate.
 *
 * **Loop avoidance:** both ends converge on the same boolean, so a single shared [synced] value is
 * enough — a change is only propagated when it differs from what we last reconciled. Pushing host→watch
 * makes the watch echo the value back (and vice-versa); comparing against [synced] swallows that echo,
 * which is what makes [DndSyncMode.BOTH] stable instead of ping-ponging.
 *
 * The host is treated as the source of truth at startup: [synced] is seeded from [HostDndBackend.current]
 * and (when syncing to the watch) pushed once so the watch matches the desktop on connect.
 */
class DndSync(
    private val libPebble: LibPebble,
    private val scope: CoroutineScope,
    private val mode: DndSyncMode,
    private val backend: HostDndBackend,
) {
    private val toWatch = mode == DndSyncMode.TO_WATCH || mode == DndSyncMode.BOTH
    private val toHost = mode == DndSyncMode.TO_HOST || mode == DndSyncMode.BOTH
    private val lock = Any()
    @Volatile private var synced: Boolean? = null

    fun start() {
        val initial = backend.current()
        if (initial != null) {
            synced = initial
            if (toWatch) pushToWatch(initial)
        }
        // Observe the host only when host changes need to reach the watch.
        if (toWatch) backend.observe { dnd -> onHostChange(dnd) }
        // Observe the watch only when watch changes need to reach the host.
        if (toHost) {
            libPebble.watchPrefs
                .map { prefs ->
                    prefs.firstOrNull { it.pref == BoolWatchPref.QuietTimeManuallyEnabled }
                        ?.valueOrDefault() as? Boolean ?: false
                }
                .distinctUntilChanged()
                .onEach { dnd -> onWatchChange(dnd) }
                .launchIn(scope)
        }
    }

    private fun onHostChange(dnd: Boolean) {
        synchronized(lock) {
            if (dnd == synced) return  // our own echo, or no real change
            synced = dnd
            log.info { "Host DND → $dnd; updating watch Quiet Time" }
            pushToWatch(dnd)
        }
    }

    private fun onWatchChange(dnd: Boolean) {
        synchronized(lock) {
            if (dnd == synced) return  // our own echo, or no real change
            synced = dnd
            log.info { "Watch Quiet Time → $dnd; updating host DND" }
            backend.set(dnd)
        }
    }

    private fun pushToWatch(dnd: Boolean) =
        libPebble.setWatchPref(WatchPreference(BoolWatchPref.QuietTimeManuallyEnabled, dnd))
}
