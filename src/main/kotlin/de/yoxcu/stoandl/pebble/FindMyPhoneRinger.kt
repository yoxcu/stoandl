package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.util.UserDirs
import de.yoxcu.stoandl.util.onPath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.host.PhoneSoundOutput
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

/** Players tried in this order, the same programs text to speech plays through (without aplay: it can't
 *  decode the Ogg sound). Both take the file as their one argument and play it once at the stream's full
 *  volume; the system volume is left alone. */
internal val RING_PLAYERS = listOf("pw-play", "paplay")

/** The freedesktop sound theme's names, most fitting first (sound-theme-freedesktop ships both). */
private val RING_SOUNDS = listOf("alarm-clock-elapsed", "phone-incoming-call")

/** A play that ends sooner than this without an error can't have rung: treated as a failed player, so a
 *  broken file never spins the loop. The theme's sounds last several seconds. */
private const val MIN_PLAY_MS = 300L

/**
 * Find my phone: the watch (libpebble3's built-in FindMyPhone plugin, through `PhoneSoundPlayer`) rings this
 * device. Loops the freedesktop alarm sound through the first player on PATH ([RING_PLAYERS]) and shows a
 * desktop notification whose Stop ends it, until the watch or the plugin stops the ring (the handle's close).
 *
 * Who ends a ring decides what follows: a close (the watch said stop) withdraws the notification; the Stop
 * button, or a player that fails or exits at once, calls `onStopped` so libpebble3's ringing state follows.
 * [start] throws when there is no player or no sound to play.
 *
 * [notify] shows the notification and calls its argument on Stop; closing what it returns withdraws it.
 */
internal class FindMyPhoneRinger(
    private val notify: (onStop: () -> Unit) -> AutoCloseable,
    private val findProgram: (String) -> File? = ::onPath,
    private val sound: () -> File? = ::freedesktopRingSound,
    private val startPlayer: (List<String>) -> Process = { argv ->
        ProcessBuilder(argv).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    },
) : PhoneSoundOutput {
    override fun start(onStopped: () -> Unit): AutoCloseable {
        val argv = ringCommand(findProgram, sound())
            ?: error("Can't ring this phone: needs ${RING_PLAYERS.joinToString(" or ")} and the freedesktop sound theme")
        return Ring(argv, onStopped)
    }

    private inner class Ring(private val argv: List<String>, private val onStopped: () -> Unit) : AutoCloseable {
        private val stopped = AtomicBoolean(false)
        @Volatile private var player: Process = startPlayer(argv)   // a failure to start throws out of start()
        // Without a notification (no notification server) the ring still plays; the watch stops it.
        private val notification: AutoCloseable = runCatching { notify { end(byHost = true, withdraw = false) } }
            .getOrElse { log.warn { "Find my phone: no Stop notification: ${it.message}" }; AutoCloseable {} }

        init {
            log.info { "Find my phone: ringing (${argv.first()} ${argv.last()})" }
            Thread.ofVirtual().name("find-my-phone").start(::loop)
        }

        private fun loop() {
            while (!stopped.get()) {
                val began = System.currentTimeMillis()
                val code = runCatching { player.waitFor() }.getOrElse { -1 }
                if (stopped.get()) return
                val tooShort = System.currentTimeMillis() - began < MIN_PLAY_MS
                if (code != 0 || tooShort) {
                    log.warn { "Find my phone: ${argv.first()} ${if (code != 0) "exited $code" else "ended at once"}" }
                    end(byHost = true, withdraw = true)
                    return
                }
                val next = try {
                    startPlayer(argv)
                } catch (e: Exception) {
                    log.warn { "Find my phone: can't restart ${argv.first()}: ${e.message}" }
                    end(byHost = true, withdraw = true)
                    return
                }
                synchronized(this) {
                    if (stopped.get()) next.destroyTree() else player = next
                }
            }
        }

        /** Ends the ring once: stops the player, withdraws the notification unless it went by itself (its
         *  Stop button), and tells libpebble3 when the ring ended here rather than by [close]. */
        private fun end(byHost: Boolean, withdraw: Boolean) {
            if (!stopped.compareAndSet(false, true)) return
            synchronized(this) { player.destroyTree() }
            if (withdraw) runCatching { notification.close() }.onFailure { log.debug { "Find my phone: notification not withdrawn: ${it.message}" } }
            log.info { "Find my phone: ring stopped" + if (byHost) " on this device" else "" }
            if (byHost) onStopped()
        }

        override fun close() = end(byHost = false, withdraw = true)
    }
}

private fun Process.destroyTree() {
    descendants().forEach { it.destroy() }
    destroy()
}

/** The player command for [sound]: the first of [RING_PLAYERS] [findProgram] finds, or null when there is
 *  none or no sound. */
internal fun ringCommand(findProgram: (String) -> File?, sound: File?): List<String>? {
    if (sound == null) return null
    val player = RING_PLAYERS.firstNotNullOfOrNull(findProgram) ?: return null
    return listOf(player.path, sound.path)
}

/** The freedesktop theme's alarm sound in the XDG data dirs ([RING_SOUNDS], `.oga`), or null. */
internal fun freedesktopRingSound(dataDirs: List<File> = UserDirs.dataDirs()): File? =
    RING_SOUNDS.firstNotNullOfOrNull { name ->
        dataDirs.map { File(it, "sounds/freedesktop/stereo/$name.oga") }.firstOrNull { it.isFile }
    }
