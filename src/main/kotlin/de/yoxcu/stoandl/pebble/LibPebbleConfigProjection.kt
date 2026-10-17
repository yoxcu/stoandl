package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.config.StoandlConfig
import io.rebble.libpebblecommon.BleConfig
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.NotificationConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.permissions.PermissionPolicy
import kotlin.time.Duration.Companion.minutes

/**
 * The [LibPebbleConfig] stoandl runs libpebble3 with, as a pure function of its own config: the one
 * mapping used at startup and again after every config change, so stoandl.conf stays the single source
 * and nothing persisted by libpebble3 (its Java Preferences) can override a field.
 *
 * Fixed regardless of config:
 * - forward PPoG (`legacyReversedPPoG=false`, `useReversedPpogV2=false`): the phone hosts PPoG as the BLE
 *   peripheral; any reversed PPoG service the watch advertises is ignored;
 * - `lanDevConnection=true`: the developer connection uses the LAN WebSocket server on port 9000, not the
 *   CloudPebble proxy (stoandl has no Rebble token).
 *
 * From config: the notification catch-up window (`notification.catch_up_minutes`; libpebble3's
 * NotificationCatchUp), the calendar knobs PhoneCalendarSyncer reads live (`calendar.reminders`,
 * `calendar.show_declined`, `calendar.reminder_vibe`) and plugins with their permission policy
 * (`plugins.*`; PluginRegistry, LockerPluginLoader and AppPermissions follow the flow).
 */
fun libPebbleConfigOf(cfg: StoandlConfig): LibPebbleConfig = LibPebbleConfig(
    bleConfig = BleConfig(legacyReversedPPoG = false, useReversedPpogV2 = false),
    watchConfig = WatchConfig(
        lanDevConnection = true,
        calendarReminders = cfg.calendarReminders,
        calendarShowDeclinedEvents = cfg.calendarShowDeclined,
        overrideCalendarVibePattern = cfg.calendarReminderVibe?.let(::vibePatternName),
        enablePlugins = cfg.pluginsEnabled,
        permissionPolicy = when (cfg.pluginsPermissionPolicy) {
            StoandlConfig.PermissionPolicyMode.ASK_IF_EXFILTRATING -> PermissionPolicy.AskIfExfiltrating
            StoandlConfig.PermissionPolicyMode.ALWAYS_ASK -> PermissionPolicy.AlwaysAsk
            StoandlConfig.PermissionPolicyMode.NEVER_ASK -> PermissionPolicy.NeverAsk
        },
        askLegacyAppsForPermissions = cfg.pluginsAskLegacyApps,
    ),
    notificationConfig = NotificationConfig(
        missedNotificationCatchUpMs = cfg.notificationCatchUpMinutes.minutes.inWholeMilliseconds,
    ),
)

/** A vibe pattern as libpebble3 stores it: a built-in one (by display name or enum name, any case) in
 *  its display spelling, since the lookup is exact; any other name (a custom pattern) as written. */
internal fun vibePatternName(name: String): String = defaultVibeOf(name)?.displayName ?: name.trim()
