package de.yoxcu.stoandl

import co.touchlab.kermit.Logger
import de.yoxcu.stoandl.dbus.IncomingNotification
import de.yoxcu.stoandl.dbus.STOANDL_BUS_NAME
import de.yoxcu.stoandl.dbus.STOANDL_OBJECT_PATH
import de.yoxcu.stoandl.dbus.StoandlControl
import de.yoxcu.stoandl.dbus.monitorNotifications
import de.yoxcu.stoandl.pebble.PebbleIntegration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import de.yoxcu.stoandl.calendar.ICalParser
import de.yoxcu.stoandl.language.LanguagePackCatalog
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

private val CTL_COMMANDS = setOf("sideload", "add", "config", "fakecall", "findwatch", "apps", "launch", "remove", "backup", "restore", "weather", "settings", "set-setting", "pair", "unpair", "repair", "list", "calendar", "datalog", "firmware", "language", "screenshot", "logs", "support")

private val HELP_FLAGS = setOf("help", "--help", "-h")
private val VERSION_FLAGS = setOf("version", "--version", "-v")

fun main(args: Array<String>) {
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

    Logger.setLogWriters(KermitSlf4jWriter())
    log.info { "stoandl ${BuildInfo.version} starting" }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val notificationBus = MutableSharedFlow<IncomingNotification>(extraBufferCapacity = 64)

    val serviceConn = DBusConnectionBuilder.forSessionBus()
        .withShared(false)
        .build() as DBusConnection
    serviceConn.requestBusName(STOANDL_BUS_NAME)
    log.info { "D-Bus bus name acquired: $STOANDL_BUS_NAME" }

    val pebble = PebbleIntegration(notificationBus, scope, serviceConn)
    pebble.init()

    // Start DBus notification monitor and feed into libpebble3
    monitorNotifications()
        .onEach { notification ->
            notificationBus.emit(notification)
        }
        .launchIn(scope)

    log.info { "stoandl running — press Ctrl-C to stop" }

    runBlocking {
        // Keep the process alive until interrupted
        val latch = java.util.concurrent.CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info { "stoandl shutting down" }
            pebble.gracefulShutdown()
            serviceConn.releaseBusName(STOANDL_BUS_NAME)
            serviceConn.disconnect()
            latch.countDown()
        })
        latch.await()
    }
}

private fun printUsage() {
    println("stoandl — Pebble companion daemon for Linux")
    println()
    println("Usage:")
    println("  stoandl                    Run the daemon (foreground; used by the systemd service)")
    println("  stoandl <command> [args]")
    println()
    println("Commands:")
    println("  apps                       List the apps and watchfaces in the watch locker")
    println("  launch <name|uuid>         Launch an app or watchface on the watch")
    println("  remove <name|uuid>         Uninstall an app or watchface from the locker")
    println("  sideload <path>            Install a .pbw watchface or app onto the watch (alias: add)")
    println("  config [app]               Open a PKJS app's Clay config page (launches the app if needed)")
    println("  backup [out.tar.gz]        Archive the locker, app cache and PKJS settings")
    println("  restore <in.tar.gz>        Restore a backup (daemon must be stopped; --force to override)")
    println("  fakecall ring [name] [number]   Debug: ring the watch with a synthetic call")
    println("  fakecall end               Debug: clear the synthetic call")
    println("  findwatch                  Ring the watch to find it (press a watch button to silence)")
    println("  version                    Show the running daemon's version (and this CLI's)")
    println("  weather                    Fetch weather now and push it to the watch")
    println("  settings [filter]          List the watch's advanced settings (optionally filtered)")
    println("  set-setting <id> <value>   Set a watch setting (e.g. set-setting lightAmbientThreshold 200)")
    println("  pair                       Pair a new Pebble watch (opens a ~2 min window; blocks until done)")
    println("  unpair                     Forget the watch on this host (use after moving it to another device)")
    println("  repair <name>              Re-pair one specific watch (forgets just it, then opens a pairing window)")
    println("  list                       List known watches and their connection state")
    println("  calendar [list|sync|enable|disable|dump]   Calendar→timeline sync (dump <file|url> works offline)")
    println("  datalog [list|dump|tail]   Inspect captured watchapp datalog (set datalog.enabled in stoandl.conf)")
    println("  firmware <file.pbz>        Flash a local firmware bundle onto the watch (shows progress)")
    println("  firmware check             Check the PebbleOS GitHub for newer firmware (needs firmware.github)")
    println("  firmware update            Download+flash the latest matching firmware from GitHub")
    println("  firmware status            Show the current firmware-update state")
    println("  language [list]            List language packs for the watch (or the full catalog if none connected)")
    println("  language sideload <file.pbl>   Install a local language pack onto the watch")
    println("  language install <locale>  Download+install a pack (e.g. de_DE; needs language.download)")
    println("  language status            Show the current language-pack install state")
    println("  screenshot [path]          Capture the watch screen to a PNG (default: ./pebble-screenshot-<time>.png)")
    println("  logs [path]                Dump the watch's firmware logs to a text file (default: ./pebble-logs-<time>.txt)")
    println("  support [out.tar.gz]       Build a support bundle (watch logs + watch info + daemon log + config, secrets redacted)")
    println("                             Add --coredump to also pull a coredump off the watch")
    println("  help                       Show this help")
}

