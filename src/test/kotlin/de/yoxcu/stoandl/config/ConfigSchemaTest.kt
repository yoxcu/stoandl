package de.yoxcu.stoandl.config

import de.yoxcu.stoandl.assertGuiFixture
import de.yoxcu.stoandl.notification.AppNamePlacement
import de.yoxcu.stoandl.voice.ESPEAK_VOICE
import de.yoxcu.stoandl.voice.ESPEAK_VOICE_NAME
import de.yoxcu.stoandl.voice.ModelKind
import de.yoxcu.stoandl.voice.SPEECH_MODELS
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The settings surface is the one part of the daemon that is unit-testable without a watch or a
 * session bus — and the part where a mistake is expensive and silent: a bad token corrupts
 * `stoandl.conf` on the next read, and a key that [StoandlConfig.load] doesn't parse is a control the
 * GUI shows, writes and the daemon ignores forever.
 *
 * Two kinds of test live here:
 *  - [ConfigField.parse] behaviour per widget kind;
 *  - **invariants** tying [GUI_CONFIG_FIELDS] to [StoandlConfig.load] and to the `GetConfigSchema`
 *    wire format, so `docs/settings-parity.md` cannot quietly go stale.
 */
class ConfigSchemaTest {

    private fun field(key: String): ConfigField =
        guiConfigField(key) ?: fail("no GUI config field '$key' — did the schema change?")

    private fun ok(f: ConfigField, input: String): String = when (val r = f.parse(input)) {
        is ConfigParse.Ok -> r.token
        is ConfigParse.Err -> fail("expected '$input' to be accepted for ${f.key}, got: ${r.message}")
    }

    private fun err(f: ConfigField, input: String): String = when (val r = f.parse(input)) {
        is ConfigParse.Ok -> fail("expected '$input' to be rejected for ${f.key}, got token '${r.token}'")
        is ConfigParse.Err -> r.message
    }

