package io.github.simoncek.voiceprompt.stt

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock as NioFileLock
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * Cross-process exclusive lock (review P6): the runtime directory is now shared across IDE
 * products and versions, and a JVM monitor only serializes within one process, so two IDEs
 * cold-starting must not extract into the same directory at once. An OS file lock is released
 * when the holding process exits, so a crashed IDE can never wedge it.
 *
 * Usage: `FileLock(file, 120, TimeUnit.SECONDS).use { ... }` — the lock is held until [close].
 *
 * The lock file must be creatable (its parent directory must exist) before construction.
 *
 * Within one JVM a second [java.nio.channels.FileChannel.tryLock] on the same file throws
 * [java.nio.channels.OverlappingFileLockException] rather than returning null (the JDK keeps a
 * per-JVM lock table), so callers that may contend inside the process must serialize
 * themselves first (see [WhisperRuntime.ensureRuntime]'s `synchronized(LOCK)`).
 */
class FileLock(lockFile: Path, timeout: Long, unit: TimeUnit = TimeUnit.SECONDS) : AutoCloseable {

    class LockTimeoutException(message: String) : IOException(message)

    private val channel: FileChannel
    private val held: NioFileLock

    init {
        val ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        var acquired: NioFileLock? = null
        while (acquired == null) {
            acquired = ch.tryLock()
            if (acquired == null && System.nanoTime() >= deadline) {
                ch.close()
                throw LockTimeoutException("timed out waiting for the lock $lockFile")
            }
            // A 50 ms poll: the holder is another IDE extracting a ~50 MB runtime — a sub-second
            // hand-off is plenty, and the poll keeps the wait interruptible by the deadline.
            if (acquired == null) Thread.sleep(50)
        }
        channel = ch
        held = acquired
    }

    override fun close() {
        runCatching { held.close() }
        runCatching { channel.close() }
    }
}