/** Report the running daemon's version (over D-Bus), falling back to this CLI's own embedded version. */
private fun printVersion() {
    val cliVersion = BuildInfo.version
    val conn = try {
        DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection
    } catch (e: Exception) {
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
    if (args.isEmpty()) {
        printUsage()
        return
    }
    when (args[0]) {
        "sideload", "add" -> {
            if (args.size < 2) {
                System.err.println("Usage: stoandl ${args[0]} <path>")
                System.exit(1); return
            }
            // Resolve against THIS process's working directory and send an absolute path: the daemon
            // runs as a systemd user service with its own cwd ($HOME), so a relative path would be
            // looked up in the wrong place (and surface as a misleading "Pbw does not contain manifest").
            val file = File(args[1])
            if (!file.isFile) {
                System.err.println("No such file: ${args[1]}"); System.exit(1); return
            }
            val path = file.absolutePath
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val resp = try { control.SideloadApp(path) } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                handleStatusResponse(resp)
            } finally {
                conn.disconnect()
            }
        }
        "firmware" -> ctlFirmware(args.drop(1))
        "language" -> ctlLanguage(args.drop(1))
        "apps" -> {
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val records = try { control.ListApps() } catch (e: Exception) {
                    System.err.println("Error contacting daemon: ${e.message}"); System.exit(1); return
                }
                printAppList(records)
            } finally {
                conn.disconnect()
            }
        }
        "backup" -> {
            val out = args.drop(1).firstOrNull { !it.startsWith("-") }
            doBackup(out)
        }
        "restore" -> {
            val rest = args.drop(1)
            val force = rest.any { it == "--force" || it == "-f" }
            val path = rest.firstOrNull { !it.startsWith("-") }
            if (path == null) {
                System.err.println("Usage: stoandl restore <in.tar.gz> [--force]"); System.exit(1); return
            }
            doRestore(path, force)
        }
        "launch", "remove" -> {
            if (args.size < 2) {
                System.err.println("Usage: stoandl ${args[0]} <name|uuid>"); System.exit(1)
            }
            val query = args.drop(1).joinToString(" ")
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val resp = try {
                    if (args[0] == "launch") control.LaunchApp(query) else control.RemoveApp(query)
                } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                handleStatusResponse(resp)
            } finally {
                conn.disconnect()
            }
        }
        "config" -> {
            val app = args.getOrNull(1) ?: ""
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val configUrl = try { control.OpenConfig(app) } catch (e: Exception) {
                    System.err.println("Error contacting daemon: ${e.message}")
                    System.exit(1); return
                }
                if (configUrl.isEmpty()) {
                    System.err.println("No config page available (app not found, not configurable, or it didn't start in time)")
                    System.exit(1)
                    return
                }
                runConfigProxy(configUrl) { data -> control.WebviewClose(data) }
            } finally {
                conn.disconnect()
            }
        }
        "fakecall" -> {
            val sub = args.getOrNull(1) ?: "ring"
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
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
                        System.exit(1)
                    }
                }
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
                System.exit(1)
            } finally {
                conn.disconnect()
            }
        }
        "findwatch" -> {
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                if (control.FindWatch()) println("Ringing watch — press a button on the watch to silence it")
                else { System.err.println("Daemon not ready (no watch connected?)"); System.exit(1) }
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
                System.exit(1)
            } finally {
                conn.disconnect()
            }
        }
        "weather" -> {
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val resp = try { control.SyncWeather() } catch (e: Exception) {
                    System.err.println("Error contacting daemon: ${e.message}"); System.exit(1); return
                }
                handleStatusResponse(resp)
            } finally {
                conn.disconnect()
            }
        }
        "settings" -> {
            val filter = args.getOrNull(1)
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val records = try { control.ListWatchPrefs() } catch (e: Exception) {
                    System.err.println("Error contacting daemon: ${e.message}"); System.exit(1); return
                }
                printWatchPrefs(records, filter)
            } finally {
                conn.disconnect()
            }
        }
        "set-setting" -> {
            if (args.size < 3) {
                System.err.println("Usage: stoandl set-setting <id> <value>"); System.exit(1); return
            }
            val id = args[1]
            val value = args.drop(2).joinToString(" ")
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val resp = try { control.SetWatchPref(id, value) } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                handleStatusResponse(resp)
            } finally {
                conn.disconnect()
            }
        }
        "pair" -> {
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val startResp = try { control.Pair() } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                if (!startResp.startsWith("ok:")) { handleStatusResponse(startResp); return }
                println("Searching for watch to pair (up to 2 minutes)...")
                pollPairStatus(control)
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}"); System.exit(1)
            } finally {
                conn.disconnect()
            }
        }
        "repair" -> {
            if (args.size < 2) {
                System.err.println("Usage: stoandl repair <watch name>"); System.exit(1); return
            }
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val startResp = try { control.Repair(args[1]) } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                if (!startResp.startsWith("ok:")) { handleStatusResponse(startResp); return }
                println(startResp.removePrefix("ok:"))
                pollPairStatus(control)
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}"); System.exit(1)
            } finally {
                conn.disconnect()
            }
        }
        "unpair" -> {
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                handleStatusResponse(control.Unpair())
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}"); System.exit(1)
            } finally {
                conn.disconnect()
            }
        }
        "list" -> {
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val watches = control.ListWatches()
                if (watches.isEmpty()) {
                    println("No known watches. Run 'stoandl pair' to add one.")
                } else {
                    watches.forEach { entry ->
                        val parts = entry.split('\t')
                        println("  %-24s %s".format(parts.getOrElse(0) { entry }, parts.getOrElse(1) { "" }))
                    }
                }
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}"); System.exit(1)
            } finally {
                conn.disconnect()
            }
        }
        "calendar" -> {
            when (val sub = args.getOrNull(1) ?: "list") {
                "dump" -> {
                    val src = args.getOrNull(2)
                    if (src == null) {
                        System.err.println("Usage: stoandl calendar dump <file.ics|url>"); System.exit(1); return
                    }
                    dumpCalendar(src)
                }
                "list" -> {
                    val conn = connectDbusOrExit() ?: return
                    try {
                        val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                        val cals = try { control.ListCalendars() } catch (e: Exception) {
                            System.err.println("Error contacting daemon: ${e.message}"); System.exit(1); return
                        }
                        if (cals.isEmpty()) {
                            println("No calendars synced (configure calendar.* in stoandl.conf, or none discovered yet).")
                        } else {
                            cals.forEach { entry ->
                                val p = entry.split('\t')
                                println("  %-4s %-30s %s".format(p.getOrElse(0) { "" }, p.getOrElse(1) { entry }, p.getOrElse(2) { "" }))
                            }
                            println("\nToggle one with:  stoandl calendar disable <id|name>")
                        }
                    } finally { conn.disconnect() }
                }
                "sync" -> {
                    val conn = connectDbusOrExit() ?: return
                    try {
                        val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                        handleStatusResponse(try { control.SyncCalendar() } catch (e: Exception) {
                            System.err.println("Error contacting daemon: ${e.message}"); System.exit(1); return
                        })
                    } finally { conn.disconnect() }
                }
                "enable", "disable" -> {
                    val query = args.drop(2).joinToString(" ")
                    if (query.isBlank()) {
                        System.err.println("Usage: stoandl calendar $sub <id|name>"); System.exit(1); return
                    }
                    val conn = connectDbusOrExit() ?: return
                    try {
                        val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                        handleStatusResponse(try { control.SetCalendarEnabled(query, sub == "enable") } catch (e: Exception) {
                            System.err.println("Error: ${e.message}"); System.exit(1); return
                        })
                    } finally { conn.disconnect() }
                }
                else -> {
                    System.err.println("Usage: stoandl calendar <list|sync|enable <id|name>|disable <id|name>|dump <file|url>>")
                    System.exit(1)
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
                        System.exit(1); return
                    }
                    datalogShow(pos[0], pos.getOrNull(1), tail = if (sub == "tail") n else null)
                }
                else -> {
                    System.err.println("Usage: stoandl datalog <list | dump <uuid> [tag] | tail <uuid> [tag] [-n lines]>")
                    System.exit(1)
                }
            }
        }
        "screenshot" -> {
            // Resolve the target against THIS process's cwd and send an absolute path: the daemon writes
            // the file, and its cwd ($HOME) differs from the caller's. Default to a timestamped name;
            // accept an explicit file or a directory; tack on .png if missing.
            val arg = args.getOrNull(1)
            val target = when {
                arg == null -> File("pebble-screenshot-${timestamp()}.png")
                File(arg).isDirectory -> File(arg, "pebble-screenshot-${timestamp()}.png")
                arg.endsWith(".png", ignoreCase = true) -> File(arg)
                else -> File("$arg.png")
            }
            val path = target.absolutePath
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                println("Capturing watch screen…")
                val resp = try { control.TakeScreenshot(path) } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                val (kind, body) = splitStatus(resp)
                if (kind == "ok") {
                    val f = body.split('\t')
                    val saved = f.getOrElse(0) { path }
                    val dims = if (f.size >= 3) " (${f[1]}×${f[2]})" else ""
                    println("Saved $saved$dims")
                } else {
                    handleStatusResponse(resp)
                }
            } finally {
                conn.disconnect()
            }
        }
        "logs" -> ctlLogs(args.drop(1))
        "support" -> ctlSupport(args.drop(1))
        in VERSION_FLAGS -> printVersion()
        in HELP_FLAGS -> printUsage()
        else -> {
            System.err.println("Unknown command: ${args[0]}")
            System.err.println()
            printUsage()
            System.exit(1)
        }
    }
}

