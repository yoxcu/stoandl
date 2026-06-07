@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.pebble

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.database.entity.RgbColorWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.database.entity.WatchPrefEnum
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

/**
 * Drives the watch's "advanced settings" (the WatchPrefs BlobDB) that the official companion app
 * exposes but the watch itself doesn't — quick-launch button mappings, ambient-light threshold,
 * backlight, vibration patterns, etc. libpebble3 enumerates every pref ([WatchPref.enumeratePrefs])
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
) {
    /** Apply every configured `watch.<id> = value`. Unknown ids / bad values are logged and skipped
     *  (one bad entry never blocks the rest). Called on each connect so config stays authoritative. */
    fun applyConfigured(prefs: Map<String, String>) {
        if (prefs.isEmpty()) return
        prefs.forEach { (id, raw) ->
            val pref = WatchPref.from(id)
            if (pref == null) {
                log.warn { "Unknown watch pref '$id' in config — ignoring (try 'stoandl settings')" }
                return@forEach
            }
            try {
                libPebble.setWatchPref(parse(pref, raw))
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
            libPebble.setWatchPref(wp)
            "ok:Set ${pref.id} = ${format(pref, wp.valueOrDefault())} (syncs to the watch on next connect)"
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
                pref.description?.replace('\t', ' ') ?: "",
            ).joinToString("\t")
        }
    }

    // ---- parsing (string → typed WatchPreference) -------------------------------------------------

    private fun parse(pref: WatchPref<*>, raw: String): WatchPreference<*> = when (pref) {
        is BoolWatchPref -> WatchPreference(pref, parseBool(raw))
        is NumberWatchPref -> WatchPreference(pref, parseNumber(pref, raw))
        is EnumWatchPref -> WatchPreference(pref, parseEnum(pref, raw))
        is QuicklaunchWatchPref -> WatchPreference(pref, parseQuickLaunch(raw))
        is RgbColorWatchPref -> WatchPreference(pref, parseColor(pref, raw))
    }

    private fun parseBool(raw: String): Boolean = when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> throw IllegalArgumentException("'$raw' is not a boolean (use true/false)")
    }

    private fun parseNumber(pref: NumberWatchPref, raw: String): Long {
        val n = raw.trim().toLongOrNull()
            ?: throw IllegalArgumentException("'$raw' is not a number for ${pref.id}")
        if (n < pref.min || n > pref.max) {
            val unit = if (pref.unit.isNotEmpty()) " ${pref.unit}" else ""
            throw IllegalArgumentException("${pref.id} must be ${pref.min}..${pref.max}$unit (got $n)")
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
        val uuid = resolveAppUuid(t)
            ?: throw IllegalArgumentException("no single app matching '$t' for quick launch (name or UUID)")
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

    // ---- display ---------------------------------------------------------------------------------

    private fun typeName(pref: WatchPref<*>): String = when (pref) {
        is BoolWatchPref -> "bool"
        is NumberWatchPref -> "number"
        is EnumWatchPref -> "enum"
        is QuicklaunchWatchPref -> "quicklaunch"
        is RgbColorWatchPref -> "color"
    }

    private fun allowed(pref: WatchPref<*>): String = when (pref) {
        is BoolWatchPref -> "true|false"
        is NumberWatchPref -> "${pref.min}..${pref.max}" + if (pref.unit.isNotEmpty()) " ${pref.unit}" else ""
        is EnumWatchPref -> pref.options.joinToString("|") { (it as Enum<*>).name }
        is QuicklaunchWatchPref -> "off|<app name or uuid>"
        is RgbColorWatchPref -> "RRGGBB|" + pref.presets.joinToString("|") { it.displayName }
    }

    private fun format(pref: WatchPref<*>, value: Any?): String = when (pref) {
        is BoolWatchPref -> (value as? Boolean)?.toString() ?: "?"
        is NumberWatchPref -> (value as? Long)?.let { "$it" + if (pref.unit.isNotEmpty()) " ${pref.unit}" else "" } ?: "?"
        is EnumWatchPref -> (value as? WatchPrefEnum)?.let { (it as Enum<*>).name } ?: "?"
        is QuicklaunchWatchPref -> (value as? QuickLaunchSetting)?.let {
            val u = it.uuid
            if (!it.enabled || u == null) "off" else (appName(u) ?: u.toString())
        } ?: "off"
        is RgbColorWatchPref -> (value as? UInt)?.let { "0x" + it.toString(16).padStart(6, '0').uppercase() } ?: "?"
    }
}
