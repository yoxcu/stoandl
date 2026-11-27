package de.yoxcu.stoandl.language

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.testutil.fakeConnectedWatch
import de.yoxcu.stoandl.testutil.strictStub
import de.yoxcu.stoandl.testutil.unexpected
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.UserFacingError
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstallState
import io.rebble.libpebblecommon.connection.endpointmanager.WatchLanguagePack
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * libpebble3 no longer keeps an install's outcome on its state (`Idle` holds only the active language)
 * and reports a failure as a [UserFacingError] that names no watch, so [LanguageControl] keeps the
 * `done:`/`failed:` the CLI and GUI wait for. A mistake there ends `stoandl language` on a false
 * "Done" or a wait for its timeout, so the outcome rules are driven here through a fake watch.
 */
class LanguageControlStatusTest {
    private val watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    private val errors = MutableSharedFlow<UserFacingError>(extraBufferCapacity = 1)
    // Unconfined: each `watches` emission reaches the follower before the test reads a status.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val config = StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)
    private val control = LanguageControl(AtomicReference(fakeLibPebble()), scope) { config }
    private val pbl = File.createTempFile("de_DE", ".pbl").apply { writeBytes(byteArrayOf(1)) }
    private val installs = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pbl.delete()
    }

    @Test
    fun aFinishedInstallIsDoneUntilTheWatchReconnects() = runBlocking<Unit> {
        watches.value = listOf(watch(LanguagePackInstallState.Idle()))
        assertEquals("idle:", control.status())
        assertEquals("ok:Installing ${pbl.name}", control.sideload(pbl.path))
        assertEquals(listOf(pbl.path), installs)

        watches.value = listOf(watch(LanguagePackInstallState.Downloading(pbl.name)))
        assertEquals("downloading:${pbl.name}", control.status())
        watches.value = listOf(watch(LanguagePackInstallState.Installing(MutableStateFlow(0.4f), pbl.name)))
        assertEquals("installing:40", control.status())
        watches.value = listOf(watch(LanguagePackInstallState.Idle(activeLanguage = pbl.name)))
        awaitUntil { control.status() == "done:${pbl.name}" }
        withTimeout(5.seconds) { control.statusFlow().first { it == "done:${pbl.name}" } }

        // A new connection starts from a plain idle, as libpebble3's own per-connection state does.
        watches.value = emptyList()
        watches.value = listOf(watch(LanguagePackInstallState.Idle()))
        assertEquals("idle:", control.status())
    }

    @Test
    fun aReportedFailureIsFailedNotDone() = runBlocking<Unit> {
        watches.value = listOf(watch(LanguagePackInstallState.Idle()))
        control.sideload(pbl.path)
        watches.value = listOf(watch(LanguagePackInstallState.Installing(MutableStateFlow(0.9f), pbl.name)))

        // libpebble3 reports the failure, then goes idle with the previous language.
        errors.emit(failure("All 3 language pack slots are full - remove one to add another"))
        watches.value = listOf(watch(LanguagePackInstallState.Idle(activeLanguage = "English")))
        assertEquals("failed:All 3 language pack slots are full - remove one to add another", control.status())
        withTimeout(5.seconds) { control.statusFlow().first { it.startsWith("failed:") } }
    }

    @Test
    fun aFailureBeforeAnyProgressIsFailedToo() = runBlocking<Unit> {
        // A download that fails at once: the watch may never be seen downloading.
        watches.value = listOf(watch(LanguagePackInstallState.Idle()))
        control.sideload(pbl.path)
        errors.emit(failure("Failed to download language pack"))
        watches.value = listOf(watch(LanguagePackInstallState.Idle()))
        assertEquals("failed:Failed to download language pack", control.status())

        // The next install starts clean.
        control.sideload(pbl.path)
        assertEquals("idle:", control.status())
        watches.value = listOf(watch(LanguagePackInstallState.Downloading(pbl.name)))
        watches.value = listOf(watch(LanguagePackInstallState.Idle(activeLanguage = pbl.name)))
        awaitUntil { control.status() == "done:${pbl.name}" }
    }

    @Test
    fun anIdleWatchThatNeverRanIsNotDone() = runBlocking<Unit> {
        // Right after the call the snapshot can still be the old idle one: that is not a finished install.
        watches.value = listOf(watch(LanguagePackInstallState.Idle(activeLanguage = "Deutsch")))
        control.sideload(pbl.path)
        watches.value = listOf(watch(LanguagePackInstallState.Idle(activeLanguage = "Deutsch")))
        delay(100)
        assertEquals("idle:", control.status())
    }

    /** libpebble3 drops an install asked for while one runs: a second must be refused, or the first's
     *  outcome would be reported under the second's name. */
    @Test
    fun aSecondInstallWhileOneRunsIsBusy() = runBlocking<Unit> {
        watches.value = listOf(watch(LanguagePackInstallState.Idle()))
        assertEquals("ok:Installing ${pbl.name}", control.sideload(pbl.path))
        // The snapshot may still show the watch idle right after the call.
        assertTrue(control.sideload(pbl.path).startsWith("busy:"))
        watches.value = listOf(watch(LanguagePackInstallState.Installing(MutableStateFlow(0.2f), pbl.name)))
        assertTrue(control.sideload(pbl.path).startsWith("busy:"))
        assertEquals(listOf(pbl.path), installs, "only the first install reached libpebble3")
        watches.value = listOf(watch(LanguagePackInstallState.Idle(activeLanguage = pbl.name)))
        awaitUntil { control.status() == "done:${pbl.name}" }
        assertEquals("ok:Installing ${pbl.name}", control.sideload(pbl.path), "once it ended, the next one starts")
    }

    /** No failure reported (the error stream drops events), but the watch kept its language: not done. */
    @Test
    fun anInstallThatLeftTheWatchOnItsLanguageIsFailed() = runBlocking<Unit> {
        watches.value = listOf(watch(LanguagePackInstallState.Idle(activeLanguage = "English")))
        control.sideload(pbl.path)
        watches.value = listOf(watch(LanguagePackInstallState.Installing(MutableStateFlow(1f), pbl.name)))
        watches.value = listOf(watch(LanguagePackInstallState.Idle(activeLanguage = "English")))
        awaitUntil { control.status().startsWith("failed:") }
        assertTrue("isn't the watch's language" in control.status(), control.status())
    }

    @Test
    fun aFullOrDuplicateMultiPackWatchRefusesAnInstall() = runBlocking<Unit> {
        onWatch.addAll(listOf(pack(0, "de_DE", "Deutsch", active = true), pack(1, "fr_FR", "Français"),
            pack(2, "es_ES", "Español"), pack(3, "it_IT", "Italiano")))
        watches.value = listOf(watch(LanguagePackInstallState.Idle(), multiPack = true))
        val full = control.sideload(pbl.path)
        assertTrue(full.startsWith("error:All 4 language-pack slots"), full)
        onWatch.removeAt(3)
        assertEquals("ok:Installing ${pbl.name}", control.sideload(pbl.path))
        assertEquals(listOf(pbl.path), installs)
    }

    @Test
    fun packsAreListedSwitchedAndRemovedOnAMultiPackWatch() = runBlocking<Unit> {
        onWatch.addAll(listOf(pack(0, "de_DE", "Deutsch", active = true), pack(1, "fr_FR", "Français"),
            pack(2, "ja_JP", "日本語", translation = false)))
        watches.value = listOf(watch(LanguagePackInstallState.Idle(), multiPack = true))
        assertEquals(
            "ok:0\tde_DE\tDeutsch\t1\tyes\ttranslation\n1\tfr_FR\tFrançais\t1\tno\ttranslation\n2\tja_JP\t日本語\t1\tno\tfonts",
            control.packs(),
        )
        assertEquals("ok:The watch's language is Français", control.use("fr-fr"))
        assertEquals("ok:The watch's language is English", control.use("English"))
        assertEquals(listOf("Français", "English"), switchedTo)
        assertTrue(control.use("2").startsWith("error:Slot 2 holds fonts only"))
        assertTrue(control.use("xx").startsWith("notfound:"))
        assertEquals("ok:Removed Deutsch from slot 0; the watch's language is English", control.remove("Deutsch"))
        assertEquals("ok:Removed Français from slot 1", control.remove("1"))
        assertEquals(listOf(0, 1), removed)
    }

    @Test
    fun aSinglePackWatchHasNoPackManagement() = runBlocking<Unit> {
        watches.value = listOf(watch(LanguagePackInstallState.Idle()))
        assertTrue(control.packs().startsWith("unavailable:"))
        assertTrue(control.use("english").startsWith("unavailable:"))
        assertTrue(control.remove("0").startsWith("unavailable:"))
        watches.value = emptyList()
        assertEquals("notready:No watch connected", control.packs())
    }

    @Test
    fun aPackIsNamedBySlotLocaleOrName() {
        val packs = listOf(pack(0, "de_DE", "Deutsch"), pack(1, "de_AT", "Deutsch (Österreich)"), pack(2, "fr_FR", "Français"))
        assertEquals(packs[1], (matchPack(packs, "1") as PackMatch.Found).pack)
        assertEquals(packs[0], (matchPack(packs, "de-de") as PackMatch.Found).pack)
        assertEquals(packs[0], (matchPack(packs, "deutsch") as PackMatch.Found).pack, "an exact name beats a part")
        assertEquals(packs[2], (matchPack(packs, "franç") as PackMatch.Found).pack)
        assertTrue((matchPack(packs, "Deut") as PackMatch.Refused).status.startsWith("ambiguous:"))
        assertTrue((matchPack(packs, "9") as PackMatch.Refused).status.startsWith("notfound:"))
    }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) = withTimeout(5.seconds) {
        while (!condition()) delay(10)
    }

    private fun failure(message: String) = UserFacingError.FailedToInstallLanguagePack(message)

    private fun fakeLibPebble(): LibPebble =
        Proxy.newProxyInstance(LibPebble::class.java.classLoader, arrayOf(LibPebble::class.java)) { proxy, method, args ->
            when (method.name) {
                "getWatches" -> watches
                "getUserFacingErrors" -> errors
                "toString" -> "fake LibPebble"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("LibPebble.${method.name}")
            }
        } as LibPebble

    /** The packs on the fake multi-pack watch, what it was asked to switch to and remove. */
    private val onWatch = CopyOnWriteArrayList<WatchLanguagePack>()
    private val switchedTo = CopyOnWriteArrayList<String>()
    private val removed = CopyOnWriteArrayList<Int>()

    private fun pack(slot: Int, locale: String, name: String, active: Boolean = false, translation: Boolean = true) =
        WatchLanguagePack(slot, locale, name, version = 1, readable = true, hasTranslation = translation, isActive = active)

    /** A connected watch as one `watches` emission sees it (see [fakeConnectedWatch]); [multiPack]: firmware
     *  that keeps several packs, whose list, switch and remove answer from [onWatch]. */
    private fun watch(state: LanguagePackInstallState, multiPack: Boolean = false): ConnectedPebbleDevice = fakeConnectedWatch(
        capabilities = if (multiPack) setOf(ProtocolCapsFlag.SupportsMultipleLanguagePacks) else emptySet(),
        installState = state,
        language = strictStub<ConnectedPebble.LanguageInstall> { method, args ->
            when (method) {
                "installLanguagePack" -> installs.add(args!![0].toString()).let { null }
                "listLanguagePacks" -> onWatch.toList()
                "setActiveLanguage" -> (args!![0] as WatchLanguagePack?).let { switchedTo.add(it?.name ?: "English"); true }
                "removeLanguagePack" -> (args!![0] as WatchLanguagePack).let { removed.add(it.slot); true }
                else -> unexpected("LanguageInstall", method)
            }
        },
    )
}
