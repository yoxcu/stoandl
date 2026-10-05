package de.yoxcu.stoandl.notification

import io.rebble.libpebblecommon.database.entity.AttributesListBuilder

/**
 * Where a notification's app name goes on the watch: `notification.app_name`, or an app's own override
 * ([AppNamePlacements]). PebbleOS draws a notification card in a fixed order, Title (bold), Subtitle (the
 * largest bold font), LocationName (small, regular), Body, and the host can't change fonts or order, so
 * the only choice is which text goes into which attribute.
 */
enum class AppNamePlacement(val token: String) {
    /** The LocationName attribute: a small line under the title. */
    SMALL("small"),
    /** The Subtitle attribute, when the source has no subtitle of its own: large and bold. */
    SUBTITLE("subtitle"),
    /** Not on the card; the icon and colour still tell the app. */
    OFF("off");

    companion object {
        /** The placement for a `stoandl.conf` or store token (case-insensitive), or null if unknown. */
        fun parse(token: String): AppNamePlacement? =
            entries.firstOrNull { it.token.equals(token.trim(), ignoreCase = true) }
    }
}

/** The text a watch notification carries; null = the attribute isn't sent. [small] is LocationName. */
data class WatchFields(val title: String, val subtitle: String?, val small: String?, val body: String?)

/**
 * The text attributes for a notification from [appName]. A source's own [subtitle] (an extension's) keeps
 * the subtitle slot; the app name goes where [placement] says, and is left out when it would repeat the
 * title or that subtitle (an app that titles a notification with its own name, `notify-send -a Demo Demo`),
 * so no line shows twice.
 */
fun watchFields(
    appName: String,
    title: String,
    subtitle: String?,
    body: String,
    placement: AppNamePlacement,
): WatchFields {
    val own = subtitle?.takeIf { it.isNotBlank() }
    val name = appName.trim().takeIf { n ->
        n.isNotEmpty() && !n.equals(title.trim(), ignoreCase = true) &&
            (own == null || !n.equals(own.trim(), ignoreCase = true))
    }
    return WatchFields(
        title = title,
        subtitle = own ?: name?.takeIf { placement == AppNamePlacement.SUBTITLE },
        small = name?.takeIf { placement == AppNamePlacement.SMALL },
        body = body.takeIf { it.isNotEmpty() },
    )
}

/** Add [fields] to a notification's attributes: Title always (the firmware requires it), the rest when set. */
fun AttributesListBuilder.watchText(fields: WatchFields) {
    title { fields.title }
    fields.subtitle?.let { s -> subtitle { s } }
    fields.small?.let { s -> location { s } }
    fields.body?.let { b -> body { b } }
}
