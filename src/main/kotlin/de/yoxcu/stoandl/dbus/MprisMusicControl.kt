package de.yoxcu.stoandl.dbus

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import io.rebble.libpebblecommon.music.PlaybackState
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.PlayerInfo
import io.rebble.libpebblecommon.music.RepeatType
import io.rebble.libpebblecommon.music.SystemMusicControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

// MPRIS Volume is a double 0.0..1.0; the watch's ± volume buttons nudge the active player by this much.
private const val VOLUME_STEP = 0.1

/**
 * Bridges desktop media players (MPRIS, on the session bus) to libpebble3's [SystemMusicControl], so
 * the Pebble's native Music app shows now-playing (title / artist / album, play state, position) and
 * its buttons drive the active player: play/pause, next/previous and volume.
 *
 * libpebble3's `MusicControlManager` already does the whole bidirectional bridge — it pushes
 * [playbackState] to the watch and routes the watch's button presses back to [play]/[pause]/etc. — so
 * all this class supplies is the platform half: read MPRIS and act on it. (The JVM target binds a
 * no-op [SystemMusicControl]; `PebbleIntegration` overrides it with this when `music.enabled`.)
 *
 * **Discovery.** Players own a well-known name `org.mpris.MediaPlayer2.<suffix>`. We enumerate them
 * via `org.freedesktop.DBus.ListNames`/`GetNameOwner` at startup and track appear/disappear through
 * the `NameOwnerChanged` signal. `playerctld` (a proxy that mirrors whichever player is most recent)
 * is skipped so it doesn't duplicate the real player it fronts.
 *
 * **State.** All players publish at the same object path ([MPRIS_OBJECT_PATH]) and signal changes via
 * `Properties.PropertiesChanged`, so we demux by the signal's sender (its unique bus name, mapped back
 * to a well-known name). On any change we re-read the player's full state with one `GetAll` round-trip
 * (simpler and race-free vs. merging the partial delta the signal carries; track changes are rare).
 *
 * **Target selection** (mirrors the Android implementation): prefer an actively *Playing* player — the
 * most recently started, if several; otherwise keep the previous target if it's still around (it has
 * probably just paused); otherwise the first known player. The chosen player is what the watch shows
 * and what the control buttons act on.
 *
 * Monitoring self-starts on construction (Koin builds this lazily, on the first watch connection) and
 * reconnects on bus errors, mirroring [ModemManagerCallMonitor].
 *
 * [systemVolume] selects what the watch's volume buttons drive: when non-null they nudge the
 * system/master output ([SystemVolume]); when null they adjust the active player's own MPRIS volume.
 */
