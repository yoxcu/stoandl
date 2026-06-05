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

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "ctl") {
        ctl(args.drop(1).toTypedArray())
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
            log.debug { "Forwarding notification to watch: ${notification.appName} – ${notification.summary}" }
            notificationBus.emit(notification)
        }
        .launchIn(scope)

    log.info { "stoandl running — press Ctrl-C to stop" }

    runBlocking {
        // Keep the process alive until interrupted
        val latch = java.util.concurrent.CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info { "stoandl shutting down" }
            serviceConn.releaseBusName(STOANDL_BUS_NAME)
            serviceConn.disconnect()
            latch.countDown()
        })
        latch.await()
    }
}

private fun ctl(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: stoandl ctl <command> [args]")
        println()
        println("Commands:")
        println("  sideload <path>   Install a .pbw watchface or app onto the connected watch")
        return
    }
    when (args[0]) {
        "sideload" -> {
            if (args.size < 2) {
                System.err.println("Usage: stoandl ctl sideload <path>")
                System.exit(1)
            }
            val path = args[1]
            val conn = try {
                DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection
            } catch (e: Exception) {
                System.err.println("Cannot connect to D-Bus session bus: ${e.message}")
                System.exit(1)
                return
            }
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
        else -> {
            System.err.println("Unknown command: ${args[0]}")
            System.exit(1)
        }
    }
}
