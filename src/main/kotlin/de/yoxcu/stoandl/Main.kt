package de.yoxcu.stoandl

import co.touchlab.kermit.Logger
import de.yoxcu.stoandl.battery.HeartbeatLayouts
import de.yoxcu.stoandl.imaging.ImageDecoder
import de.yoxcu.stoandl.dbus.NotificationEvent
import de.yoxcu.stoandl.dbus.STOANDL_BUS_NAME
import de.yoxcu.stoandl.dbus.STOANDL_INSTANCE_NAME
import de.yoxcu.stoandl.dbus.STOANDL_OBJECT_PATH
import de.yoxcu.stoandl.dbus.StoandlControl
import de.yoxcu.stoandl.dbus.monitorNotifications
import de.yoxcu.stoandl.pebble.PebbleIntegration
import de.yoxcu.stoandl.pebble.WatchTasks
import de.yoxcu.stoandl.util.openServiceBus
import de.yoxcu.stoandl.util.openSessionBus
import de.yoxcu.stoandl.util.softOpenSessionBus
import de.yoxcu.stoandl.voice.ESPEAK_VOICE
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import de.yoxcu.stoandl.calendar.ICalParser
import de.yoxcu.stoandl.ext.configFields
import de.yoxcu.stoandl.ext.extConfigPayload
import de.yoxcu.stoandl.ext.extConfigTable
import de.yoxcu.stoandl.ext.extConfigValue
import de.yoxcu.stoandl.firmware.FirmwareControl
import de.yoxcu.stoandl.language.LanguagePackCatalog
import de.yoxcu.stoandl.notification.AppNamePlacement
import io.rebble.libpebblecommon.notification.DefaultVibePattern
import io.rebble.libpebblecommon.timeline.TimelineColor
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.messages.MethodCall
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

// Lazy: initialising it would configure logback, and main() first picks the CLI's configuration.
private val log by lazy { KotlinLogging.logger("de.yoxcu.stoandl.Main") }

/** How long the CLI waits for the daemon's answer. Most methods answer at once; the verbs that wait on
 *  the watch or the network pass [CTL_SLOW_REPLY_TIMEOUT_MS] (the daemon bounds each of those itself). */
private const val CTL_REPLY_TIMEOUT_MS = 15_000L
private const val CTL_SLOW_REPLY_TIMEOUT_MS = 120_000L

/** CLI exit codes, listed in `stoandl --help`: 0 = done, 1 = failed, 2 = usage, 3 = daemon unreachable. */
internal const val EXIT_FAILURE = 1
internal const val EXIT_USAGE = 2
internal const val EXIT_UNREACHABLE = 3

private val CTL_COMMANDS = setOf("status", "watch", "apps", "settings", "notif", "ext", "weather", "calendar", "health", "datalog", "firmware", "language", "screenshot", "logs", "support", "reset", "developer", "sync", "backup", "restore", "config", "daemon", "fakecall", "voice", "plugins", "perms")

private const val LOGBACK_CONFIG_PROPERTY = "logback.configurationFile"

private val HELP_FLAGS = setOf("help", "--help", "-h")
private val VERSION_FLAGS = setOf("version", "--version", "-v")

fun main(args: Array<String>) {
    // Image decoding (javax.imageio) must never reach for a display, even in a desktop session.
    System.setProperty("java.awt.headless", "true")
    // Any argument means a CLI call: log to stderr only (logback-cli.xml), never to the daemon's log file.
    // Before the first logger; an explicit -Dlogback.configurationFile still wins.
    if (args.isNotEmpty() && System.getProperty(LOGBACK_CONFIG_PROPERTY) == null) {
        System.setProperty(LOGBACK_CONFIG_PROPERTY, "logback-cli.xml")
    }
    if (args.isNotEmpty() && args[0] in HELP_FLAGS) {
        printUsage()
        return
    }
    if (args.isNotEmpty() && args[0] in VERSION_FLAGS) {
        printVersion()
        return
    }
    if (args.isNotEmpty() && (args[0] == "ctl" || args[0] in CTL_COMMANDS)) {
        ctl(if (args[0] == "ctl") args.drop(1).toTypedArray() else args)
        return
    }
    // A non-empty arg that isn't a known command/flag is a typo — don't silently start the daemon.
    if (args.isNotEmpty()) {
        System.err.println("Unknown command: ${args.joinToString(" ")}\n")
        printUsage()
        System.exit(EXIT_USAGE); return
    }

    Logger.setLogWriters(KermitSlf4jWriter())
    log.info { "stoandl ${BuildInfo.version} starting" }
    log.info { ImageDecoder.describe() }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val notificationBus = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 64)

    val serviceConn = openServiceBus()
    // The private guard name first: a second daemon on this bus fails here, before it touches anything
    // shared. The public name comes last (end of pebble.init()), once Control answers.
    serviceConn.requestBusName(STOANDL_INSTANCE_NAME)

    // Before anything opens libpebble3.db (pebble.init() → Koin): musl __isnan shim + a stable copy of
    // the SQLite JNI library. After the guard name, so only one daemon at a time manages that cache.
    SqliteNative.prepare()

    val pebble = PebbleIntegration(notificationBus, scope, serviceConn)
    pebble.init()

    // Start DBus notification monitor and feed into libpebble3
    monitorNotifications()
        .onEach { event ->
            notificationBus.emit(event)
        }
        .launchIn(scope)

    log.info { "stoandl running — press Ctrl-C to stop" }

    runBlocking {
        // Keep the process alive until interrupted
        val latch = java.util.concurrent.CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info { "stoandl shutting down" }
            pebble.gracefulShutdown()
            runCatching { serviceConn.releaseBusName(STOANDL_BUS_NAME) }
            serviceConn.releaseBusName(STOANDL_INSTANCE_NAME)
            serviceConn.disconnect()
            latch.countDown()
        })
        latch.await()
    }
}

private fun printUsage() {
    // Compact, phone-narrow help: one line per command group; the verb list is the description. Run a
    // group with no verb (e.g. `stoandl watch`) for its own usage. Keep every line short (~46 cols).
    fun row(name: String, rest: String) = println("  " + name.padEnd(9) + " " + rest)
    println("stoandl — headless Pebble companion (Linux)")
    println()
    println("  stoandl <command> [args]")
    println()
    row("status", "the daemon, watch and services at a glance")
    println("Watch & apps")
    row("watch", "list connect disconnect pair")
    row("", "unpair repair")
    row("", "battery [history|insights|activity|")
    row("", "         power|heartbeat] · find")
    row("apps", "list launch install remove order")
    row("settings", "list · set <id> <value> · reset")
    row("notif", "list mute unmute style vibe-test filter")
    row("ext", "list install enable disable")
    row("", "restart uninstall config")
    row("config", "[app]   PKJS config page")
    row("daemon", "list · get/set <key> [value]")
    row("", "restart")
    println("Sync")
    row("sync", "list · enable/disable <service>")
    row("weather", "[sync] · search <city> [--add [n]]")
    row("calendar", "list sources sync enable disable add remove passwd dump")
    row("health", "[days] hr sync activities dump")
    row("datalog", "list dump tail")
    println("Maintenance")
    row("firmware", "sideload check update status")
    row("language", "list install sideload status")
    row("screenshot", "[path]")
    row("voice", "status · models · download · use · test · say · stop")
    row("plugins", "list · read · invoke")
    row("perms", "pending · list · allow · deny · reset · later")
    row("logs", "[path]")
    row("support", "[out.tar.gz]")
    row("reset", "recovery · factory")
    row("backup", "[out.tar.gz]")
    row("restore", "<in.tar.gz> [--force]")
    row("developer", "start · stop · status")
    println("Other")
    row("fakecall", "ring · end   (debug)")
    row("version", "· help")
    println()
    println("Run a group with no verb for its options")
    println("(e.g. `stoandl watch` lists its verbs).")
    println()
    println("Exit status: 0 done · 1 failed")
    println("2 usage error · 3 daemon not reachable")
}

/** Report the running daemon's version (over D-Bus), falling back to this CLI's own embedded version. */
private fun printVersion() {
    val cliVersion = BuildInfo.version
    val conn = softOpenSessionBus() ?: run {
        println("stoandl $cliVersion (couldn't reach D-Bus to query the service)")
        return
    }
    try {
        val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
        val daemon = try { control.Version() } catch (e: Exception) { null }
        when {
            daemon == null -> println("stoandl $cliVersion (daemon not running)")
            daemon == cliVersion -> println("stoandl $daemon")
            else -> println("stoandl $cliVersion (this CLI) · service running $daemon — restart it to match")
        }
    } finally {
        conn.disconnect()
    }
}

private fun ctl(args: Array<String>) {
    // The calls that don't go through withControl (version, language list, support) too.
    MethodCall.setDefaultTimeout(CTL_REPLY_TIMEOUT_MS)
    if (args.isEmpty()) {
        printUsage()
        return
    }
    when (args[0]) {
        "apps" -> ctlApps(args.drop(1))
        "firmware" -> ctlFirmware(args.drop(1))
        "language" -> ctlLanguage(args.drop(1))
        "notif" -> ctlNotif(args.drop(1))
        "ext" -> ctlExt(args.drop(1))
        "backup" -> {
            val out = args.drop(1).firstOrNull { !it.startsWith("-") }
            doBackup(out)
        }
        "restore" -> {
            val rest = args.drop(1)
            val force = rest.any { it == "--force" || it == "-f" }
            val path = rest.firstOrNull { !it.startsWith("-") }
            if (path == null) {
                System.err.println("Usage: stoandl restore <in.tar.gz> [--force]"); System.exit(EXIT_USAGE); return
            }
            doRestore(path, force)
        }
        "config" -> {
            val app = args.getOrNull(1) ?: ""
            withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
                val configUrl = control.OpenConfig(app)
                if (configUrl.isEmpty()) {
                    System.err.println("No config page available (app not found, not configurable, or it didn't start in time)")
                    System.exit(1)
                    return
                }
                // The daemon serves the page and hands Save back to the app; we only open it.
                println("Config URL: $configUrl")
                try { ProcessBuilder("xdg-open", configUrl).start() } catch (_: Exception) {}
                println("Press Save on the page to send the settings to the watch.")
            }
        }
        "daemon" -> ctlDaemon(args.drop(1))
        "fakecall" -> {
            val sub = args.getOrNull(1) ?: "ring"
            withControl { control ->
                when (sub) {
                    "ring" -> {
                        val name = args.getOrNull(2) ?: "Test Caller"
                        val number = args.getOrNull(3) ?: "+15551234567"
                        if (control.FakeCallRing(name, number)) println("Ringing watch: $name <$number>")
                        else { System.err.println("Daemon not ready (no watch connected?)"); System.exit(1) }
                    }
                    "end" -> {
                        control.FakeCallEnd()
                        println("Call ended")
                    }
                    else -> {
                        System.err.println("Usage: stoandl fakecall [ring [name] [number] | end]")
                        System.exit(EXIT_USAGE)
                    }
                }
            }
        }
        "watch" -> ctlWatch(args.drop(1))
        "weather" -> ctlWeather(args.drop(1))
        "settings" -> ctlSettings(args.drop(1))
        "health" -> ctlHealth(args.drop(1))
        "calendar" -> {
            when (val sub = args.getOrNull(1) ?: "list") {
                "dump" -> {
                    val src = args.getOrNull(2)
                    if (src == null) {
                        System.err.println("Usage: stoandl calendar dump <file.ics|url>"); System.exit(EXIT_USAGE); return
                    }
                    dumpCalendar(src)
                }
                "list" -> withControl { control ->
                    val cals = control.ListCalendars()
                    if (cals.isEmpty()) {
                        println("No calendars synced (configure calendar.* in stoandl.conf, or none discovered yet).")
                    } else {
                        cals.forEach { entry ->
                            val p = entry.split('\t')
                            println("  %-4s %-30s %s".format(p.getOrElse(0) { "" }, p.getOrElse(1) { entry }, p.getOrElse(2) { "" }))
                        }
                        println("\nToggle one with:  stoandl calendar disable <id|name>")
                    }
                }
                "sync" -> withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
                    handleStatusResponse(control.SyncCalendar())
                }
                "enable", "disable" -> {
                    val query = args.drop(2).joinToString(" ")
                    if (query.isBlank()) {
                        System.err.println("Usage: stoandl calendar $sub <id|name>"); System.exit(EXIT_USAGE); return
                    }
                    withControl { control ->
                        handleStatusResponse(control.SetCalendarEnabled(query, sub == "enable"))
                    }
                }
                "sources", "accounts" -> withControl { control -> printCalendarSources(control) }
                "add" -> ctlCalendarAdd(args.drop(2))
                "remove", "rm", "delete" -> {
                    val id = args.getOrNull(2)
                    if (id.isNullOrBlank()) {
                        System.err.println("Usage: stoandl calendar remove <id>   (id from `stoandl calendar sources`)")
                        System.exit(EXIT_USAGE); return
                    }
                    withControl { control ->
                        handleStatusResponse(control.RemoveCalendarSource(id))
                    }
                }
                "passwd", "password" -> ctlCalendarPasswd(args.drop(2))
                else -> {
                    System.err.println(
                        "Usage: stoandl calendar <list|sources|sync|enable <id|name>|disable <id|name>|\n" +
                            "                          add <caldav|ical|ics> <url|path> [username]|passwd <id>|remove <id>|dump <file|url>>",
                    )
                    System.exit(EXIT_USAGE)
                }
            }
        }
        "datalog" -> {
            // Reads the captured NDJSON files directly — no daemon needed (like `calendar dump`).
            when (val sub = args.getOrNull(1) ?: "list") {
                "list" -> datalogList()
                "dump", "tail" -> {
                    var n = 20
                    val pos = mutableListOf<String>()
                    var i = 2
                    while (i < args.size) {
                        val a = args[i]
                        if (a == "-n" || a == "--lines") { args.getOrNull(i + 1)?.toIntOrNull()?.let { n = it }; i += 2 }
                        else { pos.add(a); i++ }
                    }
                    if (pos.isEmpty()) {
                        System.err.println("Usage: stoandl datalog $sub <app uuid> [tag]" + if (sub == "tail") " [-n lines]" else "")
                        System.err.println("Run 'stoandl datalog list' to see captured apps and tags.")
                        System.exit(EXIT_USAGE); return
                    }
                    datalogShow(pos[0], pos.getOrNull(1), tail = if (sub == "tail") n else null)
                }
                else -> {
                    System.err.println("Usage: stoandl datalog <list | dump <uuid> [tag] | tail <uuid> [tag] [-n lines]>")
                    System.exit(EXIT_USAGE)
                }
            }
        }
        "voice" -> ctlVoice(args.drop(1))
        "plugins" -> ctlPlugins(args.drop(1))
        "perms" -> ctlPerms(args.drop(1))
        "screenshot" -> {
            // No argument: the daemon picks a new file in Pictures/Screenshots, as it does for the GUI.
            // Otherwise resolve the target against THIS process's cwd and send an absolute path (the
            // daemon's cwd differs); accept a file or a directory; tack on .png if missing.
            val arg = args.getOrNull(1)
            val path = arg?.let { resolveOutPath(it, "pebble-screenshot-${timestamp()}.png", listOf(".png")).absolutePath }.orEmpty()
            withControl { control ->
                println("Capturing watch screen…")
                val resp = awaitTask(control, "screenshot") { control.TakeScreenshot(path) }
                val (kind, body) = splitStatus(resp)
                if (kind == "ok") {
                    val f = body.split('\t')
                    val dims = if (f.size >= 3) " (${f[1]}×${f[2]})" else ""
                    println("Saved ${f[0]}$dims")
                } else {
                    handleStatusResponse(resp)
                }
            }
        }
        "logs" -> ctlLogs(args.drop(1))
        "support" -> ctlSupport(args.drop(1))
        "reset" -> ctlReset(args.drop(1))
        "developer" -> ctlDeveloper(args.drop(1))
        "sync" -> ctlSync(args.drop(1))
        "status" -> ctlStatus()
        in VERSION_FLAGS -> printVersion()
        in HELP_FLAGS -> printUsage()
        else -> {
            System.err.println("Unknown command: ${args[0]}")
            System.err.println()
            printUsage()
            System.exit(EXIT_USAGE)
        }
    }
}

/** Parse a `status:message` response from a control method and exit non-zero on failure. */
private fun handleStatusResponse(resp: String) {
    val (status, message) = splitStatus(resp)
    if (status == "ok") {
        println(message)
    } else {
        System.err.println(message.ifEmpty { status })
        System.exit(1)
    }
}

/** `stoandl weather [sync]` pushes weather now; `weather search <city> [--add [n]] [--as <label>]` looks
 *  the city up (Open-Meteo, gated by weather.place_search) and with `--add` stores result n (default 1). */
private fun ctlWeather(rest: List<String>) {
    when (rest.firstOrNull() ?: "sync") {
        "sync" -> withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
            val resp = control.SyncWeather()
            handleStatusResponse(resp)
        }
        "search" -> {
            val opts = parseWeatherSearchArgs(rest.drop(1)) ?: run {
                System.err.println("Usage: stoandl weather search <city> [--add [n]] [--as <label>]")
                System.exit(EXIT_USAGE); return
            }
            withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
                val resp = control.SearchWeatherPlace(opts.query)
                val (status, body) = splitStatus(resp)
                if (status != "ok") { System.err.println(body.ifEmpty { status }); System.exit(1); return }
                val places = body.lines().map { it.split('\t') }.filter { it.size >= 3 }
                val pick = opts.add
                if (pick == null) {
                    places.forEachIndexed { i, p ->
                        val where = listOf(p.getOrElse(3) { "" }, p.getOrElse(4) { "" }).filter { it.isNotEmpty() }
                        println("%2d  %s%s  (%s, %s)".format(i + 1, p[0],
                            if (where.isEmpty()) "" else ", " + where.joinToString(", "), p[1], p[2]))
                    }
                    println("Add one with: stoandl weather search ${shellQuote(opts.query)} --add <n>")
                    return@withControl
                }
                val p = places.getOrNull(pick - 1) ?: run {
                    System.err.println("No result $pick (found ${places.size})"); System.exit(1); return
                }
                val lat = p[1].toDoubleOrNull()
                val lon = p[2].toDoubleOrNull()
                if (lat == null || lon == null) { System.err.println("Bad coordinates in result $pick"); System.exit(1); return }
                val added = control.AddWeatherLocation(opts.label ?: p[0], lat, lon)
                handleStatusResponse(added)
            }
        }
        else -> {
            System.err.println("Usage: stoandl weather [sync | search <city> [--add [n]] [--as <label>]]")
            System.exit(EXIT_USAGE)
        }
    }
}