/** Parse a `status:message` response from a control method and exit non-zero on failure. */
private fun handleStatusResponse(resp: String) {
    val idx = resp.indexOf(':')
    val status = if (idx >= 0) resp.substring(0, idx) else resp
    val message = if (idx >= 0) resp.substring(idx + 1) else ""
    if (status == "ok") {
        println(message)
    } else {
        System.err.println(message.ifEmpty { status })
        System.exit(1)
    }
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
    val dateFmt = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd").withZone(zone)
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

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}

/** Polls PairStatus() until the pairing window resolves, printing pending messages as they change.
 *  Shared by the `pair` and `repair` commands (both open the same pairing window). */
private fun pollPairStatus(control: StoandlControl) {
    var lastPendingMsg = ""
    val startMs = System.currentTimeMillis()
    while (true) {
        Thread.sleep(1_500)
        val status = try { control.PairStatus() } catch (e: Exception) {
            System.err.println("Error: ${e.message}"); System.exit(1); return
        }
        if (status.startsWith("pending:")) {
            val msg = status.removePrefix("pending:")
            if (msg.isNotEmpty() && msg != lastPendingMsg) { println(msg); lastPendingMsg = msg }
        } else {
            handleStatusResponse(status); return
        }
        if (System.currentTimeMillis() - startMs > 145_000) {
            System.err.println("Pairing timed out"); System.exit(1); return
        }
    }
}

