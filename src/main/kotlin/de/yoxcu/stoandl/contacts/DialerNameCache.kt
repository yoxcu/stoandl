package de.yoxcu.stoandl.contacts

/**
 * Remembers the title of the most recent dialer notification, so an incoming call whose number
 * isn't in the vCard files can still show a name. The dialer (GNOME Calls, Spacebar, …) typically
 * raises its own "Incoming call from X" notification at roughly the same moment ModemManager
 * reports the call; that notification is suppressed from the watch but its title is captured here.
 *
 * Best-effort: the name only appears if the dialer notification arrives at or before the call
 * rings (within [recent]'s window). The vCard lookup is the reliable path.
 */
class DialerNameCache {
    @Volatile private var name: String? = null
    @Volatile private var timeMs: Long = 0

    fun record(title: String) {
        if (title.isBlank()) return
        name = title.trim()
        timeMs = System.currentTimeMillis()
    }

    /** The last recorded dialer title if seen within [withinMs], else null. */
    fun recent(withinMs: Long = 5_000): String? =
        name?.takeIf { System.currentTimeMillis() - timeMs <= withinMs }
}