internal data class WeatherSearchArgs(val query: String, val add: Int?, val label: String?)

/** `<words…> [--add [n]] [--as <label>]`; null when the query is missing or an option is malformed. */
internal fun parseWeatherSearchArgs(args: List<String>): WeatherSearchArgs? {
    val words = mutableListOf<String>()
    var add: Int? = null
    var label: String? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--add" -> {
                val n = args.getOrNull(i + 1)?.toIntOrNull()
                if (n != null) { if (n < 1) return null; add = n; i++ } else add = 1
            }
            "--as" -> { label = args.getOrNull(i + 1)?.takeIf { it.isNotBlank() } ?: return null; i++ }
            else -> words += a
        }
        i++
    }
    val query = words.joinToString(" ").trim()
    return if (query.isEmpty()) null else WeatherSearchArgs(query, add, label)
}

private fun shellQuote(s: String): String = if (s.all { it.isLetterOrDigit() || it in "-_." }) s else "'" + s.replace("'", "'\\''") + "'"

/** Print the editable calendar sources (CalDAV accounts / iCal feeds / .ics paths) with their ids — the
 *  handle for `calendar add|remove|passwd`. The password is never shown (write-only). */
private fun printCalendarSources(control: StoandlControl) {
    val rows = control.ListCalendarSources()
    if (rows.isEmpty()) {
        println("No calendar sources configured.")
        println("Add one with:  stoandl calendar add <caldav|ical|ics> <url|path> [username]")
        return
    }
    rows.forEach { row ->
        val p = row.split('\t')
        val id = p.getOrElse(0) { "" }; val type = p.getOrElse(1) { "" }; val url = p.getOrElse(2) { "" }
        val user = p.getOrElse(3) { "" }; val label = p.getOrElse(4) { "" }
        println("  %-7s %-24s %s".format(type, label, url) + if (user.isNotEmpty()) "  (user: $user)" else "")
        println("          id: $id")
        // Columns 5 and 6: why the last read failed, and when the last good one was (epoch seconds).
        p.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { println("          problem: $it") }
        p.getOrNull(6)?.toLongOrNull()?.let { println("          last sync: ${de.yoxcu.stoandl.pebble.relAge(it)}") }
    }
    println("\nEdit credentials:  stoandl calendar passwd <id>   ·   remove:  stoandl calendar remove <id>")
}

/** `stoandl calendar add <caldav|ical|ics> <url|path> [username]` — prompts (no echo) for a CalDAV
 *  password and routes it to the secret store; iCal/.ics take no credentials. */
private fun ctlCalendarAdd(rest: List<String>) {
    val type = rest.getOrNull(0)?.lowercase()
    val target = rest.getOrNull(1)
    if (type == null || target == null || type !in setOf("caldav", "ical", "ics")) {
        System.err.println("Usage: stoandl calendar add <caldav|ical|ics> <url|path> [username]"); System.exit(EXIT_USAGE); return
    }
    val username = rest.getOrNull(2) ?: ""
    val password = if (type == "caldav") promptPassword("CalDAV password (leave blank for none): ") else ""
    withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
        val resp = control.AddCalendarSource(type, target, username, password)
        val (status, message) = splitStatus(resp)
        if (status != "ok") { System.err.println(message.ifEmpty { status }); System.exit(1); return }
        val parts = message.split('\t')
        println("Added $type source (${parts.getOrElse(0) { "" }})" + secretNote(parts.getOrElse(1) { "none" }))
        println("Syncing… run `stoandl calendar list` shortly to see its calendars.")
    }
}

/** `stoandl calendar passwd <caldav:id>` — change a CalDAV account's password (keeping its URL/user). */
private fun ctlCalendarPasswd(rest: List<String>) {
    val id = rest.getOrNull(0)
    if (id.isNullOrBlank() || !id.startsWith("caldav:")) {
        System.err.println("Usage: stoandl calendar passwd <caldav:id>   (id from `stoandl calendar sources`)")
        System.exit(EXIT_USAGE); return
    }
    val password = promptPassword("New CalDAV password: ")
    if (password.isEmpty()) { System.err.println("No password entered; aborting."); System.exit(1); return }
    withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
        val src = control.ListCalendarSources().map { it.split('\t') }.firstOrNull { it.getOrNull(0) == id }
        if (src == null) { System.err.println("No CalDAV account with id $id"); System.exit(1); return }
        val resp = control.UpdateCalendarSource(id, src.getOrElse(2) { "" }, src.getOrElse(3) { "" }, password)
        val (status, message) = splitStatus(resp)
        if (status != "ok") { System.err.println(message.ifEmpty { status }); System.exit(1); return }
        println("Password updated for $id" + secretNote(message.split('\t').getOrElse(1) { "none" }))
    }
}

private fun secretNote(backend: String): String = when (backend) {
    "keyring" -> " — password saved to the system keyring."
    "file" -> " — keyring unavailable; password saved to the local 0600 secrets file."
    else -> "."
}

/** Read a secret from the terminal without echo (falls back to a plain line when there's no console,
 *  e.g. a pipe). */
private fun promptPassword(prompt: String): String {
    val console = System.console()
    return if (console != null) String(console.readPassword(prompt))
    else { print(prompt); System.out.flush(); readLine().orEmpty() }
}

/** Offline debug aid: parse an .ics file or http(s) URL and print the events expanded into the
 *  default sync window (now-1d .. now+30d). Runs in-process — no daemon or watch needed — so it's
 *  the way to verify recurrence expansion and field mapping in the sandbox. */
private fun dumpCalendar(src: String) {
    val text = try {
        if (src.startsWith("http://") || src.startsWith("https://")) java.net.URI(src).toURL().readText()
        else File(src).readText()
    } catch (e: Exception) {
        System.err.println("Cannot read '$src': ${e.message}"); System.exit(1); return
    }
    val nowSec = java.time.Instant.now().epochSecond
    val start = kotlin.time.Instant.fromEpochSeconds(nowSec - 86_400L)
    val end = kotlin.time.Instant.fromEpochSeconds(nowSec + 30L * 86_400L)
    val events = ICalParser.parse(text, calendarId = src, start = start, end = end).sortedBy { it.startTime }
    if (events.isEmpty()) {
        println("Parsed OK but 0 occurrences in window (now-1d .. now+30d).")
        return
    }
    val zone = ZoneId.systemDefault()
    val dateTimeFmt = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm").withZone(zone)
    // All-day events start at UTC midnight of their date (the watch's all-day frame), so read the date in UTC.
    val dateFmt = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd").withZone(ZoneOffset.UTC)
    println("${events.size} occurrence(s) in window (now-1d .. now+30d):")
    events.forEach { ev ->
        val at = java.time.Instant.ofEpochSecond(ev.startTime.epochSeconds)
        val whenStr = if (ev.allDay) dateFmt.format(at) + " (all-day)" else dateTimeFmt.format(at)
        val extras = buildList {
            if (!ev.location.isNullOrBlank()) add("@${ev.location}")
            if (ev.recurs) add("recurring")
            if (ev.attendees.isNotEmpty()) add("${ev.attendees.size} attendee(s)")
            if (ev.reminders.isNotEmpty()) add("reminders: " + ev.reminders.joinToString(",") { "${it.minutesBefore}m" })
        }.joinToString("  ")
        println("  %-30s  %s%s".format(whenStr, ev.title, if (extras.isEmpty()) "" else "   ($extras)"))
    }
}

/** Where DatalogStore writes captured frames: ~/.config/stoandl/datalog/<uuid>/<tag>.ndjson. */
private fun datalogDir(): File = File(configDir(), "datalog")

/** List captured datalog sessions: one row per (app UUID, tag) file with line count, size, mtime. */
private fun datalogList() {
    val dirs = datalogDir().listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
    val rows = dirs.flatMap { sdir ->
        (sdir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") } ?: emptyArray())
            .sortedBy { it.name }
            .map { sdir.name to it }
    }
    if (rows.isEmpty()) {
        println("No datalog captured yet.")
        println("Enable it with 'datalog.enabled = true' in stoandl.conf (then restart the daemon),")
        println("and run a watchapp that logs data via the PebbleKit DataLogging API.")
        return
    }
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    println("%-38s %-10s %8s %10s  %s".format("APP UUID", "TAG", "LINES", "SIZE", "UPDATED"))
    rows.forEach { (uuid, f) ->
        val lines = f.bufferedReader().useLines { it.count() }
        val updated = fmt.format(java.time.Instant.ofEpochMilli(f.lastModified()))
        println("%-38s %-10s %8d %10s  %s".format(uuid, f.name.removeSuffix(".ndjson"), lines, humanSize(f.length()), updated))
    }
}

/** Print (or tail) the NDJSON for an app. [uuidArg] is a case-insensitive substring of the UUID dir
 *  (UUIDs are long); [tagArg] null = all tags. [tail] null = full dump, else last N lines. */
private fun datalogShow(uuidArg: String, tagArg: String?, tail: Int?) {
    val matches = datalogDir().listFiles()
        ?.filter { it.isDirectory && it.name.contains(uuidArg, ignoreCase = true) }
        ?.sortedBy { it.name } ?: emptyList()
    if (matches.isEmpty()) {
        System.err.println("No datalog session matching '$uuidArg' (try 'stoandl datalog list')"); System.exit(1); return
    }
    if (matches.size > 1) {
        System.err.println("'$uuidArg' matches multiple apps — be more specific:")
        matches.forEach { System.err.println("  ${it.name}") }
        System.exit(1); return
    }
    val sdir = matches.first()
    val files = (sdir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") } ?: emptyArray())
        .sortedBy { it.name }
        .let { all -> if (tagArg == null) all.toList() else all.filter { it.name.removeSuffix(".ndjson") == tagArg } }
    if (files.isEmpty()) {
        System.err.println(if (tagArg == null) "No data under ${sdir.name}" else "No tag '$tagArg' under ${sdir.name}")
        System.exit(1); return
    }
    files.forEach { f ->
        if (files.size > 1) println("== ${sdir.name} / ${f.name.removeSuffix(".ndjson")} ==")
        if (tail == null) f.bufferedReader().useLines { seq -> seq.forEach { println(it) } }
        else f.readLines().takeLast(tail).forEach { println(it) }
    }
}

/** Where HealthExporter writes the projected data: ~/.config/stoandl/health/. */
private fun healthDir(): File = File(configDir(), "health")

/** Dispatch `stoandl health ...`. A bare day count (default 7) prints the daily summary; `activities`
 *  lists workout sessions; `dump` prints raw NDJSON. All three read the exported files directly (no
 *  daemon needed, like `datalog`). `hr` and `sync` talk to the daemon — `hr` shows the latest stored
 *  heart-rate reading, `sync` pulls fresh data from the watch. */
private fun ctlHealth(rest: List<String>) {
    when (val sub = rest.firstOrNull()?.lowercase()) {
        null -> healthSummary(7)
        "hr" -> healthHr()
        "sync" -> healthSync()
        "profile" -> healthProfile(rest.drop(1))
        "activities" -> healthActivities(rest.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 7)
        "dump" -> {
            val which = rest.getOrNull(1)?.lowercase() ?: "daily"
            val file = when (which) {
                "daily" -> File(healthDir(), "daily.ndjson")
                "activities" -> File(healthDir(), "activities.ndjson")
                else -> { System.err.println("Usage: stoandl health dump [daily|activities]"); System.exit(EXIT_USAGE); return }
            }
            if (!file.isFile) { System.err.println("No $which export yet at ${file.path}"); return }
            file.bufferedReader().useLines { seq -> seq.forEach { println(it) } }
        }
        else -> {
            val days = sub.toIntOrNull()
            if (days != null && days > 0) healthSummary(days)
            else {
                System.err.println("Usage: stoandl health [days | hr | sync | profile [set <key> <value>] | activities [days] | dump [daily|activities]]")
                System.exit(EXIT_USAGE)
            }
        }
    }
}

/** `stoandl health profile` prints the watch's health/tracking configuration; `health profile set
 *  <key> <value>` changes one field and syncs it to the watch. This is the *write* side of health
 *  (the watch's own activity-tracking profile) — the read side is `health` / `health sync`. */
private fun healthProfile(args: List<String>) {
    if (args.firstOrNull() == "set") {
        val key = args.getOrNull(1)
        val value = args.drop(2).joinToString(" ")
        if (key.isNullOrBlank() || value.isBlank()) {
            System.err.println("Usage: stoandl health profile set <key> <value>\n" +
                "  keys: tracking, activity_insights, sleep_insights, hrm (on/off), hrm_interval (5min/10min/30min/1h/off; 5min needs fw 4.39.0+), hrm_activity (on/off),\n" +
                "        units (metric/imperial), height_cm, weight_kg, age, gender (female/male/other), resting_hr, max_hr")
            System.exit(EXIT_USAGE); return
        }
        withControl { control ->
            handleStatusResponse(control.SetHealthProfile(key, value))
        }
        return
    }
    withControl { control ->
        val records = control.GetHealthProfile()
        if (records.isEmpty()) { println("No health profile available (is a health-capable watch set up?)"); return }
        // A third field lists the values the daemon accepts for that key right now.
        val rows = records.mapNotNull { val f = it.split('\t'); if (f.size < 2) null else f }
        val w = rows.maxOf { it[0].length }
        rows.forEach { f ->
            val choices = f.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { "   ($it)" } ?: ""
            println("  ${f[0].padEnd(w)}  ${f[1]}$choices")
        }
        println("\nSet one with:  stoandl health profile set <key> <value>")
    }
}

private fun readNdjson(file: File): List<JsonObject> {
    if (!file.isFile) return emptyList()
    return file.readLines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty()) null else runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
    }
}

private fun jInt(o: JsonObject, key: String): Int? =
    (o[key]?.jsonPrimitive)?.content?.toLongOrNull()?.toInt()
private fun jLong(o: JsonObject, key: String): Long? =
    (o[key]?.jsonPrimitive)?.content?.toLongOrNull()
private fun jStr(o: JsonObject, key: String): String =
    (o[key]?.jsonPrimitive)?.content ?: "-"

private fun fmtInt(n: Int?): String = n?.let { "%,d".format(it) } ?: "-"
private fun fmtDist(meters: Int?): String = when {
    meters == null -> "-"
    meters >= 1000 -> "%.1fkm".format(meters / 1000.0)
    else -> "${meters}m"
}
private fun fmtDur(minutes: Int?): String = when {
    minutes == null -> "-"
    minutes >= 60 -> "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}m"
    else -> "${minutes}m"
}

private fun healthSummary(days: Int) {
    val rows = readNdjson(File(healthDir(), "daily.ndjson"))
    if (rows.isEmpty()) {
        println("No health data exported yet.")
        println("It syncs from the watch on connect (health.sync) and exports to ${healthDir().path} (health.export).")
        println("With the watch connected, run 'stoandl health sync' to pull now.")
        return
    }
    println("%-12s %8s %9s %7s %7s %5s %5s".format("DATE", "STEPS", "DIST", "SLEEP", "ACTIVE", "RHR", "AVGHR"))
    rows.takeLast(days).forEach { o ->
        println("%-12s %8s %9s %7s %7s %5s %5s".format(
            jStr(o, "date"),
            fmtInt(jInt(o, "steps")),
            fmtDist(jInt(o, "distance_m")),
            jInt(o, "sleep_total_min")?.let { fmtDur(it) } ?: "-",
            fmtDur(jInt(o, "active_minutes")),
            jInt(o, "resting_hr")?.toString() ?: "-",
            jInt(o, "avg_hr")?.toString() ?: "-",
        ))
    }
}

private fun healthActivities(days: Int) {
    val rows = readNdjson(File(healthDir(), "activities.ndjson"))
    val cutoff = System.currentTimeMillis() / 1000 - days.toLong() * 86_400
    val recent = rows.filter { (jLong(it, "start") ?: 0) >= cutoff }
    if (recent.isEmpty()) {
        println(if (rows.isEmpty()) "No activity sessions exported yet." else "No activity sessions in the last $days day(s).")
        return
    }
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    println("%-16s %-10s %7s %8s %9s %6s".format("WHEN", "TYPE", "DUR", "STEPS", "DIST", "KCAL"))
    recent.forEach { o ->
        println("%-16s %-10s %7s %8s %9s %6s".format(
            fmt.format(java.time.Instant.ofEpochSecond(jLong(o, "start") ?: 0)),
            jStr(o, "type"),
            fmtDur(jInt(o, "duration_min")),
            fmtInt(jInt(o, "steps")),
            fmtDist(jInt(o, "distance_m")),
            jInt(o, "active_kcal")?.toString() ?: "-",
        ))
    }
}

private fun healthSync() {
    withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
        val resp = control.SyncHealth()
        handleStatusResponse(resp)
    }
}