/** Dispatch `stoandl firmware ...`: a local `.pbz` path to flash, or `check`/`update`/`status`. */
private fun ctlFirmware(rest: List<String>) {
    val sub = rest.firstOrNull()
    if (sub == null) {
        System.err.println("Usage: stoandl firmware <file.pbz> | check | update | status")
        System.exit(1); return
    }
    val conn = connectDbusOrExit() ?: return
    try {
        val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
        when (sub) {
            "check" -> {
                val resp = try { control.CheckFirmware() } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                printFirmwareCheck(resp)
            }
            "update" -> {
                val resp = try { control.UpdateFirmware() } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                val (kind, body) = splitStatus(resp)
                when (kind) {
                    "ok" -> {
                        val f = body.split('\t')
                        println("Updating ${f.getOrElse(0) { "watch" }}: " +
                            "${f.getOrElse(1) { "?" }} → ${f.getOrElse(2) { "?" }} (${f.getOrElse(3) { "firmware" }})")
                        pollFirmwareStatus(control)
                    }
                    "uptodate", "noasset" -> println(body)
                    else -> handleStatusResponse(resp) // busy / disabled / notready / error
                }
            }
            "status" -> {
                val resp = try { control.FirmwareStatus() } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                printFirmwareStatusOnce(resp)
            }
            else -> {
                // Anything else is treated as a path to a local .pbz to sideload.
                val file = File(sub)
                if (!file.isFile) { System.err.println("No such file: $sub"); System.exit(1); return }
                if (!file.name.endsWith(".pbz")) {
                    System.err.println("Not a firmware bundle (expected a .pbz): $sub"); System.exit(1); return
                }
                // Send an absolute path: the daemon's cwd differs from the caller's.
                val resp = try { control.SideloadFirmware(file.absolutePath) } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                if (!resp.startsWith("ok:")) { handleStatusResponse(resp); return }
                println("Flashing ${file.name} onto the watch — keep it close and charged.")
                pollFirmwareStatus(control)
            }
        }
    } finally {
        conn.disconnect()
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
            println("Watch board:    $board")
            println("Running:        $current")
            println("Latest on repo: $latest")
            if (newer == "yes") println("→ Update available ($asset). Run: stoandl firmware update")
            else println("→ Up to date.")
        }
        "noasset" -> {
            val f = body.split('\t')
            val board = f.getOrElse(0) { "?" }
            val current = f.getOrElse(1) { "?" }
            val latest = f.getOrElse(2) { "?" }
            println("No firmware published for board '$board' on the configured repo " +
                "(latest release $latest, running $current).")
            println("That's expected for classic Pebbles — only Core devices publish there.")
        }
        else -> handleStatusResponse(resp) // disabled / notready / error
    }
}

