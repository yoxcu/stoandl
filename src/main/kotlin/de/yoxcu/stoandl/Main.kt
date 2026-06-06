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

private val log = KotlinLogging.logger {}

private val CTL_COMMANDS = setOf("sideload", "settings")

fun main(args: Array<String>) {
    if (args.isNotEmpty() && (args[0] == "ctl" || args[0] in CTL_COMMANDS)) {
        ctl(if (args[0] == "ctl") args.drop(1).toTypedArray() else args)
        return
    }

    Logger.setLogWriters(KermitSlf4jWriter())
    log.info { "stoandl starting" }

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

private fun ctl(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: stoandl <command> [args]")
        println()
        println("Commands:")
        println("  sideload <path>   Install a .pbw watchface or app onto the connected watch")
        println("  settings [app]    Open the configuration page for a running PKJS app")
        return
    }
    when (args[0]) {
        "sideload" -> {
            if (args.size < 2) {
                System.err.println("Usage: stoandl sideload <path>")
                System.exit(1)
            }
            val path = args[1]
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val ok = control.SideloadApp(path)
                if (ok) println("Sideloaded: $path") else { System.err.println("Sideload failed"); System.exit(1) }
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
                System.exit(1)
            } finally {
                conn.disconnect()
            }
        }
        "settings" -> {
            val app = args.getOrNull(1) ?: ""
            val conn = connectDbusOrExit() ?: return
            try {
                val control = conn.getRemoteObject(STOANDL_BUS_NAME, STOANDL_OBJECT_PATH, StoandlControl::class.java)
                val configUrl = try { control.OpenConfig(app) } catch (e: Exception) {
                    System.err.println("Error contacting daemon: ${e.message}")
                    System.exit(1); return
                }
                if (configUrl.isEmpty()) {
                    System.err.println("No config page available (app not running or has no configuration page)")
                    System.exit(1)
                    return
                }
                runConfigProxy(configUrl) { data -> control.WebviewClose(data) }
            } finally {
                conn.disconnect()
            }
        }
        else -> {
            System.err.println("Unknown command: ${args[0]}")
            System.exit(1)
        }
    }
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
            java.net.URL(configUrl).readText()
        } catch (e: Exception) {
            System.err.println("Failed to fetch config page: ${e.message}")
            exchange.sendResponseHeaders(502, -1); exchange.responseBody.close(); return@createContext
        }
        // Derive base URL for relative resources in the config page
        val baseUrl = configUrl.substringBeforeLast('/') + '/'
        val interceptJs = buildString {
            append("<base href=\"$baseUrl\">\n")
            append("<script>\n(function(){\n")
            append("  var d=Object.getOwnPropertyDescriptor(Location.prototype,'href');\n")
            append("  if(!d)return;\n")
            append("  Object.defineProperty(Location.prototype,'href',{\n")
            append("    set:function(url){\n")
            append("      if(typeof url==='string'&&url.indexOf('pebblejs://')===0){\n")
            append("        var h=url.indexOf('#'),data=h>=0?decodeURIComponent(url.slice(h+1)):'';\n")
            append("        var x=new XMLHttpRequest();\n")
            append("        x.open('POST','http://localhost:$port/pebblejs-close',false);\n")
            append("        x.setRequestHeader('Content-Type','text/plain');\n")
            append("        x.send(data);\n")
            append("      } else { d.set.call(this,url); }\n")
            append("    },\n")
            append("    get:d.get,configurable:true,enumerable:true\n")
            append("  });\n")
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
    try {
        ProcessBuilder("xdg-open", localUrl).start()
    } catch (e: Exception) {
        println("Open this URL in your browser: $localUrl")
    }
    println("Waiting for settings to be saved...")
    latch.await()
    server.stop(0)
    onClose(closedData)
    println("Settings saved.")
}