private fun healthHr(): Unit = withControl { control ->
    val resp = control.HeartRate()
    val (kind, body) = splitStatus(resp)
    when (kind) {
        "ok" -> {
            val f = body.split('\t')
            val bpm = f.getOrElse(0) { "?" }
            val ageSec = f.getOrNull(1)?.toLongOrNull()?.let { (System.currentTimeMillis() / 1000) - it }
            val age = when {
                ageSec == null || ageSec < 0 -> ""
                ageSec < 90 -> " (just now)"
                ageSec < 3600 -> " (${ageSec / 60} min ago)"
                ageSec < 86400 -> " (${ageSec / 3600}h ago)"
                else -> " (${ageSec / 86400}d ago)"
            }
            println("Heart rate: $bpm bpm$age")
        }
        "none" -> {
            println("No heart rate reading yet.")
            println("HR fills from the watch on connect (health.sync); run 'stoandl health sync' with the watch connected to pull now.")
        }
        else -> handleStatusResponse(resp)
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}

/** Polls PairStatus() until the pairing window resolves, printing pending messages as they change.
 *  Shared by the `pair` and `repair` commands (both open the same pairing window); [assumeYes] is
 *  their `--yes`. */
private fun pollPairStatus(control: StoandlControl, assumeYes: Boolean) {
    var lastPendingMsg = ""
    var lastConfirmCode = ""
    val startMs = System.currentTimeMillis()
    while (true) {
        Thread.sleep(1_500)
        val status = control.PairStatus()
        if (status.startsWith("confirm:")) {
            // Numeric comparison: the daemon waits for this side's answer (see confirmPairingCode). One
            // answer per code; the daemon declines on its own timeout.
            val code = status.removePrefix("confirm:")
            if (code != lastConfirmCode) {
                lastConfirmCode = code
                val accept = confirmPairingCode(code, assumeYes)
                control.ConfirmPairing(accept)
            }
        } else if (status.startsWith("pending:")) {
            // `Confirm code <digits> on the watch` means this side has already accepted and only the watch
            // has to confirm; the digits are there for the GUI to show. Don't echo a code the CLI can't act
            // on — drop the digits, keep the instruction.
            val msg = status.removePrefix("pending:")
                .replace(Regex("""Confirm code \d+ on the watch"""), "Confirm the pairing code on the watch")
            if (msg.isNotEmpty() && msg != lastPendingMsg) { println(msg); lastPendingMsg = msg }
        } else {
            handleStatusResponse(status); return
        }
        if (System.currentTimeMillis() - startMs > 145_000) {
            System.err.println("Pairing timed out"); System.exit(1); return
        }
    }
}

/** This side's answer to a numeric-comparison [code] during `watch pair`/`repair`. When stdin is a
 *  terminal the user compares it with the watch and answers y/N; an answer already piped in
 *  (`echo n | stoandl watch pair`) counts the same, and Ctrl-D at the prompt declines like any answer
 *  other than y. With `--yes` ([assumeYes]) or with nothing to read an answer from (`ssh host stoandl
 *  watch pair` without -t, `< /dev/null`) it accepts and says so, rather than declining for want of a
 *  keyboard: the same code is still confirmed on the watch, which is the MITM check — and it's what the
 *  daemon's agent does for a pairing nobody is asked about. */
private fun confirmPairingCode(code: String, assumeYes: Boolean): Boolean {
    val interactive = !assumeYes && stdinIsTerminal()
    // Not a terminal, but an answer is already waiting in the pipe or file: read it rather than guess.
    // available() never blocks; an open but empty pipe (ssh without -t) reports 0.
    val piped = !assumeYes && !interactive && runCatching { System.`in`.available() > 0 }.getOrDefault(false)
    if (interactive || piped) {
        print("Pairing code: $code — does it match the code on the watch? Accept? [y/N] ")
        System.out.flush()
        val answer = readlnOrNull()
        if (answer == null) {
            // Ctrl-D: someone at the prompt chose not to answer y.
            println()
            return false
        }
        if (piped) println(answer.trim()) // no terminal echoed it
        return answer.trim().lowercase() in setOf("y", "yes")
    }
    val why = if (assumeYes) "--yes" else "stdin is not a terminal; use ssh -t to be asked"
    println("Pairing code: $code — accepted on this side ($why). Confirm the same code on the watch.")
    return true
}

/** Whether stdin is a terminal someone can answer on. System.console() alone also needs stdout to be
 *  one (JDK 25 returns null otherwise), so `stoandl watch pair | tee pair.log` would never ask; on Linux
 *  /proc/self/fd/0 names the tty stdin reads from. */
private fun stdinIsTerminal(): Boolean =
    System.console()?.isTerminal == true || runCatching {
        val fd0 = java.nio.file.Files.readSymbolicLink(java.nio.file.Path.of("/proc/self/fd/0")).toString()
        fd0.startsWith("/dev/pts/") || fd0.startsWith("/dev/tty")
    }.getOrDefault(false)

/** Dispatch `stoandl firmware ...`: a local `.pbz` path to flash, or `check`/`update`/`status`. */
private fun ctlFirmware(rest: List<String>) {
    val sub = rest.firstOrNull()
    if (sub == null) {
        System.err.println("Usage: stoandl firmware <sideload <file.pbz> | check [--refresh] | update | status>")
        System.exit(EXIT_USAGE); return
    }
    // `firmware sideload <file>` is the explicit verb; `firmware <file>` still works (handled below).
    val target = if (sub == "sideload") (rest.getOrNull(1) ?: run {
        System.err.println("Usage: stoandl firmware sideload <file.pbz>"); System.exit(EXIT_USAGE); return
    }) else sub
    withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
        when (sub) {
            "check" -> {
                // --refresh skips the daemon's 3 h check cache (and refreshes it).
                val refresh = "--refresh" in rest.drop(1)
                val resp = if (refresh) control.CheckFirmwareForce() else control.CheckFirmware()
                printFirmwareCheck(resp)
            }
            "update" -> {
                val resp = control.UpdateFirmware()
                val (kind, body) = splitStatus(resp)
                when (kind) {
                    "ok" -> {
                        val f = body.split('\t')
                        println("Updating ${f.getOrElse(0) { "watch" }}: " +
                            "${f.getOrElse(1) { "?" }} → ${f.getOrElse(2) { "?" }} (${f.getOrElse(3) { "firmware" }})")
                        printLanguageNote(f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, f.getOrElse(2) { "" })
                        pollFirmwareStatus(control)
                    }
                    "uptodate", "noasset" -> println(body)
                    else -> handleStatusResponse(resp) // busy / disabled / notready / error
                }
            }
            "status" -> {
                val resp = control.FirmwareStatus()
                printFirmwareStatusOnce(resp)
            }
            else -> {
                // `firmware sideload <file>` or a bare `firmware <file>` — sideload a local .pbz.
                val file = File(target)
                if (!file.isFile) { System.err.println("No such file: $target"); System.exit(1); return }
                if (!file.name.endsWith(".pbz")) {
                    System.err.println("Not a firmware bundle (expected a .pbz): $target"); System.exit(1); return
                }
                // Send an absolute path: the daemon's cwd differs from the caller's.
                val resp = control.SideloadFirmware(file.absolutePath)
                if (!resp.startsWith("ok:")) { handleStatusResponse(resp); return }
                println("Flashing ${file.name} onto the watch — keep it close and charged.")
                pollFirmwareStatus(control)
            }
        }
    }
}

private fun splitStatus(resp: String): Pair<String, String> {
    val idx = resp.indexOf(':')
    return if (idx >= 0) resp.substring(0, idx) to resp.substring(idx + 1) else resp to ""
}

private fun printFirmwareCheck(resp: String) {
    val (kind, body) = splitStatus(resp)
    when (kind) {
        "ok" -> {
            val f = body.split('\t')
            val board = f.getOrElse(0) { "?" }
            val current = f.getOrElse(1) { "?" }
            val latest = f.getOrElse(2) { "?" }
            val asset = f.getOrElse(3) { "?" }
            val newer = f.getOrElse(4) { "no" }
            val source = f.getOrElse(5) { "" }
            val changelog = f.getOrElse(6) { "" }
            println("Watch board:      $board")
            println("Running:          $current")
            println("Latest available: $latest")
            if (source.isNotEmpty()) println("Source:           $source")
            if (newer == "yes") println("→ Update available ($asset). Run: stoandl firmware update")
            else println("→ Up to date.")
            if (changelog.isNotEmpty()) println("What's new:        $changelog")
            if (newer == "yes") printLanguageNote(board, current, latest)
        }
        "noasset" -> {
            val f = body.split('\t')
            val board = f.getOrElse(0) { "?" }
            val current = f.getOrElse(1) { "?" }
            val source = f.getOrElse(2) { "the configured source" }
            println("$source publishes no firmware for board '$board' (running $current).")
        }
        else -> handleStatusResponse(resp) // disabled / notready / error
    }
}

/** Warn before (and while) updating across the PebbleOS release that removed the built-in translations. */
private fun printLanguageNote(board: String, current: String, latest: String) {
    if (FirmwareControl.dropsBuiltInLanguages(board, current, latest)) println(FirmwareControl.BUILT_IN_LANGUAGES_NOTE)
}

private fun printFirmwareStatusOnce(resp: String) {
    val (kind, body) = splitStatus(resp)
    val human = when (kind) {
        "idle" -> body.removePrefix("available ").takeIf { body.startsWith("available ") }
            ?.let { "Idle — firmware $it is available (stoandl firmware update)" }
            ?: "Idle (no firmware update in progress)"
        "downloading" -> "Downloading $body…"
        "waiting" -> "Starting firmware transfer…"
        "inprogress" -> "Flashing: $body%"
        "reboot" -> "Transfer complete — watch rebooting to apply"
        "prf" -> "Downgrade to $body: the watch is rebooting into recovery; stoandl flashes it there once it reconnects"
        "failed" -> "Last firmware update failed: ${body.ifEmpty { "unknown error" }}"
        "notready" -> body.ifEmpty { "No watch connected" }
        else -> resp
    }
    println(human)
}

/** Polls FirmwareStatus() while a flash runs, rendering a progress bar in place. Treats `reboot:`
 *  (or a disconnect after activity) as success and `failed:` as failure. A downgrade (`prf:`) reboots
 *  into recovery first and is flashed from there, so polling follows it across that reconnect. */
private fun pollFirmwareStatus(control: StoandlControl) {
    var startMs = System.currentTimeMillis()
    var viaRecovery = false
    var sawActivity = false
    var barShown = false
    var lastDownloading = ""
    while (true) {
        Thread.sleep(800)
        // End the progress bar's line before withControl reports the error.
        val st = try { control.FirmwareStatus() } catch (e: DBusExecutionException) { if (barShown) println(); throw e }
        val (kind, body) = splitStatus(st)
        when (kind) {
            "downloading" -> {
                sawActivity = true
                if (body != lastDownloading) { println("Downloading $body…"); lastDownloading = body }
            }
            "waiting" -> sawActivity = true
            "inprogress" -> {
                sawActivity = true
                renderProgressBar("Flashing", body.toIntOrNull() ?: 0); barShown = true
            }
            "reboot" -> {
                if (barShown) { renderProgressBar("Flashing", 100); println() }
                println("Done — watch rebooting to apply the firmware.")
                return
            }
            "prf" -> if (!viaRecovery) {
                viaRecovery = true
                println("Downgrade to $body: rebooting the watch into recovery; stoandl flashes it there once it reconnects…")
                // The reboot's disconnect doesn't end this flash; the one from recovery does.
                sawActivity = false
                startMs = System.currentTimeMillis()
            }
            "failed" -> {
                if (barShown) println()
                System.err.println("Firmware update failed: ${body.ifEmpty { "unknown error" }}")
                System.exit(1); return
            }
            "notready" -> if (sawActivity) {
                // The watch reboots and drops the link once the transfer completes.
                if (barShown) { renderProgressBar("Flashing", 100); println() }
                println("Watch disconnected — it's rebooting to apply the firmware.")
                return
            }
            // "idle" (and an early "notready" before any activity): keep polling.
        }
        if (System.currentTimeMillis() - startMs > 600_000) {
            if (barShown) println()
            System.err.println("Timed out waiting for the firmware update to finish.")
            System.exit(1); return
        }
    }
}

/** A 20-wide `[####----]` progress bar on the current line (carriage return, no newline). */
private fun renderProgressBar(label: String, pct: Int) {
    val clamped = pct.coerceIn(0, 100)
    val width = 20
    val filled = clamped * width / 100
    val bar = "#".repeat(filled) + "-".repeat(width - filled)
    print("\r$label [$bar] %3d%%".format(clamped))
    System.out.flush()
}

/** Dispatch `stoandl notif ...`: `styles` (offline), else `list`/`mute`/`unmute`/`mute-all`/`unmute-all`/`style`/`images`. */
private fun ctlNotif(rest: List<String>) {
    val sub = rest.firstOrNull() ?: "list"
    // `styles` lists the available colours/icons/vibes — generated from the enums, fully offline
    // (no daemon, no watch), so handle it before opening the control bus.
    if (sub == "styles") { printNotifStyles(); return }
    withControl { control ->
        when (sub) {
            "list" -> {
                val (muted, until) = splitStatus(control.NotifMuteAllStatus())
                until.toLongOrNull()?.takeIf { muted == "muted" }?.let { epoch ->
                    val at = LocalDateTime.ofEpochSecond(epoch, 0, ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochSecond(epoch)))
                    println("All notifications muted until ${at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} (stoandl notif unmute-all)\n")
                }
                printNotifList(control.NotifList())
            }
            // Inject a synthetic notification to the watch (through the normal mute/style/filter path).
            "test" -> {
                val title = rest.getOrNull(1) ?: "Test notification"
                val body = rest.drop(2).joinToString(" ")
                handleStatusResponse(control.SendTestNotification(title, body))
            }
            // Buzz the watch with a vibration (name, CSV or `default`) — the preview for `--vibe`.
            "vibe-test" -> {
                val spec = rest.drop(1).joinToString(" ")
                if (spec.isBlank()) {
                    System.err.println("Usage: stoandl notif vibe-test <name|ms,ms,…|default>  ('stoandl notif vibes' lists the names)"); System.exit(EXIT_USAGE); return
                }
                handleStatusResponse(control.NotifVibeTest(spec))
            }
            // Every vibration the daemon takes, with the custom ones stored there.
            "vibes" -> {
                val rows = control.NotifStyleChoices()
                printVibes(rows)
            }
            // Named custom vibrations; quote multi-word names: `stoandl notif vibe-add "Work chat" 100,50,100`.
            "vibe-add" -> {
                val name = rest.getOrNull(1)
                val pattern = rest.getOrNull(2)
                if (name.isNullOrBlank() || pattern.isNullOrBlank()) {
                    System.err.println("Usage: stoandl notif vibe-add <name> <ms,ms,…>  (on/off milliseconds, e.g. 100,50,100)"); System.exit(EXIT_USAGE); return
                }
                handleStatusResponse(control.NotifSetVibePattern(name, pattern))
            }
            "vibe-remove" -> {
                val name = rest.drop(1).joinToString(" ")
                if (name.isBlank()) {
                    System.err.println("Usage: stoandl notif vibe-remove <name>"); System.exit(EXIT_USAGE); return
                }
                handleStatusResponse(control.NotifSetVibePattern(name, ""))
            }
            // Quote multi-word app names: `stoandl notif mute "My App" 1h`.
            "mute", "unmute" -> {
                val query = rest.getOrNull(1)
                if (query.isNullOrBlank()) {
                    System.err.println("Usage: stoandl notif $sub <app>" + if (sub == "mute") " [always|weekdays|weekends|<1h|30m|2d>]" else ""); System.exit(EXIT_USAGE); return
                }
                val spec = if (sub == "unmute") "never" else rest.getOrNull(2) ?: "always"
                handleStatusResponse(control.NotifSetMute(query, spec))
            }
            "mute-all", "unmute-all" -> {
                val spec = if (sub == "unmute-all") "never" else rest.getOrNull(1)
                if (spec.isNullOrBlank()) {
                    System.err.println("Usage: stoandl notif mute-all <30m|1h|2d|today>  ·  stoandl notif unmute-all"); System.exit(EXIT_USAGE); return
                }
                handleStatusResponse(control.NotifSetMuteAll(spec))
            }
            "style" -> {
                val query = rest.getOrNull(1)
                if (query.isNullOrBlank()) {
                    System.err.println("Usage: stoandl notif style <app> [--color <name>] [--icon <name>] [--vibe <preset|ms,ms,…>] [--app-name small|subtitle|off]  (use 'default' to reset; 'stoandl notif styles' lists the choices)"); System.exit(EXIT_USAGE); return
                }
                val color = flagValue(rest, "--color") ?: ""
                val icon = flagValue(rest, "--icon") ?: ""
                val vibe = flagValue(rest, "--vibe") ?: ""
                // Its own D-Bus method (NotifSetStyle's signature is fixed); null = not asked for.
                val appName = flagValue(rest, "--app-name")
                if (color.isEmpty() && icon.isEmpty() && vibe.isEmpty() && appName == null) {
                    System.err.println("Nothing to set: pass --color <name>, --icon <name>, --vibe <preset|ms,ms,…> and/or --app-name small|subtitle|off"); System.exit(EXIT_USAGE); return
                }
                if (color.isNotEmpty() || icon.isNotEmpty() || vibe.isNotEmpty()) {
                    handleStatusResponse(control.NotifSetStyle(query, color, icon, vibe))
                }
                if (appName != null) {
                    handleStatusResponse(control.NotifSetAppName(query, appName))
                }
            }
            // An app's pictures off while notification.images stays on, or back to following it.
            "images" -> {
                val query = rest.getOrNull(1)
                val mode = rest.getOrNull(2)
                if (query.isNullOrBlank() || mode.isNullOrBlank()) {
                    System.err.println("Usage: stoandl notif images <app> on|off|default  (on and default follow notification.images)"); System.exit(EXIT_USAGE); return
                }
                handleStatusResponse(control.NotifSetImages(query, mode))
            }
            // Global allow/block notification filters (regex on app/title/body). `allow` is a whitelist
            // that bypasses block filters, the forwarding switch and per-app mute; `block` drops a match.
            "filter", "filters" -> {
                when (val fsub = rest.getOrNull(1)?.lowercase() ?: "list") {
                    "list" -> {
                        val rows = control.NotifListFilters()
                        if (rows.isEmpty()) {
                            println("No notification filters.")
                        } else {
                            println("  %-6s %s".format("ACTION", "PATTERN"))
                            rows.forEach { entry ->
                                val f = entry.split('\t') // pattern \t action
                                println("  %-6s %s".format(f.getOrElse(1) { "" }, f.getOrElse(0) { entry }))
                            }
                        }
                    }
                    "add" -> {
                        val pattern = rest.getOrNull(2)
                        val action = rest.getOrNull(3)?.lowercase() ?: "block"
                        if (pattern.isNullOrBlank()) {
                            System.err.println("Usage: stoandl notif filter add <regex> [allow|block]"); System.exit(EXIT_USAGE); return
                        }
                        if (action != "allow" && action != "block") {
                            System.err.println("Action must be 'allow' or 'block'"); System.exit(EXIT_USAGE); return
                        }
                        handleStatusResponse(control.NotifAddFilter(pattern, action))
                    }
                    "remove", "rm", "del" -> {
                        val pattern = rest.drop(2).joinToString(" ")
                        if (pattern.isBlank()) {
                            System.err.println("Usage: stoandl notif filter remove <regex>"); System.exit(EXIT_USAGE); return
                        }
                        handleStatusResponse(control.NotifRemoveFilter(pattern))
                    }
                    else -> {
                        System.err.println("Usage: stoandl notif filter <list | add <regex> [allow|block] | remove <regex>>")
                        System.exit(EXIT_USAGE)
                    }
                }
            }
            else -> {
                System.err.println("Usage: stoandl notif <list|styles|mute <app> [spec]|unmute <app>|mute-all <30m|1h|2d|today>|unmute-all|style <app> [--color <c>] [--icon <i>] [--vibe <v>] [--app-name <a>]|images <app> on|off|default|vibes|vibe-test <v>|vibe-add <name> <ms,…>|vibe-remove <name>|filter <list|add|remove>>")
                System.exit(EXIT_USAGE)
            }
        }
    }
}

