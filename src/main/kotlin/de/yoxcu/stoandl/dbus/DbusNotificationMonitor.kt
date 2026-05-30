package de.yoxcu.stoandl.dbus

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.base.AbstractConnectionBase
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.connections.transports.AbstractTransport
import org.freedesktop.dbus.connections.transports.TransportConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.Message
import org.freedesktop.dbus.spi.message.IMessageWriter
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

private const val NOTIFICATIONS_OBJECT_PATH = "/org/freedesktop/Notifications"

@DBusInterfaceName("org.freedesktop.DBus.Monitoring")
private interface DBusMonitoring : DBusInterface {
    fun BecomeMonitor(rules: Array<String>, flags: UInt32)
}

data class IncomingNotification(
    val id: UInt32,
    val appName: String,
    val summary: String,
    val body: String,
    val hints: Map<String, Variant<*>>,
)

fun monitorNotifications(): Flow<IncomingNotification> = callbackFlow {
    val nextId = AtomicLong(1)

    launch {
        // Dedup: dbus-java can dispatch the same monitored message to multiple handler threads.
        val lastSeen = ConcurrentHashMap<String, Long>()

        // Reflected accessors — reused across reconnect iterations.
        val getTransportMethod = AbstractConnectionBase::class.java
            .getDeclaredMethod("getTransport")
            .also { it.isAccessible = true }
        val writerField = TransportConnection::class.java
            .getDeclaredField("writer")
            .also { it.isAccessible = true }

        while (isActive) {
            val monitorConn = try {
                DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection
            } catch (e: Exception) {
                log.warn { "BecomeMonitor: cannot open DBus connection: ${e.message}" }
                delay(2000)
                continue
            }
            var tc: TransportConnection? = null
            var originalWriter: IMessageWriter? = null
            try {
                monitorConn.addFallback(NOTIFICATIONS_OBJECT_PATH, object : FreedesktopNotifications {
                    override fun isRemote() = false
                    override fun getObjectPath() = NOTIFICATIONS_OBJECT_PATH

                    override fun Notify(
                        app_name: String, replaces_id: UInt32, app_icon: String,
                        summary: String, body: String, actions: List<String>,
                        hints: Map<String, Variant<*>>, expire_timeout: Int,
                    ): UInt32 {
                        val now = System.currentTimeMillis()
                        val prev = lastSeen.put("$app_name|$summary", now)
                        if (prev != null && now - prev < 200L) return UInt32(0)
                        log.debug { "Notify: app=$app_name summary=$summary" }
                        if (summary.isNotEmpty())
                            trySend(IncomingNotification(UInt32(nextId.getAndIncrement()), app_name, summary, body, hints))
                        return UInt32(0)
                    }

                    override fun CloseNotification(id: UInt32) {}
                    override fun GetCapabilities(): List<String> = emptyList()
                    override fun GetServerInformation(): Array<String> = arrayOf("stoandl", "stoandl", "1.0", "1.2")
                })

                val monitoring = monitorConn.getRemoteObject(
                    "org.freedesktop.DBus", "/org/freedesktop/DBus", DBusMonitoring::class.java,
                )
                monitoring.BecomeMonitor(
                    arrayOf("type='method_call',interface='org.freedesktop.Notifications',member='Notify'"),
                    UInt32(0),
                )

                // BecomeMonitor connections must never write back to the daemon — if they do,
                // the daemon closes the connection.  dbus-java auto-replies to every MethodCall
                // it dispatches, so we swap in a no-op writer after the BecomeMonitor call itself
                // has been sent (that call needs the real writer).
                val transport = getTransportMethod.invoke(monitorConn) as AbstractTransport
                tc = transport.getTransportConnection()
                originalWriter = writerField.get(tc) as IMessageWriter
                writerField.set(tc, object : IMessageWriter {
                    override fun writeMessage(msg: Message?) {}
                    override fun isClosed(): Boolean = false
                    override fun close() {}
                })

                log.info { "Notification monitor: BecomeMonitor active" }

                while (isActive && monitorConn.isConnected()) {
                    delay(500)
                }
                if (isActive) log.warn { "BecomeMonitor connection lost, reconnecting..." }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "BecomeMonitor error: ${e.message}" }
            } finally {
                // Restore the real writer so disconnect() can send its cleanup messages.
                if (tc != null && originalWriter != null) {
                    try { writerField.set(tc, originalWriter) } catch (_: Exception) {}
                }
                try { monitorConn.disconnect() } catch (_: Exception) {}
            }
            if (isActive) delay(1000)
        }
    }

    awaitClose { log.info { "DBus notification monitor stopping" } }
}
