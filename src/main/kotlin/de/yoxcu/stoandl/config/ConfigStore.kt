package de.yoxcu.stoandl.config

import java.util.concurrent.atomic.AtomicReference

/**
 * The single, **live** source of truth for [StoandlConfig]. Seeded once at startup and re-read from
 * disk on [reload] — called after a `SetConfig`/`SetSyncEnabled` write — so running subsystems read the
 * current value instead of a frozen startup snapshot. This is what lets config changes (and the Sync
 * screen's on/off toggles) take effect without a daemon restart: the writer persists to `stoandl.conf`,
 * reloads here, then re-reconciles the affected subsystem against [current].
 *
 * [current] is cheap (an atomic read) so call sites can read it per-event rather than capturing a
 * snapshot. Holders that must stay live (e.g. the notification choke point) are handed this store, not a
 * `StoandlConfig` value.
 */
class ConfigStore(initial: StoandlConfig = StoandlConfig.load()) {
    private val ref = AtomicReference(initial)

    /** The current config (atomic read). */
    fun current(): StoandlConfig = ref.get()

    /** Re-read `stoandl.conf` (quietly — no INFO summary) and publish it. Returns the new config. */
    fun reload(): StoandlConfig = StoandlConfig.load(logResult = false).also { ref.set(it) }
}