// ---- sync group (list / enable / disable a sync service) ---------------------------------------

/** dnd.sync has four modes, so `sync enable|disable dnd` can't say which one (the daemon refuses it too). */
private const val DND_SYNC_HINT = "Do Not Disturb sync has four modes: stoandl daemon set dnd.sync off|to_watch|to_host|both"

private fun ctlSync(rest: List<String>) {
    val sub = rest.firstOrNull() ?: "list"
    if (sub == "dnd" || (sub in setOf("enable", "disable") && rest.getOrNull(1) == "dnd")) {
        System.err.println(DND_SYNC_HINT); System.exit(EXIT_USAGE); return
    }
    withControl { control ->
        when (sub) {
            "list", "status" -> {
                val rows = control.GetSyncStatus()
                println("  %-14s %-9s %-12s %-12s %s".format("SERVICE", "STATE", "AVAILABLE", "LAST SYNC", "KEY"))
                rows.forEach { entry ->
                    // service \t enabled|disabled \t available|unavailable \t lastSync \t key
                    val f = entry.split('\t')
                    println("  %-14s %-9s %-12s %-12s %s".format(
                        f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, f.getOrElse(3) { "" }, f.getOrElse(4) { "" }))
                }
                println("\nToggle one with:  stoandl sync enable|disable <service>  (dnd: stoandl daemon set dnd.sync <mode>)")
            }
            "enable", "disable" -> {
                val service = rest.getOrNull(1)
                if (service.isNullOrBlank()) {
                    System.err.println("Usage: stoandl sync $sub <service>  (notifications weather calendar music health)")
                    System.exit(EXIT_USAGE); return
                }
                handleStatusResponse(control.SetSyncEnabled(service, sub == "enable"))
            }
            else -> {
                System.err.println("Usage: stoandl sync <list | enable <service> | disable <service>>  (notifications weather calendar music health)")
                System.exit(EXIT_USAGE)
            }
        }
    }
}

// ---- daemon group (the stoandl.conf keys the GUI's "Daemon configuration" page edits) -----------

/**
 * `stoandl daemon [list | get <key> | set <key> <value>]` — the CLI half of `GetConfigSchema` /
 * `GetConfig` / `SetConfig`, i.e. the same keys and the same validation the GUI's "Daemon
 * configuration" page drives. There was previously no CLI path to daemon config at all (`stoandl
 * config` is the PKJS/Clay *watchapp* settings page, which takes an app name), so a headless host
 * could only hand-edit stoandl.conf and restart.
 *
 * `list` renders the schema grouped as the GUI shows it ("Page › Section"), with the current value and a
 * `(restart)` marker on keys the daemon only reads at startup; `get` adds the key's help, default,
 * when it matters and what it contacts on stderr; `restart` restarts the daemon through its service
 * manager (Control.Restart).
 */
private fun ctlDaemon(rest: List<String>) {
    val sub = rest.firstOrNull() ?: "list"
    withControl { control ->
        val schema = control.GetConfigSchema()
        // key -> row fields (see StoandlControl.GetConfigSchema for the column order).
        val fields = schema.map { it.split('\t') }.filter { it.isNotEmpty() && it[0].isNotBlank() }
        fun col(f: List<String>, i: Int) = f.getOrElse(i) { "" }
        val values = control.GetConfig().associate { val p = it.split('\t'); p.getOrElse(0) { "" } to p.getOrElse(1) { "" } }

        when (sub) {
            "list", "status" -> {
                if (fields.isEmpty()) { println("The daemon exposes no editable configuration keys."); return@withControl }
                var group = ""
                fields.forEach { f ->
                    // Columns 11 (page) and 5 (section), as the GUI's settings pages group them.
                    val g = listOf(col(f, 11), col(f, 5)).filter { it.isNotBlank() }.joinToString(" › ").ifBlank { "Settings" }
                    if (g != group) { group = g; println("\n$group") }
                    val unit = col(f, 9).let { if (it.isBlank()) "" else " $it" }
                    val restart = if (col(f, 6) == "restart") "  (restart)" else ""
                    println("  %-30s %s%s%s".format(col(f, 0), values[col(f, 0)] ?: "", unit, restart))
                }
                println("\nSet one with:  stoandl daemon set <key> <value>")
                println("Keys marked (restart) only take effect after: systemctl --user restart stoandl")
            }
            "get" -> {
                val key = rest.getOrNull(1)
                if (key.isNullOrBlank()) { System.err.println("Usage: stoandl daemon get <key>"); System.exit(EXIT_USAGE); return }
                val f = fields.firstOrNull { it[0] == key }
                if (f == null) {
                    System.err.println("No config key '$key'  (list them with: stoandl daemon list)")
                    System.exit(1); return
                }
                println(values[key] ?: "")
                // Everything a caller needs to write a valid value back, on stderr so `get` stays pipeable.
                val allowed = when (col(f, 1)) {
                    "combo" -> col(f, 3)
                    "toggle" -> "true, false"
                    "int" -> "${col(f, 7)}..${col(f, 8)}${col(f, 9).let { if (it.isBlank()) "" else " $it" }}"
                    "list" -> "comma-separated" + col(f, 10).let { if (it.isBlank()) "" else " (e.g. $it)" }
                    else -> "text" + col(f, 10).let { if (it.isBlank()) "" else " (e.g. $it)" }
                }
                // Columns 12–15: help, requires, default, egress.
                val labels = fields.associate { it[0] to col(it, 2) }
                System.err.println("# ${col(f, 2)} — ${col(f, 4)}")
                col(f, 12).takeIf { it.isNotBlank() }?.let { System.err.println("# $it") }
                col(f, 14).takeIf { it.isNotBlank() }?.let { System.err.println("# default: $it") }
                col(f, 13).takeIf { it.isNotBlank() }?.let { System.err.println("# used when: ${usedWhen(it, labels::get)}") }
                col(f, 15).takeIf { it.isNotBlank() }?.let { System.err.println("# contacts: $it") }
                System.err.println("# accepts: $allowed" + if (col(f, 6) == "restart") "  (needs a daemon restart)" else "")
            }
            "restart" -> handleStatusResponse(control.Restart())
            "set" -> {
                val key = rest.getOrNull(1)
                // Join the remainder so a value with spaces (a volume command, a location list) works
                // without the caller having to quote it.
                val value = rest.drop(2).joinToString(" ")
                if (key.isNullOrBlank() || rest.size < 3) {
                    System.err.println("Usage: stoandl daemon set <key> <value>   (list keys with: stoandl daemon list)")
                    System.exit(EXIT_USAGE); return
                }
                handleStatusResponse(control.SetConfig(key, value))
            }
            else -> {
                System.err.println("Usage: stoandl daemon <list | get <key> | set <key> <value> | restart>")
                System.exit(EXIT_USAGE)
            }
        }
    }
}

/**
 * A schema row's `requires` (`alt('|'alt)*`, alt = `key=value` or `key!=value`) as a sentence: "Forward
 * notifications is on or Text to speech isn't Off". [labelOf] names a key (the key itself when unknown);
 * a toggle's true/false read as on/off.
 */
internal fun usedWhen(requires: String, labelOf: (String) -> String?): String =
    requires.split('|').filter { it.isNotBlank() }.joinToString(" or ") { alt ->
        val negated = "!=" in alt
        val key = alt.substringBefore(if (negated) "!=" else "=")
        val value = when (val v = alt.substringAfter(if (negated) "!=" else "=")) {
            "true" -> "on"
            "false" -> "off"
            else -> v
        }
        "${labelOf(key) ?: key} ${if (negated) "isn't" else "is"} $value"
    }

// ---- watch group (list/connect/pair/unpair/repair/battery/find) --------------------------------

private fun ctlWatch(rest: List<String>) {
    // `--yes`/`-y` on pair/repair: accept the pairing code on this side without asking.
    val assumeYes = rest.any { it == "--yes" || it == "-y" }
    when (rest.firstOrNull() ?: "list") {
        "list" -> watchList()
        "connect" -> watchConnect(rest.getOrNull(1))
        "disconnect" -> withControl { control ->
            handleStatusResponse(control.Disconnect())
        }
        "pair" -> watchPair(assumeYes)
        "unpair" -> watchUnpair(rest.getOrNull(1))
        "repair" -> watchRepair(rest.drop(1).firstOrNull { !it.startsWith("-") }, assumeYes)
        "battery" -> ctlBattery(rest.drop(1))
        "find" -> watchFind()
        "ping" -> withControl { control ->
            handleStatusResponse(control.Ping())
        }
        "running" -> watchRunning()
        "rename", "nickname" -> watchRename(rest.getOrNull(1), rest.drop(2).joinToString(" "))
        else -> {
            System.err.println("Usage: stoandl watch <list|connect [n]|disconnect|pair [--yes]|unpair [n]|repair <n> [--yes]|rename <n> <name>|battery|find|ping|running>")
            System.exit(EXIT_USAGE)
        }
    }
}

/** Show the app/watchface currently on the watch's screen (distinct from the default watchface). */
private fun watchRunning(): Unit = withControl { control ->
    val resp = control.RunningApp()
    when {
        resp.startsWith("ok:") -> {
            val parts = resp.removePrefix("ok:").split('\t')
            val title = parts.getOrElse(0) { "" }
            val uuid = parts.getOrElse(1) { "" }
            println("Running: $title" + if (uuid.isNotBlank()) "  ($uuid)" else "")
        }
        resp.startsWith("none:") -> println("No app reported as running yet.")
        else -> handleStatusResponse(resp)
    }
}

/** Rename (set the nickname of) the watch matching [query] — the CLI counterpart of the GUI rename. */
private fun watchRename(query: String?, nickname: String) {
    if (query.isNullOrBlank() || nickname.isBlank()) {
        System.err.println("Usage: stoandl watch rename <name|uuid> <new name>"); System.exit(EXIT_USAGE); return
    }
    withControl { control ->
        handleStatusResponse(control.SetWatchNickname(query, nickname))
    }
}

private fun watchList() = withControl { control ->
    val watches = control.ListWatches()
    if (watches.isEmpty()) {
        println("No known watches. Run 'stoandl watch pair' to add one.")
    } else {
        watches.forEach { entry ->
            val parts = entry.split('\t')
            val battery = parts.getOrElse(2) { "" }
            val transport = parts.getOrElse(3) { "" }
            val tail = buildString {
                if (battery.isNotBlank()) append("  ${battery}%")
                if (transport.isNotBlank()) append("  $transport")
            }
            println("  %-24s %-12s%s".format(parts.getOrElse(0) { entry }, parts.getOrElse(1) { "" }, tail))
        }
    }
    if (control.BluetoothStatus() == "ok:off") println("Bluetooth is off — no watch can connect until it is on.")
    val pause = runCatching { control.LinkPause() }.getOrNull()?.removePrefix("ok:")?.split(',').orEmpty()
    when {
        "manual" in pause -> println("Watch connection paused — 'stoandl watch connect' resumes it.")
        "quiet_time" in pause -> println("Watch connection paused for Quiet Time (power.quiet_time_link_off).")
    }
}

// `watch connect` resumes after `watch disconnect`; `watch connect <name>` also switches to that watch.
private fun watchConnect(name: String?) {
    withControl { control ->
        handleStatusResponse(control.Connect(name ?: ""))
    }
}

private fun watchPair(assumeYes: Boolean): Unit = withControl { control ->
    val startResp = control.Pair()
    if (!startResp.startsWith("ok:")) { handleStatusResponse(startResp); return }
    println("Searching for watch to pair (up to 2 minutes)...")
    pollPairStatus(control, assumeYes)
}

private fun watchRepair(name: String?, assumeYes: Boolean) {
    if (name.isNullOrBlank()) { System.err.println("Usage: stoandl watch repair <name> [--yes]"); System.exit(EXIT_USAGE); return }
    withControl { control ->
        val startResp = control.Repair(name)
        if (!startResp.startsWith("ok:")) { handleStatusResponse(startResp); return }
        println(startResp.removePrefix("ok:"))
        pollPairStatus(control, assumeYes)
    }
}

// `watch unpair` = blanket (all); `watch unpair <name>` = just the matching watch.
private fun watchUnpair(name: String?) = withControl { control ->
    handleStatusResponse(control.Unpair(name ?: ""))
}

// `watch battery` = live level; `history`/`insights` show the recorded trend (daemon-computed);
// `heartbeat` dumps the captured analytics heartbeats (offline file read, like `datalog`/`health dump`).
private fun ctlBattery(rest: List<String>) {
    when (rest.firstOrNull()?.lowercase()) {
        null -> watchBattery()
        "history" -> batteryHistory(rest.drop(1))
        "insights" -> batteryInsights(rest.drop(1))
        "activity" -> batteryActivity(rest.drop(1))
        "power" -> batteryPower(rest.drop(1))
        "heartbeat" -> batteryHeartbeat(rest.drop(1))
        else -> {
            System.err.println("Usage: stoandl watch battery [history|insights|activity|power|heartbeat] [--watch <name>] [--since <dur>]")
            System.err.println("       heartbeat [--limit <n>] [--raw] [--all]   (--all = every metric of the newest record)")
            System.exit(EXIT_USAGE)
        }
    }
}

private fun watchBattery(): Unit = withControl { control ->
    val resp = control.Battery()
    val (kind, body) = splitStatus(resp)
    when (kind) {
        "ok" -> { val f = body.split('\t'); println("%s: %s%%".format(f.getOrElse(0) { "Watch" }, f.getOrElse(1) { "?" })) }
        "unknown" -> println("$body: battery level not available yet")
        else -> handleStatusResponse(resp)
    }
}

/** Parse a duration like `24h`, `7d`, `90m`, `3600s` (or a bare number = seconds) to seconds. */
private fun parseDurationSeconds(s: String): Long {
    val t = s.trim().lowercase()
    val num = t.dropLast(if (t.isNotEmpty() && !t.last().isDigit()) 1 else 0).toLongOrNull() ?: return 24L * 3600
    return when (t.lastOrNull()) {
        's' -> num
        'm' -> num * 60
        'h' -> num * 3600
        'd' -> num * 86_400
        else -> num
    }
}

private fun batteryHistory(args: List<String>) {
    val watch = flagValue(args, "--watch") ?: ""
    val since = System.currentTimeMillis() / 1000 - parseDurationSeconds(flagValue(args, "--since") ?: "24h")
    withControl { control ->
        val resp = control.BatteryHistory(watch, since)
        val (kind, body) = splitStatus(resp)
        when (kind) {
            "ok" -> {
                if (body.isBlank()) { println("No battery samples in that window."); return@withControl }
                body.split('\n').forEach { line ->
                    val f = line.split('\t')
                    val ts = f.getOrElse(0) { "" }.toLongOrNull()
                    val volt = f.getOrElse(3) { "" }
                    println("  %-19s  %6s%%  %-9s %s".format(
                        ts?.let { formatEpoch(it) } ?: "?", f.getOrElse(1) { "?" }, f.getOrElse(2) { "" },
                        if (volt.isNotBlank()) "${volt}V" else ""))
                }
            }
            else -> handleStatusResponse(resp)
        }
    }
}

private fun batteryInsights(args: List<String>) {
    val watch = flagValue(args, "--watch") ?: ""
    withControl { control ->
        val resp = control.BatteryInsights(watch)
        val (kind, body) = splitStatus(resp)
        when (kind) {
            "ok" -> {
                val f = body.split('\t')
                val charging = f.getOrElse(2) { "0" } == "1"
                val rate = f.getOrElse(3) { "" }
                val hours = f.getOrElse(4) { "" }
                val lastCharged = f.getOrElse(6) { "-1" }.toLongOrNull() ?: -1
                val voltage = f.getOrElse(10) { "" }
                println("${f.getOrElse(0) { "Watch" }} — ${f.getOrElse(1) { "?" }}%${if (charging) "  (charging)" else ""}")
                if (voltage.isNotBlank()) println("  Voltage:        $voltage V")
                if (!charging && hours.isNotBlank()) println("  Time remaining: ~$hours h")
                if (rate.isNotBlank() && rate != "0.00") println("  Discharge rate: $rate %/h")
                println("  24h range:      ${f.getOrElse(7) { "?" }}–${f.getOrElse(8) { "?" }}%")
                if (lastCharged > 0) println("  Last charged:   ${relativeAge(lastCharged)}")
                println("  Charge cycles:  ${f.getOrElse(5) { "0" }} in last 7d")
                println("  (source: ${f.getOrElse(11) { "?" }}, ${f.getOrElse(9) { "0" }} samples)")
            }
            "unknown" -> println("$body: not enough battery history yet (needs a couple of samples)")
            else -> handleStatusResponse(resp)
        }
    }
}

