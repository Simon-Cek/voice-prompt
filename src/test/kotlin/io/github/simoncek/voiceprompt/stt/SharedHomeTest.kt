package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.util.SystemInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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

    // --- review N11: the platform default home, the env override, the legacy-shared-home sources ---

    @Test
    fun `on windows the home is LOCALAPPDATA with the dot dir as fallback`() {
        assertEquals(
            Paths.get("/lad", "VoicePrompt"),
            VoicePromptPaths.defaultHome(true, false, "/u", "/lad", null),
        )
        assertEquals(
            Paths.get("/u", ".voice-prompt"),
            VoicePromptPaths.defaultHome(true, false, "/u", null, null),
        )
        assertEquals(
            Paths.get("/u", ".voice-prompt"),
            VoicePromptPaths.defaultHome(true, false, "/u", "  ", null),
        )
    }

    @Test
    fun `on linux the home is XDG_DATA_HOME with the local share default`() {
        assertEquals(
            Paths.get("/xdg", "voice-prompt"),
            VoicePromptPaths.defaultHome(false, true, "/u", null, "/xdg"),
        )
        assertEquals(
            Paths.get("/u", ".local", "share", "voice-prompt"),
            VoicePromptPaths.defaultHome(false, true, "/u", null, null),
        )
        assertEquals(
            Paths.get("/u", ".local", "share", "voice-prompt"),
            VoicePromptPaths.defaultHome(false, true, "/u", null, ""),
        )
    }

    @Test
    fun `elsewhere the historical dot dir is kept`() {
        assertEquals(
            Paths.get("/u", ".voice-prompt"),
            VoicePromptPaths.defaultHome(false, false, "/u", "/lad", "/xdg"),
        )
    }

    @Test
    fun `without a user home the fallback cannot be resolved`() {
        try {
            VoicePromptPaths.defaultHome(true, false, null, null, null)
            fail("user.home is required for the ~/.voice-prompt fallback")
        } catch (expected: IOException) {
            // expected
        }
    }

    @Test
    fun `the VOICE_PROMPT_HOME environment variable overrides the platform default`() {
        val savedProperty = System.getProperty(VoicePromptPaths.HOME_PROPERTY)
        val savedOverride = VoicePromptPaths.override
        val savedGetenv = VoicePromptPaths.getenv
        System.clearProperty(VoicePromptPaths.HOME_PROPERTY)
        VoicePromptPaths.override = null
        VoicePromptPaths.getenv = { name ->
            when (name) {
                VoicePromptPaths.HOME_ENV -> "/corp/home"
                else -> null
            }
        }
        try {
            assertEquals(Paths.get("/corp/home"), VoicePromptPaths.baseDir())
        } finally {
            VoicePromptPaths.getenv = savedGetenv
            VoicePromptPaths.override = savedOverride
            if (savedProperty != null) System.setProperty(VoicePromptPaths.HOME_PROPERTY, savedProperty)
        }
    }

    @Test
    fun `the system property still beats the environment variable`() {
        val savedOverride = VoicePromptPaths.override
        val savedGetenv = VoicePromptPaths.getenv
        VoicePromptPaths.override = null
        VoicePromptPaths.getenv = { name ->
            when (name) {
                VoicePromptPaths.HOME_ENV -> "/corp/home"
                else -> null
            }
        }
        try {
            // The test suite points the property at a build-local dir (build.gradle.kts); it wins.
            val expected = Paths.get(System.getProperty(VoicePromptPaths.HOME_PROPERTY)!!)
            assertEquals(expected, VoicePromptPaths.baseDir())
        } finally {
            VoicePromptPaths.getenv = savedGetenv
            VoicePromptPaths.override = savedOverride
        }
    }

    @Test
    fun `the platform default branch of baseDir follows the per-OS rules`() {
        val savedUserHome = System.getProperty("user.home")
        val savedProperty = System.getProperty(VoicePromptPaths.HOME_PROPERTY)
        val savedOverride = VoicePromptPaths.override
        val savedGetenv = VoicePromptPaths.getenv
        // Redirect user.home so the legacy-shared-home migration source (user.home/.voice-prompt)
        // is a non-existent dir — the daemon migration must not touch the developer's real home.
        System.setProperty("user.home", Files.createTempDirectory("vp-fake-user").toString())
        System.clearProperty(VoicePromptPaths.HOME_PROPERTY)
        VoicePromptPaths.override = null
        VoicePromptPaths.getenv = { name ->
            when (name) {
                "LOCALAPPDATA" -> "/lad"
                "XDG_DATA_HOME" -> "/xdg"
                else -> null
            }
        }
        try {
            val expected = VoicePromptPaths.defaultHome(
                SystemInfo.isWindows,
                SystemInfo.isLinux,
                "/u", "/lad", "/xdg",
            )
            assertEquals(expected, VoicePromptPaths.baseDir())
        } finally {
            VoicePromptPaths.getenv = savedGetenv
            VoicePromptPaths.override = savedOverride
            System.setProperty("user.home", savedUserHome)
            if (savedProperty != null) System.setProperty(VoicePromptPaths.HOME_PROPERTY, savedProperty)
        }
    }

    @Test
    fun `migration candidates exclude the target and duplicates`() {
        val target = Paths.get("/home", "VoicePrompt")
        val perIde = Paths.get("/system", "copilot-voice")
        val shared = Paths.get("/u", ".voice-prompt")

        assertEquals(
            listOf(perIde, shared),
            VoicePromptPaths.migrationCandidates(target, perIde, shared),
        )
        // macOS: the resolved home IS the historical shared dir — it must not be a source of itself.
        assertEquals(
            listOf(perIde),
            VoicePromptPaths.migrationCandidates(shared, perIde, shared),
        )
        assertEquals(
            listOf(shared),
            VoicePromptPaths.migrationCandidates(Paths.get("/new", "home"), null, shared),
        )
        assertEquals(emptyList<Path>(), VoicePromptPaths.migrationCandidates(target, null, null))
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
