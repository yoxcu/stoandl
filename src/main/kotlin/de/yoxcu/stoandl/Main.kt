package de.yoxcu.stoandl

import co.touchlab.kermit.Logger
import de.yoxcu.stoandl.dbus.IncomingNotification
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

private val log = KotlinLogging.logger {}

fun main() {
    Logger.setLogWriters(KermitSlf4jWriter())
    log.info { "stoandl starting" }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val notificationBus = MutableSharedFlow<IncomingNotification>(extraBufferCapacity = 64)

    val pebble = PebbleIntegration(notificationBus, scope)
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
            latch.countDown()
        })
        latch.await()
    }
}
