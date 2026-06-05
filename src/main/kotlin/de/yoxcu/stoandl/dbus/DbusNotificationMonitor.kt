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
import org.freedesktop.dbus.messages.MethodCall
import org.freedesktop.dbus.messages.MethodReturn
import org.freedesktop.dbus.spi.message.IMessageReader
import org.freedesktop.dbus.spi.message.IMessageWriter
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

private const val NOTIFICATIONS_OBJECT_PATH = "/org/freedesktop/Notifications"
private const val NOTIFICATIONS_IFACE = "org.freedesktop.Notifications"
private const val NOTIFY_MEMBER = "Notify"

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

private data class PendingNotify(val appName: String, val summary: String, val body: String)

// Wraps the transport reader to intercept Notify method calls and their return values.
// Notify call serials are recorded when the call is seen; when the corresponding
// MethodReturn arrives the daemon-assigned notification ID is extracted and the
// IncomingNotification is emitted. The existing Notify() fallback handler is a no-op.
private class InterceptingReader(
    private val inner: IMessageReader,
    private val pending: ConcurrentHashMap<Long, PendingNotify>,
    private val lastSeen: ConcurrentHashMap<String, Long>,
    private val emit: (IncomingNotification) -> Unit,
) : IMessageReader {

    override fun readMessage(): Message? {
        val msg = inner.readMessage() ?: return null
        try {
            when (msg) {
                is MethodCall ->
                    if (msg.getInterface() == NOTIFICATIONS_IFACE && msg.getName() == NOTIFY_MEMBER) {
                        val params = msg.getParameters()
                        if (params != null && params.size >= 5) {
                            val appName = params[0] as? String ?: ""
                            val summary = params[3] as? String ?: ""
                            val body = params[4] as? String ?: ""
                            if (summary.isNotEmpty()) {
                                pending[msg.getSerial()] = PendingNotify(appName, summary, body)
                            }
                        }
                    }

                is MethodReturn -> {
                    val p = pending.remove(msg.getReplySerial()) ?: return msg
                    val params = try { msg.getParameters() } catch (_: Exception) { return msg }
                    val rawId = params?.firstOrNull() ?: return msg
                    val id = when (rawId) {
                        is UInt32 -> rawId
                        is Long   -> UInt32(rawId)
                        is Int    -> UInt32(rawId.toLong())
                        else      -> return msg
                    }
                    val now = System.currentTimeMillis()
                    val dedupeKey = "${p.appName}|${p.summary}"
                    val prev = lastSeen.put(dedupeKey, now)
                    if (prev == null || now - prev >= 200L) {
                        emit(IncomingNotification(id, p.appName, p.summary, p.body, emptyMap()))
                    }
                }
            }
        } catch (_: Exception) {}
        return msg
    }

    override fun isClosed() = inner.isClosed()
    override fun close() = inner.close()
}

fun monitorNotifications(): Flow<IncomingNotification> = callbackFlow {
    launch {
        // Dedup state persists across reconnect iterations so a reconnect doesn't re-admit
        // a notification that arrived in the 200 ms window straddling the reconnect.
        val lastSeen = ConcurrentHashMap<String, Long>()

        // Reflected accessors — reused across reconnect iterations.
        val getTransportMethod = AbstractConnectionBase::class.java
            .getDeclaredMethod("getTransport")
            .also { it.isAccessible = true }
        val writerField = TransportConnection::class.java
            .getDeclaredField("writer")
            .also { it.isAccessible = true }
        val readerField = TransportConnection::class.java
            .getDeclaredField("reader")
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
            // Per-connection pending map; cleared implicitly when the connection is replaced.
            val pendingBySerial = ConcurrentHashMap<Long, PendingNotify>()
            try {
                monitorConn.addFallback(NOTIFICATIONS_OBJECT_PATH, object : FreedesktopNotifications {
                    override fun isRemote() = false
                    override fun getObjectPath() = NOTIFICATIONS_OBJECT_PATH

                    // Notifications are emitted by InterceptingReader on MethodReturn so we
                    // have the real daemon-assigned ID. This handler is intentionally a no-op.
                    override fun Notify(
                        app_name: String, replaces_id: UInt32, app_icon: String,
                        summary: String, body: String, actions: List<String>,
                        hints: Map<String, Variant<*>>, expire_timeout: Int,
                    ): UInt32 = UInt32(0)

                    override fun CloseNotification(id: UInt32) {}
                    override fun GetCapabilities(): List<String> = emptyList()
                    override fun GetServerInformation(): Array<String> = arrayOf("stoandl", "stoandl", "1.0", "1.2")
                })

                val monitoring = monitorConn.getRemoteObject(
                    "org.freedesktop.DBus", "/org/freedesktop/DBus", DBusMonitoring::class.java,
                )

                val transport = getTransportMethod.invoke(monitorConn) as AbstractTransport
                tc = transport.getTransportConnection()

                // Install intercepting reader *before* BecomeMonitor so that the Notify call
                // serial is captured before the matching MethodReturn can arrive.
                val originalReader = tc.getReader()
                readerField.set(tc, InterceptingReader(originalReader, pendingBySerial, lastSeen) { notif ->
                    trySend(notif)
                })

                monitoring.BecomeMonitor(
                    arrayOf(
                        "type='method_call',interface='$NOTIFICATIONS_IFACE',member='$NOTIFY_MEMBER'",
                        "type='method_return'",
                    ),
                    UInt32(0),
                )

                // Install no-op writer after BecomeMonitor so the BecomeMonitor call itself
                // goes through. BecomeMonitor connections must never write back to the daemon.
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
