package de.yoxcu.stoandl.dbus

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.time.TimeChanged
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal

private const val TIMEDATE1_PATH = "/org/freedesktop/timedate1"
private const val TIMEDATE1_IFACE = "org.freedesktop.timedate1"

private val log = KotlinLogging.logger {}

/**
 * Linux/JVM [TimeChanged]: the headless analogue of Android's `ACTION_TIMEZONE_CHANGED` broadcast.
 *
 * libpebble3's `LibPebble3.init()` registers a callback here and, when it fires, pushes a fresh
 * `TimeMessage.SetUTC` (unix time + UTC offset + timezone name) to every connected watch. The JVM
 * default (`TimeChanged.jvm.kt`) is a no-op, so without this the watch only ever learns the time at
 * connect time (the negotiator's `updateTime()`): change the host timezone while the watch stays
 * connected and it keeps the stale offset until the next reconnect.
 *
 * We watch systemd-timedated: `org.freedesktop.DBus.Properties.PropertiesChanged` on
 * `/org/freedesktop/timedate1`. timedated emits it when the timezone changes (`timedatectl
 * set-timezone`, a DE timezone toggle) and on NTP enable/sync, which covers the common "I travelled /
 * fixed the clock" cases. Re-pushing the time is cheap, so any timedate1 change triggers a resend.
 *
 * Caveat: timedated does NOT signal a plain DST rollover (the `Timezone` property is unchanged — only
 * the offset moves) nor a bare `timedatectl set-time` wall-clock step. Those still wait for the next
 * reconnect or a watch-initiated `GetTimeUtcRequest`. Covering them would need a CLOCK_REALTIME
 * discontinuity watch (timerfd `TFD_TIMER_CANCEL_ON_SET`), which is out of scope here.
 *
 * If the system bus or timedated is unavailable, this degrades silently: connect-time sync still works.
 */
class TimedateTimeChanged : TimeChanged {
    // Held for the daemon lifetime — the interface has no unregister, and dropping the connection
    // would tear down the signal handler.
    @Volatile private var conn: DBusConnection? = null

    override fun registerForTimeChanges(onChanged: () -> Unit) {
        val c = try {
            DBusConnectionBuilder.forSystemBus().withShared(false).build()
        } catch (e: Exception) {
            log.debug { "timedate1 time-change monitor unavailable (no system bus): ${e.message}" }
            return
        }
        conn = c
        try {
            val rule = DBusMatchRuleBuilder.create()
                .withType("signal")
                .withInterface("org.freedesktop.DBus.Properties")
                .withMember("PropertiesChanged")
                .withPath(TIMEDATE1_PATH)
                .build()
            c.addGenericSigHandler(rule) { msg: DBusSignal ->
                try {
                    // PropertiesChanged(interface_name: s, changed: a{sv}, invalidated: as).
                    // The path filter already pins this to timedated; double-check the interface name.
                    val ifaceName = msg.getParameters()?.getOrNull(0) as? String
                    if (ifaceName == null || ifaceName == TIMEDATE1_IFACE) {
                        // The JVM caches its default time zone at startup, so `timedatectl set-timezone`
                        // changing /etc/localtime does NOT update a running process — `TimeZone.
                        // currentSystemDefault()` (which libpebble3 uses to build SetUTC) would keep
                        // returning the OLD zone and the re-push would carry a stale offset. Invalidate
                        // the cache so the resend reads the new zone. (Clear `user.timezone` too: it's
                        // set to the resolved id at startup and getDefault() prefers it over re-reading
                        // /etc/localtime.)
                        System.clearProperty("user.timezone")
                        java.util.TimeZone.setDefault(null)
                        log.info { "timedate1 changed (tz now ${java.util.TimeZone.getDefault().id}) — re-syncing watch clock" }
                        onChanged()
                    }
                } catch (e: Exception) {
                    log.debug { "timedate1 PropertiesChanged parse error: ${e.message}" }
                }
            }
            log.info { "Time-change monitor started (org.freedesktop.timedate1 → watch clock re-sync)" }
        } catch (e: Exception) {
            log.debug { "Failed to subscribe to timedate1 changes: ${e.message}" }
            c.disconnect()
            conn = null
        }
    }
}
