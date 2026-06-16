package de.yoxcu.stoandl.util

import io.rebble.libpebblecommon.connection.LibPebble
import java.util.concurrent.atomic.AtomicReference

/**
 * The first currently-connected device of type [T] across all watches, or null. This is the watch
 * selector shared by the per-feature `*Control` classes (each holds a `libPebbleRef` and wants either
 * a `ConnectedPebbleDevice` or a `CommonConnectedDevice`).
 */
inline fun <reified T> AtomicReference<LibPebble?>.connectedDevice(): T? =
    get()?.watches?.value?.filterIsInstance<T>()?.firstOrNull()
