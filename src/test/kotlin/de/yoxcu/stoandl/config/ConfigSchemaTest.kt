package de.yoxcu.stoandl.config

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
        assertEquals("player", ok(f, "Player"))
        assertEquals("player", ok(f, "player"))
        assertEquals("player", ok(f, "PLAYER"))
        assertEquals("system", ok(f, "System"))
        // The message names the valid options, since that is what a CLI/GUI shows the user.
        val msg = err(f, "headphones")
        assertContains(msg, "System")
        assertContains(msg, "Player")
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

    @Test
    fun `connection parameters are validated with the decoder that reads them back`() {
        val f = field("ble.conn_params")
        assertEquals("500,520,0,6000", ok(f, "500,520,0,6000"))
        assertEquals("7.5,15,0,2000", ok(f, " 7.5,15,0,2000 "))
        // Empty and `off` both mean "the phone manages the parameters" (upstream behaviour).
        assertEquals("", ok(f, ""))
        assertEquals("off", ok(f, "off"))
        assertContains(err(f, "500,520,0"), "min_ms,max_ms,latency,supervision_ms")
        assertContains(err(f, "slow"), "min_ms,max_ms,latency,supervision_ms")
        // Well-formed but refused by BleConnParamSet.validate(): load() would log and drop these.
        assertContains(err(f, "500,400,0,6000"), "max interval")
        assertContains(err(f, "500,520,0,1000"), "supervision")
        assertContains(err(field("ble.conn_params_fast"), "5,15,0,2000"), "min interval")
        // toDoubleOrNull() parses NaN, which passes every range check and then fails every connect.
        assertContains(err(f, "NaN,520,0,6000"), "numbers")
        assertContains(err(f, "500,NaN,0,6000"), "numbers")
    }

    /** The conn-params value is rendered from the decoded set, so it must come back in the form written —
     *  `500`, not `500.0` — or the GUI would show a value the user never typed. */
    @Test
    fun `connection parameters read back the way they were written`(): Unit = withTempConf { conf ->
        val f = field("ble.conn_params")
        listOf("500,520,0,6000", "7.5,15,0,2000").forEach { v ->
            assertTrue(applyGuiConfig(f.key, v, conf).startsWith("ok:"))
            assertEquals(v, f.value(StoandlConfig.load(conf, logResult = false)))
        }
        assertTrue(applyGuiConfig(f.key, "off", conf).startsWith("ok:"))
        assertEquals("", f.value(StoandlConfig.load(conf, logResult = false)))
    }

    // --- wire format --------------------------------------------------------------------------

    @Test
    fun `schema rows carry the eleven columns in the documented order`() {
        val f = field("weather.interval")
        val cols = f.schemaRow().split('\t')
        assertEquals(11, cols.size, "GetConfigSchema column count changed — update both front-ends")
        assertEquals(
            listOf("weather.interval", "int", "Refresh interval", "", f.desc, "Weather", "live", "5", "1440", "min", ""),
            cols,
        )
        // The first five columns are the original contract an older client reads positionally.
        val combo = field("music.volume")
        val c = combo.schemaRow().split('\t')
        assertEquals(listOf("music.volume", "combo", "Volume buttons", "System,Player"), c.take(4))
    }

    @Test
    fun `no schema text can corrupt the tab-separated record framing`() {
        GUI_CONFIG_FIELDS.forEach { f ->
            val row = f.schemaRow()
            assertEquals(11, row.split('\t').size, "${f.key}: a label/desc/group contains a tab")
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
                    f.choices.any { it.label == v },
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
        assertTrue(restartKeys.isNotEmpty(), "expected some restart-required keys")
        restartKeys.forEach { f ->
            val r = applyGuiConfig(f.key, f.parse(f.value(defaults())).let { (it as ConfigParse.Ok).token }, conf)
            assertContains(r, "restart stoandl to apply", message = "${f.key}: no restart hint in '$r'")
        }
        // ...and a live key must NOT carry the hint.
        val live = field("weather.pins")
        assertTrue(!applyGuiConfig(live.key, "true", conf).contains("restart"))
    }

    /** `weather.units` was dropped (the watch's own units decide): a line left in a user's file must
     *  still load, and the key must not come back as a GUI control. */
    @Test
    fun `canned replies default to the firmware list, also when set empty`(): Unit = withTempConf { conf ->
        assertEquals(DEFAULT_CANNED_REPLIES, StoandlConfig.load(conf, logResult = false).notificationCannedReplies)
        conf.writeText("notification.canned_replies =\n")
        assertEquals(DEFAULT_CANNED_REPLIES, StoandlConfig.load(conf, logResult = false).notificationCannedReplies)
        conf.writeText("notification.canned_replies = On my way, Later ,\n")
        assertEquals(listOf("On my way", "Later"), StoandlConfig.load(conf, logResult = false).notificationCannedReplies)
    }

    @Test
    fun `a leftover weather_units line is ignored`(): Unit = withTempConf { conf ->
        conf.writeText("weather.units = imperial\nweather.interval = 45\n")
        assertEquals(45L, StoandlConfig.load(conf, logResult = false).weatherIntervalMinutes)
        assertNull(guiConfigField("weather.units"))
        assertTrue(applyGuiConfig("weather.units", "Imperial", conf).startsWith("notfound:"))
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
        // Structured text/list fields need a value their own validator accepts.
        else -> when (f.key) {
            "weather.locations" -> "Testville:1.5:2.5"
            "firmware.github_repo" -> "example/fork"
            "firmware.cohorts_url" -> "https://example.invalid"
            "ble.conn_params" -> "500,520,0,6000"
            "ble.conn_params_fast" -> "15,15,0,6000"
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
                        assertTrue('#' !in r.token, "${f.key}: accepted '$h' → token has '#'")
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
}
