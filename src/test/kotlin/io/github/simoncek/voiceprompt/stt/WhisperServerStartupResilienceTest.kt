package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.net.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Pre-1.1.0 finding 3: a server that never becomes healthy (a security tool or a proxy blocking
 * loopback) must stop costing time on corporate machines.
 *
 * 1. After two consecutive startup failures the server is disabled for the session — the
 *    dictation falls back to whisper-cli and no further start is attempted (a fake binary logs
 *    every launch, so the test counts them); a model or processing change re-enables it.
 * 2. The HttpClient used for loopback must never go through a proxy (the spec's
 *    `ProxySelector.of(null)`, expressed as the platform's `HttpClient.Builder.NO_PROXY`
 *    selector in JDK 25).
 *
 * Linux/macOS only, like the other fake-server tests.
 */
class WhisperServerStartupResilienceTest {

    private var dir: Path? = null
    private var wav: File? = null

    @Before
    fun setUp() {
        Assume.assumeFalse("the fake server needs a POSIX shell", SystemInfo.isWindows)
        Assume.assumeTrue(
            "python3 is available for the fake server",
            runCatching { ProcessBuilder("python3", "--version").start().destroy() }.isSuccess,
        )
        val d = Files.createTempDirectory("vp-server-resilience-test")
        d.toFile().deleteOnExit()
        dir = d
        val w = File(d.toFile(), "recording.wav")
        w.writeBytes(byteArrayOf(1, 2, 3, 4))
        w.deleteOnExit()
        wav = w
        WhisperServerEngine.resetForTest()
        // The fake dies on startup, so the /health wait ends fast — bound it anyway. And close
        // the 30 s backoff so the second attempt (the one that trips the session-disable) runs
        // inside the test instead of 30 s later.
        WhisperServerEngine.startupTimeoutMsForTest = 3_000
        WhisperServerEngine.startupBackoffMsForTest = 0
    }

    @After
    fun tearDown() {
        runCatching { WhisperServerEngine.shutdown() }
        runCatching { WhisperServerEngine.resetForTest() }
        dir?.toFile()?.deleteRecursively()
    }

    /**
     * Two failed starts disable the server for the session (no third launch for the same
     * model/processing); a different model gets a fresh allowance.
     */
    @Test
    fun `two consecutive startup failures disable the server for the session`() {
        val dying = fakeDyingServer()
        val model = Paths.get("/models/ggml-tiny.en.bin")
        val otherModel = Paths.get("/models/ggml-base.en.bin")

        // Failures 1 and 2: the engine attempts the start each time; the fake dies on startup,
        // so the utterance fails (with allowFallback=false the server failure is rethrown).
        val first = transcribe(dying, model)
        assertTrue("the first failure must fail the utterance, was: ${first.exceptionOrNull()}", first.isFailure)
        val second = transcribe(dying, model)
        assertTrue("the second failure must fail the utterance, was: ${second.exceptionOrNull()}", second.isFailure)
        assertEquals("both failures must have attempted a start", 2, startSequence().size)

        // Disabled for the session now: this utterance must NOT attempt a start (in production it
        // falls back to whisper-cli; with allowFallback=false the disabled path throws the
        // no-server ISE). This is the regression — before the fix every utterance re-paid the
        // startup wait, and that attempt would show up here as a third launch.
        val third = transcribe(dying, model)
        assertTrue(
            "the disabled path must surface as the no-server ISE, was: ${third.exceptionOrNull()}",
            third.exceptionOrNull() is IllegalStateException,
        )
        assertEquals(
            "a disabled server must not be started again for the same model/processing",
            2,
            startSequence().size,
        )

        // Reset on model change: the new config gets a fresh allowance and is attempted again.
        val fourth = transcribe(dying, otherModel)
        assertTrue(
            "the new model must still go through the server path, was: ${fourth.exceptionOrNull()}",
            fourth.isFailure,
        )
        assertEquals(
            "a model change must re-enable the server (a fresh start is attempted)",
            3,
            startSequence().size,
        )
    }

    /** The loopback client must resolve to no proxy, even when the machine has one configured. */
    @Test
    fun `the loopback http client bypasses proxies`() {
        // JDK 25: HttpClient.proxy() is Optional — empty when the default selector is in use,
        // present when we set one explicitly.
        val selectorOpt = httpClient().proxy()
        assertTrue(
            "the HttpClient must carry an explicit proxy selector (no proxy)",
            selectorOpt.isPresent,
        )
        assertEquals(
            "loopback must resolve to no proxy",
            listOf(Proxy.NO_PROXY),
            selectorOpt.get().select(URI.create("http://127.0.0.1:1/")),
        )
    }

    // --- helpers ---

    private fun transcribe(binary: Path, model: Path): Result<String> =
        runInThread {
            WhisperServerEngine.transcribeInternal(
                wavFile = wav!!,
                model = model,
                language = WhisperLanguage.ENGLISH.code,
                timeoutMs = 30_000,
                useGpu = false,
                threads = 0,
                indicator = null,
                serverBinary = binary,
                logFile = File(dir!!.toFile(), "server.log"),
                // No CLI fallback: the server path alone must produce the answer or throw, so the
                // CLI cannot mask what the server path actually did.
                allowFallback = false,
            )
        }

    /** A whisper-server stand-in that logs its launch and dies — a startup that never succeeds. */
    private fun fakeDyingServer(): Path {
        val python = """
            #!/usr/bin/env python3
            import sys

            with open("starts.log", "a") as f:
                f.write("FAKE-STARTUP mode=dies\n")
            sys.exit(1)
        """.trimIndent()
        val script = dir!!.resolve("whisper-server")
        Files.writeString(script, python)
        val perms = Files.getPosixFilePermissions(script)
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(script, perms)
        return script
    }

    private fun startSequence(): List<String> =
        File(dir!!.toFile(), "starts.log").takeIf { it.exists() }
            ?.readLines()?.filter { it.startsWith("FAKE-STARTUP") }
            ?: emptyList()

    /** The engine's private HttpClient (its lazy field), for the proxy assertion. */
    private fun httpClient(): HttpClient {
        val field = WhisperServerEngine::class.java.getDeclaredField("clientLazy")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(WhisperServerEngine) as Lazy<HttpClient>).value
    }

    /** Runs [block] on a worker thread and joins it with a bound. */
    private fun runInThread(block: () -> String): Result<String> {
        val r = AtomicReference<Result<String>?>()
        val t = Thread { r.set(runCatching(block)) }
        t.start()
        t.join(20_000)
        assertFalse("the transcribe thread must have finished within 20 s", t.isAlive)
        return r.get() ?: error("no result from the transcribe thread")
    }
}