class MprisMusicControl(
    private val scope: CoroutineScope,
    private val systemVolume: SystemVolume? = null,
) : SystemMusicControl {

    private val _playbackState = MutableStateFlow<PlaybackStatus?>(null)
    override val playbackState: StateFlow<PlaybackStatus?> = _playbackState.asStateFlow()

    @Volatile private var conn: DBusConnection? = null

    // All player bookkeeping is guarded by [lock]; the StateFlow is set inside the lock too, so target
    // recomputation is fully serialized (no stale overwrite when several players change at once).
    private val lock = Any()
    private val players = LinkedHashMap<String, PlaybackStatus>() // busName -> latest status (insertion order)
    private val uniqueToBus = HashMap<String, String>()           // signal sender (unique name) -> busName
    private val busToUnique = HashMap<String, String>()           // busName -> its owner's unique name
    private val identities = HashMap<String, String>()            // busName -> human player name (cached)
    private val startedPlayingAt = HashMap<String, Long>()        // busName -> when it last became Playing
    @Volatile private var targetBus: String? = null

    init { start() }

    private fun start() {
        scope.launch(Dispatchers.IO) {
            while (coroutineContext.isActive) {
                try {
                    runMonitor()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "MPRIS monitor error: ${e.message}" }
                }
                try { conn?.disconnect() } catch (_: Exception) {}
                conn = null
                clearAll()
                if (coroutineContext.isActive) delay(5.seconds)
            }
        }
    }

    private suspend fun runMonitor() {
        val c = withContext(Dispatchers.IO) {
            DBusConnectionBuilder.forSessionBus().withShared(false).build() as DBusConnection
        }
        conn = c
        log.info { "MPRIS music monitor: connected to session bus" }

        // Players come and go as MPRIS bus-name owners change.
        c.addSigHandler(DBus.NameOwnerChanged::class.java) { sig ->
            val name = sig.name
            if (!name.startsWith(MPRIS_BUS_PREFIX) || isProxy(name)) return@addSigHandler
            if (sig.newOwner.isNotEmpty()) {
                scope.launch(Dispatchers.IO) { addPlayer(name, sig.newOwner) }
            } else if (sig.oldOwner.isNotEmpty()) {
                removePlayer(name)
            }
        }
        // Property changes on any MPRIS player (all share MPRIS_OBJECT_PATH) — demux by signal sender.
        c.addSigHandler(Properties.PropertiesChanged::class.java) { sig ->
            if (sig.interfaceName != MPRIS_PLAYER_IFACE) return@addSigHandler
            val bus = sig.source?.let { src -> synchronized(lock) { uniqueToBus[src] } } ?: return@addSigHandler
            scope.launch(Dispatchers.IO) { refreshPlayer(bus) }
        }

        enumerateExisting(c)

        while (coroutineContext.isActive && c.isConnected) {
            delay(2.seconds)
        }
        if (coroutineContext.isActive) log.warn { "MPRIS session-bus connection lost, reconnecting…" }
    }

    private fun enumerateExisting(c: DBusConnection) {
        try {
            val dbus = c.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            dbus.ListNames()
                .filter { it.startsWith(MPRIS_BUS_PREFIX) && !isProxy(it) }
                .forEach { name ->
                    val owner = try { dbus.GetNameOwner(name) } catch (_: Exception) { return@forEach }
                    scope.launch(Dispatchers.IO) { addPlayer(name, owner) }
                }
        } catch (e: Exception) {
            log.warn { "MPRIS: could not enumerate players: ${e.message}" }
        }
    }

    private fun addPlayer(busName: String, uniqueName: String) {
        val status = readStatus(busName) ?: return // already gone / unreadable
        synchronized(lock) {
            players[busName] = status
            uniqueToBus[uniqueName] = busName
            busToUnique[busName] = uniqueName
            if (status.playbackState == PlaybackState.Playing) startedPlayingAt[busName] = System.currentTimeMillis()
        }
        log.info { "MPRIS player added: ${status.playerInfo?.name ?: busName}" }
        recompute()
    }

    private fun refreshPlayer(busName: String) {
        val status = readStatus(busName)
        if (status == null) { removePlayer(busName); return }
        synchronized(lock) {
            if (!busToUnique.containsKey(busName)) return // removed meanwhile
            val prev = players.put(busName, status)
            if (status.playbackState == PlaybackState.Playing && prev?.playbackState != PlaybackState.Playing) {
                startedPlayingAt[busName] = System.currentTimeMillis()
            }
        }
        recompute()
    }

    private fun removePlayer(busName: String) {
        synchronized(lock) {
            players.remove(busName)
            startedPlayingAt.remove(busName)
            identities.remove(busName)
            busToUnique.remove(busName)?.let { uniqueToBus.remove(it) }
        }
        log.info { "MPRIS player removed: $busName" }
        recompute()
    }

    /** Pick the target player and publish its status. Done entirely under [lock] so concurrent player
     *  updates can't race to overwrite the StateFlow with a stale value. */
    private fun recompute() {
        synchronized(lock) {
            val target = selectTarget()
            targetBus = target
            _playbackState.value = target?.let { players[it] }
        }
    }

    /** Must be called holding [lock]. */
    private fun selectTarget(): String? {
        if (players.isEmpty()) return null
        // An actively-playing player wins (most-recently-started if several are playing)…
        players.entries
            .filter { it.value.playbackState == PlaybackState.Playing }
            .maxByOrNull { startedPlayingAt[it.key] ?: Long.MIN_VALUE }
            ?.let { return it.key }
        // …otherwise stick with the current target if it's still present (it likely just paused)…
        targetBus?.takeIf { players.containsKey(it) }?.let { return it }
        // …otherwise the first known player (so a single paused player still shows).
        return players.keys.firstOrNull()
    }

    /** One `GetAll` round-trip → a [PlaybackStatus]; null if the player vanished or can't be read. */
    private fun readStatus(busName: String): PlaybackStatus? {
        val c = conn ?: return null
        return try {
            val props = c.getRemoteObject(busName, MPRIS_OBJECT_PATH, Properties::class.java)
            val all = props.GetAll(MPRIS_PLAYER_IFACE)
            val state = when (unwrap(all["PlaybackStatus"]) as? String) {
                "Playing" -> PlaybackState.Playing
                else -> PlaybackState.Paused // "Paused" / "Stopped" / unknown → the watch shows paused
            }
            val volume = (unwrap(all["Volume"]) as? Number)?.toDouble()
            PlaybackStatus(
                playerInfo = PlayerInfo(packageId = busName.removePrefix(MPRIS_BUS_PREFIX), name = identityOf(busName)),
                playbackState = state,
                currentTrack = trackFromMetadata(unwrap(all["Metadata"]) as? Map<*, *>),
                playbackPositionMs = (asLong(unwrap(all["Position"])) ?: 0L) / 1000,
                playbackRate = (unwrap(all["Rate"]) as? Number)?.toFloat() ?: 1.0f,
                shuffle = (unwrap(all["Shuffle"]) as? Boolean) ?: false,
                repeat = when (unwrap(all["LoopStatus"]) as? String) {
                    "Track" -> RepeatType.One
                    "Playlist" -> RepeatType.All
                    else -> RepeatType.Off
                },
                // MPRIS volume is per-player 0.0..1.0; many players (e.g. browsers) omit it — default 100.
                volume = volume?.let { (it * 100).roundToInt().coerceIn(0, 100) } ?: 100,
            )
        } catch (e: Exception) {
            log.debug { "MPRIS readStatus($busName) failed: ${e.message}" }
            null
        }
    }

    private fun trackFromMetadata(meta: Map<*, *>?): MusicTrack? {
        if (meta == null) return null
        val title = unwrap(meta["xesam:title"]) as? String
        val artist = artistOf(unwrap(meta["xesam:artist"]))
        val album = unwrap(meta["xesam:album"]) as? String
        // A player with nothing identifiable → no track (so the watch clears rather than showing blanks).
        if (title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()) return null
        return MusicTrack(
            title = title,
            artist = artist,
            album = album,
            length = (asLong(unwrap(meta["mpris:length"])) ?: 0L).microseconds, // mpris:length is in µs
            trackNumber = (unwrap(meta["xesam:trackNumber"]) as? Number)?.toInt()?.takeIf { it > 0 },
            totalTracks = null, // not part of the MPRIS metadata spec
        )
    }

    /** `xesam:artist` is an array of strings; tolerate a bare string from sloppy players too. */
    private fun artistOf(v: Any?): String? = when (v) {
        is List<*> -> v.filterIsInstance<String>().filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        is Array<*> -> v.filterIsInstance<String>().filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        is String -> v.ifBlank { null }
        else -> null
    }

    /** Human-readable player name from the root `Identity` property, cached; falls back to the suffix. */
    private fun identityOf(busName: String): String {
        synchronized(lock) { identities[busName]?.let { return it } }
        val fromRoot = try {
            conn?.getRemoteObject(busName, MPRIS_OBJECT_PATH, Properties::class.java)
                ?.Get<Any?>(MPRIS_ROOT_IFACE, "Identity") as? String
        } catch (_: Exception) { null }
        val name = fromRoot?.takeIf { it.isNotBlank() } ?: prettySuffix(busName)
        synchronized(lock) { identities[busName] = name }
        return name
    }

    override fun play() = control("play") { it.Play() }
    override fun pause() = control("pause") { it.Pause() }
    override fun playPause() = control("playPause") { it.PlayPause() }
    override fun nextTrack() = control("next") { it.Next() }
    override fun previousTrack() = control("previous") { it.Previous() }
    // System/master volume when configured (and a backend was found), else the active player's volume.
    override fun volumeUp() { systemVolume?.up(scope) ?: adjustPlayerVolume(VOLUME_STEP) }
    override fun volumeDown() { systemVolume?.down(scope) ?: adjustPlayerVolume(-VOLUME_STEP) }

    private fun control(label: String, action: (MprisPlayer) -> Unit) {
        val bus = targetBus ?: run { log.debug { "MPRIS $label: no active player" }; return }
        scope.launch(Dispatchers.IO) {
            try {
                conn?.getRemoteObject(bus, MPRIS_OBJECT_PATH, MprisPlayer::class.java)?.let(action)
                log.info { "MPRIS $label → $bus" }
            } catch (e: Exception) {
                log.warn { "MPRIS $label($bus) failed: ${e.message}" }
            }
        }
    }

    private fun adjustPlayerVolume(delta: Double) {
        val bus = targetBus ?: run { log.debug { "MPRIS volume: no active player" }; return }
        scope.launch(Dispatchers.IO) {
            try {
                val props = conn?.getRemoteObject(bus, MPRIS_OBJECT_PATH, Properties::class.java) ?: return@launch
                val current = (props.Get<Any?>(MPRIS_PLAYER_IFACE, "Volume") as? Number)?.toDouble()
                if (current == null) { log.debug { "MPRIS volume: $bus exposes no Volume property" }; return@launch }
                val next = (current + delta).coerceIn(0.0, 1.0)
                props.Set(MPRIS_PLAYER_IFACE, "Volume", Variant(next))
                log.info { "MPRIS volume ${if (delta > 0) "up" else "down"} → ${(next * 100).roundToInt()}% ($bus)" }
            } catch (e: Exception) {
                log.debug { "MPRIS volume($bus) failed (player may not support it): ${e.message}" }
            }
        }
    }

    private fun clearAll() {
        synchronized(lock) {
            players.clear(); uniqueToBus.clear(); busToUnique.clear(); identities.clear(); startedPlayingAt.clear()
            targetBus = null
            _playbackState.value = null
        }
    }

    /** `playerctld` mirrors the most-recent player; skip it so it doesn't duplicate that player. */
    private fun isProxy(busName: String): Boolean =
        busName.removePrefix(MPRIS_BUS_PREFIX).substringBefore('.') == "playerctld"

    private fun prettySuffix(busName: String): String =
        busName.removePrefix(MPRIS_BUS_PREFIX).substringBefore('.')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    /** `GetAll` returns a{sv}, so values arrive as [Variant]s; `Get` returns them already unwrapped. */
    private fun unwrap(v: Any?): Any? = if (v is Variant<*>) v.value else v

    /** Coerce a D-Bus integer (Int32/Int64/UInt32/UInt64 — all [Number] in dbus-java) to Long. */
    private fun asLong(v: Any?): Long? = (v as? Number)?.toLong()
}

