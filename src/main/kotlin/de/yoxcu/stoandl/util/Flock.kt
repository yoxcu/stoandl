package de.yoxcu.stoandl.util

import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * flock(2) on a lock file: the lock a sync tool holds while it rewrites calendar files (the op6's dav-sync
 * runs vdirsyncer under busybox `flock` on `$XDG_RUNTIME_DIR/dav-sync.lock`; Calindori takes it to save).
 * Java's `FileChannel.lock()` takes fcntl(2) record locks, which Linux keeps apart from flock locks, so
 * neither side would see the other: this calls libc's `open`/`flock`/`close` through FFM (the JDK's default
 * lookup: libc on glibc and musl alike). A lock belongs to its open file description, so two opens in this
 * process exclude each other as well.
 */
object Flock {
    /** A held lock; [close] releases it (closes the descriptor). */
    class Held internal constructor(private val fd: Int) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) libc.close(fd)
        }
    }

    /**
     * Take [file]'s lock exclusively, creating the file if it is missing, trying every [poll] for up to [wait]
     * (never blocking in the kernel, so a cancelled caller lets go at once). Null when another holder kept it
     * that long; throws [IOException] when the file can't be opened or locked at all.
     */
    suspend fun acquire(file: File, wait: Duration, poll: Duration = 100.milliseconds): Held? {
        val fd = libc.open(file.path, O_RDONLY or O_CREAT or O_CLOEXEC)
        if (fd < 0) throw IOException("can't open ${file.path}: ${libc.strerror(-fd)}")
        val deadline = TimeSource.Monotonic.markNow() + wait
        try {
            while (true) {
                val errno = libc.tryLock(fd)
                when {
                    errno == 0 -> return Held(fd)
                    errno == EINTR -> continue
                    errno != EWOULDBLOCK -> throw IOException("can't lock ${file.path}: ${libc.strerror(errno)}")
                    deadline.hasPassedNow() -> { libc.close(fd); return null }
                }
                delay(poll)
            }
        } catch (e: Throwable) {
            libc.close(fd)
            throw e
        }
    }

    /** Whether another open file holds [file]'s lock now. A file that is missing or can't be opened or locked
     *  counts as free: there is nothing to wait for. Takes the lock for a moment when it is free. */
    fun isHeld(file: File): Boolean {
        val fd = libc.open(file.path, O_RDONLY or O_CLOEXEC)
        if (fd < 0) return false
        try {
            var errno: Int
            do errno = libc.tryLock(fd) while (errno == EINTR)
            return errno == EWOULDBLOCK
        } finally {
            libc.close(fd) // also releases it when it was free
        }
    }

    /** Wait until nobody holds [file]'s lock, checking every [poll], for up to [wait]. True once it is free,
     *  false when it was still held at the end. */
    suspend fun awaitFree(file: File, wait: Duration, poll: Duration = 1.seconds): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + wait
        while (isHeld(file)) {
            if (deadline.hasPassedNow()) return false
            delay(poll)
        }
        return true
    }

    // The asm-generic values, which x86_64, arm and aarch64 (glibc and musl) all use.
    private const val O_RDONLY = 0
    private const val O_CREAT = 0x40
    private const val O_CLOEXEC = 0x80000
    private const val LOCK_EX = 2
    private const val LOCK_NB = 4
    private const val EINTR = 4
    private const val EWOULDBLOCK = 11 // = EAGAIN

    private val libc by lazy { Libc() }

    private class Libc {
        private val linker = Linker.nativeLinker()
        private val errnoState = Linker.Option.captureStateLayout()
        private val errnoOffset = errnoState.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
        private val captureErrno = Linker.Option.captureCallState("errno")

        private fun h(name: String, desc: FunctionDescriptor, vararg options: Linker.Option): MethodHandle {
            val addr = linker.defaultLookup().find(name).orElseThrow { UnsatisfiedLinkError("libc symbol not found: $name") }
            return linker.downcallHandle(addr, desc, *options)
        }

        // int open(const char *path, int flags, ... /* mode_t mode */), errno captured
        private val open = h("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
            Linker.Option.firstVariadicArg(2), captureErrno)
        // int flock(int fd, int operation), errno captured
        private val flock = h("flock", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), captureErrno)
        // int close(int fd)
        private val close = h("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
        // char *strerror(int errnum)
        private val strerror = h("strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT))

        /** The descriptor, or minus errno. A created file gets mode 0600 (0x180), as Calindori creates it. */
        fun open(path: String, flags: Int): Int = Arena.ofConfined().use { arena ->
            val state = arena.allocate(errnoState)
            val fd = open.invokeExact(state, arena.allocateFrom(path), flags, 0x180) as Int
            if (fd >= 0) fd else -state.get(JAVA_INT, errnoOffset)
        }

        /** `flock(fd, LOCK_EX | LOCK_NB)`: 0 when taken, else errno. */
        fun tryLock(fd: Int): Int = Arena.ofConfined().use { arena ->
            val state = arena.allocate(errnoState)
            val r = flock.invokeExact(state, fd, LOCK_EX or LOCK_NB) as Int
            if (r == 0) 0 else state.get(JAVA_INT, errnoOffset)
        }

        fun close(fd: Int): Int = close.invokeExact(fd) as Int

        fun strerror(errno: Int): String {
            val msg = strerror.invokeExact(errno) as MemorySegment
            return if (msg.address() == 0L) "errno $errno" else msg.reinterpret(Long.MAX_VALUE).getString(0)
        }
    }
}
