package io.github.simoncek.voiceprompt.stt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pre-1.1.0 finding 7: the shared home (review N11) accumulates one
 * `whisper-server-<pid>.log` per IDE session. These pin the cleanup rule — a log is stale
 * when its PID is dead OR it is older than 7 days (the backstop against recycled PID
 * numbers) — and that the engine's first-use hook runs it against the shared home.
 * Deterministic: ages are set via the mtime and the clock is injected; no sleeps.
 */
class StaleServerLogCleanupTest {

    private var dirs: MutableList<Path> = mutableListOf()

    @After
    fun tearDown() {
        dirs.forEach { it.toFile().deleteRecursively() }
        dirs.clear()
    }

    private fun newDir(): Path {
        val d = Files.createTempDirectory("voiceprompt-cleanup")
        dirs.add(d)
        return d
    }

    /** Creates a server log in [dir] with a [ageDays]-old mtime (deterministic — no sleeps). */
    private fun serverLog(dir: Path, pid: Long, ageDays: Double): Path {
        val f = dir.resolve("whisper-server-$pid.log")
        Files.write(f, "stale server output".toByteArray())
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - (ageDays * 86_400_000).toLong()))
        return f
    }

    /** A PID that is verifiably not alive in this environment. */
    private fun deadPid(): Long {
        var pid = 999_999L
        while (ProcessHandle.of(pid).isPresent) pid--
        return pid
    }

    /** The test JVM itself — verifiably alive. */
    private fun livePid(): Long = ProcessHandle.current().pid()

    @Test
    fun `a fresh log of a dead pid is removed`() {
        val d = newDir()
        val stale = serverLog(d, deadPid(), ageDays = 1.0)
        val removed = WhisperServerEngine.cleanStaleServerLogs(d)
        assertEquals("the dead-pid log must be removed", listOf(stale), removed)
        assertFalse(Files.exists(stale))
    }

    @Test
    fun `a fresh log of a live pid is kept`() {
        val d = newDir()
        val live = serverLog(d, livePid(), ageDays = 1.0)
        assertEquals("a live session's log must survive", emptyList<Path>(), WhisperServerEngine.cleanStaleServerLogs(d))
        assertTrue(Files.exists(live))
    }

    @Test
    fun `a log older than 7 days is removed even if its pid is live`() {
        // The PID-recycling backstop: an old log whose number now belongs to a live process
        // must still go.
        val d = newDir()
        val old = serverLog(d, livePid(), ageDays = 30.0)
        val removed = WhisperServerEngine.cleanStaleServerLogs(d)
        assertEquals(listOf(old), removed)
        assertFalse(Files.exists(old))
    }

    @Test
    fun `only server log files are ever touched`() {
        val d = newDir()
        val stale = serverLog(d, deadPid(), ageDays = 1.0)
        val notAPidName = d.resolve("whisper-server-notapid.log")
        val otherExtension = d.resolve("whisper-server-${deadPid()}.txt")
        val model = d.resolve("ggml-tiny.bin")
        for (keep in listOf(notAPidName, otherExtension, model)) {
            Files.write(keep, "must survive".toByteArray())
        }
        val removed = WhisperServerEngine.cleanStaleServerLogs(d)
        assertEquals(listOf(stale), removed)
        for (keep in listOf(notAPidName, otherExtension, model)) {
            assertTrue("$keep must not be touched by the cleanup", Files.exists(keep))
        }
    }

    @Test
    fun `a missing home dir is a no-op`() {
        val d = newDir()
        Files.delete(d)
        assertEquals(emptyList<Path>(), WhisperServerEngine.cleanStaleServerLogs(d))
    }

    @Test
    fun `the first-use cleanup runs once and cleans the shared home`() {
        // End to end: the engine calls ensureOnce() on the first dictation of the session; the
        // work must run against VoicePromptPaths.baseDir() (build/test-home under the suite)
        // on a background thread — and a second call must be a no-op (once per JVM).
        val home = VoicePromptPaths.baseDir()
        Files.createDirectories(home)
        dirs.add(home)
        val stale = serverLog(home, deadPid(), ageDays = 1.0)
        val live = serverLog(home, livePid(), ageDays = 1.0)
        val cleanup = StaleServerLogCleanup()
        val latch = CountDownLatch(1)
        cleanup.doneForTest = latch
        cleanup.ensureOnce()
        cleanup.ensureOnce()
        assertTrue("the cleanup must run to completion", latch.await(10, TimeUnit.SECONDS))
        assertFalse("the stale log must be gone after the cleanup", Files.exists(stale))
        assertTrue("the live session's log must survive the cleanup", Files.exists(live))
    }
}