/**
 * Adjusts the **system/master** output volume for `music.volume = system`, by running a short command
 * per watch button press. Linux has no portable session-bus volume API (PulseAudio's D-Bus interface
 * is rarely enabled and PipeWire has none), so — like the weather command source — we shell out to the
 * standard tools.
 *
 * [resolve] picks the backend once: an explicit config override if both up/down commands are set,
 * otherwise the first of `wpctl` (PipeWire) → `pactl` (PulseAudio / pipewire-pulse) → `amixer` (ALSA)
 * found on `PATH`. It returns null when system volume was requested but nothing usable is present, so
 * the caller falls back to per-player volume.
 */
class SystemVolume private constructor(
    private val upCmd: List<String>,
    private val downCmd: List<String>,
    private val label: String,
) {
    fun up(scope: CoroutineScope) { run(scope, upCmd, "up") }
    fun down(scope: CoroutineScope) { run(scope, downCmd, "down") }

    private fun run(scope: CoroutineScope, cmd: List<String>, dir: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                when {
                    !p.waitFor(5, TimeUnit.SECONDS) -> { p.destroy(); log.warn { "system volume $dir timed out ($label)" } }
                    p.exitValue() != 0 -> log.warn { "system volume $dir failed ($label, exit ${p.exitValue()}): ${out.trim()}" }
                    else -> log.info { "system volume $dir ($label)" }
                }
            } catch (e: Exception) {
                log.warn { "system volume $dir ($label) failed: ${e.message}" }
            }
        }
    }

    companion object {
        private const val STEP_PCT = 5

        fun resolve(upOverride: String, downOverride: String): SystemVolume? {
            if (upOverride.isNotBlank() && downOverride.isNotBlank()) {
                log.info { "Music volume: system (custom command)" }
                return SystemVolume(listOf("sh", "-c", upOverride), listOf("sh", "-c", downOverride), "custom command")
            }
            for (b in BACKENDS) {
                if (onPath(b.bin)) {
                    log.info { "Music volume: system via ${b.bin}" }
                    return SystemVolume(b.up, b.down, b.bin)
                }
            }
            log.warn {
                "music.volume=system but none of ${BACKENDS.joinToString(" / ") { it.bin }} found on PATH — " +
                    "falling back to per-player volume (set music.volume_up_command/down_command to override)"
            }
            return null
        }

        private class Backend(val bin: String, val up: List<String>, val down: List<String>)

        // wpctl caps at 100% (-l 1.0) on the way up; pactl/amixer use their own clamping.
        private val BACKENDS = listOf(
            Backend("wpctl",
                listOf("wpctl", "set-volume", "-l", "1.0", "@DEFAULT_AUDIO_SINK@", "$STEP_PCT%+"),
                listOf("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "$STEP_PCT%-")),
            Backend("pactl",
                listOf("pactl", "set-sink-volume", "@DEFAULT_SINK@", "+$STEP_PCT%"),
                listOf("pactl", "set-sink-volume", "@DEFAULT_SINK@", "-$STEP_PCT%")),
            Backend("amixer",
                listOf("amixer", "-q", "sset", "Master", "$STEP_PCT%+", "unmute"),
                listOf("amixer", "-q", "sset", "Master", "$STEP_PCT%-")),
        )

        private fun onPath(bin: String): Boolean = try {
            val p = ProcessBuilder("sh", "-c", "command -v $bin").redirectErrorStream(true).start()
            p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) { false }
    }
}