// Per-interval drop + notification counts — the data behind the GUI's drop-per-hour bar and
// notification overlay. Handy to confirm the richer heartbeat decode on real hardware.
private fun batteryActivity(args: List<String>) {
    val watch = flagValue(args, "--watch") ?: ""
    val since = System.currentTimeMillis() / 1000 - parseDurationSeconds(flagValue(args, "--since") ?: "24h")
    withControl { control ->
        val resp = control.BatteryActivity(watch, since)
        val (kind, body) = splitStatus(resp)
        when (kind) {
            "ok" -> {
                if (body.isBlank()) { println("No battery activity in that window."); return@withControl }
                body.split('\n').forEach { line ->
                    val f = line.split('\t')
                    val ts = f.getOrElse(0) { "" }.toLongOrNull()
                    val dnd = f.getOrElse(3) { "0" }.toLongOrNull() ?: 0
                    println("  %-19s  −%5s%%  %s notif%s".format(
                        ts?.let { formatEpoch(it) } ?: "?", f.getOrElse(1) { "0" }, f.getOrElse(2) { "0" },
                        if (dnd > 0) " ($dnd in quiet time)" else ""))
                }
            }
            else -> handleStatusResponse(resp)
        }
    }
}

// Estimated battery-drain attribution (the "what drew power" pie) as a text bar chart.
private fun batteryPower(args: List<String>) {
    val watch = flagValue(args, "--watch") ?: ""
    val since = System.currentTimeMillis() / 1000 - parseDurationSeconds(flagValue(args, "--since") ?: "24h")
    withControl { control ->
        val resp = control.BatteryPower(watch, since)
        val (kind, body) = splitStatus(resp)
        when (kind) {
            "ok" -> {
                if (body.isBlank()) { println("No power-attribution data yet (needs the hourly analytics heartbeat)."); return@withControl }
                // Wire: category \t estDrainPct \t sharePct (decimals are locale-independent "." form).
                val rows = body.split('\n').map { it.split('\t') }
                val totalDrain = rows.sumOf { it.getOrElse(1) { "0" }.toDoubleOrNull() ?: 0.0 }
                println(
                    if (totalDrain > 0)
                        String.format(java.util.Locale.ROOT,
                            "Estimated battery-drain attribution (modeled current × on-time, split over ~%.1f%% measured drain):", totalDrain)
                    else "Estimated battery-drain attribution (modeled current × on-time — no measured discharge to anchor):"
                )
                rows.forEach { f ->
                    val share = f.getOrElse(2) { "0" }.toDoubleOrNull() ?: 0.0
                    val drain = f.getOrElse(1) { "0" }.toDoubleOrNull() ?: 0.0
                    val bar = "█".repeat((share / 5.0).toInt().coerceIn(0, 20))
                    val abs = if (totalDrain > 0) String.format(java.util.Locale.ROOT, "~%.2f%%", drain) else "—"
                    println(String.format(java.util.Locale.ROOT, "  %-12s %5.1f%%  %7s  %s", f.getOrElse(0) { "?" }, share, abs, bar))
                }
            }
            else -> handleStatusResponse(resp)
        }
    }
}

/** Dump the captured analytics heartbeats — offline, reading the per-serial NDJSON under
 *  `<configDir>/battery/heartbeat/` directly (no daemon). Useful to confirm B decodes on the actual
 *  hardware; `--raw` shows the raw blob (base64) so an undecodable firmware layout can be sent upstream
 *  to finalize the offsets; `--all` decodes and prints **every** metric of the newest record (101 on
 *  fw ≥ 4.33 — see docs/heartbeat-metrics.md), which is the same data the GUI's
 *  Debug → Heartbeat page shows. */
private fun batteryHeartbeat(args: List<String>) {
    val watch = flagValue(args, "--watch")
    val limit = flagValue(args, "--limit")?.toIntOrNull()?.takeIf { it > 0 } ?: 10
    val raw = args.contains("--raw")
    val all = args.contains("--all")
    val dir = File(configDir(), "battery/heartbeat")
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") }
        ?.filter { watch == null || it.name.removeSuffix(".ndjson").contains(watch, ignoreCase = true) }
        ?.sortedBy { it.name } ?: emptyList()
    if (files.isEmpty()) {
        println("No analytics heartbeats captured yet under ${dir.path}.")
        println("(They arrive ~hourly while the watch is connected; needs battery.heartbeat on.)")
        return
    }
    files.forEach { file ->
        val rows = file.readLines().filter { it.isNotBlank() }
        if (all) {
            // Full metric dump of the newest record — the same view as the GUI's Debug → Heartbeat.
            println("== ${file.name.removeSuffix(".ndjson")} (${rows.size} record${if (rows.size == 1) "" else "s"}, newest decoded in full) ==")
            val o = runCatching { Json.parseToJsonElement(rows.last()).jsonObject }.getOrNull() ?: return@forEach
            val blob = o["raw"]?.jsonPrimitive?.content
                ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } ?: ByteArray(0)
            val metrics = HeartbeatLayouts.decodeAll(blob)
            if (metrics.isEmpty()) {
                println("  Layout ${blob.size}B/v${if (blob.isNotEmpty()) blob[0].toInt() and 0xFF else -1} is not in the verified table —")
                println("  no metrics decoded (raw is kept; add the layout with tools/hb_layouts_from_source.py).")
                return@forEach
            }
            metrics.forEach { m ->
                val v = m.text ?: m.value?.let { d ->
                    if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString()
                    else String.format(java.util.Locale.ROOT, "%.2f", d)
                } ?: ""
                println("  %-48s %s".format(m.name, v))
            }
            return@forEach
        }
        println("== ${file.name.removeSuffix(".ndjson")} (${rows.size} record${if (rows.size == 1) "" else "s"}, showing last ${minOf(limit, rows.size)}) ==")
        rows.takeLast(limit).forEach { line ->
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
            fun s(k: String) = o[k]?.jsonPrimitive?.content
            val decoded = s("decoded") == "true"
            val rx = s("rx")?.toLongOrNull()?.let { formatEpoch(it) } ?: "?"
            if (decoded) {
                val charging = s("charging") == "true"
                val tte = s("tte_s")?.toLongOrNull()?.takeIf { it > 0 }
                    ?.let { String.format(java.util.Locale.ROOT, " tte=%.1fh", it / 3600.0) } ?: ""
                println("  %-19s  %6s%%  %sV%s%s".format(
                    rx, s("soc") ?: "?", s("voltage") ?: "?", if (charging) " charging" else "", tte))
            } else {
                println("  %-19s  UNDECODED (size=${s("size") ?: "?"} version=${s("version") ?: "?"} build=${s("build_id") ?: "?"})".format(rx))
            }
            if (raw) s("raw")?.let { println("      raw: $it") }
        }
    }
}

/** Format an epoch-second as a local `yyyy-MM-dd HH:mm` for the battery listings. */
private fun formatEpoch(epochSeconds: Long): String =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private fun watchFind() = withControl { control ->
    if (control.FindWatch()) println("Ringing watch — press a button on the watch to silence it")
    else { System.err.println("Daemon not ready (no watch connected?)"); System.exit(1) }
}

// ---- settings group (list/set) ------------------------------------------------------------------

private fun ctlSettings(rest: List<String>) {
    when (val sub = rest.firstOrNull()) {
        null -> settingsList(null)
        "list" -> settingsList(rest.getOrNull(1))
        "set" -> settingsSet(rest.getOrNull(1), rest.drop(2).joinToString(" ").takeIf { it.isNotBlank() })
        "reset" -> withControl { control -> handleStatusResponse(control.ResetWatchPrefs()) }
        else -> settingsList(sub)  // back-compat: `settings <filter>`
    }
}

private fun settingsList(filter: String?): Unit = withControl { control ->
    val records = control.ListWatchPrefs()
    printWatchPrefs(records, filter)
}

private fun settingsSet(id: String?, value: String?) {
    if (id.isNullOrBlank() || value.isNullOrBlank()) {
        System.err.println("Usage: stoandl settings set <id> <value>"); System.exit(EXIT_USAGE); return
    }
    withControl { control ->
        val resp = control.SetWatchPref(id, value)
        handleStatusResponse(resp)
    }
}

private fun ctlApps(rest: List<String>) {
    when (val sub = rest.firstOrNull() ?: "list") {
        "list" -> withControl { control ->
            val records = control.ListApps()
            printAppList(records)
        }
        "launch" -> appLaunchOrRemove(true, rest.drop(1).joinToString(" ").takeIf { it.isNotBlank() })
        "remove" -> appLaunchOrRemove(false, rest.drop(1).joinToString(" ").takeIf { it.isNotBlank() })
        "sideload", "install", "add" -> appSideload(rest.getOrNull(1))
        "order" -> appOrder(rest.drop(1))
        else -> {
            System.err.println("Usage: stoandl apps <list|launch <name|uuid>|remove <name|uuid>|install <path.pbw>|order <name|uuid> <pos>|order reset>")
            System.exit(EXIT_USAGE)
        }
    }
}

/** Reorder the locker: `apps order <name|uuid> <pos>` moves an app to 0-based position [pos]; the
 *  name may contain spaces (the position is the last token). `apps order reset` restores the default
 *  system-app order. */
private fun appOrder(args: List<String>) {
    if (args.firstOrNull() == "reset") {
        withControl { control ->
            handleStatusResponse(control.RestoreSystemAppOrder())
        }
        return
    }
    val pos = args.lastOrNull()?.toIntOrNull()
    val query = args.dropLast(1).joinToString(" ")
    if (args.size < 2 || pos == null || pos < 0 || query.isBlank()) {
        System.err.println("Usage: stoandl apps order <name|uuid> <position≥0> | apps order reset")
        System.exit(EXIT_USAGE); return
    }
    withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
        handleStatusResponse(control.SetAppOrder(query, pos))
    }
}

/** Install a .pbw onto the watch. Resolves a relative path against THIS process's cwd and sends an
 *  absolute path: the daemon runs as a systemd user service with its own cwd ($HOME), so a relative
 *  path would be looked up in the wrong place (a misleading "Pbw does not contain manifest"). */
private fun appSideload(pathArg: String?) {
    if (pathArg.isNullOrBlank()) {
        System.err.println("Usage: stoandl apps install <path.pbw>  (aliases: sideload, add)"); System.exit(EXIT_USAGE); return
    }
    val file = File(pathArg)
    if (!file.isFile) { System.err.println("No such file: $pathArg"); System.exit(1); return }
    val path = file.absolutePath
    withControl { control ->
        handleStatusResponse(awaitTask(control, "sideload") { control.SideloadApp(path) })
    }
}

private fun appLaunchOrRemove(launch: Boolean, query: String?) {
    if (query.isNullOrBlank()) {
        System.err.println("Usage: stoandl apps ${if (launch) "launch" else "remove"} <name|uuid>"); System.exit(EXIT_USAGE); return
    }
    // A launch waits until the watch runs the app (a fetch over BLE first when it isn't on the watch); a
    // remove may ask the remote locker.
    withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
        val resp = if (launch) control.LaunchApp(query) else control.RemoveApp(query)
        handleStatusResponse(resp)
    }
}

private fun ctlExt(rest: List<String>) {
    val sub = rest.firstOrNull() ?: "list"
    withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
        when (sub) {
            "list", "status" -> {
                val rows = control.ExtList()
                if (rows.isEmpty()) { println("No extensions installed. (stoandl ext install <archive>)"); return }
                println(String.format("%-20s %-10s %-9s %s", "NAME", "INSTALLED", "ENABLED", "RUNNING"))
                rows.forEach { row ->
                    val c = row.split('\t')
                    println(String.format("%-20s %-10s %-9s %s",
                        c.getOrElse(0) { "" }, c.getOrElse(1) { "" }, c.getOrElse(2) { "" }, c.getOrElse(3) { "" }))
                    // Column 8: the child's last stderr line, which names why a crashed or quarantined one stopped.
                    c.getOrNull(8)?.takeIf { it.isNotBlank() }?.let { println("  stderr: ${it.take(160)}") }
                }
            }
            "install" -> {
                val p = rest.getOrNull(1)
                if (p.isNullOrBlank()) {
                    System.err.println("Usage: stoandl ext install <archive.tar.gz|.tgz|.tar|.zip|directory>"); System.exit(EXIT_USAGE); return
                }
                val f = File(p)
                if (!f.exists()) { System.err.println("No such file or directory: $p"); System.exit(1); return }
                handleStatusResponse(control.ExtInstall(f.absolutePath))
            }
            "config", "settings" -> {
                val name = rest.getOrNull(1)
                if (name.isNullOrBlank()) {
                    System.err.println("Usage: stoandl ext config <name> [<key> [<value> | --unset]]"); System.exit(EXIT_USAGE); return
                }
                val args = rest.drop(2).filter { it != "--unset" }
                extConfig(control, name, args.firstOrNull(), args.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" "), "--unset" in rest)
            }
            "uninstall", "remove" -> {
                val name = rest.getOrNull(1)?.takeIf { !it.startsWith("-") }
                if (name.isNullOrBlank()) {
                    System.err.println("Usage: stoandl ext uninstall <name> [--keep-config|--delete-config]"); System.exit(EXIT_USAGE); return
                }
                val keepConfig = resolveKeepConfig(name, rest)
                val resp = control.ExtUninstall(name, keepConfig)
                handleStatusResponse(resp)
            }
            "enable", "disable", "restart" -> {
                val name = rest.getOrNull(1)
                if (name.isNullOrBlank()) {
                    System.err.println("Usage: stoandl ext $sub <name>"); System.exit(EXIT_USAGE); return
                }
                val resp = when (sub) {
                    "enable" -> control.ExtEnable(name)
                    "disable" -> control.ExtDisable(name)
                    else -> control.ExtRestart(name)
                }
                handleStatusResponse(resp)
            }
            else -> {
                System.err.println("Usage: stoandl ext <list|install <archive|dir>|uninstall <name> [--keep-config|--delete-config]|enable <name>|disable <name>|restart <name>|config <name> [<key> [<value>]]>")
                System.exit(EXIT_USAGE)
            }
        }
    }
}

/**
 * `stoandl ext config <name>` lists the extension's settings; `<key>` prints one, or for a secret prompts
 * for its new value without echo (a secret is never taken from the command line, where shell history and
 * `ps` would keep it); `<key> <value>` sets one and `<key> --unset` clears it. The daemon restarts the
 * extension when it runs, or starts it once its required settings are there.
 */
private fun extConfig(control: StoandlControl, name: String, key: String?, value: String?, unset: Boolean) {
    val (schemaKind, schema) = splitStatus(control.ExtConfigSchema(name))
    if (schemaKind != "ok" && schemaKind != "none") { System.err.println(schema.ifEmpty { schemaKind }); exitProcess(EXIT_FAILURE) }
    val fields = configFields(schema.takeIf { schemaKind == "ok" })
    val (valuesKind, valuesJson) = splitStatus(control.ExtGetConfig(name))
    if (valuesKind != "ok") { System.err.println(valuesJson.ifEmpty { valuesKind }); exitProcess(EXIT_FAILURE) }
    val values = Json.parseToJsonElement(valuesJson).jsonObject
    if (key == null) {
        val table = extConfigTable(fields, values)
        if (table.isEmpty()) { println("$name has no settings."); return }
        println("$name settings:")
        table.forEach(::println)
        println("\nSet one with:  stoandl ext config $name <key> <value>")
        if (fields.any { it.secret }) println("A secret is asked for:  stoandl ext config $name <key>")
        return
    }
    val field = fields.firstOrNull { it.key == key }
    if (fields.isNotEmpty() && field == null) {
        System.err.println("$name has no setting '$key' (list them: stoandl ext config $name)"); exitProcess(EXIT_USAGE)
    }
    val secret = field?.secret == true
    val newValue = when {
        unset -> null
        value != null && secret -> {
            System.err.println("Type a secret at the prompt, not on the command line: stoandl ext config $name $key")
            exitProcess(EXIT_USAGE)
        }
        value != null -> extConfigValue(field, value).getOrElse { System.err.println(it.message); exitProcess(EXIT_USAGE) }
        secret -> promptPassword("${field?.label ?: key}: ").ifEmpty { println("Nothing entered; $key is unchanged."); return }
        else -> { println(values[key]?.jsonPrimitive?.contentOrNull ?: ""); return }
    }
    handleStatusResponse(control.ExtSetConfig(name, extConfigPayload(key, newValue, secret)))
}

/**
 * On uninstall, decide whether to keep the extension's `config` file. `--keep-config`/`--delete-config`
 * (`--purge`) skip the prompt; otherwise, only if the extension actually has a `config`, prompt with a
 * default of Yes (so reinstalling restores its settings). No config, or no answer → keep (the daemon
 * only keeps it if it exists, so the flag is harmless then).
 */
private fun resolveKeepConfig(name: String, rest: List<String>): Boolean {
    if (rest.any { it == "--delete-config" || it == "--purge" }) return false
    if (rest.any { it == "--keep-config" }) return true
    val dir = File(File(configDir(), "ext"), name)
    val cfg = File(dir, "config")
    val secrets = de.yoxcu.stoandl.ext.schemaFlags(de.yoxcu.stoandl.ext.readExtManifestMeta(dir).configSchemaJson).secretKeys
    if (!cfg.isFile && secrets.isEmpty()) return true
    val what = if (secrets.isEmpty()) "config (${cfg.path})" else "config and stored secrets (${secrets.joinToString(", ")})"
    print("Keep $name's $what? It'll be restored if you reinstall. [Y/n]: ")
    System.out.flush()
    val ans = readlnOrNull()?.trim()?.lowercase()
    return ans != "n" && ans != "no"
}

