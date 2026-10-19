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

/**
 * The daemon's own session-bus connection, which exports `de.yoxcu.stoandl.Control`. dbus-java runs
 * incoming method calls on a pool of 4 threads by default; a few slow calls (a firmware check, a
 * screenshot, a transcription) then held every worker and queued the GUI's quick reads behind them.
 */
fun openServiceBus(): DBusConnection =
    DBusConnectionBuilder.forSessionBus().withShared(false)
        .receivingThreadConfig().withMethodCallThreadCount(SERVICE_METHOD_CALL_THREADS).connectionConfig()
        .build() as DBusConnection

private const val SERVICE_METHOD_CALL_THREADS = 12

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

/** A private system-bus connection: its own bus peer, closed for real by `disconnect()`. For services
 *  that keep per-peer state, like GeoClue's one client per peer. */
fun openPrivateSystemBus(): DBusConnection =
    DBusConnectionBuilder.forSystemBus().withShared(false).build() as DBusConnection

/** Unwrap a dbus-java [Variant] to its contained value; pass-through for anything that isn't a Variant. */
fun unwrapVariant(v: Any?): Any? = if (v is Variant<*>) v.value else v