private fun printFirmwareStatusOnce(resp: String) {
    val (kind, body) = splitStatus(resp)
    val human = when (kind) {
        "idle" -> "Idle (no firmware update in progress)"
        "downloading" -> "Downloading $body…"
        "waiting" -> "Starting firmware transfer…"
        "inprogress" -> "Flashing: $body%"
        "reboot" -> "Transfer complete — watch rebooting to apply"
        "failed" -> "Last firmware update failed: ${body.ifEmpty { "unknown error" }}"
        "notready" -> body.ifEmpty { "No watch connected" }
        else -> resp
    }
    println(human)
}

/** Polls FirmwareStatus() while a flash runs, rendering a progress bar in place. Treats `reboot:`
 *  (or a disconnect after activity) as success and `failed:` as failure. */
private fun pollFirmwareStatus(control: StoandlControl) {
    val startMs = System.currentTimeMillis()
    var sawActivity = false
    var barShown = false
    var lastDownloading = ""
    while (true) {
        Thread.sleep(800)
        val st = try { control.FirmwareStatus() } catch (e: Exception) {
            if (barShown) System.err.println()
            System.err.println("Error: ${e.message}"); System.exit(1); return
        }
        val (kind, body) = splitStatus(st)
        when (kind) {
            "downloading" -> {
                sawActivity = true
                if (body != lastDownloading) { println("Downloading $body…"); lastDownloading = body }
            }
            "waiting" -> sawActivity = true
            "inprogress" -> {
                sawActivity = true
                renderFirmwareBar(body.toIntOrNull() ?: 0); barShown = true
            }
            "reboot" -> {
                if (barShown) { renderFirmwareBar(100); println() }
                println("Done — watch rebooting to apply the firmware.")
                return
            }
            "failed" -> {
                if (barShown) println()
                System.err.println("Firmware update failed: ${body.ifEmpty { "unknown error" }}")
                System.exit(1); return
            }
            "notready" -> if (sawActivity) {
                // The watch reboots and drops the link once the transfer completes.
                if (barShown) { renderFirmwareBar(100); println() }
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

private fun renderFirmwareBar(pct: Int) {
    val clamped = pct.coerceIn(0, 100)
    val width = 20
    val filled = clamped * width / 100
    val bar = "#".repeat(filled) + "-".repeat(width - filled)
    print("\rFlashing [$bar] %3d%%".format(clamped))
    System.out.flush()
}

/** Dispatch `stoandl language ...`: `catalog`/`search` (offline), `list`/`sideload`/`install`/`status`. */
private fun ctlLanguage(rest: List<String>) {
    val sub = rest.firstOrNull() ?: "list"
    // `list` is resilient: it shows the watch's packs when the daemon+watch are there, and otherwise
    // falls back to the full bundled catalog (offline), so it works before pairing / with no daemon.
    if (sub == "list") { ctlLanguageList(); return }
    val conn = connectDbusOrExit() ?: return
    try {
        val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
        when (sub) {
            "status" -> {
                val resp = try { control.LanguageStatus() } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                printLanguageStatusOnce(resp)
            }
            "sideload", "add" -> {
                val path = rest.getOrNull(1)
                if (path == null) {
                    System.err.println("Usage: stoandl language sideload <file.pbl>"); System.exit(1); return
                }
                val file = File(path)
                if (!file.isFile) { System.err.println("No such file: $path"); System.exit(1); return }
                if (!file.name.endsWith(".pbl")) {
                    System.err.println("Not a language pack (expected a .pbl): $path"); System.exit(1); return
                }
                // Absolute path: the daemon's cwd differs from the caller's.
                val resp = try { control.SideloadLanguage(file.absolutePath) } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                if (!resp.startsWith("ok:")) { handleStatusResponse(resp); return }
                println("Installing ${file.name} onto the watch — keep it close.")
                pollLanguageStatus(control)
            }
            "install" -> {
                val query = rest.drop(1).joinToString(" ")
                val resp = try { control.InstallLanguage(query) } catch (e: Exception) {
                    System.err.println("Error: ${e.message}"); System.exit(1); return
                }
                if (!resp.startsWith("ok:")) { handleStatusResponse(resp); return }
                println("Installing ${resp.removePrefix("ok:")} — downloading then transferring to the watch.")
                pollLanguageStatus(control)
            }
            else -> {
                System.err.println("Usage: stoandl language [list | sideload <file.pbl> | install <locale|name|id> | status]")
                System.exit(1)
            }
        }
    } finally {
        conn.disconnect()
    }
}

/**
 * `stoandl language list`: the watch's installable packs (board-filtered, installed pack marked) when
 * the daemon and a watch are present, else the full bundled catalog. Uses a soft D-Bus connect so a
 * missing daemon (or no watch) degrades to the offline catalog instead of erroring out.
 */
private fun ctlLanguageList() {
    val conn = try {
        DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection
    } catch (e: Exception) {
        printFullCatalog(); return
    }
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
        val st = try { control.LanguageStatus() } catch (e: Exception) {
            if (barShown) System.err.println()
            System.err.println("Error: ${e.message}"); System.exit(1); return
        }
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
                renderLanguageBar(body.toIntOrNull() ?: 0); barShown = true
            }
            "done" -> {
                if (barShown) { renderLanguageBar(100); println() }
                println("Done — installed ${body.ifEmpty { "the language pack" }}.")
                return
            }
            "failed" -> {
                if (barShown) println()
                System.err.println("Language-pack install failed: ${body.ifEmpty { "unknown error" }}")
                System.exit(1); return
            }
            "notready" -> if (sawActivity) {
                if (barShown) { renderLanguageBar(100); println() }
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

private fun renderLanguageBar(pct: Int) {
    val clamped = pct.coerceIn(0, 100)
    val width = 20
    val filled = clamped * width / 100
    val bar = "#".repeat(filled) + "-".repeat(width - filled)
    print("\rInstalling [$bar] %3d%%".format(clamped))
    System.out.flush()
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
        val default: String, val allowed: String, val description: String, val debug: Boolean,
    )
    val rows = records.mapNotNull { rec ->
        val f = rec.split('\t')
        if (f.size < 8) null
        else Row(id = f[0], name = f[6], current = f[2], default = f[3], allowed = f[4], description = f[7], debug = f[5] == "debug")
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
    println("\nSet one with:  stoandl set-setting <SETTING> <value>")
}

/** stoandl's data directory: locker DB, pbw cache and PKJS settings all live here. */
private fun configDir(): File = File(System.getProperty("user.home"), ".config/stoandl")

private fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

/** True if the stoandl daemon currently owns its D-Bus name. Returns false (with a warning) if
 *  the bus can't be reached, so a detection glitch doesn't block a restore. */
private fun daemonRunning(): Boolean = try {
    val conn = DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection
    try {
        val bus = conn.getRemoteObject(
            "org.freedesktop.DBus", "/org/freedesktop/DBus",
            org.freedesktop.dbus.interfaces.DBus::class.java,
        )
        bus.NameHasOwner(STOANDL_BUS_NAME)
    } finally {
        conn.disconnect()
    }
} catch (e: Exception) {
    System.err.println("Warning: couldn't check daemon status: ${e.message}")
    false
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
    val code = runProcess("tar", "czf", out.path, "-C", dir.parentFile.path, dir.name)
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

/** Dump the watch's firmware logs to a text file. Resolves the target against THIS process's cwd
 *  and sends an absolute path (the daemon writes the file, and its cwd $HOME differs from ours). */
private fun ctlLogs(rest: List<String>) {
    val arg = rest.firstOrNull { !it.startsWith("-") }
    val target = when {
        arg == null -> File("pebble-logs-${timestamp()}.txt")
        File(arg).isDirectory -> File(arg, "pebble-logs-${timestamp()}.txt")
        arg.endsWith(".txt", ignoreCase = true) || arg.endsWith(".log", ignoreCase = true) -> File(arg)
        else -> File("$arg.txt")
    }
    val path = target.absolutePath
    val conn = connectDbusOrExit() ?: return
    try {
        val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
        println("Gathering watch logs… (this can take a few seconds)")
        val resp = try { control.GatherLogs(path) } catch (e: Exception) {
            System.err.println("Error: ${e.message}"); System.exit(1); return
        }
        val (kind, body) = splitStatus(resp)
        if (kind == "ok") println("Saved $body") else handleStatusResponse(resp)
    } finally {
        conn.disconnect()
    }
}

/**
 * Assemble a support bundle (a `.tar.gz`) for sharing with a maintainer. Resilient: it always
 * gathers the host-side pieces it can read directly — the daemon log and the (secret-redacted)
 * config — even with no daemon or watch, and folds in the watch's firmware logs + metadata (and,
 * with `--coredump`, a coredump) when the daemon and a watch are reachable. What's missing is noted
 * in `bundle-notes.txt` inside the archive rather than aborting.
 */
private fun ctlSupport(rest: List<String>) {
    val wantCoredump = rest.any { it == "--coredump" }
    val outArg = rest.firstOrNull { !it.startsWith("-") }
    val stamp = timestamp()
    val out = File(when {
        outArg == null -> "stoandl-support-$stamp.tar.gz"
        File(outArg).isDirectory -> File(outArg, "stoandl-support-$stamp.tar.gz").path
        outArg.endsWith(".tar.gz") || outArg.endsWith(".tgz") -> outArg
        else -> "$outArg.tar.gz"
    }).absoluteFile

    val tmpRoot = java.nio.file.Files.createTempDirectory("stoandl-support").toFile()
    val bundleDir = File(tmpRoot, "stoandl-support-$stamp").apply { mkdirs() }
    val notes = StringBuilder()
    fun note(line: String) { notes.appendLine(line); println("  $line") }
    println("Building support bundle…")

    // --- Watch-side pieces (need the daemon + a connected watch) ---
    val conn = try {
        DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection
    } catch (e: Exception) { null }
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
                val lr = try { control.GatherLogs(File(bundleDir, "watch-logs.txt").absolutePath) }
                catch (e: Exception) { "error:${e.message}" }
                val (lk, lb) = splitStatus(lr)
                if (lk == "ok") note("watch logs: included") else note("watch logs unavailable: ${lb.ifEmpty { lk }}")

                if (wantCoredump) {
                    println("  fetching coredump…")
                    val cr = try { control.GetCoreDump(File(bundleDir, "coredump.bin").absolutePath) }
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
    println("Review it before sharing — config secrets are redacted, but watch logs may contain personal data.")
}

/** Redact secrets from a stoandl.conf before it goes into a support bundle: CalDAV passwords
 *  (`url|user|password`), and any credentials embedded in URLs (`scheme://user:pass@…` userinfo and
 *  secret-looking query params like `token`/`key`/`password`). Conservative — only touches values it
 *  recognises as secret-bearing, leaving the rest readable so misconfig is still diagnosable. */
private fun sanitizeConfig(text: String): String {
    val userinfo = Regex("""(://)[^/@\s:]+:[^/@\s]+@""")
    val secretParam = Regex("""([?&](?:token|key|apikey|api_key|auth|password|passwd|secret|sig|signature)=)[^&\s]+""", RegexOption.IGNORE_CASE)
    fun redactUrl(s: String) = s.replace(userinfo, "$1***:***@").replace(secretParam, "$1***")
    val lines = text.lines().map { raw ->
        val hash = raw.indexOf('#')
        val code = if (hash >= 0) raw.substring(0, hash) else raw
        val comment = if (hash >= 0) raw.substring(hash) else ""
        val eq = code.indexOf('=')
        if (eq <= 0) return@map raw
        val key = code.substring(0, eq).trim()
        var value = code.substring(eq + 1)
        value = when (key) {
            // calendar.caldav = url|user|password, url2|user2|password2, …  → redact the password field.
            "calendar.caldav" -> value.split(',').joinToString(",") { entry ->
                val parts = entry.split('|')
                if (parts.size >= 3) (parts.take(2) + "***" + parts.drop(3)).joinToString("|")
                else redactUrl(entry)
            }
            else -> redactUrl(value)
        }
        code.substring(0, eq + 1) + value + comment
    }
    return "# Secrets redacted by `stoandl support`. Review before sharing.\n" + lines.joinToString("\n")
}

private fun connectDbusOrExit(): DBusConnection? = try {
    DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection
} catch (e: Exception) {
    System.err.println("Cannot connect to D-Bus session bus: ${e.message}")
    System.exit(1)
    null
}

private fun runConfigProxy(configUrl: String, onClose: (String) -> Unit) {
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
    val port = (server.address as java.net.InetSocketAddress).port
    val latch = java.util.concurrent.CountDownLatch(1)
    var closedData = ""

    server.createContext("/pebblejs-close") { exchange ->
        if (exchange.requestMethod == "POST") {
            closedData = exchange.requestBody.bufferedReader().readText()
            val resp = "OK".toByteArray()
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.write(resp)
            exchange.responseBody.close()
            latch.countDown()
        } else {
            exchange.sendResponseHeaders(405, -1)
            exchange.responseBody.close()
        }
    }

    server.createContext("/") { exchange ->
        if (exchange.requestMethod != "GET") {
            exchange.sendResponseHeaders(405, -1); exchange.responseBody.close(); return@createContext
        }
        val content = try {
            if (configUrl.startsWith("data:")) {
                // Clay encodes the entire config page as a data URI
                val commaIdx = configUrl.indexOf(',')
                require(commaIdx >= 0) { "malformed data URI" }
                val meta = configUrl.substring(5, commaIdx)
                val encoded = configUrl.substring(commaIdx + 1)
                if (meta.contains("base64", ignoreCase = true))
                    java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                else
                    java.net.URLDecoder.decode(encoded, "UTF-8")
            } else {
                java.net.URI(configUrl).toURL().readText()
            }
        } catch (e: Exception) {
            System.err.println("Failed to fetch config page: ${e.message}")
            exchange.sendResponseHeaders(502, -1); exchange.responseBody.close(); return@createContext
        }
        // For http(s) URLs inject a <base> tag so relative resources resolve; not needed for data: URIs.
        val baseTag = if (!configUrl.startsWith("data:"))
            "<base href=\"${configUrl.substringBeforeLast('/') + '/'}\">\\n" else ""
        val interceptJs = buildString {
            if (baseTag.isNotEmpty()) append(baseTag)
            append("<script>\n(function(){\n")
            // Relative URL: same origin as the config page, no hard-coded port needed
            append("  function sendClose(url){\n")
            append("    var h=url.indexOf('#'),d=h>=0?decodeURIComponent(url.slice(h+1)):'';\n")
            append("    var r=new XMLHttpRequest();\n")
            append("    r.open('POST','/pebblejs-close',false);\n")
            append("    r.setRequestHeader('Content-Type','text/plain');\n")
            append("    try{r.send(d);}catch(e){}\n")
            append("  }\n")
            // Chrome 102+: Navigation API fires for ALL JS-initiated navigations incl. custom protocols
            append("  if(typeof navigation!=='undefined'&&navigation.addEventListener){\n")
            append("    navigation.addEventListener('navigate',function(e){\n")
            append("      try{\n")
            append("        var url=e.destination&&e.destination.url||'';\n")
            append("        if(url.indexOf('pebblejs://')===0)sendClose(url);\n")
            append("      }catch(ex){}\n")
            append("    });\n")
            append("  }\n")
            // Firefox/older: override Location.prototype.href (Chrome blocks this with a TypeError)
            append("  try{\n")
            append("    var d=Object.getOwnPropertyDescriptor(Location.prototype,'href');\n")
            append("    if(d&&d.set)Object.defineProperty(Location.prototype,'href',{\n")
            append("      set:function(u){\n")
            append("        if(typeof u==='string'&&u.indexOf('pebblejs://')===0)sendClose(u);\n")
            append("        else d.set.call(this,u);\n")
            append("      },get:d.get,configurable:true,enumerable:true\n")
            append("    });\n")
            append("  }catch(e){}\n")
            append("})();\n</script>\n")
        }
        val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(content)
        val injected = if (headMatch != null) {
            content.substring(0, headMatch.range.last + 1) + "\n$interceptJs" +
                content.substring(headMatch.range.last + 1)
        } else {
            interceptJs + content
        }
        val bytes = injected.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.close()
    }

    server.executor = null
    server.start()

    val localUrl = "http://localhost:$port/"
    println("Config URL: $localUrl")
    try {
        ProcessBuilder("xdg-open", localUrl).start()
    } catch (_: Exception) {}
    println("Waiting for settings to be saved...")
    latch.await()
    server.stop(0)
    onClose(closedData)
    println("Settings saved.")
}