/** Value following [flag] in [args], or null if the flag is absent or has no following token. */
private fun flagValue(args: List<String>, flag: String): String? {
    val i = args.indexOf(flag)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

/** Print the per-app styling values (`notif style` choices). Generated from the libpebble3
 *  `TimelineColor`/`TimelineIcon` enums, the shared vibe presets and [AppNamePlacement], so the listing
 *  can't drift from what the daemon actually accepts. Fully offline — no daemon or watch needed. */
private fun printNotifStyles() {
    println("Per-app notification styling — set with:")
    println("  stoandl notif style <app> [--color <name>] [--icon <name>] [--vibe <preset|ms,ms,…>] [--app-name small|subtitle|off]")
    println("Names are case-insensitive; pass 'default' (or 'none'/'clear') for any flag to reset it.")
    println()

    println("App name on the watch (--app-name):")
    println("  " + AppNamePlacement.entries.joinToString("  ") { it.token })
    println("  (small = a small line under the title, subtitle = the large bold line, off = not shown;")
    println("   default = follow notification.app_name)")
    println()

    println("Vibrations (--vibe):")
    DefaultVibePattern.entries.forEach { println("  %-14s %s ms".format(it.displayName, it.pattern.joinToString(","))) }
    println("  (or a CSV of on/off milliseconds, e.g. 100,50,100, or a name you added with")
    println("   'stoandl notif vibe-add <name> <ms,ms,…>'; 'stoandl notif vibes' lists those too,")
    println("   'stoandl notif vibe-test <vibe>' buzzes one on the watch)")
    println()

    val colors = TimelineColor.entries.map { it.name }.sorted()
    println("Colors (--color) — ${colors.size}:")
    printColumns(colors)
    println()

    val (appIcons, otherIcons) = TimelineIcon.entries.map { it.name }.sorted()
        .partition { it.startsWith("Notification") }
    println("Icons (--icon) — app / messaging set (${appIcons.size}):")
    printColumns(appIcons)
    println()
    println("Other icons (${otherIcons.size}):")
    printColumns(otherIcons)
}

/** Print the `vibe` records of NotifStyleChoices (`vibe \t value \t label \t ms,ms,… \t builtin|custom`). */
private fun printVibes(rows: List<String>) {
    val vibes = rows.map { it.split('\t') }.filter { it.firstOrNull() == "vibe" }
    val (custom, builtin) = vibes.partition { it.getOrNull(4) == "custom" }
    val w = (vibes.maxOfOrNull { it.getOrElse(1) { "" }.length } ?: 0).coerceIn(14, 40)
    println("Built-in vibrations:")
    builtin.forEach { println("  %-${w}s %s ms".format(it.getOrElse(1) { "" }, it.getOrElse(3) { "" })) }
    println()
    if (custom.isEmpty()) {
        println("No custom vibrations (add one: stoandl notif vibe-add <name> <ms,ms,…>).")
    } else {
        println("Custom vibrations:")
        custom.forEach { println("  %-${w}s %s ms".format(it.getOrElse(1) { "" }, it.getOrElse(3) { "" })) }
    }
    println()
    println("Try one on the watch: stoandl notif vibe-test <name|ms,ms,…|default>")
}

/** Print [items] wrapped into space-padded columns that fit within [width] characters. */
private fun printColumns(items: List<String>, indent: String = "  ", width: Int = 100) {
    if (items.isEmpty()) return
    val colW = items.maxOf { it.length } + 2
    val perRow = ((width - indent.length) / colW).coerceAtLeast(1)
    items.chunked(perRow).forEach { row ->
        println(indent + row.joinToString("") { it.padEnd(colW) }.trimEnd())
    }
}

/** Render NotifList() rows (`name \t mute \t color \t icon \t vibe \t lastNotifiedEpoch \t appNamePlacement`)
 *  as a table. */
private fun printNotifList(rows: List<String>) {
    if (rows.isEmpty()) {
        println("No notification apps tracked yet — they're added as desktop apps notify (needs notification.per_app = true).")
        return
    }
    val fmt = "%-22s %-13s %-10s %-18s %-8s %-8s %-6s %s"
    println(fmt.format("APP", "MUTE", "COLOR", "ICON", "VIBE", "APP NAME", "IMAGES", "LAST"))
    rows.sortedBy { it.substringBefore('\t').lowercase() }.forEach { rec ->
        val f = rec.split('\t')
        val name = f.getOrElse(0) { "" }
        val mute = f.getOrElse(1) { "" }
        val color = f.getOrElse(2) { "" }.ifEmpty { "—" }
        // TimelineIcon enum names are long and all share a "Notification" prefix — strip it to fit.
        val icon = f.getOrElse(3) { "" }.ifEmpty { "—" }.removePrefix("Notification")
        val vibe = f.getOrElse(4) { "" }.ifEmpty { "—" }
        val epoch = f.getOrElse(5) { "0" }.toLongOrNull() ?: 0L
        // Empty (shown as —, like the other defaults) = the app follows notification.app_name.
        val appName = f.getOrElse(6) { "" }.ifEmpty { "—" }
        // `off`, or — for an app following notification.images.
        val images = f.getOrElse(7) { "" }.ifEmpty { "—" }
        println(fmt.format(name.take(22), mute, color.take(10), icon.take(18), vibe.take(8), appName, images, relativeAge(epoch)))
    }
    println()
    println("  Mute:   stoandl notif mute <app> [always|weekdays|weekends|1h|2d]")
    println("  Style:  stoandl notif style <app> [--color <c>] [--icon <i>] [--vibe <v>] [--app-name <a>]")
    println("  Images: stoandl notif images <app> on|off|default")
}

private fun relativeAge(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "—"
    val d = java.time.Instant.now().epochSecond - epochSeconds
    return when {
        d < 0 -> "just now"
        d < 60 -> "${d}s ago"
        d < 3600 -> "${d / 60}m ago"
        d < 86_400 -> "${d / 3600}h ago"
        else -> "${d / 86_400}d ago"
    }
}

private fun ctlLanguage(rest: List<String>) {
    val sub = rest.firstOrNull() ?: "list"
    // `list` is resilient: it shows the watch's packs when the daemon+watch are there, and otherwise
    // falls back to the full bundled catalog (offline), so it works before pairing / with no daemon.
    if (sub == "list") { ctlLanguageList(); return }
    withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
        when (sub) {
            "status" -> {
                val resp = control.LanguageStatus()
                printLanguageStatusOnce(resp)
            }
            "sideload", "add" -> {
                val path = rest.getOrNull(1)
                if (path == null) {
                    System.err.println("Usage: stoandl language sideload <file.pbl>"); System.exit(EXIT_USAGE); return
                }
                val file = File(path)
                if (!file.isFile) { System.err.println("No such file: $path"); System.exit(1); return }
                if (!file.name.endsWith(".pbl")) {
                    System.err.println("Not a language pack (expected a .pbl): $path"); System.exit(1); return
                }
                // Absolute path: the daemon's cwd differs from the caller's.
                val resp = control.SideloadLanguage(file.absolutePath)
                if (!resp.startsWith("ok:")) { handleStatusResponse(resp); return }
                println("Installing ${file.name} onto the watch — keep it close.")
                pollLanguageStatus(control)
            }
            "install" -> {
                val query = rest.drop(1).joinToString(" ")
                val resp = control.InstallLanguage(query)
                if (!resp.startsWith("ok:")) { handleStatusResponse(resp); return }
                println("Installing ${resp.removePrefix("ok:")} — downloading then transferring to the watch.")
                pollLanguageStatus(control)
            }
            else -> {
                System.err.println("Usage: stoandl language [list | sideload <file.pbl> | install <locale|name|id> | status]")
                System.exit(EXIT_USAGE)
            }
        }
    }
}

/**
 * `stoandl language list`: the watch's installable packs (board-filtered, installed pack marked) when
 * the daemon and a watch are present, else the full bundled catalog. Uses a soft D-Bus connect so a
 * missing daemon (or no watch) degrades to the offline catalog instead of erroring out.
 */
private fun ctlLanguageList() {
    val conn = softOpenSessionBus() ?: run { printFullCatalog(); return }
    try {
        val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
        val records = try { control.ListLanguages() } catch (e: Exception) { emptyList() }
        if (records.isEmpty()) printFullCatalog() else printLanguageList(records)
    } finally {
        conn.disconnect()
    }
}

/** Render the tab-separated records from ListLanguages() as an aligned table. */
private fun printLanguageList(records: List<String>) {
    // Empty is handled by the caller (it falls back to the full catalog), so records is non-empty here.
    println("%-3s %-7s %s".format("", "LOCALE", "LANGUAGE"))
    records.forEach { rec ->
        val f = rec.split('\t')
        val isoLocal = f.getOrElse(1) { "" }
        val displayName = f.getOrElse(2) { "" }
        val installed = f.getOrElse(3) { "no" } == "yes"
        val source = f.getOrElse(4) { "" }
        val mark = if (installed) " * " else "   "
        val src = if (source == "github") "  [community]" else ""
        println("%s %-7s %s%s".format(mark, isoLocal, displayName, src))
    }
    println()
    println("  * = currently installed.  Install one with:  stoandl language install <locale>")
    println("  (downloads need 'language.download = true' in stoandl.conf; or use 'language sideload <file.pbl>')")
}

/**
 * The full bundled catalog (every locale and board), shown by `language list` when no watch is
 * connected — so you can still see what's available before pairing or while out of range. Fully
 * offline (the manifest is a bundled resource). One row per language, with how many boards carry it.
 */
private fun printFullCatalog() {
    val packs = LanguagePackCatalog.load().all()
    data class Entry(val locale: String, val boards: Int, val allBoards: Boolean, val community: Boolean, val lang: String)
    val rows = packs.groupBy { it.isoLocal to it.name }
        .map { (key, ps) ->
            val first = ps.first()
            Entry(
                locale = key.first,
                boards = ps.mapNotNull { it.hardware }.distinct().size,
                allBoards = ps.any { it.hardware == null },
                community = ps.any { !it.file.startsWith("https://binaries.rebble.io") },
                lang = "${first.localName} (${first.name})",
            )
        }
        .sortedWith(compareBy({ it.locale }, { it.lang }))
    if (rows.isEmpty()) {
        println("No language packs available (no watch connected, and the bundled catalog is empty).")
        return
    }
    println("No watch connected — showing the full catalog (every board).")
    println("%-8s %-10s %s".format("LOCALE", "BOARDS", "LANGUAGE"))
    rows.forEach { r ->
        val boards = if (r.allBoards) "all" else r.boards.toString()
        val tag = if (r.community) "  [community]" else ""
        println("%-8s %-10s %s%s".format(r.locale, boards, r.lang, tag))
    }
    println()
    println("${rows.size} language(s).  Connect your watch and run 'stoandl language list' to see the")
    println("packs for its board (and which is installed), then 'stoandl language install <locale>'.")
}

private fun printLanguageStatusOnce(resp: String) {
    val (kind, body) = splitStatus(resp)
    val human = when (kind) {
        "idle" -> "Idle (no language-pack install in progress)"
        "downloading" -> "Downloading $body…"
        "installing" -> "Installing: $body%"
        "done" -> "Last install finished: $body"
        "failed" -> "Last language-pack install failed: ${body.ifEmpty { "unknown error" }}"
        "notready" -> body.ifEmpty { "No watch connected" }
        else -> resp
    }
    println(human)
}

/** Polls LanguageStatus() while an install runs, rendering a progress bar in place. Treats `done:`
 *  as success and `failed:` as failure. */
private fun pollLanguageStatus(control: StoandlControl) {
    val startMs = System.currentTimeMillis()
    var sawActivity = false
    var barShown = false
    var lastDownloading = ""
    var firstPoll = true
    while (true) {
        Thread.sleep(600)
        // End the progress bar's line before withControl reports the error.
        val st = try { control.LanguageStatus() } catch (e: DBusExecutionException) { if (barShown) println(); throw e }
        val (kind, body) = splitStatus(st)
        // A successful install leaves a *sticky* `done:`/`idle:` on the watch's snapshot. On the very
        // first poll that terminal value may be the PREVIOUS install's, before our kickoff has
        // propagated — skip one cycle so we don't report a stale "Done" for a fresh install.
        if (firstPoll && !sawActivity && (kind == "done" || kind == "idle" || kind == "failed")) {
            firstPoll = false
            continue
        }
        firstPoll = false
        when (kind) {
            "downloading" -> {
                sawActivity = true
                if (body != lastDownloading) { println("Downloading $body…"); lastDownloading = body }
            }
            "installing" -> {
                sawActivity = true
                renderProgressBar("Installing", body.toIntOrNull() ?: 0); barShown = true
            }
            "done" -> {
                if (barShown) { renderProgressBar("Installing", 100); println() }
                println("Done — installed ${body.ifEmpty { "the language pack" }}.")
                return
            }
            "failed" -> {
                if (barShown) println()
                System.err.println("Language-pack install failed: ${body.ifEmpty { "unknown error" }}")
                System.exit(1); return
            }
            "notready" -> if (sawActivity) {
                if (barShown) { renderProgressBar("Installing", 100); println() }
                println("Watch disconnected during install.")
                return
            }
            // "idle" before any activity (the kickoff hasn't landed yet): keep polling.
        }
        if (System.currentTimeMillis() - startMs > 180_000) {
            if (barShown) println()
            System.err.println("Timed out waiting for the language-pack install to finish.")
            System.exit(1); return
        }
    }
}

/** Render the tab-separated locker records from ListApps() as an aligned table. */
private fun printAppList(records: List<String>) {
    if (records.isEmpty()) {
        println("No apps in locker (is a watch connected and synced?)")
        return
    }
    // record: uuid \t type \t order \t flags \t title \t developer
    data class Row(val title: String, val type: String, val developer: String, val flags: String, val uuid: String)
    val rows = records.mapNotNull { rec ->
        val f = rec.split('\t')
        if (f.size < 6) null else Row(title = f[4], type = f[1], developer = f[5], flags = f[3], uuid = f[0])
    }
    val header = Row("NAME", "TYPE", "DEVELOPER", "FLAGS", "UUID")
    val all = listOf(header) + rows
    val wName = all.maxOf { it.title.length }
    val wType = all.maxOf { it.type.length }
    val wDev = all.maxOf { it.developer.length }
    val wFlags = all.maxOf { it.flags.length }
    fun render(r: Row) = buildString {
        append(r.title.padEnd(wName)); append("  ")
        append(r.type.padEnd(wType)); append("  ")
        append(r.developer.padEnd(wDev)); append("  ")
        append(r.flags.padEnd(wFlags)); append("  ")
        append(r.uuid)
    }
    println(render(header))
    rows.forEach { println(render(it)) }
}

/** Render the watch-pref records from ListWatchPrefs() as an aligned table.
 *  Record: id \t type \t current \t default \t allowed \t flags \t name \t description */
private fun printWatchPrefs(records: List<String>, filter: String?) {
    if (records.isEmpty()) {
        println("No watch settings available (is a watch connected and synced?)")
        return
    }
    data class Row(
        val id: String, val name: String, val current: String,
        val default: String, val allowed: String, val description: String, val debug: Boolean, val help: String = "",
    )
    val rows = records.mapNotNull { rec ->
        val f = rec.split('\t')
        if (f.size < 8) null
        else Row(id = f[0], name = f[6], current = f[2], default = f[3], allowed = f[4], description = f[7], debug = f[5] == "debug",
            help = f.getOrElse(9) { "" })
    }.let { all ->
        if (filter.isNullOrBlank()) all
        else all.filter { it.id.contains(filter, true) || it.name.contains(filter, true) }
    }
    if (rows.isEmpty()) {
        println("No watch settings matching '$filter'")
        return
    }
    fun idCol(r: Row) = r.id + if (r.debug) " *" else ""
    if (!filter.isNullOrBlank()) {
        // Narrowed down: show the full detail (name + description) for each match.
        rows.forEach { r ->
            println(idCol(r))
            println("    ${r.name}" + if (r.description.isNotEmpty()) " — ${r.description}" else "")
            if (r.help.isNotEmpty()) println("    ${r.help}")
            println("    current: ${r.current}    default: ${r.default}    allowed: ${r.allowed}")
        }
    } else {
        // Overview: SETTING / NAME / CURRENT (filter to also see allowed values + descriptions).
        val header = Row("SETTING", "NAME", "CURRENT", "", "", "", false)
        val all = listOf(header) + rows
        val wId = all.maxOf { idCol(it).length }
        val wName = all.maxOf { it.name.length }
        fun render(r: Row) = buildString {
            append(idCol(r).padEnd(wId)); append("  ")
            append(r.name.padEnd(wName)); append("  ")
            append(r.current)
        }
        println(render(header))
        rows.forEach { println(render(it)) }
        if (rows.any { it.debug }) println("\n* debug/advanced setting · allowed values + descriptions: stoandl settings <name>")
    }
    println("\nSet one with:  stoandl settings set <SETTING> <value>")
}

/** stoandl's data directory: locker DB, pbw cache and PKJS settings all live here. */
private fun configDir(): File = de.yoxcu.stoandl.config.StoandlConfig.configDir()

private fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

/**
 * Resolve a user-supplied output [arg] for a generated file, sharing the logic across `support` /
 * `logs` / `screenshot`:
 *  - null → [defaultName] in the cwd
 *  - an existing directory **or** a path ending in `/` → [defaultName] inside it (so `support a/`
 *    writes `a/<default>` rather than the empty-named `a/.tar.gz`)
 *  - a path already ending in one of [exts] → used verbatim
 *  - otherwise → [arg] + the first extension
 * Any missing parent directories are created, so a not-yet-existing dir prefix just works.
 */
private fun resolveOutPath(arg: String?, defaultName: String, exts: List<String>): File {
    val target = when {
        arg == null -> File(defaultName)
        arg.endsWith("/") || File(arg).isDirectory -> File(arg, defaultName)
        exts.any { arg.endsWith(it, ignoreCase = true) } -> File(arg)
        else -> File(arg + exts.first())
    }.absoluteFile
    target.parentFile?.mkdirs()
    return target
}

/** True if a stoandl daemon is running: it holds its instance guard from the first moment of its startup
 *  (the public name only once it is up). Returns false (with a warning) if the bus can't be reached, so a
 *  detection glitch doesn't block a restore. */
private fun daemonRunning(): Boolean = try {
    instanceHeld()
} catch (e: Exception) {
    System.err.println("Warning: couldn't check daemon status: ${e.message}")
    false
}

/** Whether a daemon holds its instance guard (see [daemonRunning]); throws if the bus can't be asked. */
private fun instanceHeld(): Boolean {
    val conn = openSessionBus()
    try {
        val bus = conn.getRemoteObject(
            "org.freedesktop.DBus", "/org/freedesktop/DBus",
            org.freedesktop.dbus.interfaces.DBus::class.java,
        )
        return bus.NameHasOwner(STOANDL_INSTANCE_NAME)
    } finally {
        conn.disconnect()
    }
}

