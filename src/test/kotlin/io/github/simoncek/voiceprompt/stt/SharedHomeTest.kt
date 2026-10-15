package io.github.simoncek.voiceprompt.stt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Review P6: the plugin's on-disk home (runtime + models + server log) moved from the
 * per-IDE-product/version system path to one shared home (`~/.voice-prompt`), with a
 * one-time migration of what the current IDE already holds, and a cross-process lock so
 * two IDEs cold-starting don't extract the runtime into the shared dir at once.
 */
class VoicePromptPathsTest {

    private lateinit var home: Path
    private lateinit var legacy: Path

    @Before
    fun setUp() {
        home = Files.createTempDirectory("vp-home")
        legacy = Files.createTempDirectory("vp-legacy")
        VoicePromptPaths.override = home
    }

    @After
    fun tearDown() {
        VoicePromptPaths.override = null
        home.toFile().deleteRecursively()
        legacy.toFile().deleteRecursively()
    }

    @Test
    fun `the shared home derives the models and runtime locations`() {
        assertEquals(home, VoicePromptPaths.baseDir())
        assertEquals(home.resolve("models"), VoicePromptPaths.modelsDir())
        assertEquals(
            home.resolve("runtime").resolve("v1").resolve("linux-x64"),
            VoicePromptPaths.runtimeDir("v1", "linux-x64"),
        )
    }

    @Test
    fun `migration moves models and runtime out of the legacy per-IDE dir`() {
        val model = legacy.resolve("models").resolve("ggml-tiny.en.bin")
        val marker = legacy.resolve("runtime").resolve("v1").resolve("linux-x64").resolve(".validated")
        val cli = legacy.resolve("runtime").resolve("v1").resolve("linux-x64").resolve("whisper-cli")
        Files.createDirectories(model.parent)
        Files.writeString(model, "model-bytes")
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "v1")
        Files.writeString(cli, "cli-bytes")

        VoicePromptPaths.migrateFrom(legacy)

        assertEquals("model-bytes", Files.readString(home.resolve("models").resolve("ggml-tiny.en.bin")))
        assertEquals("v1", Files.readString(home.resolve("runtime").resolve("v1").resolve("linux-x64").resolve(".validated")))
        assertEquals("cli-bytes", Files.readString(home.resolve("runtime").resolve("v1").resolve("linux-x64").resolve("whisper-cli")))
        assertFalse("the legacy model must be moved, not copied", Files.exists(model))
        assertFalse("the legacy runtime must be moved, not copied", Files.exists(legacy.resolve("runtime")))
    }

    @Test
    fun `migration keeps an existing shared copy and drops the redundant legacy one`() {
        val sharedModel = home.resolve("models").resolve("ggml-tiny.en.bin")
        Files.createDirectories(sharedModel.parent)
        Files.writeString(sharedModel, "shared-bytes")
        val legacyModel = legacy.resolve("models").resolve("ggml-tiny.en.bin")
        Files.createDirectories(legacyModel.parent)
        Files.writeString(legacyModel, "legacy-bytes")

        VoicePromptPaths.migrateFrom(legacy)

        assertEquals("the shared copy must win", "shared-bytes", Files.readString(sharedModel))
        assertFalse("the redundant per-IDE copy must go", Files.exists(legacyModel))
    }

    @Test
    fun `migration leaves non-model files and an absent legacy dir alone`() {
        val part = legacy.resolve("models").resolve("ggml-small.en.bin.part")
        Files.createDirectories(part.parent)
        Files.writeString(part, "in-progress download")

        VoicePromptPaths.migrateFrom(legacy)
        VoicePromptPaths.migrateFrom(Files.createTempDirectory("vp-empty-legacy"))

        assertTrue("an interrupted download part is not a finished model; it stays", Files.exists(part))
        assertFalse("nothing may appear in the shared models dir", Files.exists(home.resolve("models")))
    }
}

/**
 * Review P6: [FileLock] is what makes the shared runtime dir safe for two IDE processes.
 *
 * Cross-process semantics only: within one JVM a second [java.nio.channels.FileChannel.tryLock]
 * on an already-locked file throws [java.nio.channels.OverlappingFileLockException] instead of
 * returning null (JDK per-JVM lock table), so the null-polling path is exercised here with a
 * separate holder process — Python's `fcntl.lockf` takes the same POSIX (fcntl) locks as Java's
 * `tryLock`. Production relies on `synchronized(LOCK)` in [WhisperRuntime.ensureRuntime] so the
 * in-JVM overlap can never occur.
 */
class FileLockTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vp-lock")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    /**
     * Spawns a process that takes an exclusive POSIX lock on [lockFile] and holds it for
     * [holdSeconds]. Prints "held" on stdout once the lock is actually owned.
     */
    private fun spawnHolder(lockFile: Path, holdSeconds: Double): Process {
        val script = """
            import fcntl, sys, time
            path, seconds = sys.argv[1], float(sys.argv[2])
            fd = open(path, "a+")
            fcntl.lockf(fd, fcntl.LOCK_EX)
            print("held", flush=True)
            time.sleep(seconds)
            fcntl.lockf(fd, fcntl.LOCK_UN)
        """.trimIndent()
        return ProcessBuilder("python3", "-c", script, lockFile.toString(), holdSeconds.toString())
            .redirectErrorStream(true)
            .start()
    }

    private fun python3Available(): Boolean =
        runCatching {
            ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

    @Test
    fun `acquisition times out while another process holds the lock`() {
        Assume.assumeTrue("python3 is required for the cross-process lock test", python3Available())
        val lockFile = dir.resolve("test.lock")
        val holder = spawnHolder(lockFile, 10.0)
        try {
            assertEquals("the holder must own the lock", "held", holder.inputStream.bufferedReader().readLine())
            try {
                FileLock(lockFile, 500, TimeUnit.MILLISECONDS)
                fail("another process holds the lock; acquisition must time out, not block")
            } catch (expected: FileLock.LockTimeoutException) {
                // expected
            }
        } finally {
            holder.destroy()
            holder.waitFor()
        }
        // The OS releases the lock when the holder exits; it is acquirable again.
        FileLock(lockFile, 5, TimeUnit.SECONDS).use { }
    }

    @Test
    fun `a waiting acquirer proceeds as soon as the holding process exits`() {
        Assume.assumeTrue("python3 is required for the cross-process lock test", python3Available())
        val lockFile = dir.resolve("test.lock")
        val holder = spawnHolder(lockFile, 1.5)
        try {
            assertEquals("the holder must own the lock", "held", holder.inputStream.bufferedReader().readLine())
            val acquired = AtomicBoolean(false)
            val waiter = Thread {
                FileLock(lockFile, 10, TimeUnit.SECONDS).use { acquired.set(true) }
            }
            waiter.start()
            Thread.sleep(300)
            assertFalse("the waiter must not hold the lock while the holder does", acquired.get())
            waiter.join(15_000)
            assertTrue("the waiter must acquire once the holder released", acquired.get())
        } finally {
            holder.destroy()
            holder.waitFor()
        }
    }
}
