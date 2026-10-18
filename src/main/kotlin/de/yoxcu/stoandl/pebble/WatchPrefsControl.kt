@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.pebble

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.QuickLaunchActionSupport
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.database.entity.QuietTimeSchedule
import io.rebble.libpebblecommon.database.entity.RgbColorWatchPref
import io.rebble.libpebblecommon.database.entity.ScheduleWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.database.entity.WatchPrefEnum
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

/**
 * Drives the watch's "advanced settings" (the WatchPrefs BlobDB) that the official companion app
 * exposes but the watch itself doesn't — quick-launch button mappings, ambient-light threshold,
 * backlight, vibration patterns, Quiet Time schedules, etc. libpebble3 enumerates every pref ([WatchPref.enumeratePrefs])
 * and syncs a written value to the watch; this maps stoandl's string config/CLI values onto the
 * correct typed [WatchPreference] and applies them via [LibPebble.setWatchPref].
 *
 * [resolveAppUuid]/[appName] bridge quick-launch app references to/from the locker so a button can be
 * mapped by app name as well as UUID.
 */
class WatchPrefsControl(
    private val libPebble: LibPebble,
    private val resolveAppUuid: (String) -> Uuid?,
    private val appName: (Uuid) -> String?,
    /**
     * Keep an EXISTING `watch.<id>` line in `stoandl.conf` in step with a pref set here.
     *
     * `config.watchPrefs` is re-applied authoritatively on every fresh connect, so without this a pref
     * pinned in the config file silently reverted a GUI/CLI change at the next reconnect — the change
     * appeared to work, then undid itself. Only keys that are ALREADY pinned are rewritten: writing
     * every pref you touch would turn `watch.*` from an opt-in pin list into a full mirror.
     *
     * Null in tests / when there is no config file to keep in step with.
     */
    private val syncPinnedPref: ((id: String, raw: String) -> Unit)? = null,
    /** The connected watch's running firmware, or null when none is connected (or in tests). */
    private val connectedFirmware: () -> FirmwareVersion? = { null },
    /** The connected watch's platform, or null when none is connected (or in tests). */
    private val connectedWatchType: () -> WatchType? = { null },
) {
    /** `<id>@<firmware>` pins already reported as too new for the watch, so a reconnect logs them at DEBUG. */
    private val tooNewReported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Apply every configured `watch.<id> = value`. Unknown ids / bad values are logged and skipped
     *  (one bad entry never blocks the rest). Called on each connect so config stays authoritative.
     *  A pin the connected firmware is too old for is skipped: that firmware rejects the key. */
    fun applyConfigured(prefs: Map<String, String>) {
        if (prefs.isEmpty()) return
        val firmware = connectedFirmware()
        prefs.forEach { (id, raw) ->
            val pref = WatchPref.from(id)
            if (pref == null) {
                log.warn { "Unknown watch pref '$id' in config — ignoring (try 'stoandl settings')" }
                return@forEach
            }
            val tooOld = tooOldFor(pref, firmware)
            if (tooOld != null) {
                val message = "Not applying watch pref $id: $tooOld"
                if (tooNewReported.add("$id@${firmware?.stringVersion}")) log.info { message } else log.debug { message }
                return@forEach
            }
            try {
                val wp = parse(pref, raw)
                val cannotRun = cannotRun(wp)
                if (cannotRun != null) {
                    val message = "Not applying watch pref $id: $cannotRun"
                    if (tooNewReported.add("$id@${firmware?.stringVersion}")) log.info { message } else log.debug { message }
                    return@forEach
                }
                libPebble.setWatchPref(wp)
                log.info { "Applied watch pref $id = $raw" }
            } catch (e: Exception) {
                log.warn { "Skipping watch pref $id: ${e.message}" }
            }
        }
    }

    /** Set a single pref now (CLI). Returns a status-prefixed `ok:`/`error:` string. */
    fun setOne(id: String, raw: String): String {
        val pref = WatchPref.from(id) ?: return "error:Unknown watch pref '$id' (see 'stoandl settings')"
        return try {
            val wp = parse(pref, raw)
            cannotRun(wp)?.let { return "error:$it" }
            libPebble.setWatchPref(wp)
            // Keep a pinned `watch.<id>` in stoandl.conf in step, else it would revert this on reconnect.
            syncPinnedPref?.invoke(pref.id, raw.trim())
            val sync = tooOldFor(pref, connectedFirmware())?.let { "$it; it syncs once the watch is updated" }
                ?: "syncs to the watch on next connect"
            val audience = gatedAction(wp)?.takeIf { !watchKnown() }?.audience?.let { "; $it" } ?: ""
            "ok:Set ${pref.id} = ${format(pref, wp.valueOrDefault())} ($sync$audience)"
        } catch (e: IllegalArgumentException) {
            "error:${e.message}"
        } catch (e: Exception) {
            log.warn(e) { "setWatchPref($id) failed" }
            "error:${e.message ?: "failed"}"
        }
    }

    /** Tab-separated records for the CLI: `id \t type \t current \t default \t allowed \t debug \t name \t description`. */
    fun list(): List<String> {
        val current = runBlocking { withTimeoutOrNull(5_000) { libPebble.watchPrefs.first() } } ?: return emptyList()
        return current.map { wp ->
            val pref = wp.pref
            listOf(
                pref.id,
                typeName(pref),
                format(pref, wp.valueOrDefault()),
                format(pref, pref.defaultValue),
                allowed(pref),
                if (pref.isDebugSetting) "debug" else "",
                pref.displayName,
                describe(pref, quickLaunchNote(pref)).replace('\t', ' '),
            ).joinToString("\t")
        }
    }

    internal companion object {
        /** What stoandl adds to libpebble3's description where the firmware changed what a pref does. */
        private val DESCRIPTION_NOTES = listOf(EnumWatchPref.SystemTextSize, EnumWatchPref.NotificationTextSize).associate {
            it.id to "On older firmware change the size on the watch. Extra Large is set on the watch only."
        }

        /** Why [firmware] can't take [pref] (it rejects keys newer than its whitelist), or null when it can. */
        fun tooOldFor(pref: WatchPref<*>, firmware: FirmwareVersion?): String? {
            val min = pref.minFirmware ?: return null
            if (firmware == null || min.isMetBy(firmware)) return null
            return "needs PebbleOS $min or later, the watch runs ${firmware.stringVersion}"
        }

        /** libpebble3's description, the firmware it needs (from [WatchPref.minFirmware], unless the description
         *  already says so), stoandl's note and [extra], as sentences. */
        fun describe(pref: WatchPref<*>, extra: String? = null): String {
            val description = pref.description
            val needs = pref.minFirmware?.let { "Needs PebbleOS $it or later." }
                ?.takeUnless { description != null && "PebbleOS ${pref.minFirmware}" in description }
            val notes = listOfNotNull(needs, DESCRIPTION_NOTES[pref.id], extra)
            if (description.isNullOrBlank()) return notes.joinToString(" ")
            if (notes.isEmpty()) return description
            val sentence = if (description.trimEnd().last() in ".!?") description.trimEnd() else "${description.trimEnd()}."
            return (listOf(sentence) + notes).joinToString(" ")
        }
    }

    // ---- system actions only some watches have ------------------------------------------------------

    private fun watchKnown() = connectedWatchType() != null && connectedFirmware() != null

    /** The gated system action [wp] sets an enabled quick-launch button to, or null. */
    private fun gatedAction(wp: WatchPreference<*>): QuickLaunchActions.Action? =
        (wp.value as? QuickLaunchSetting)?.takeIf { it.enabled }?.uuid?.let { QuickLaunchActions.gated(it) }

    /**
     * Why the connected watch can't run the system action [wp] sets, or null when it can, the action runs
     * everywhere, or no watch is connected to tell. The firmware registers such an action only on some
     * boards; any other watch reads its UUID as an uninstalled app, so nothing would happen (before
     * PebbleOS 4.40 a tap on that button reboots the watch). libpebble3 doesn't send it to such a watch.
     */
    private fun cannotRun(wp: WatchPreference<*>): String? {
        val action = gatedAction(wp) ?: return null
        return QuickLaunchActions.whyNot(action, connectedWatchType() ?: return null, connectedFirmware() ?: return null)
    }

    /** The system actions the connected watch can run (all of them when none is connected). */
    private fun offeredActions(): List<QuickLaunchActions.Action> {
        val type = connectedWatchType()
        val firmware = connectedFirmware()
        if (type == null || firmware == null) return QuickLaunchActions.all
        return QuickLaunchActions.all.filter { QuickLaunchActions.whyNot(it, type, firmware) == null }
    }

    /** For a quick-launch pref with no watch connected to tell: which offered actions only some watches run. */
    private fun quickLaunchNote(pref: WatchPref<*>): String? {
        if (pref !is QuicklaunchWatchPref || watchKnown()) return null
        return QuickLaunchActions.all.filter { it.audience != null }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ") { "${it.name}: ${it.audience}." }
    }

    // ---- parsing (string → typed WatchPreference) -------------------------------------------------

    private fun parse(pref: WatchPref<*>, raw: String): WatchPreference<*> = when (pref) {
        is BoolWatchPref -> WatchPreference(pref, parseBool(raw))
        is NumberWatchPref -> WatchPreference(pref, parseNumber(pref, raw))
        is EnumWatchPref -> WatchPreference(pref, parseEnum(pref, raw))
        is QuicklaunchWatchPref -> WatchPreference(pref, parseQuickLaunch(raw))
        is RgbColorWatchPref -> WatchPreference(pref, parseColor(pref, raw))
        is ScheduleWatchPref -> WatchPreference(pref, parseSchedule(pref, raw))
    }

    private fun parseBool(raw: String): Boolean = when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> throw IllegalArgumentException("'$raw' is not a boolean (use true/false)")
    }

    /** A leading-space unit suffix (e.g. ` ms`), or empty when the pref is unitless. */
    private fun NumberWatchPref.unitSuffix(): String = if (unit.isNotEmpty()) " $unit" else ""

    private fun parseNumber(pref: NumberWatchPref, raw: String): Long {
        val n = raw.trim().toLongOrNull()
            ?: throw IllegalArgumentException("'$raw' is not a number for ${pref.id}")
        if (n < pref.min || n > pref.max) {
            throw IllegalArgumentException("${pref.id} must be ${pref.min}..${pref.max}${pref.unitSuffix()} (got $n)")
        }
        return n
    }

    private fun parseEnum(pref: EnumWatchPref, raw: String): WatchPrefEnum {
        val t = raw.trim()
        pref.options.firstOrNull { (it as Enum<*>).name.equals(t, true) || it.displayName.equals(t, true) }
            ?.let { return it }
        t.toUByteOrNull()?.let { code -> pref.options.firstOrNull { it.code == code }?.let { return it } }
        val allowed = pref.options.joinToString(", ") { (it as Enum<*>).name }
        throw IllegalArgumentException("'$raw' is not valid for ${pref.id}; allowed: $allowed")
    }

    private fun parseQuickLaunch(raw: String): QuickLaunchSetting {
        val t = raw.trim()
        if (t.isEmpty() || t.equals("off", true) || t.equals("none", true) || t.equals("disabled", true)) {
            return QuickLaunchSetting(enabled = false, uuid = null)
        }
        val uuid = QuickLaunchActions.parse(t) ?: resolveAppUuid(t)
            ?: throw IllegalArgumentException(
                "no system action or single app matching '$t' for quick launch " +
                    "(${QuickLaunchActions.names.joinToString(", ")}, or an app name or UUID)",
            )
        return QuickLaunchSetting(enabled = true, uuid = uuid)
    }

    private fun parseColor(pref: RgbColorWatchPref, raw: String): UInt {
        val t = raw.trim()
        pref.presets.firstOrNull { it.displayName.equals(t, true) }?.let { return it.rgb }
        val hex = t.removePrefix("#").removePrefix("0x").removePrefix("0X")
        val v = hex.toUIntOrNull(16)
            ?: throw IllegalArgumentException("'$raw' is not a color (hex RRGGBB or a preset name) for ${pref.id}")
        return v and 0x00FFFFFFu
    }

    /** A daily `HH:MM-HH:MM` window; an end before the start runs overnight (e.g. `22:00-07:00`). */
    private fun parseSchedule(pref: ScheduleWatchPref, raw: String): QuietTimeSchedule =
        QuietTimeSchedule.parse(raw)
            ?: throw IllegalArgumentException("'$raw' is not a time window (HH:MM-HH:MM, 24 h) for ${pref.id}")

    // ---- display ---------------------------------------------------------------------------------

    private fun typeName(pref: WatchPref<*>): String = when (pref) {
        is BoolWatchPref -> "bool"
        is NumberWatchPref -> "number"
        is EnumWatchPref -> "enum"
        is QuicklaunchWatchPref -> "quicklaunch"
        is RgbColorWatchPref -> "color"
        is ScheduleWatchPref -> "schedule"
    }

    private fun allowed(pref: WatchPref<*>): String = when (pref) {
        is BoolWatchPref -> "true|false"
        is NumberWatchPref -> "${pref.min}..${pref.max}" + pref.unitSuffix()
        // Human display names (e.g. "Standard - Low"), not the Kotlin constant ("StandardShortPulseLow"),
        // for the CLI/GUI — round-trip-safe because [parseEnum] also accepts the display name and the
        // names are unique within each pref's option list. Pipe-joined (a display name can contain ',').
        is EnumWatchPref -> pref.options.joinToString("|") { it.displayName }
        is QuicklaunchWatchPref -> (listOf("off") + offeredActions().map { it.name } + "<app name or uuid>").joinToString("|")
        is RgbColorWatchPref -> "RRGGBB|" + pref.presets.joinToString("|") { it.displayName }
        is ScheduleWatchPref -> "HH:MM-HH:MM"
    }

    private fun format(pref: WatchPref<*>, value: Any?): String = when (pref) {
        is BoolWatchPref -> (value as? Boolean)?.toString() ?: "?"
        is NumberWatchPref -> (value as? Long)?.let { "$it" + pref.unitSuffix() } ?: "?"
        is EnumWatchPref -> (value as? WatchPrefEnum)?.displayName ?: "?"
        is QuicklaunchWatchPref -> (value as? QuickLaunchSetting)?.let {
            val u = it.uuid
            if (!it.enabled || u == null) "off" else (QuickLaunchActions.nameOf(u) ?: appName(u) ?: u.toString())
        } ?: "off"
        is RgbColorWatchPref -> (value as? UInt)?.let { "0x" + it.toString(16).padStart(6, '0').uppercase() } ?: "?"
        is ScheduleWatchPref -> (value as? QuietTimeSchedule)?.encode() ?: "?"
    }
}

