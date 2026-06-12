package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

// MPRIS (Media Player Remote Interfacing Specification) lives on the session bus. Each media player
// owns a well-known name `org.mpris.MediaPlayer2.<suffix>` and exports its objects at a single shared
// object path. Playback state is read via the standard org.freedesktop.DBus.Properties interface
// (PlaybackStatus, Metadata, Position, Volume, Shuffle, LoopStatus) and its PropertiesChanged signal;
// players appearing/disappearing show up as org.freedesktop.DBus.NameOwnerChanged. Both of those are
// provided by dbus-java's built-in DBus / Properties interfaces, so the only thing we must declare is
// the transport-control method interface below.
const val MPRIS_BUS_PREFIX = "org.mpris.MediaPlayer2."
const val MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2"
const val MPRIS_ROOT_IFACE = "org.mpris.MediaPlayer2"
const val MPRIS_PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"

/**
 * org.mpris.MediaPlayer2.Player — the playback-control interface every MPRIS media player exports at
 * [MPRIS_OBJECT_PATH]. We only need the transport methods (state is read through [Properties]).
 * [PlayPause] is native to MPRIS, so the watch's play/pause toggle maps to it directly.
 */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Play()
    fun Pause()
    fun PlayPause()
    fun Next()
    fun Previous()
}
