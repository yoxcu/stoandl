package de.yoxcu.stoandl.util

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant

/**
 * Open a private (unshared) session-bus connection. The `withShared(false)` is load-bearing — a
 * shared connection would be handed back to other callers and closing it would break them — and easy
 * to forget, so every session-bus caller goes through here.
 */
fun openSessionBus(): DBusConnection =
    DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection

/** [openSessionBus] but returns null instead of throwing, for the soft callers that degrade gracefully. */
fun softOpenSessionBus(): DBusConnection? = try {
    openSessionBus()
} catch (_: Exception) {
    null
}

/**
 * Open a system-bus connection (ModemManager / GeoClue). These callers use the shared default — kept
 * as-is to preserve existing behavior; only the easily-mistyped builder + cast is centralized here.
 */
fun openSystemBus(): DBusConnection =
    DBusConnectionBuilder.forSystemBus().build() as DBusConnection

/** Unwrap a dbus-java [Variant] to its contained value; pass-through for anything that isn't a Variant. */
fun unwrapVariant(v: Any?): Any? = if (v is Variant<*>) v.value else v
