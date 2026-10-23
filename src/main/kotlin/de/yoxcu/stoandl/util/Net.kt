package de.yoxcu.stoandl.util

import java.net.InetAddress

/**
 * IPv4 loopback, the address every local server of the daemon binds. `InetAddress.getLoopbackAddress()`
 * is `::1` once `main()` sets `java.net.preferIPv6Addresses=system`, while the URLs these servers hand
 * out (and the OAuth redirects users register with a provider) say `127.0.0.1`.
 */
val LOOPBACK_V4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

/** `http://127.0.0.1:<port>`: the origin of a server bound on [LOOPBACK_V4]. */
fun loopbackOrigin(port: Int): String = "http://${LOOPBACK_V4.hostAddress}:$port"
