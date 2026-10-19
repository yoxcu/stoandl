package de.yoxcu.stoandl.config

import java.util.concurrent.atomic.AtomicReference

/**
 * The single, **live** source of truth for [StoandlConfig]. Seeded once at startup and re-read from
 * disk on [reload] — after a `SetConfig`/`SetSyncEnabled` write and after any other edit of
 * `stoandl.conf` (PebbleIntegration follows the file, see [fileEdits]) — so running subsystems read the
 * current value instead of a frozen startup snapshot. This is what lets config changes take effect
 * without a daemon restart: the file changes, the daemon reloads here, then re-reconciles the affected
 * subsystem against [current] ([changedConfigFields] says which).
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