/** Run a command, passing its stdout/stderr straight through to ours. Returns the exit code. */
private fun runProcess(vararg cmd: String): Int =
    ProcessBuilder(*cmd).inheritIO().start().waitFor()

private fun doBackup(outPath: String?) {
    val dir = configDir()
    if (!dir.isDirectory) {
        System.err.println("Nothing to back up: ${dir.path} does not exist"); System.exit(1); return
    }
    val out = File(outPath ?: "stoandl-backup-${timestamp()}.tar.gz").absoluteFile
    if (daemonRunning()) {
        System.err.println("Note: daemon is running; for a guaranteed-consistent DB snapshot, stop it first.")
    }
    // Archive the directory by name relative to its parent so it restores as ~/.config/stoandl/.
    // Exclude the local secrets file: a backup tarball must never carry plaintext credentials (the
    // keyring isn't backed up either, so CalDAV passwords are simply re-entered after a restore).
    val code = runProcess(
        "tar", "czf", out.path, "--exclude=${dir.name}/secrets", "--exclude=${dir.name}/secrets.tmp",
        "-C", dir.parentFile.path, dir.name,
    )
    if (code != 0) {
        System.err.println("Backup failed (tar exit $code)"); System.exit(1); return
    }
    val size = out.length()
    println("Backed up ${dir.path} → ${out.path} (${size / 1024} KiB)")
}

private fun doRestore(inPath: String, force: Boolean) {
    val archive = File(inPath)
    if (!archive.isFile) {
        System.err.println("Backup not found: ${archive.path}"); System.exit(1); return
    }
    if (daemonRunning() && !force) {
        System.err.println("The stoandl daemon is running. Stop it before restoring:")
        System.err.println("  systemctl --user stop stoandl")
        System.err.println("(or pass --force to restore anyway — not recommended)")
        System.exit(1); return
    }
    // Sanity-check the archive: every entry must live under stoandl/ so we never scatter files
    // across ~/.config when handed the wrong tarball.
    val listing = try {
        val p = ProcessBuilder("tar", "tzf", archive.absolutePath).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        if (p.waitFor() != 0) { System.err.println("Cannot read archive:\n${lines.joinToString("\n")}"); System.exit(1); return }
        lines
    } catch (e: Exception) {
        System.err.println("Cannot read archive: ${e.message}"); System.exit(1); return
    }
    if (listing.isEmpty() || listing.any { !it.removePrefix("./").startsWith("stoandl/") && it.removePrefix("./").trimEnd('/') != "stoandl" }) {
        System.err.println("Not a stoandl backup (expected all entries under stoandl/): ${archive.path}")
        System.exit(1); return
    }

    val dir = configDir()
    val configParent = dir.parentFile
    configParent.mkdirs()

    // Move any existing config aside (reversible) rather than merging over it.
    var movedAside: File? = null
    if (dir.exists()) {
        val aside = File(configParent, "stoandl.old-${timestamp()}")
        if (!dir.renameTo(aside)) {
            System.err.println("Failed to move existing config aside (${dir.path} → ${aside.path})")
            System.exit(1); return
        }
        movedAside = aside
    }

    val code = runProcess("tar", "xzf", archive.absolutePath, "-C", configParent.path)
    if (code != 0 || !dir.isDirectory) {
        System.err.println("Restore failed (tar exit $code)")
        // Roll back: remove the half-extracted dir and put the original back.
        if (movedAside != null) {
            dir.deleteRecursively()
            if (movedAside.renameTo(dir)) System.err.println("Original config restored.")
            else System.err.println("Original config left at ${movedAside.path}")
        }
        System.exit(1); return
    }
    println("Restored ${dir.path} from ${archive.path}")
    if (movedAside != null) println("Previous config kept at ${movedAside.path}")
    println("Start the daemon to pick it up: systemctl --user start stoandl")
}

/** Dump the watch's firmware logs to a text file: with no argument a new one the daemon picks in
 *  Documents/stoandl, else the target resolved against THIS process's cwd and sent as an absolute path
 *  (the daemon writes the file, and its cwd $HOME differs from ours). */
private fun ctlLogs(rest: List<String>) {
    val arg = rest.firstOrNull { !it.startsWith("-") }
    val path = arg?.let { resolveOutPath(it, "pebble-logs-${timestamp()}.txt", listOf(".txt", ".log")).absolutePath }.orEmpty()
    withControl { control ->
        println("Gathering watch logs… (this can take a few seconds)")
        val resp = awaitTask(control, "logs") { control.GatherLogs(path) }
        val (kind, body) = splitStatus(resp)
        if (kind == "ok") println("Saved $body") else handleStatusResponse(resp)
    }
}

/** How long [awaitTask] follows a watch task before giving up on it (each transfer bounds itself well
 *  inside this; a stuck one keeps running in the daemon). */
private const val TASK_WAIT_MS = 300_000L

/** Start a daemon watch task ([kind]: sideload, screenshot, logs, coredump) with [start] and follow it
 *  through TaskStatus to its end: the start's own reply when it didn't start, else the task's result. */
private fun awaitTask(control: StoandlControl, kind: String, start: () -> String): String {
    val started = start()
    if (started != "ok:started") return started
    val deadline = System.currentTimeMillis() + TASK_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(300)
        val status = control.TaskStatus(kind)
        if (!status.startsWith("running:")) return status
    }
    return "error:still running after ${TASK_WAIT_MS / 60_000} min (stoandl keeps at it; see: stoandl status)"
}

/**
 * Assemble a support bundle (a `.tar.gz`) for sharing with a maintainer. Resilient: it always
 * gathers the host-side pieces it can read directly — the daemon log, JVM crash reports and the
 * (secret-redacted) config — even with no daemon or watch, and folds in the watch's firmware logs +
 * metadata (and, with `--coredump`, a coredump) when the daemon and a watch are reachable. What's missing is noted
 * in `bundle-notes.txt` inside the archive rather than aborting.
 */
