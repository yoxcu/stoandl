package de.yoxcu.stoandl.dbus

import io.github.oshai.kotlinlogging.KotlinLogging
import org.freedesktop.dbus.interfaces.DBusInterface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

private val log = KotlinLogging.logger {}

/** A D-Bus method that runs at least this long is logged at WARN. The GUI calls the control interface
 *  on its UI thread, so a slow method freezes it; this leaves a trace of which one did. */
const val SLOW_DBUS_CALL_MS = 500L

/**
 * Wrap [target] so that every call into it through [iface] that takes [thresholdMs] or longer is
 * reported to [warn] with the method name and duration, and nothing is logged below that. Export the
 * wrapper instead of [target]: dbus-java finds the exported methods through the object's interfaces
 * and calls them with `Method.invoke`, which a [Proxy] serves like the class, so this is the one place
 * that times every `de.yoxcu.stoandl.Control` method. Arguments aren't logged (some carry passwords).
 * Exceptions reach dbus-java unwrapped, so its error replies are unchanged.
 */
fun <T : DBusInterface> timeSlowCalls(
    iface: Class<T>,
    target: T,
    thresholdMs: Long = SLOW_DBUS_CALL_MS,
    nanoTime: () -> Long = System::nanoTime,
    warn: (String) -> Unit = { msg -> log.warn { msg } },
): T {
    val name = iface.simpleName
    val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
        val start = nanoTime()
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } finally {
            val ms = (nanoTime() - start) / 1_000_000
            if (ms >= thresholdMs) warn("D-Bus $name.${method.name} took $ms ms")
        }
    }
    return iface.cast(proxy)
}
