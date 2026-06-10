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
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

private val CTL_COMMANDS = setOf("sideload", "add", "config", "fakecall", "apps", "launch", "remove", "backup", "restore", "weather", "settings", "set-setting", "pair", "unpair")

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
    println("  version                    Show the running daemon's version (and this CLI's)")
    println("  weather                    Fetch weather now and push it to the watch")
    println("  settings [filter]          List the watch's advanced settings (optionally filtered)")
    println("  set-setting <id> <value>   Set a watch setting (e.g. set-setting lightAmbientThreshold 200)")
    println("  pair                       Pair a new Pebble watch (opens a ~2 min window; blocks until done)")
    println("  unpair                     Forget the watch on this host (use after moving it to another device)")
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
                var lastPendingMsg = ""
                val startMs = System.currentTimeMillis()
                while (true) {
                    Thread.sleep(1_500)
                    val status = try { control.PairStatus() } catch (e: Exception) {
                        System.err.println("Error: ${e.message}"); System.exit(1); return
                    }
                    if (status.startsWith("pending:")) {
                        val msg = status.removePrefix("pending:")
                        if (msg.isNotEmpty() && msg != lastPendingMsg) {
                            println(msg)
                            lastPendingMsg = msg
                        }
                    } else {
                        handleStatusResponse(status); return
                    }
                    if (System.currentTimeMillis() - startMs > 145_000) {
                        System.err.println("Pairing timed out"); System.exit(1); return
                    }
                }
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