/**
 * The firmware system actions a quick-launch button can run instead of an app, named and ordered as the
 * official app's picker lists them. They have no locker entry, so without this table they parsed as no
 * app at all and listed as a raw UUID (the `qlBack` and `qlSingleClickDown` defaults among them).
 * A name wins over a locker app of the same title; address that app by UUID.
 */
internal object QuickLaunchActions {
    /**
     * [support] is what the firmware needs to have the action (libpebble3's [QuickLaunchActionSupport], null
     * when every watch has it); [audience] says so in words when no watch is connected to check against.
     */
    class Action(val name: String, val uuid: Uuid, val audience: String? = null) {
        val support: QuickLaunchActionSupport? = QuickLaunchActionSupport.of(uuid)
    }

    val all = listOf(
        Action("Quiet Time", SystemAppIDs.QUIET_TIME_TOGGLE_UUID),
        Action("Backlight", SystemAppIDs.BACKLIGHT_UUID),
        Action("Motion Backlight", SystemAppIDs.MOTION_BACKLIGHT_UUID),
        Action("Airplane Mode", SystemAppIDs.AIRPLANE_MODE_UUID),
        Action("Timeline Past", SystemAppIDs.TIMELINE_PAST_UUID),
        Action("Timeline Future", SystemAppIDs.TIMELINE_FUTURE_UUID),
        // PebbleOS 4.39.0, Pebble Time 2 and Pebble Round 2 only.
        Action("Toggle Touch", SystemAppIDs.TOUCH_TOGGLE_UUID, audience = "touch watches only"),
    )

