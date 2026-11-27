package de.yoxcu.stoandl.util

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * libpebble3 as stoandl uses it: the same object, except that [watches] is a [StateFlow], so a D-Bus method,
 * a sleep-guard poll or a pairing decision can read the current list without suspending ([watchesNow]).
 *
 * libpebble3's own `watches` is a plain Flow that emits nothing until its known watches are loaded (from the
 * database, started by `init()`). [loadWatches] turns it into the one StateFlow every stoandl reader shares,
 * right after `init()`, and only once that first list is there: an empty list before it would make every known
 * watch look new to the pairing window and the Classic handler. Reading [watches] before [loadWatches] is a bug
 * and fails loudly instead of answering "no watches".
 */
class StoandlLibPebble(private val libPebble: LibPebble) : LibPebble by libPebble {
    @Volatile private var state: StateFlow<List<PebbleDevice>>? = null

    override val watches: StateFlow<List<PebbleDevice>>
        get() = state ?: error("libpebble3's watches read before they were loaded (StoandlLibPebble.loadWatches)")

    /** The watches, or null before [loadWatches]: for a caller that may run while the daemon starts or
     *  stops (the BlueZ agent, shutdown), where "none known yet" is the right answer. */
    fun watchesIfLoaded(): List<PebbleDevice>? = state?.value

    /**
     * Shares libpebble3's watches as a StateFlow in [scope], returning once the known watches have loaded. Call
     * once, right after `init()`. Logs if that takes longer than [warnAfter], and throws once [giveUpAfter]
     * has passed: there is no correct list to answer with meanwhile, and libpebble3 loads them in a
     * coroutine of its own (`WatchManager.init()`), so a load that failed (a database migration that threw)
     * shows only as no first list. Thrown out of the daemon's start, it exits and its service manager sees it.
     */
    fun loadWatches(scope: CoroutineScope, warnAfter: Duration, giveUpAfter: Duration) {
        check(state == null) { "watches already loaded" }
        require(giveUpAfter >= warnAfter) { "giveUpAfter ($giveUpAfter) before warnAfter ($warnAfter)" }
        state = runBlocking {
            val loading = scope.async { libPebble.watches.stateIn(scope) }
            withTimeoutOrNull(warnAfter) { loading.await() } ?: run {
                log.warn { "libpebble3 hasn't loaded its known watches after $warnAfter; waiting up to $giveUpAfter" }
                withTimeoutOrNull(giveUpAfter - warnAfter) { loading.await() } ?: run {
                    loading.cancel()
                    throw IllegalStateException(
                        "libpebble3 didn't load its known watches within $giveUpAfter (see the log for its error); " +
                            "stopping rather than running with no watches known"
                    )
                }
            }
        }
    }
}

/**
 * The watches libpebble3 knows right now. [LibPebble.watches] is a Flow upstream; stoandl's [StoandlLibPebble]
 * (and a test's fake) keeps it as a StateFlow, which this reads. Any other LibPebble is a wiring bug.
 */
fun LibPebble.watchesNow(): List<PebbleDevice> {
    val flow = watches as? StateFlow<*>
        ?: error("watchesNow() on a LibPebble whose watches aren't a StateFlow (use StoandlLibPebble)")
    @Suppress("UNCHECKED_CAST")
    return flow.value as List<PebbleDevice>
}

/**
 * The first currently-connected device of type [T] across all watches, or null. This is the watch
 * selector shared by the per-feature `*Control` classes (each holds a `libPebbleRef` and wants either
 * a `ConnectedPebbleDevice` or a `CommonConnectedDevice`).
 */
inline fun <reified T> AtomicReference<LibPebble?>.connectedDevice(): T? =
    get()?.watchesNow()?.filterIsInstance<T>()?.firstOrNull()