    /** The defaults, obtained the way the daemon does when no config file exists. */
    private fun defaults(): StoandlConfig =
        StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)

    /** S1: [StoandlConfig.defaults] is what an existing but empty `stoandl.conf` loads as, so the schema's
     *  `default` column is what a user without the key really gets. */
    @Test
    fun `an empty conf file loads as the defaults`(): Unit = withTempConf { conf ->
        conf.writeText("# nothing set\n")
        assertEquals(StoandlConfig.defaults(), StoandlConfig.load(conf, logResult = false))
        assertEquals(StoandlConfig.defaults(), defaults())
    }

    // --- per-kind parsing ---------------------------------------------------------------------

    @Test
    fun `toggle accepts the usual boolean words and normalises them`() {
        val f = field("weather.pins")
        listOf("true", "yes", "on", "1", "TRUE", " on ").forEach { assertEquals("true", ok(f, it), it) }
        listOf("false", "no", "off", "0", "OFF").forEach { assertEquals("false", ok(f, it), it) }
        assertContains(err(f, "maybe"), "expected true or false")
    }

    @Test
    fun `combo accepts either the display label or the raw conf token`() {
        val f = field("music.volume")
        assertEquals("player", ok(f, "Player volume"))
        assertEquals("player", ok(f, "player"))
        assertEquals("player", ok(f, "PLAYER"))
        assertEquals("system", ok(f, "System volume"))
        // The message names the valid options, since that is what a CLI/GUI shows the user.
        val msg = err(f, "headphones")
        assertContains(msg, "System volume")
        assertContains(msg, "Player volume")
    }

    @Test
    fun `int is range-checked against the schema's own bounds`() {
        val f = field("weather.interval")
        assertEquals(5, f.min)
        assertEquals(1440, f.max)
        assertEquals("30", ok(f, "30"))
        assertEquals("5", ok(f, " 5 "))
        assertContains(err(f, "4"), "at least 5")
        assertContains(err(f, "1441"), "at most 1440")
        assertContains(err(f, "soon"), "whole number")
    }

    @Test
    fun `list is normalised to the comma form the config parser reads back`() {
        val f = field("call.dialer_apps")
        assertEquals("spacebar,calls", ok(f, " spacebar , calls "))
        assertEquals("spacebar,calls", ok(f, "spacebar,,calls,"))
        assertEquals("", ok(f, "   "))
    }

    /** A canned reply may contain a comma: the GUI submits it as `\,`, the conf keeps it escaped, and
     *  the loader and `GetConfig` give back the same items. */
    @Test
    fun `list items with commas round-trip through SetConfig, the conf file and GetConfig`(): Unit = withTempConf { conf ->
        val f = field("notification.canned_replies")
        assertEquals("Sure\\, see you,Ok", ok(f, " Sure\\, see you , Ok ,"))
        assertEquals("ok", applyGuiConfig(f.key, "Sure\\, see you,Ok", conf).substringBefore(':'))
        val cfg = StoandlConfig.load(conf, logResult = false)
        assertEquals(listOf("Sure, see you", "Ok"), cfg.notificationCannedReplies)
        assertEquals("Sure\\, see you,Ok", f.value(cfg))
    }

    /**
     * `stoandl.conf` is `key = value`, one per line, with `#` starting a comment — so a value carrying
     * either would be silently truncated or split on the next read. Rejecting at the write path is the
     * only place that can be caught.
     */
    @Test
    fun `text and list reject values stoandl_conf cannot round-trip`() {
        val text = field("weather.gps_name")
        assertContains(err(text, "Home # 2"), "'#'")
        assertContains(err(text, "Home\nWork"), "single line")
        assertContains(err(text, "Home\rWork"), "single line")
        assertEquals("Büro", ok(text, " Büro "))

        val list = field("contacts.vcard_paths")
        assertContains(err(list, "/a/b#c"), "'#'")
        assertContains(err(list, "/a\n/b"), "single line")
    }

    @Test
    fun `weather locations are validated with the parser that reads them back`() {
        val f = field("weather.locations")
        assertEquals("Berlin:52.52:13.405", ok(f, "Berlin:52.52:13.405"))
        assertEquals(
            "Berlin:52.52:13.405,Sankt Gallen:47.42:9.37",
            ok(f, "Berlin:52.52:13.405, Sankt Gallen:47.42:9.37"),
        )
        assertEquals("", ok(f, ""))
        // A name may itself contain ':' — the last two fields are lat/lon.
        assertEquals("A:B:1.0:2.0", ok(f, "A:B:1.0:2.0"))
        assertContains(err(f, "Berlin"), "Name:lat:lon")
        assertContains(err(f, "Berlin:north:east"), "Name:lat:lon")
        // One bad entry rejects the whole write rather than being silently dropped at load time.
        assertContains(err(f, "Berlin:52.52:13.405,Nowhere"), "Name:lat:lon")
    }

    @Test
    fun `structured text fields validate their shape`() {
        val repo = field("firmware.github_repo")
        assertEquals("coredevices/PebbleOS", ok(repo, "coredevices/PebbleOS"))
        assertContains(err(repo, "PebbleOS"), "owner/repo")
        assertContains(err(repo, "a/b/c"), "owner/repo")
        assertContains(err(repo, ""), "empty")

        val url = field("firmware.cohorts_url")
        assertEquals("https://cohorts.rebble.io", ok(url, "https://cohorts.rebble.io"))
        assertEquals("http://192.168.1.2:8080", ok(url, "http://192.168.1.2:8080"))
        assertContains(err(url, "cohorts.rebble.io"), "http")
        assertContains(err(url, ""), "empty")
    }

    // --- wire format --------------------------------------------------------------------------

    @Test
    fun `schema rows carry the sixteen columns in the documented order`() {
        val f = field("weather.interval")
        val cols = f.schemaRow().split('\t')
        assertEquals(SCHEMA_COLUMNS, cols.size, "GetConfigSchema column count changed — update the GUI and the mock")
        assertEquals(
            listOf("weather.interval", "int", "Refresh every", "", f.desc, "Weather on the watch", "live", "5", "1440",
                "min", "", "Weather", f.help, "", "30", ""),
            cols,
        )
        // The first five columns are the original contract an older client reads positionally.
        val combo = field("music.volume")
        val c = combo.schemaRow().split('\t')
        assertEquals(listOf("music.volume", "combo", "Volume buttons change", "System volume,Player volume"), c.take(4))
        assertEquals(listOf("music.enabled=true", "System volume", ""), c.drop(13))
        // A secret's default is never shown, and an egress host is named.
        assertEquals("", field("voice.api_key").schemaRow().split('\t')[14])
        assertEquals("api.open-meteo.com", field("weather.enabled").schemaRow().split('\t')[15])
    }

    @Test
    fun `no schema text can corrupt the tab-separated record framing`() {
        GUI_CONFIG_FIELDS.forEach { f ->
            val row = f.schemaRow()
            assertEquals(SCHEMA_COLUMNS, row.split('\t').size, "${f.key}: a label/desc/help/group contains a tab")
            assertTrue('\n' !in row && '\r' !in row, "${f.key}: schema text contains a newline")
            // `options` is a CSV, so a comma inside an option label would split it in both front-ends.
            f.choices.forEach { c ->
                assertTrue(',' !in c.label, "${f.key}: option label '${c.label}' contains a comma")
            }
        }
    }

    // --- invariants against the config parser --------------------------------------------------

    @Test
    fun `every exposed key is unique`() {
        val dupes = GUI_CONFIG_FIELDS.groupBy { it.key }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate keys in GUI_CONFIG_FIELDS: $dupes")
    }

    /**
     * A key the GUI can write but [StoandlConfig.load] never reads is a dead control: it persists,
     * `GetConfig` reads it back off the freshly-parsed store as the OLD value, and nothing happens. A
     * typo'd key would look completely normal in the GUI, so it is checked mechanically.
     */
    @Test
    fun `every exposed key is actually read back by the config parser`(): Unit = withTempConf { conf ->
        GUI_CONFIG_FIELDS.forEach { f ->
            val before = f.value(StoandlConfig.load(conf, logResult = false))
            val flipped = flip(f, before)
                ?: return@forEach // single-option combo: nothing to flip, nothing to prove
            val result = applyGuiConfig(f.key, flipped, conf)
            assertTrue(result.startsWith("ok:"), "${f.key}: $result")
            val after = f.value(StoandlConfig.load(conf, logResult = false))
            assertEquals(
                flipped, after,
                "${f.key}: written '$flipped' but StoandlConfig.load() reads back '$after' — " +
                    "the key is in GUI_CONFIG_FIELDS but not parsed in load(), or the token mapping disagrees",
            )
        }
    }

    @Test
    fun `every field reports a value for the daemon defaults`() {
        val d = defaults()
        GUI_CONFIG_FIELDS.forEach { f ->
            val v = f.value(d)
            when (f.type) {
                "toggle" -> assertTrue(v == "true" || v == "false", "${f.key}: toggle value '$v'")
                "combo" -> assertTrue(
                    f.choices.any { it.label == v } || (v.isEmpty() && f.choicesOf != null),
                    "${f.key}: default '$v' is not one of ${f.options} — no choice matches the default config",
                )
                "int" -> {
                    val n = v.toIntOrNull() ?: fail("${f.key}: int value '$v' is not a number")
                    val lo = assertNotNull(f.min, "${f.key}: int field with no min")
                    val hi = assertNotNull(f.max, "${f.key}: int field with no max")
                    assertTrue(n in lo..hi, "${f.key}: default $n is outside its own range $lo..$hi")
                }
                // text/list may legitimately default to empty.
                else -> assertTrue('\t' !in v && '\n' !in v, "${f.key}: value contains framing characters")
            }
        }
    }

    @Test
    fun `a fields own default value is accepted by its own validator`() {
        val d = defaults()
        GUI_CONFIG_FIELDS.forEach { f ->
            val v = f.value(d)
            if (f.type in setOf("text", "list") && v.isEmpty()) return@forEach // empty = "unset"
            when (val r = f.parse(v)) {
                is ConfigParse.Ok -> Unit
                is ConfigParse.Err -> fail("${f.key}: its own default '$v' fails validation: ${r.message}")
            }
        }
    }

    @Test
    fun `restart-only keys say so in the write result`(): Unit = withTempConf { conf ->
        val restartKeys = GUI_CONFIG_FIELDS.filter { it.apply == ConfigApply.RESTART }
        // A key moves in or out of this list only together with docs/settings-parity.md.
        assertEquals(
            setOf("notification.sync_to_watch", "classic.discover", "power.sleep_guard", "power.sleep_guard_max_ms",
                "datalog.enabled"),
            restartKeys.map { it.key }.toSet(),
        )
        restartKeys.forEach { f ->
            val r = applyGuiConfig(f.key, f.parse(f.value(defaults())).let { (it as ConfigParse.Ok).token }, conf)
            assertContains(r, "restart stoandl to apply", message = "${f.key}: no restart hint in '$r'")
        }
        // ...and a live key must NOT carry the hint.
        val live = field("weather.pins")
        assertTrue(!applyGuiConfig(live.key, "true", conf).contains("restart"))
    }

    @Test
    fun `canned replies default to the firmware list, also when set empty`(): Unit = withTempConf { conf ->
        assertEquals(DEFAULT_CANNED_REPLIES, StoandlConfig.load(conf, logResult = false).notificationCannedReplies)
        conf.writeText("notification.canned_replies =\n")
        assertEquals(DEFAULT_CANNED_REPLIES, StoandlConfig.load(conf, logResult = false).notificationCannedReplies)
        conf.writeText("notification.canned_replies = On my way, Later ,\n")
        assertEquals(listOf("On my way", "Later"), StoandlConfig.load(conf, logResult = false).notificationCannedReplies)
    }

    /** A mistyped `notification.app_name` must not hide the app name or fail the load: it falls back to the
     *  default small line (and the loader WARNs). */
    @Test
    fun `notification_app_name reads its tokens and falls back to small`(): Unit = withTempConf { conf ->
        assertEquals(AppNamePlacement.SMALL, StoandlConfig.load(conf, logResult = false).notificationAppName)
        conf.writeText("notification.app_name = Subtitle\n")
        assertEquals(AppNamePlacement.SUBTITLE, StoandlConfig.load(conf, logResult = false).notificationAppName)
        conf.writeText("notification.app_name = off\n")
        assertEquals(AppNamePlacement.OFF, StoandlConfig.load(conf, logResult = false).notificationAppName)
        conf.writeText("notification.app_name = huge\n")
        assertEquals(AppNamePlacement.SMALL, StoandlConfig.load(conf, logResult = false).notificationAppName)
        assertEquals("small", ok(field("notification.app_name"), "Small line"))
    }

    /** `weather.units` was dropped (the watch's own units decide): a line left in a user's file must
     *  still load, and the key must not come back as a GUI control. */
    @Test
    fun `a leftover weather_units line is ignored`(): Unit = withTempConf { conf ->
        conf.writeText("weather.units = imperial\nweather.interval = 45\n")
        assertEquals(45L, StoandlConfig.load(conf, logResult = false).weatherIntervalMinutes)
        assertNull(guiConfigField("weather.units"))
        assertTrue(applyGuiConfig("weather.units", "Imperial", conf).startsWith("notfound:"))
    }

    /** `ble.conn_params` and `ble.conn_params_fast` were dropped (the watch chooses its own link
     *  parameters). conf.example shipped `ble.conn_params = off`, so most files still carry a line. */
    @Test
    fun `a leftover ble_conn_params line is ignored`(): Unit = withTempConf { conf ->
        conf.writeText("ble.conn_params = 500,520,0,6000\nble.conn_params_fast = off\nweather.interval = 45\n")
        assertEquals(45L, StoandlConfig.load(conf, logResult = false).weatherIntervalMinutes)
        listOf("ble.conn_params", "ble.conn_params_fast").forEach { key ->
            assertNull(guiConfigField(key))
            assertTrue(applyGuiConfig(key, "500,520,0,6000", conf).startsWith("notfound:"))
        }
    }

    @Test
    fun `unknown keys and invalid values are rejected, not written`(): Unit = withTempConf { conf ->
        assertTrue(applyGuiConfig("no.such.key", "true", conf).startsWith("notfound:"))
        assertTrue(applyGuiConfig("weather.interval", "0", conf).startsWith("error:"))
        assertTrue(!conf.isFile || "weather.interval" !in conf.readText())
    }

    /** A rejected write must leave the file — and every other key — exactly as it was. */
    @Test
    fun `a rejected write does not touch the file`(): Unit = withTempConf { conf ->
        conf.writeText("music.volume = player  # keep me\nweather.pins = false\n")
        val before = conf.readText()
        assertTrue(applyGuiConfig("weather.locations", "Nowhere", conf).startsWith("error:"))
        assertEquals(before, conf.readText())
    }

    /** ConfFile keeps the inline comment when it rewrites a line — worth pinning, since the config
     *  file is hand-edited as often as it is GUI-edited. */
    @Test
    fun `an existing key keeps its inline comment when rewritten`(): Unit = withTempConf { conf ->
        conf.writeText("# header\nmusic.volume = system  # my note\n")
        assertTrue(applyGuiConfig("music.volume", "Player", conf).startsWith("ok:"))
        val text = conf.readText()
        assertContains(text, "music.volume = player")
        assertContains(text, "# my note")
        assertContains(text, "# header")
    }

    // --- helpers ------------------------------------------------------------------------------

    /** A value DIFFERENT from [current] that the field accepts, or null if it has no alternative. */
    private fun flip(f: ConfigField, current: String): String? = when (f.type) {
        "toggle" -> if (current == "true") "false" else "true"
        "combo" -> f.choices.map { it.label }.firstOrNull { it != current }
        "int" -> {
            val n = current.toIntOrNull() ?: f.min ?: 1
            val lo = f.min ?: Int.MIN_VALUE
            val hi = f.max ?: Int.MAX_VALUE
            (if (n < hi) n + 1 else n - 1).coerceIn(lo, hi).toString().takeIf { it != current }
        }
        // A secret never goes through stoandl.conf (the daemon stores it in the secret store).
        "secret" -> null
        // Structured text/list fields need a value their own validator accepts.
        else -> when (f.key) {
            "weather.locations" -> "Testville:1.5:2.5"
            "voice.url" -> "http://127.0.0.1:8080/v1/audio/transcriptions"
            "tts.url" -> "http://127.0.0.1:8000/v1/audio/speech"
            "voice.language" -> "de"
            "firmware.github_repo" -> "example/fork"
            "firmware.cohorts_url" -> "https://example.invalid"
            else -> if (current == "zz-test") null else "zz-test"
        }
    }

    private fun withTempConf(block: (File) -> Unit) {
        val dir = File.createTempFile("stoandl-conf-test", "").let { it.delete(); it.mkdirs(); it }
        try {
            block(File(dir, "stoandl.conf"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parse never returns a token containing framing characters`() {
        // Belt-and-braces over the whole schema: whatever a caller submits, the token written to
        // stoandl.conf must be a single line with no comment marker.
        val hostile = listOf("a#b", "a\nb", "a\r\nb", "\t", "x".repeat(300))
        GUI_CONFIG_FIELDS.forEach { f ->
            hostile.forEach { h ->
                when (val r = f.parse(h)) {
                    is ConfigParse.Err -> Unit
                    is ConfigParse.Ok -> {
                        // A secret never reaches stoandl.conf (applyGuiConfig refuses it), so '#' is fine there.
                        if (f.type != "secret") assertTrue('#' !in r.token, "${f.key}: accepted '$h' → token has '#'")
                        assertTrue('\n' !in r.token && '\r' !in r.token, "${f.key}: accepted '$h' → token has a newline")
                    }
                }
            }
        }
    }

    @Test
    fun `guiConfigField finds every field and nothing else`() {
        GUI_CONFIG_FIELDS.forEach { assertNotNull(guiConfigField(it.key), it.key) }
        assertNull(guiConfigField("weather"))
        assertNull(guiConfigField(""))
    }

    @Test
    fun `reminder vibe offers and takes the custom vibrations`() {
        val f = field("calendar.reminder_vibe")
        err(f, "Work")
        val saved = configEnvironment
        configEnvironment = saved.copy(customVibeNames = { listOf("Work", "a,b") })
        try {
            assertEquals("Work", ok(f, "work"))
            assertEquals("a,b", ok(f, "ab"))
            assertEquals("Pulses", ok(f, "pulses"))
            assertTrue(f.options.split(",").containsAll(listOf("Work", "ab")))
            assertEquals("Work", f.value(defaults().copy(calendarReminderVibe = "work")))
        } finally {
            configEnvironment = saved
        }
    }

    @Test
    fun `speech model and voice names reach the options column without commas`() {
        val saved = configEnvironment
        configEnvironment = saved.copy(installedModels = { kind ->
            SPEECH_MODELS.filter { it.kind == kind }.map { it.id to it.name } +
                if (kind == ModelKind.TTS) listOf(ESPEAK_VOICE to ESPEAK_VOICE_NAME) else emptyList()
        })
        try {
            listOf("voice.local_model", "tts.local_voice").forEach { key ->
                val f = field(key)
                f.choices.forEach { assertTrue(',' !in it.label, "$key: option '${it.label}' splits the CSV") }
                assertEquals(f.choices.size, f.options.split(",").size, key)
                // Every option is selectable by the label the GUI sends back.
                f.choices.forEach { assertEquals(it.token, ok(f, it.label)) }
            }
        } finally {
            configEnvironment = saved
        }
    }

    @Test
    fun `a speech model combo reports none chosen rather than its first option`() {
        val saved = configEnvironment
        configEnvironment = saved.copy(installedModels = { kind ->
            if (kind == ModelKind.STT) listOf("parakeet-v3" to "Parakeet v3") else listOf("piper-x" to "X")
        })
        try {
            assertEquals("", field("voice.local_model").value(defaults().copy(voiceLocalModel = "")))
            assertEquals("", field("tts.local_voice").value(defaults().copy(ttsLocalVoice = "removed-voice")))
            assertEquals("Parakeet v3", field("voice.local_model").value(defaults().copy(voiceLocalModel = "parakeet-v3")))
        } finally {
            configEnvironment = saved
        }
    }

    // --- page layout and texts (S2) -------------------------------------------------------------

    /** The GUI renders a page from consecutive rows; a row out of place would start a second copy of its
     *  page or section. */
    @Test
    fun `pages and their sections are contiguous`() {
        fun assertRuns(what: String, keys: List<String>) {
            val runs = keys.fold(mutableListOf<String>()) { acc, k -> if (acc.lastOrNull() != k) acc.add(k); acc }
            assertEquals(runs.distinct(), runs, "$what interleaved: $runs")
        }
        assertRuns("pages", GUI_CONFIG_FIELDS.map { it.page })
        assertRuns("sections", GUI_CONFIG_FIELDS.map { "${it.page} › ${it.group}" })
    }

    /** An older GUI lists every section on one page and merges same-named ones, so a title must be unique
     *  across pages; an `Advanced` section is a page's last one and named after it. */
    @Test
    fun `section titles are unique and Advanced comes last on its page`() {
        val pagesOf = GUI_CONFIG_FIELDS.groupBy({ it.group }, { it.page }).mapValues { it.value.distinct() }
        assertTrue(pagesOf.all { it.value.size == 1 }, "section titles shared by pages: ${pagesOf.filterValues { it.size > 1 }}")
        GUI_CONFIG_FIELDS.groupBy { it.page }.forEach { (page, rows) ->
            val advanced = rows.filter { it.group.startsWith("Advanced") }
            if (advanced.isEmpty()) return@forEach
            assertTrue(advanced.all { it.group == "Advanced ${page.lowercase()}" }, "$page: ${advanced.map { it.group }}")
            assertEquals(advanced, rows.takeLast(advanced.size), "$page: the Advanced section is not last")
        }
    }

    @Test
    fun `every subtitle is one short sentence and the help never repeats it`() {
        GUI_CONFIG_FIELDS.forEach { f ->
            assertTrue(f.desc.isNotBlank() && f.desc.length <= 90, "${f.key}: desc has ${f.desc.length} chars")
            assertTrue(". " !in f.desc && !f.desc.endsWith("."), "${f.key}: desc is more than one sentence: ${f.desc}")
            assertTrue(f.help.isNotBlank(), "${f.key}: no help")
            assertTrue(!f.help.startsWith(f.desc, ignoreCase = true), "${f.key}: help repeats desc")
        }
    }

    /** A `requires` clause names a real key and a value it can have, as GetConfig spells it. */
    @Test
    fun `requires clauses name real keys and values`() {
        GUI_CONFIG_FIELDS.filter { it.requires.isNotEmpty() }.forEach { f ->
            requirements(f).forEach { (key, _, value) ->
                val other = guiConfigField(key) ?: fail("${f.key}: requires unknown key '$key'")
                assertTrue(other !== f, "${f.key}: requires itself")
                when {
                    other.type == "toggle" -> assertTrue(value == "true" || value == "false", "${f.key}: '$value' for a toggle")
                    other.type == "combo" && other.choicesOf == null ->
                        assertTrue(other.choices.any { it.label == value }, "${f.key}: '$value' is not an option of $key")
                    other.type == "combo" -> Unit // runtime options (models, vibrations): nothing fixed to check
                    else -> fail("${f.key}: requires a ${other.type} key ($key)")
                }
            }
        }
    }

    /**
     * Every web call is opt-in and off by default: a row that names an egress host is off in an empty
     * `stoandl.conf`, or only matters under a requirement that is. The two exceptions are masters that are
     * on but contact nothing until a place or a calendar source is added.
     */
    @Test
    fun `keys that contact a host are off by default`() {
        val d = StoandlConfig.defaults()
        val inertMasters = setOf("weather.enabled", "calendar.enabled")
        fun off(f: ConfigField): Boolean = when (f.type) {
            "toggle" -> f.value(d) == "false"
            "combo" -> f.choices.firstOrNull { it.selected(d) }?.token == "off"
            else -> f.value(d).isEmpty()
        }
        fun requirementMet(f: ConfigField): Boolean = f.requires.isEmpty() || requirements(f).any { (key, negated, value) ->
            (guiConfigField(key)!!.value(d) == value) != negated
        }
        GUI_CONFIG_FIELDS.filter { it.egress.isNotEmpty() && it.key !in inertMasters }.forEach { f ->
            assertTrue(off(f) || !requirementMet(f), "${f.key} contacts ${f.egress} with the default config")
        }
        inertMasters.forEach { assertEquals("true", field(it).value(d)) }
    }

    /** `SetSyncEnabled` is an alias: each sync service names the row that switches it. */
    @Test
    fun `every sync service names a schema row`() {
        assertEquals(setOf("notifications", "weather", "calendar", "music", "health", "dnd"), SYNC_SERVICE_KEYS.keys)
        SYNC_SERVICE_KEYS.forEach { (service, key) ->
            val f = field(key)
            assertEquals(if (service == "dnd") "combo" else "toggle", f.type, key)
        }
    }

    @Test
    fun `tts keys parse with their defaults and bounds`(): Unit = withTempConf { conf ->
        val d = StoandlConfig.load(conf, logResult = false)
        assertEquals(StoandlConfig.VoiceBackend.OFF, d.ttsBackend)
        assertEquals(100, d.ttsSpeed)
        assertEquals("", d.ttsLocalVoice)
        conf.writeText("tts.backend = http\ntts.speed = 400\ntts.url = http://box:8000/v1/audio/speech\ntts.http_voice = alloy\n")
        val c = StoandlConfig.load(conf, logResult = false)
        assertEquals(StoandlConfig.VoiceBackend.HTTP, c.ttsBackend)
        assertEquals(200, c.ttsSpeed)
        assertEquals("alloy", c.ttsHttpVoice)
        assertEquals("HTTP service", field("tts.backend").value(c))
        // The engine lists are one helper: the same labels for speech to text and text to speech.
        assertEquals(field("voice.backend").options, field("tts.backend").options)
        assertContains(err(field("tts.speed"), "20"), "at least 50")
    }

    // --- shipped files ----------------------------------------------------------------------------

    /**
     * S7: `gui/tools/config-schema.tsv` is the mock daemon's schema (and its GetConfig defaults), so the GUI
     * is tested against the real contract. It is rendered with no speech models, voices or custom
     * vibrations and a fixed home, so it doesn't vary per machine.
     */
    @Test
    fun `the gui mock's schema fixture is current`() {
        val savedEnv = configEnvironment
        val savedHome = System.getProperty("user.home")
        val rendered = try {
            configEnvironment = ConfigEnvironment(installedModels = { emptyList() }, customVibeNames = { emptyList() })
            System.setProperty("user.home", "/home/user")
            GUI_CONFIG_FIELDS.joinToString("") { it.schemaRow() + "\n" }
        } finally {
            configEnvironment = savedEnv
            System.setProperty("user.home", savedHome)
        }
        assertGuiFixture("config-schema.tsv", rendered)
    }

    /** conf.example shows every real setting uncommented at its default (only templates are commented). */
    @Test
    fun `conf_example matches the defaults`() {
        val example = File("packaging/stoandl.conf.example")
        assertEquals(StoandlConfig.defaults(), StoandlConfig.load(example, logResult = false),
            "an uncommented line in ${example.path} differs from the default")
        val uncommented = example.readLines().map { it.substringBefore('#').substringBefore('=').trim() }.toSet()
        // A runtime combo's default is a placeholder ("No model installed"), not a value one writes down.
        GUI_CONFIG_FIELDS.filter { it.type != "secret" && it.choicesOf == null && it.defaultValue().isNotEmpty() }.forEach { f ->
            assertTrue(f.key in uncommented, "${f.key} is missing from ${example.path} (uncommented, at its default)")
        }
    }

    /** One `requires` alternative: key, whether it's `!=`, value. */
    private fun requirements(f: ConfigField): List<Triple<String, Boolean, String>> = f.requires.split('|').map { alt ->
        val negated = "!=" in alt
        val (key, value) = alt.split(if (negated) "!=" else "=", limit = 2).also {
            assertEquals(2, it.size, "${f.key}: malformed requires '${f.requires}'")
        }
        Triple(key, negated, value)
    }

    private companion object {
        const val SCHEMA_COLUMNS = 16
    }
}
