package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.di.CommonPhoneCapabilities
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag

/**
 * Everything stoandl claims to the watch about the phone, in one place: [PebbleIntegration] hands both
 * values to libpebble3 in its `JvmHost` (capabilities, platformFlags).
 *
 * Capability bits are claimed unconditionally, never from config: the watch reads them once per session
 * and latches what it learns (an image type answered UNSUPPORTED stays unasked until reconnect). Users
 * switch features with their own keys or watch prefs instead.
 */
object WatchClaims {
    /** The capability bits of the PhoneAppVersion handshake. On top of libpebble3's common set:
     *  - ExtendedMusicProtocol: the MPRIS bridge serves the extended music endpoint.
     *  - TwoWayDismissal (bit 3, the firmware's ExtendedNotificationService): without it the watch hides
     *    every action (Reply, Mute, app actions, Dismiss) on a notification opened from its history,
     *    offering them only on the live popup.
     *  - ImageFetch (bit 17): the watch may pull pictures over the imaging endpoint (notification images,
     *    album art); it asks only on platforms that show them (emery, gabbro). */
    val phoneCapabilities: Set<ProtocolCapsFlag> = CommonPhoneCapabilities + setOf(
        ProtocolCapsFlag.SupportsExtendedMusicProtocol,
        ProtocolCapsFlag.SupportsTwoWayDismissal,
        ProtocolCapsFlag.SupportsImageFetch,
    )

    /** The OS type is Android. The firmware rejects OSType.Unknown on its music endpoint, before 4.37 it
     *  served music only to Android, and notification dismissal and images are Android-gated. stoandl
     *  already looks like the Android app elsewhere (ANDROID_NOTIFICATIONS_UUID as the parent). */
    val platformFlags: UInt = PhoneAppVersion.PlatformFlag.makeFlags(PhoneAppVersion.OSType.Android, emptyList())
}
