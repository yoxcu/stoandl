package de.yoxcu.stoandl

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

private val log = KotlinLogging.logger {}

/**
 * Gets libpebble3's bundled SQLite (androidx `sqlite-bundled`, a JNI library) ready to load. [prepare]
 * runs once at daemon start, before anything opens the Room database, and does two jobs (Linux only):
 *
 *  1. **musl `__isnan`.** androidx's `libsqliteJni.so` is linked against glibc and imports `__isnan`
 *     (what glibc's `isnan()` macro expands to), which musl doesn't export. The JVM loads JNI libraries
 *     `RTLD_LAZY`, so musl leaves that slot unresolved instead of failing the load, and the first REAL
 *     value in any SQL (`AVG()`, a bound double, a float literal, …) jumps into the void: a SIGSEGV
 *     nothing can catch. On musl, when `dlsym(RTLD_DEFAULT, "__isnan")` finds nothing, we `dlopen` a
 *     libc-free shim (`tools/isnan-shim/`, embedded per arch) `RTLD_NOW | RTLD_GLOBAL`, from the cache
 *     or, if that can't map it, from a throwaway copy in java.io.tmpdir; its `__isnan` then sits in the
 *     global namespace, where SQLite's later load resolves it. (`System.load` won't do: it loads
 *     `RTLD_LOCAL`.) If the symbol is still missing we log an ERROR with the remedy, since that line is
 *     the only diagnosis the crash leaves. glibc exports `__isnan` itself, so on glibc this part doesn't
 *     even run.
 *  2. **One stable copy of the JNI library.** By default the driver extracts `libsqliteJni.so` to a new
 *     `/tmp/androidx_sqliteJni*.tmp` on every start (1.9 MB of tmpfs, i.e. RAM, on a phone) and only
 *     deletes it on a clean exit. We extract it once, content-addressed, under the per-user cache, load
 *     it, and point the driver there (`androidx.sqlite.driver.bundled.path`: the directory it loads
 *     `libsqliteJni.so` from). Whatever the outcome we sweep the temp copies earlier runs left behind.
 *
 * Only the daemon opens `libpebble3.db` (the CLI goes through D-Bus; `backup`/`restore` copy the file
 * with tar), so only the daemon calls this. Every failure is logged and falls back to the driver's
 * own behaviour; nothing here may stop the daemon from starting.
 */
internal object SqliteNative {
    private const val PATH_PROPERTY = "androidx.sqlite.driver.bundled.path"
    private const val SHIM_FILE = "libisnan-shim.so"
    // <dlfcn.h>: the same values on glibc and musl, x86_64 and aarch64.
    private const val RTLD_NOW = 2
    private const val RTLD_GLOBAL = 0x100
    // Leftover driver temp copies younger than this are kept: another JVM may be loading its own copy.
    private const val TEMP_SWEEP_MIN_AGE_MS = 10 * 60_000L

    /** The arches androidx ships a Linux SQLite for: our shim dir name, and androidx's `natives/` dir. */
    private enum class Arch(val shimDir: String, val androidxDir: String) {
        X86_64("linux-x86_64", "linux_x64"),
        AARCH64("linux-aarch64", "linux_arm64"),
    }