private fun ctlSupport(rest: List<String>) {
    val wantCoredump = rest.any { it == "--coredump" }
    val outArg = rest.firstOrNull { !it.startsWith("-") }
    val stamp = timestamp()
    val out = resolveOutPath(outArg, "stoandl-support-$stamp.tar.gz", listOf(".tar.gz", ".tgz"))

    val tmpRoot = java.nio.file.Files.createTempDirectory("stoandl-support").toFile()
    val bundleDir = File(tmpRoot, "stoandl-support-$stamp").apply { mkdirs() }
    val notes = StringBuilder()
    fun note(line: String) { notes.appendLine(line); println("  $line") }
    println("Building support bundle…")

    // --- Watch-side pieces (need the daemon + a connected watch) ---
    MethodCall.setDefaultTimeout(CTL_SLOW_REPLY_TIMEOUT_MS) // the watch logs and a coredump take a while
    val conn = softOpenSessionBus()
    var daemonVersion: String? = null
    if (conn != null) {
        try {
            val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
            daemonVersion = try { control.Version() } catch (e: Exception) { null }
            if (daemonVersion == null) {
                note("daemon not responding — watch logs/info/coredump omitted")
            } else {
                val wi = try { control.WatchInfoText() } catch (e: Exception) { "error:${e.message}" }
                val (wk, wb) = splitStatus(wi)
                if (wk == "ok") File(bundleDir, "watch-info.txt").writeText(wb + "\n")
                else note("watch info unavailable: ${wb.ifEmpty { wk }}")

                println("  gathering watch logs… (a few seconds)")
                val lr = try { awaitTask(control, "logs") { control.GatherLogs(File(bundleDir, "watch-logs.txt").absolutePath) } }
                catch (e: Exception) { "error:${e.message}" }
                val (lk, lb) = splitStatus(lr)
                if (lk == "ok") note("watch logs: included") else note("watch logs unavailable: ${lb.ifEmpty { lk }}")

                if (wantCoredump) {
                    println("  fetching coredump…")
                    val cr = try { awaitTask(control, "coredump") { control.GetCoreDump(File(bundleDir, "coredump.bin").absolutePath) } }
                    catch (e: Exception) { "error:${e.message}" }
                    val (ck, cb) = splitStatus(cr)
                    when (ck) {
                        "ok" -> note("coredump: included")
                        "none" -> note("coredump: none on the watch")
                        else -> note("coredump unavailable: ${cb.ifEmpty { ck }}")
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    } else {
        note("couldn't reach D-Bus — watch logs/info/coredump omitted")
    }

    // --- Host-side pieces (read directly; no daemon needed) ---
    // Daemon log + its rotated siblings (logback writes /tmp/stoandl.log and /tmp/stoandl.<i>.log).
    val logDir = File("/tmp")
    val daemonLogs = (logDir.listFiles { f ->
        f.isFile && Regex("""stoandl(\.\d+)?\.log""").matches(f.name)
    } ?: emptyArray()).sortedBy { it.name }
    if (daemonLogs.isNotEmpty()) {
        val dest = File(bundleDir, "daemon-logs").apply { mkdirs() }
        daemonLogs.forEach { it.copyTo(File(dest, it.name), overwrite = true) }
        note("daemon log: ${daemonLogs.size} file(s)")
    } else {
        note("daemon log: none found at /tmp/stoandl*.log")
    }

    // JVM crash reports: the shipped services point -XX:ErrorFile at a directory only the daemon's user
    // can read — the systemd unit at its runtime dir ($XDG_RUNTIME_DIR), the OpenRC service at
    // /run/stoandl. Only our own files: a report holds memory and the environment. Newest 5 only — a
    // crash loop writes one per start until the unit's start limit stops it.
    val crashDirs = listOfNotNull(System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }, "/run/stoandl")
    val me = System.getProperty("user.name")
    val crashReports = crashDirs.flatMap { dir ->
        (File(dir).listFiles { f ->
            f.isFile && Regex("""stoandl-hs_err_pid\d+\.log""").matches(f.name) &&
                runCatching { java.nio.file.Files.getOwner(f.toPath()).name == me }.getOrDefault(false)
        } ?: emptyArray()).toList()
    }.sortedByDescending { it.lastModified() }.take(5)
    if (crashReports.isNotEmpty()) {
        val dest = File(bundleDir, "crash-reports").apply { mkdirs() }
        val copied = crashReports.count { runCatching { it.copyTo(File(dest, it.name), overwrite = true) }.isSuccess }
        note("JVM crash reports: $copied file(s)")
    } else {
        note("JVM crash reports: none at ${crashDirs.joinToString(" or ") { "$it/stoandl-hs_err_pid*.log" }}")
    }

    // stoandl.conf — included with secrets redacted (CalDAV passwords, credentials in URLs).
    val confFile = de.yoxcu.stoandl.config.StoandlConfig.configFile()
    if (confFile.isFile) {
        val sanitized = sanitizeConfig(confFile.readText())
        File(bundleDir, "stoandl.conf").writeText(sanitized)
        note("config: included (secrets redacted — review before sharing)")
    } else {
        note("config: no stoandl.conf (running on defaults)")
    }

    // version.txt — CLI + daemon versions and a little host context.
    File(bundleDir, "version.txt").writeText(buildString {
        appendLine("stoandl CLI:    ${BuildInfo.version}")
        appendLine("stoandl daemon: ${daemonVersion ?: "(not running / unreachable)"}")
        appendLine("OS:             ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("Java:           ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        appendLine("Generated:      ${LocalDateTime.now()}")
    })

    File(bundleDir, "bundle-notes.txt").writeText(
        "stoandl support bundle — $stamp\n\n" + notes.toString())

    out.absoluteFile.parentFile?.mkdirs()
    val code = runProcess("tar", "czf", out.path, "-C", tmpRoot.path, bundleDir.name)
    tmpRoot.deleteRecursively()
    if (code != 0) {
        System.err.println("Bundle failed (tar exit $code)"); System.exit(1); return
    }
    println()
    println("Wrote ${out.path} (${humanSize(out.length())})")
    println("Review it before sharing — config secrets are redacted, but watch logs and crash reports may contain personal data.")
}

/**
 * Dispatch `stoandl reset <factory|recovery>`: factory-reset the watch, or reboot it into recovery
 * (PRF) firmware. A factory reset is irreversible (it wipes the watch), so it requires an explicit
 * confirmation — a `yes`/`y` typed at the prompt, or `--yes`/`-y` to skip the prompt for scripts.
 */
private fun ctlReset(rest: List<String>) {
    val skipConfirm = rest.any { it == "--yes" || it == "-y" }
    val sub = rest.firstOrNull { !it.startsWith("-") }
    when (sub) {
        "factory" -> {
            if (!skipConfirm) {
                print("Factory-reset the watch? This WIPES all apps, settings and pairings and is " +
                    "irreversible. Type 'yes' to confirm: ")
                System.out.flush()
                val answer = readlnOrNull()?.trim()?.lowercase()
                if (answer != "yes" && answer != "y") {
                    println("Aborted."); return
                }
            }
            sendReset { it.FactoryReset() }
        }
        "recovery", "prf" -> sendReset { it.ResetIntoRecovery() }
        "coredump", "core-dump" -> sendReset { it.ForceCoreDump() }
        else -> {
            System.err.println("Usage: stoandl reset <factory|recovery|coredump> [--yes]")
            System.exit(EXIT_USAGE)
        }
    }
}

/** Run a reset RPC and print its status-prefixed result. */
private fun sendReset(call: (StoandlControl) -> String) = withControl { control ->
    val resp = call(control)
    handleStatusResponse(resp)
}

/** `stoandl developer <start|stop|status>` — toggle the developer connection (libpebble3's LAN
 *  WebSocket server on port 9000) so the Pebble SDK / CloudPebble can install and live-debug apps
 *  through stoandl over BLE. */
private fun ctlDeveloper(rest: List<String>) {
    withControl { control ->
        when (rest.firstOrNull()) {
            "start" -> {
                val resp = control.StartDevConnection()
                val (kind, body) = splitStatus(resp)
                if (kind != "ok") { handleStatusResponse(resp); return }
                val port = body.ifEmpty { "9000" }
                println("Developer connection started (port $port).")
                println()
                println("Install / live-debug with the Pebble SDK, from this host or another machine on the LAN:")
                val ips = lanAddresses()
                if (ips.isEmpty()) println("  pebble install --phone <this-host-ip>")
                else ips.forEach { println("  pebble install --phone $it") }
                println()
                println("⚠ The server listens on ALL interfaces (0.0.0.0:$port) with no authentication —")
                println("  anyone on your network can install apps and control the watch while it runs.")
                println("  Stop it with:  stoandl developer stop")
            }
            "stop" -> {
                val resp = control.StopDevConnection()
                val (kind, _) = splitStatus(resp)
                if (kind == "ok") println("Developer connection stopped.") else handleStatusResponse(resp)
            }
            "status" -> {
                val resp = control.DevConnectionStatus()
                val (kind, body) = splitStatus(resp)
                if (kind == "ok") println("Developer connection is ${if (body == "active") "active (port 9000)" else "inactive"}.")
                else handleStatusResponse(resp)
            }
            else -> {
                System.err.println("Usage: stoandl developer <start | stop | status>")
                System.exit(EXIT_USAGE)
            }
        }
    }
}

/** Non-loopback IPv4 addresses of up interfaces, for printing `pebble install --phone <ip>` hints. */
private fun lanAddresses(): List<String> = try {
    java.net.NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<java.net.Inet4Address>()
        .map { it.hostAddress }
        .toList()
} catch (e: Exception) {
    emptyList()
}

/** Redact secrets from a stoandl.conf before it goes into a support bundle: CalDAV passwords
 *  (`url|user|password`), and any credentials embedded in URLs (`scheme://user:pass@…` userinfo and
 *  secret-looking query params like `token`/`key`/`password`). Conservative — only touches values it
 *  recognises as secret-bearing, leaving the rest readable so misconfig is still diagnosable. */
private fun sanitizeConfig(text: String): String {
    val userinfo = Regex("""(://)[^/@\s:]+:[^/@\s]+@""")
    val secretParam = Regex("""([?&](?:token|key|apikey|api_key|auth|password|passwd|secret|sig|signature)=)[^&\s]+""", RegexOption.IGNORE_CASE)
    fun redactUrl(s: String) = s.replace(userinfo, "$1***:***@").replace(secretParam, "$1***")
    // `extension.<name>.<key>` for every key an installed extension's configSchema marks secret.
    val extSecretConfKeys = File(configDir(), "ext").listFiles()?.filter { it.isDirectory }?.flatMap { d ->
        de.yoxcu.stoandl.ext.schemaFlags(de.yoxcu.stoandl.ext.readExtManifestMeta(d).configSchemaJson)
            .secretKeys.map { "extension.${d.name}.$it" }
    }?.toSet() ?: emptySet()
    val lines = text.lines().map { raw ->
        val hash = raw.indexOf('#')
        val code = if (hash >= 0) raw.substring(0, hash) else raw
        val comment = if (hash >= 0) raw.substring(hash) else ""
        val eq = code.indexOf('=')
        if (eq <= 0) return@map raw
        val key = code.substring(0, eq).trim()
        var value = code.substring(eq + 1)
        value = when (key) {
            // calendar.caldav is now `id|url|username` (no password — that lives in the secret store, not
            // this file), so there's nothing secret to redact beyond any secret query params in the URL.
            "calendar.caldav" -> value.split(',').joinToString(",") { redactUrl(it) }
            in extSecretConfKeys -> if (value.isBlank()) value else " ***"
            else -> redactUrl(value)
        }
        code.substring(0, eq + 1) + value + comment
    }
    return "# Secrets redacted by `stoandl support`. Review before sharing.\n" + lines.joinToString("\n")
}

private fun connectDbusOrExit(): DBusConnection = try {
    openSessionBus()
} catch (e: Exception) {
    System.err.println("Cannot connect to the D-Bus session bus: ${e.message}")
    exitProcess(EXIT_UNREACHABLE)
}

/**
 * Connect to the session bus, resolve the daemon's [StoandlControl] proxy, run [block], and always
 * disconnect afterwards. Centralises the connect → getRemoteObject → try/finally-disconnect scaffold
 * that nearly every CLI subcommand needs, and the handling of a failed call: [block] calls the proxy
 * directly, and a D-Bus error ends the process with [ctlError]'s message and exit code. Inline so a
 * non-local `return` in [block] (the usual bail-after-error idiom) still runs the `finally`.
 * [replyTimeoutMs]: how long each call may take; the slow verbs pass [CTL_SLOW_REPLY_TIMEOUT_MS].
 */
private inline fun withControl(replyTimeoutMs: Long = CTL_REPLY_TIMEOUT_MS, block: (StoandlControl) -> Unit) {
    MethodCall.setDefaultTimeout(replyTimeoutMs)
    val conn = connectDbusOrExit()
    try {
        block(conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java))
    } catch (e: DBusExecutionException) {
        val (message, code) = ctlError(e) { runCatching { instanceHeld() }.getOrDefault(false) }
        System.err.println(message)
        exitProcess(code)
    } finally {
        conn.disconnect()
    }
}

/**
 * What the CLI says, and its exit code, when a call to the daemon failed with [e]. dbus-java turns the
 * error names it has a class for (`org.freedesktop.DBus.Error.X` → `org.freedesktop.dbus.errors.X`) into
 * that class; any other error, such as an exception thrown in the daemon, arrives as a plain
 * DBusExecutionException carrying only its message (dbus-java drops the name). That includes
 * `NameHasNoOwner`, which only a failed D-Bus activation sends; stoandl isn't activated, and a call to a
 * name nobody owns is `ServiceUnknown`. The daemon takes its public name only at the end of its startup,
 * holding its instance guard from the start, so `ServiceUnknown` while [instanceHeld] says it is starting
 * (exit 1, as for any call that failed, not 3: it is running).
 */
internal fun ctlError(e: DBusExecutionException, instanceHeld: () -> Boolean = { false }): Pair<String, Int> =
    when (e.javaClass.simpleName) {
        "ServiceUnknown" ->
            if (instanceHeld()) "stoandl is still starting — try again in a moment" to EXIT_FAILURE
            else "stoandl isn't running — systemctl --user start stoandl (OpenRC: rc-service stoandl start)" to EXIT_UNREACHABLE
        "UnknownMethod", "UnknownInterface" ->
            "The CLI and daemon versions differ (the daemon doesn't know this call) — see: stoandl version" to EXIT_FAILURE
        "NoReply", "Timeout" -> "stoandl didn't answer in time (it may still be working)" to EXIT_FAILURE
        else -> "Error: ${e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name}" to EXIT_FAILURE
    }

/** A VoiceStatus/TtsStatus reply as the user reads it: `off`, `ready — <backend> <detail>`, `unavailable — <why>`. */
private fun engineState(status: String): String {
    val (kind, body) = splitStatus(status)
    return when (kind) {
        "off" -> "off"
        "ready" -> "ready — ${body.replace('\t', ' ')}"
        else -> "unavailable — $body"
    }
}

// ---- status (QOL-D17) ---------------------------------------------------------------------------------

/**
 * `stoandl status`: the daemon, the watch and every service at a glance, composed from the methods the
 * other verbs use, one line each. A method an older daemon lacks just leaves its line out. Firmware comes
 * only from the check cache (FirmwareStatus), never a new online check. Exits 0 when a watch is connected,
 * 1 when none is (3 when the daemon isn't running, as everywhere).
 */
private fun ctlStatus() = withControl { control ->
    // Version() unguarded: every daemon has it, so its failure is the daemon not running (withControl's exit 3).
    val daemon = control.Version()
    fun <T> ask(call: () -> T): T? = try { call() } catch (_: DBusExecutionException) { null }
    println("stoandl ${BuildInfo.version}" + if (daemon != BuildInfo.version) " (daemon $daemon)" else "")

    val watches = ask { control.ListWatches() }.orEmpty().map { it.split('\t') }
    val live = watches.firstOrNull { it.getOrNull(1) == "connected" || it.getOrNull(1) == "recovery" }
    println("Watch: " + when {
        live != null -> "${live[0]} — ${live[1]}" +
            live.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { " over ${if (it == "ble") "BLE" else "Bluetooth Classic"}" }.orEmpty() +
            live.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { ", battery $it%" }.orEmpty()
        watches.isEmpty() -> "none paired (stoandl watch pair)"
        else -> "not connected (${watches.size} paired: ${watches.joinToString(", ") { "${it[0]} ${it.getOrElse(1) { "" }}" }})"
    })
    if (ask { control.BluetoothStatus() } == "ok:off") println("Bluetooth: off")
    ask { control.LinkPause() }?.removePrefix("ok:")?.takeIf { it.isNotBlank() }?.let { why ->
        println("Link: paused (${why.replace("quiet_time", "Quiet Time").replace(",", ", ")}) — stoandl watch connect resumes a manual pause")
    }

    val sync = ask { control.GetSyncStatus() }.orEmpty().map { it.split('\t') }
    val forwarding = sync.firstOrNull { it[0] == "notifications" }?.getOrNull(1) == "enabled"
    val muted = ask { control.NotifMuteAllStatus() }?.let(::splitStatus)
        ?.takeIf { it.first == "muted" }?.second?.toLongOrNull()
    println("Notifications: " + (if (forwarding) "forwarded" else "not forwarded (notification.forward)") +
        muted?.let { " · all muted until ${LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(it), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}" }.orEmpty())
    sync.filter { it[0] != "notifications" }.takeIf { it.isNotEmpty() }?.let { rows ->
        println("Sync: " + rows.joinToString(" · ") { f ->
            "${f[0]} " + if (f.getOrNull(1) == "enabled") f.getOrElse(3) { "on" } else f.getOrElse(3) { "off" }
        })
    }

    ask { control.FirmwareStatus() }?.let(::splitStatus)?.let { (kind, body) ->
        when {
            kind == "idle" && body.startsWith("available ") -> "${body.removePrefix("available ")} available (stoandl firmware update)"
            kind in setOf("downloading", "waiting", "inprogress", "reboot", "prf") -> "updating ($kind${if (body.isNotBlank()) " $body" else ""})"
            kind == "failed" -> "last update failed: $body"
            else -> null
        }
    }?.let { println("Firmware: $it") }

    // The watch tasks a verb follows (sideload, screenshot, logs, core dump): running, or the last result.
    WatchTasks.Kind.entries.mapNotNull { k ->
        ask { control.TaskStatus(k.token) }?.let(::splitStatus)?.takeIf { it.first != "idle" }
            ?.let { (state, body) -> "${k.token} $state" + if (body.isNotBlank()) " ($body)" else "" }
    }.takeIf { it.isNotEmpty() }?.let { println("Watch tasks: " + it.joinToString(" · ")) }

    val exts = ask { control.ExtList() }.orEmpty().map { it.split('\t') }.filter { it.getOrNull(2) == "enabled" }
    if (exts.isNotEmpty()) println("Extensions: " + exts.joinToString(", ") { "${it[0]} ${it.getOrElse(3) { "" }}" })
    ask { control.PermPending() }?.takeIf { it.isNotEmpty() }?.let {
        println("Permission requests: ${it.size} waiting (stoandl perms pending)")
    }

    ask { control.VoiceStatus() }?.let { println("Speech to text: ${engineState(it)}") }
    ask { control.TtsStatus() }?.let { println("Text to speech: ${engineState(it)}") }
    if (ask { control.DevConnectionStatus() } == "ok:active") println("Developer connection: on (LAN port 9000)")

    if (live == null) exitProcess(EXIT_FAILURE)
}

// ---- voice group (speech to text, text to speech) ---------------------------------------------------

/** `stoandl voice [status]` shows the speech-to-text and text-to-speech backends; `models`/`download`/`use`/
 *  `remove`/`cancel` manage the local speech models and voices (a download makes the model or voice the active
 *  one: see VoiceDownloadModel); `test <file>` transcribes a WAV or Ogg/Opus file (the daemon reads it, so the
 *  path is made absolute here); `say <text>` reads text aloud on this device and `stop` stops it. */
private fun ctlVoice(rest: List<String>) {
    when (rest.firstOrNull() ?: "status") {
        "status" -> withControl { control ->
            val lines = listOf(
                Triple("Speech to text", control.VoiceStatus(), "stoandl voice models, then stoandl voice download <model>"),
                Triple("Text to speech", control.TtsStatus(), "stoandl voice download <voice>, or install espeak-ng and run stoandl voice use espeak-ng"),
            )
            for ((what, status, hint) in lines) {
                println("$what: ${engineState(status)}" + if (status.startsWith("off:")) " (to turn it on: $hint)" else "")
            }
            if (lines.any { (_, status, _) -> status.startsWith("unavailable:") }) System.exit(1)
        }
        "say" -> {
            val text = rest.drop(1).joinToString(" ").trim()
            if (text.isEmpty()) { System.err.println("Usage: stoandl voice say <text>"); System.exit(EXIT_USAGE); return }
            withControl { control ->
                val (kind, body) = splitStatus(control.Speak(text))
                when (kind) {
                    // The daemon answers before the audio is made; a failure after that is in its log.
                    "ok" -> println("Reading aloud — stoandl voice stop to stop")
                    "off" -> { System.err.println("Text to speech is off (tts.backend)"); System.exit(1) }
                    else -> { System.err.println("${kind.replaceFirstChar { it.uppercase() }}: $body"); System.exit(1) }
                }
            }
        }
        "stop" -> withControl { control -> println(splitStatus(control.StopSpeaking()).second) }
        "test" -> {
            val file = rest.getOrNull(1)?.let { File(it).absoluteFile }
                ?: run { System.err.println("Usage: stoandl voice test <file.wav|file.ogg>"); System.exit(EXIT_USAGE); return }
            withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
                println("Transcribing ${file.name}…")
                val (kind, body) = splitStatus(control.Transcribe(file.path))
                when (kind) {
                    "ok" -> { val (ms, text) = body.split('\t', limit = 2).let { it[0] to it.getOrElse(1) { "" } }; println(text.ifEmpty { "(no speech)" }); println("($ms ms)") }
                    "off" -> { System.err.println("Voice is off (voice.backend)"); System.exit(1) }
                    else -> { System.err.println("${kind.replaceFirstChar { it.uppercase() }}: $body"); System.exit(1) }
                }
            }
        }
        "models" -> withControl { control ->
            val rows = control.VoiceModels().map { it.split('\t') }
            // Records without the kind column come from a daemon that only had speech-to-text models.
            for ((kind, heading) in listOf("stt" to "Speech to text (voice.local_model)", "tts" to "Text to speech (tts.local_voice)")) {
                val ofKind = rows.filter { it.getOrElse(8) { "stt" } == kind }
                if (ofKind.isEmpty()) continue
                println(heading)
                ofKind.forEach { r ->
                    val state = r.getOrElse(4) { "" }
                    val mark = when {
                        state == "installed" -> "installed"
                        state.startsWith("downloading:") -> "downloading ${state.substringAfter(':')}%"
                        state.startsWith("failed:") -> "failed: ${state.substringAfter(':')}"
                        else -> "${r.getOrElse(3) { "?" }} MB"
                    }
                    val rec = if (r.getOrElse(5) { "" } == "true") " (recommended)" else ""
                    println("  %-26s %-28s %s%s".format(r.getOrElse(0) { "" }, r.getOrElse(1) { "" }, mark, rec))
                    println("  %-26s %s".format("", r.getOrElse(7) { "" }))
                    if (r.getOrElse(6) { "true" } != "true") {
                        val pkg = if (kind == "tts") "piper-tts" else "whisper.cpp"
                        println("  %-26s needs ${r.getOrElse(2) { "" }} (install $pkg)".format(""))
                    }
                }
            }
        }
        "download" -> {
            val id = rest.getOrNull(1) ?: run { System.err.println("Usage: stoandl voice download <model> (see stoandl voice models)"); System.exit(EXIT_USAGE); return }
            withControl { control ->
                val (kind, body) = splitStatus(control.VoiceDownloadModel(id))
                if (kind != "ok") { println(body); if (kind != "installed") System.exit(1); return@withControl }
                println(body)
                // Follow the download until it lands (Ctrl-C leaves it running in the daemon).
                while (true) {
                    Thread.sleep(2000)
                    val row = control.VoiceModels().map { it.split('\t') }.firstOrNull { it[0] == id } ?: break
                    val state = row.getOrNull(4) ?: break
                    when {
                        state.startsWith("downloading:") -> print("\r  ${state.substringAfter(':')}% ")
                        state == "installed" -> {
                            val status = if (row.getOrNull(8) == "tts") control.TtsStatus() else control.VoiceStatus()
                            println("\r  done — ${splitStatus(status).second.replace('\t', ' ')}"); break
                        }
                        state.startsWith("failed:") -> { println("\r  failed: ${state.substringAfter(':')}"); System.exit(1); return@withControl }
                        else -> break
                    }
                }
            }
        }
        "use" -> {
            val id = rest.getOrNull(1) ?: run { System.err.println("Usage: stoandl voice use <model|voice>"); System.exit(EXIT_USAGE); return }
            withControl { control ->
                // A voice (or espeak-ng) sets text to speech; anything else is a speech-to-text model.
                val tts = id == ESPEAK_VOICE || control.VoiceModels().any { r -> r.split('\t').let { it[0] == id && it.getOrNull(8) == "tts" } }
                val (key, backend) = if (tts) "tts.local_voice" to "tts.backend" else "voice.local_model" to "voice.backend"
                printStatusOrExit(control.SetConfig(key, id))
                printStatusOrExit(control.SetConfig(backend, "local"))
            }
        }
        "remove" -> {
            val id = rest.getOrNull(1) ?: run { System.err.println("Usage: stoandl voice remove <model>"); System.exit(EXIT_USAGE); return }
            withControl { control -> printStatusOrExit(control.VoiceRemoveModel(id)) }
        }
        "cancel" -> withControl { control -> println(splitStatus(control.VoiceCancelDownload()).second) }
        else -> { System.err.println("Usage: stoandl voice [status | models | download <model> | use <model> | remove <model> | cancel | test <file> | say <text> | stop]"); System.exit(EXIT_USAGE) }
    }
}

// ---- plugins and app permissions ---------------------------------------------------------------------

private fun printStatusOrExit(resp: String) {
    val (kind, body) = splitStatus(resp)
    if (kind == "ok") println(body)
    else { System.err.println("${kind.replaceFirstChar { it.uppercase() }}: $body"); System.exit(1) }
}

/** `stoandl plugins [list]`, `plugins read <category>/<item> [plugin]`, `plugins invoke <plugin> <action> [json]`. */
private fun ctlPlugins(rest: List<String>) {
    when (rest.firstOrNull() ?: "list") {
        "list" -> withControl { control ->
            val rows = control.PluginList().map { it.split('\t') }
            if (rows.isEmpty()) { println("No plugins (plugins.enabled is off, or none installed)"); return@withControl }
            rows.forEach { r ->
                println("${r.getOrElse(2) { "?" }}  [${r.getOrElse(1) { "" }}]  ${r.getOrElse(0) { "" }}")
                r.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { println("    $it") }
                r.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { println("    sources: ${it.replace(",", ", ")}") }
                r.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { println("    actions: ${it.replace(",", ", ")}") }
            }
        }
        "read" -> {
            val what = rest.getOrNull(1)?.takeIf { '/' in it }
                ?: run { System.err.println("Usage: stoandl plugins read <category>/<item> [plugin-uuid]"); System.exit(EXIT_USAGE); return }
            withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control ->
                printStatusOrExit(control.PluginRead(what.substringBefore('/'), what.substringAfter('/'), rest.getOrElse(2) { "" }))
            }
        }
        "invoke" -> {
            if (rest.size < 3) { System.err.println("Usage: stoandl plugins invoke <plugin> <action> [json-args]"); System.exit(EXIT_USAGE); return }
            withControl(CTL_SLOW_REPLY_TIMEOUT_MS) { control -> printStatusOrExit(control.PluginInvoke(rest[1], rest[2], rest.getOrElse(3) { "" })) }
        }
        else -> { System.err.println("Usage: stoandl plugins [list | read <category>/<item> [plugin] | invoke <plugin> <action> [json]]"); System.exit(EXIT_USAGE) }
    }
}

/** `stoandl perms [pending]`, `perms list <app>`, `perms allow|deny|reset <app> <permission>`, `perms later <app> [permission]`. */
private fun ctlPerms(rest: List<String>) {
    val usage = "Usage: stoandl perms [pending | list <app> | allow|deny|reset <app> <permission> | later <app> [permission]]"
    when (val sub = rest.firstOrNull() ?: "pending") {
        "pending" -> withControl { control ->
            val rows = control.PermPending().map { it.split('\t') }
            if (rows.isEmpty()) println("No permission requests waiting")
            rows.forEach { r ->
                val notes = listOfNotNull(
                    "not running".takeIf { r.getOrNull(5) == "false" },
                    "put off".takeIf { r.getOrNull(6) == "true" },
                ).joinToString(", ")
                println("${r.getOrElse(1) { "?" }}: ${r.getOrElse(3) { "" }}  (${r.getOrElse(2) { "" }})" + if (notes.isEmpty()) "" else "  [$notes]")
            }
        }
        "list" -> {
            val app = rest.getOrNull(1) ?: run { System.err.println("Usage: stoandl perms list <app>"); System.exit(EXIT_USAGE); return }
            withControl { control ->
                val rows = control.PermList(app).map { it.split('\t') }
                if (rows.isEmpty()) println("No permissions recorded for '$app' (or no such app)")
                rows.forEach { r -> println("%-8s %-13s %s  (%s)".format(r.getOrElse(1) { "" }, r.getOrElse(2) { "" }, r.getOrElse(3) { "" }, r.getOrElse(0) { "" })) }
            }
        }
        "allow", "deny", "reset" -> {
            if (rest.size < 3) { System.err.println("Usage: stoandl perms $sub <app> <permission>"); System.exit(EXIT_USAGE); return }
            withControl { control -> printStatusOrExit(control.PermSet(rest[1], rest[2], sub)) }
        }
        "later" -> {
            if (rest.size < 2) { System.err.println("Usage: stoandl perms later <app> [permission]"); System.exit(EXIT_USAGE); return }
            withControl { control -> printStatusOrExit(control.PermSet(rest[1], rest.getOrElse(2) { "" }, sub)) }
        }
        else -> { System.err.println(usage); System.exit(EXIT_USAGE) }
    }
}
