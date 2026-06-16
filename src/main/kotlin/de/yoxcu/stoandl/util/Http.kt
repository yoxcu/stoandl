package de.yoxcu.stoandl.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

/**
 * A Ktor CIO client with stoandl's standard timeouts (30s request / 15s connect). Shared by the
 * weather sync and the calendar sources so the timeout policy lives in one place.
 */
fun stoandlHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
    }
}