    fun prepare() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) return
        val arch = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> Arch.X86_64
            "aarch64", "arm64" -> Arch.AARCH64
            else -> return // no bundled SQLite for this arch at all; the driver's own error says so
        }
        val nativeDir = File(cacheDir(), "native")
        if (runsOnMusl() != false) ensureIsnan(arch, nativeDir)
        pinDriverLibrary(arch, nativeDir)
        sweepTempCopies()
    }

    private fun ensureIsnan(arch: Arch, nativeDir: File) {
        val resource = "de/yoxcu/stoandl/natives/${arch.shimDir}/$SHIM_FILE"
        val problems = mutableListOf<String>()
        // The stable copy first: it needs no FFM, and it is what the manual LD_PRELOAD fix points at.
        val cached: File? = try {
            if (onNoexecMount(nativeDir)) {
                problems += "$nativeDir is on a noexec mount"
                null
            } else {
                extract(resource, nativeDir, "isnan-shim", SHIM_FILE)
            }
        } catch (e: Exception) {
            problems += "cannot cache it: $e"
            null
        }
        val dl = try {
            Dl()
        } catch (t: Throwable) {
            // No FFM (e.g. --illegal-native-access=deny): we can't look for __isnan, let alone load it.
            val preload = System.getenv("LD_PRELOAD")?.takeIf { it.isNotBlank() }
            if (preload == null) {
                val why = (listOf("cannot check for or load it: $t") + problems).joinToString("; ")
                log.error { noIsnanError(why, cached, resource) }
            } else {
                log.warn {
                    "SQLite: cannot check for __isnan ($t); assuming LD_PRELOAD=$preload provides it. If it " +
                        "doesn't, the daemon crashes at the first floating-point value SQLite handles."
                }
            }
            return
        }
        try {
            if (dl.isGlobal("__isnan")) return // a compat library (gcompat, LD_PRELOAD) already has it
            // The cached copy, else a throwaway one in java.io.tmpdir: that is where the driver puts its own
            // library when ours can't be used, so wherever SQLite loads at all, the shim does too.
            val shim = cached?.takeIf { openShim(dl, it, problems) } ?: openTempShim(dl, resource, problems)
            if (shim != null && dl.isGlobal("__isnan")) {
                val via = if (problems.isEmpty()) "" else " (a temp copy: ${problems.joinToString("; ")})"
                log.info { "SQLite: C library without __isnan (musl) — loaded the built-in shim $shim$via" }
                return
            }
            if (shim != null) problems += "loaded $shim, but __isnan is still not resolvable"
        } catch (t: Throwable) {
            problems += t.toString()
        }
        // We looked and __isnan is still missing, so preloading the cached copy would fail the same way.
        log.error { noIsnanError(problems.joinToString("; "), null, resource) }
    }

    /** `dlopen` [file] into the global namespace; true on success, else the reason goes to [problems]. */
    private fun openShim(dl: Dl, file: File, problems: MutableList<String>): Boolean {
        val error = dl.openGlobal(file.path) ?: return true
        problems += "dlopen failed: $error"
        return false
    }

    /** [openShim] on a copy in a fresh java.io.tmpdir directory, deleted again right after: the mapping
     *  outlives the file. The copy (already gone) on success, else null. */
    private fun openTempShim(dl: Dl, resource: String, problems: MutableList<String>): File? {
        var dir: File? = null
        return try {
            dir = Files.createTempDirectory("stoandl-isnan").toFile()
            val file = File(dir, SHIM_FILE).apply { writeBytes(readResource(resource)) }
            file.takeIf { openShim(dl, it, problems) }
        } catch (e: Exception) {
            problems += "temp copy: $e"
            null
        } finally {
            dir?.deleteRecursively()
        }
    }

    /** The ERROR a later SIGSEGV leaves as its only diagnosis. [preloadable]: a copy of the shim that
     *  `LD_PRELOAD` can use as it is; without one the user extracts it from the JAR. */
    private fun noIsnanError(why: String, preloadable: File?, resource: String): String {
        val shim = preloadable?.path ?: "/path/to/$SHIM_FILE"
        val unzip = if (preloadable != null) "" else {
            "extract the built-in shim to a filesystem that allows executables " +
                "(unzip -p ${jarPath()} $resource > $shim) and "
        }
        return "SQLite: could not provide __isnan ($why). musl lacks it and the bundled SQLite (built for glibc) " +
            "calls it, so the daemon will crash (SIGSEGV in the SQLite JNI library, e.g. at avgFinalize or " +
            "sqlite3AtoF) at the first floating-point value SQLite handles (an AVG(), a REAL column, a float " +
            "literal in a query), e.g. the health sync after the watch connects. Fix: ${unzip}start stoandl with " +
            "LD_PRELOAD=$shim (systemd user service: a drop-in with [Service] Environment=LD_PRELOAD=$shim). " +
            "See README → Requirements."
    }

    /** Point the driver at a stable, cached copy of `libsqliteJni.so` unless the user already did. */
    private fun pinDriverLibrary(arch: Arch, nativeDir: File) {
        if (System.getProperty(PATH_PROPERTY) != null) return
        val lib = try {
            if (onNoexecMount(nativeDir)) {
                // The driver's System.load would fail there and it doesn't fall back — keep its /tmp copy.
                log.warn { "SQLite: $nativeDir is on a noexec mount; the driver extracts its library to a temp file instead" }
                return
            }
            extract("natives/${arch.androidxDir}/libsqliteJni.so", nativeDir, "sqliteJni", "libsqliteJni.so")
        } catch (e: Exception) {
            log.warn { "SQLite: cannot cache the JNI library ($e); the driver extracts it to a temp file instead" }
            return
        }
        // Once the property is set the driver loads from there or fails; it never falls back. So load the
        // copy ourselves first, since more than a noexec mount can refuse it (an LSM policy, a FUSE home).
        // Same class loader as the driver, so its own System.load of this file is then a no-op.
        try {
            System.load(lib.canonicalPath)
        } catch (t: Throwable) {
            log.warn { "SQLite: cannot load $lib ($t); the driver extracts it to a temp file instead" }
            return
        }
        System.setProperty(PATH_PROPERTY, lib.parent)
        log.info { "SQLite: JNI library $lib" }
    }

    /**
     * Copy classpath [resource] to `<dir>/<stem>-<hash>/<fileName>` (hash = SHA-256 prefix of its bytes)
     * unless an identical copy is already there, and delete the other `<stem>-*` copies. Content-addressed
     * so a different JAR never reuses a stale file; written via temp file + rename so a crash mid-write
     * can't leave a torn library behind. Deleting a copy a running process has loaded is harmless.
     */
    private fun extract(resource: String, dir: File, stem: String, fileName: String): File {
        val bytes = readResource(resource)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8)
        val home = File(dir, "$stem-$hash")
        val target = File(home, fileName)
        if (!(target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes))) {
            Files.createDirectories(home.toPath())
            val tmp = Files.createTempFile(home.toPath(), ".$fileName", ".part")
            try {
                Files.write(tmp, bytes)
                Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            log.info { "SQLite: extracted $resource to $target" }
        }
        // A kill mid-write leaves its temp file behind (only one daemon runs, so none is still in use).
        home.listFiles { f -> f.name.startsWith(".$fileName") && f.name.endsWith(".part") }?.forEach { it.delete() }
        dir.listFiles { f -> f.isDirectory && f.name.startsWith("$stem-") && f.name != home.name }
            ?.forEach { it.deleteRecursively() }
        return target
    }

    private fun readResource(resource: String): ByteArray =
        SqliteNative::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw IllegalStateException("$resource is not on the classpath")

    /** The JAR we run from, for the manual-fix command; a placeholder when not run from one. */
    private fun jarPath(): String = try {
        File(SqliteNative::class.java.protectionDomain.codeSource.location.toURI()).path
    } catch (_: Exception) {
        "stoandl.jar"
    }

    /** Delete the `androidx_sqliteJni*.tmp` copies the driver left in java.io.tmpdir on earlier runs. */
    private fun sweepTempCopies() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val cutoff = System.currentTimeMillis() - TEMP_SWEEP_MIN_AGE_MS
        val removed = tmpDir.listFiles { f ->
            f.name.startsWith("androidx_sqliteJni") && f.name.endsWith(".tmp") && f.isFile && f.lastModified() < cutoff
        }?.count { it.delete() } ?: 0
        if (removed > 0) log.info { "SQLite: removed $removed stale androidx_sqliteJni*.tmp copies from $tmpDir" }
    }

    /** `$XDG_CACHE_HOME/stoandl` (default `~/.cache/stoandl`): per-user, regenerable, and not in backups. */
    private fun cacheDir(): File {
        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        return File(xdg ?: (System.getProperty("user.home") + "/.cache"), "stoandl")
    }

    /** Whether this JVM runs on musl — its libc *is* its loader, `ld-musl-<arch>.so.1`. Null if unknown. */
    private fun runsOnMusl(): Boolean? = try {
        File("/proc/self/maps").useLines { lines -> lines.any { "/ld-musl-" in it } }
    } catch (_: Exception) {
        null
    }

    /** Whether [dir] (or the part of it that exists) is on a `noexec` mount, where no library can be
     *  mapped. False when that can't be read. */
    private fun onNoexecMount(dir: File): Boolean = try {
        val path = dir.canonicalPath
        val octal = Regex("""\\([0-7]{3})""") // mountinfo escapes space/tab/newline/backslash as \ooo
        var best: List<String>? = null
        File("/proc/self/mountinfo").forEachLine { line ->
            // "<id> <parent> <maj:min> <root> <mount point> <mount options> …"
            val f = line.split(' ')
            if (f.size < 6) return@forEachLine
            val mp = octal.replace(f[4]) { it.groupValues[1].toInt(8).toChar().toString() }
            val contains = mp == "/" || path == mp || path.startsWith("$mp/")
            // The longest mount point wins; on a tie the later entry (mounted on top) does.
            if (contains && mp.length >= (best?.get(0)?.length ?: -1)) best = listOf(mp, f[5])
        }
        best?.get(1)?.split(',')?.contains("noexec") == true
    } catch (_: Exception) {
        false
    }

    /**
     * The libdl calls we need, bound through the JDK's default lookup (libc on musl; the JDK's lookup
     * library also links libdl for glibc < 2.34). That lookup is NOT `dlsym(RTLD_DEFAULT)` — it never
     * sees a library we `dlopen` ourselves — hence the real dlsym for the check.
     */
    private class Dl {
        private val linker = Linker.nativeLinker()

        private fun handle(name: String, desc: FunctionDescriptor): MethodHandle {
            val addr = linker.defaultLookup().find(name).orElseThrow { UnsatisfiedLinkError("libc symbol not found: $name") }
            return linker.downcallHandle(addr, desc)
        }

        // void* dlsym(void* handle, const char* symbol)
        private val dlsym = handle("dlsym", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        // void* dlopen(const char* file, int mode)
        private val dlopen = handle("dlopen", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
        // char* dlerror(void)
        private val dlerror = handle("dlerror", FunctionDescriptor.of(ADDRESS))

        /** Whether [symbol] resolves in the process-global namespace: `dlsym(RTLD_DEFAULT, symbol)`. */
        fun isGlobal(symbol: String): Boolean = Arena.ofConfined().use { arena ->
            val addr = dlsym.invokeExact(MemorySegment.NULL, arena.allocateFrom(symbol)) as MemorySegment
            addr.address() != 0L
        }

        /** `dlopen(path, RTLD_NOW | RTLD_GLOBAL)`: null on success, else dlerror()'s message. Never
         *  dlclose()d — the library has to stay for the life of the process. */
        fun openGlobal(path: String): String? = Arena.ofConfined().use { arena ->
            val handle = dlopen.invokeExact(arena.allocateFrom(path), RTLD_NOW or RTLD_GLOBAL) as MemorySegment
            if (handle.address() != 0L) return@use null
            val msg = dlerror.invokeExact() as MemorySegment
            if (msg.address() == 0L) "unknown error" else msg.reinterpret(Long.MAX_VALUE).getString(0)
        }
    }
}