    val names: List<String> = all.map { it.name }

    /** An action by its name (case, spaces, `-` and `_` ignored: `quiet-time`, `AirplaneMode`) or its UUID. */
    fun parse(raw: String): Uuid? {
        val key = squash(raw)
        all.firstOrNull { squash(it.name) == key }?.let { return it.uuid }
        val asUuid = runCatching { Uuid.parse(raw.trim()) }.getOrNull() ?: return null
        return all.firstOrNull { it.uuid == asUuid }?.uuid
    }

    fun nameOf(uuid: Uuid): String? = all.firstOrNull { it.uuid == uuid }?.name

    /** The action [uuid] if only some watches have it. */
    fun gated(uuid: Uuid): Action? = all.firstOrNull { it.uuid == uuid && it.support != null }

    /** Why a [type] watch on [firmware] lacks [action], or null when it has it. */
    fun whyNot(action: Action, type: WatchType, firmware: FirmwareVersion): String? {
        val support = action.support ?: return null
        return when {
            type !in support.watchTypes -> "${action.name} is for ${action.audience ?: "other watches"}; " +
                "the connected watch (${type.codename}) doesn't have it"
            !support.minFirmware.isMetBy(firmware) ->
                "${action.name} needs PebbleOS ${support.minFirmware} or later, the watch runs ${firmware.stringVersion}"
            else -> null
        }
    }

    private fun squash(s: String) = s.filterNot { it.isWhitespace() || it == '-' || it == '_' }.lowercase()
}
