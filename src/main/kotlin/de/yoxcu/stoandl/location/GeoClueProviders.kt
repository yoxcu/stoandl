package de.yoxcu.stoandl.location

/**
 * The daemon's one [GeoClueLocationProvider], keyed by the GeoClue identity ([desktopId],
 * `weather.gps_desktop_id`). Weather's current location and watchapps' `navigator.geolocation` both ask
 * [current], so they share one client (never two side by side) and one fix cache: a watchapp's GPS fix
 * answers the next weather lookup, and the other way round when it is accurate enough.
 *
 * This holder is the only caller of [GeoClueLocationProvider.stop], which releases a provider for good:
 * when the identity changes (the next [current] builds a provider for the new one, so the geoclue.conf
 * allow rule in force follows the setting without a restart) and at daemon shutdown ([stop]). Turning
 * weather or geolocation off leaves the provider alone; its client stops on its own after each answer.
 */
class GeoClueProviders(
    private val desktopId: () -> String,
    private val factory: (String) -> GeoClueLocationProvider,
) {
    private var cached: Pair<String, GeoClueLocationProvider>? = null

    /** The provider for the current identity, built on first use. */
    @Synchronized
    fun current(): GeoClueLocationProvider {
        val id = desktopId()
        cached?.let { (cachedId, provider) ->
            if (cachedId == id) return provider
            provider.stop()
        }
        return factory(id).also { cached = id to it }
    }

    /** Stop the provider (daemon shutdown). A later [current] builds a new one. */
    @Synchronized
    fun stop() {
        cached?.second?.stop()
        cached = null
    }
}
