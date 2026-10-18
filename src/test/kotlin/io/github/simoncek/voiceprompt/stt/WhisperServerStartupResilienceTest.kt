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
 * 3. 1.1.0: an HTTP 5xx from a live server is a failed inference, not a startup failure — it
 *    does not count toward the session disable and the warm server is kept; a successful
 *    request still resets the failure counter.
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

    /**
     * 1.1.0 fix: an HTTP 5xx from a LIVE server (it answered, so the process is healthy) is a
     * failed inference, not a startup failure. Two 500s in a row must not disable the server —
     * the third dictation still goes through the SAME warm server (before the fix the two 500s
     * had already disabled it and the third was the no-server ISE).
     */
    @Test
    fun `http 5xx responses do not disable the server`() {
        val broken = fake500Server()
        val model = Paths.get("/models/ggml-tiny.en.bin")

        // Three utterances in a row all get HTTP 500 from the live fake server…
        val first = transcribe(broken, model)
        assertTrue("the first 500 must fail the utterance, was: ${first.exceptionOrNull()}", first.isFailure)
        assertTrue(
            "the error must carry the HTTP 500: ${first.exceptionOrNull()}",
            first.exceptionOrNull() is WhisperCliEngine.TranscriptionException,
        )
        val second = transcribe(broken, model)
        assertTrue("the second 500 must fail the utterance, was: ${second.exceptionOrNull()}", second.isFailure)
        // …and the THIRD must still go through the server path (and fail with the HTTP error,
        // not the disabled-server ISE).
        val third = transcribe(broken, model)
        assertTrue(
            "two 5xx must NOT disable the server — the third must fail with the HTTP error, was: ${third.exceptionOrNull()}",
            third.exceptionOrNull() is WhisperCliEngine.TranscriptionException,
        )
        assertEquals(
            "the same warm server must serve all three (no restart, no disable)",
            1,
            startSequence().size,
        )
    }

    /**
     * Pre-1.1.0 finding 3, the reset half: a SUCCESSFUL request clears the startup-failure
     * counter. One model/processing: a startup death (1), a success (reset), a startup death
     * (1 again), a startup death (2 → disabled). Without the reset the counter would hit 2
     * already on the third utterance, so the fourth would be the disabled no-server ISE
     * instead of an attempted start, and there would be 3 starts, not 4.
     *
     * The fakes are stateless (a death fake and a serve-once fake, chosen per call) — a fake
     * must not be able to count its own launches, because that file state is not reliable
     * across processes (observed in this environment).
     */
    @Test
    fun `a successful request resets the startup-failure counter`() {
        val dying = fakeDyingServer()
        val once = fakeOnceServer()
        val model = Paths.get("/models/ggml-tiny.en.bin")

        // 1: a startup death — the first failure for this config.
        val t1 = transcribe(dying, model)
        assertTrue("the first startup death must fail the utterance, was: ${t1.exceptionOrNull()}", t1.isFailure)
        // 2: the one good start — its success resets the counter.
        val t2 = transcribe(once, model)
        assertEquals("the one good start must serve the utterance, was: ${t2.exceptionOrNull()}", "ok", t2.getOrNull())
        waitUntilEngineSeesDeath("the serve-once fake")
        // 3: a fresh startup death — counts from one again, NOT from two.
        val t3 = transcribe(dying, model)
        assertTrue("the next startup death must fail the utterance, was: ${t3.exceptionOrNull()}", t3.isFailure)
        // 4: the second failure since the success — the disable lands HERE: t4 must still be a
        // failed start ATTEMPT, not the disabled no-server ISE (which would mean the counter
        // never reset and the disable already landed on the 3rd attempt).
        val t4ex = transcribe(dying, model).exceptionOrNull()
        assertTrue("the fourth utterance must fail, was: $t4ex", t4ex != null)
        assertTrue(
            "the success must have reset the counter — the disable must not have landed before " +
                "the 4th attempt (that would be the disabled no-server ISE), was: $t4ex",
            t4ex !is IllegalStateException || !t4ex.message!!.contains("no whisper-server available"),
        )
        // 5: disabled now — no further start is attempted.
        val t5 = transcribe(dying, model)
        assertTrue(
            "the disabled path must surface as the no-server ISE, was: ${t5.exceptionOrNull()}",
            t5.exceptionOrNull() is IllegalStateException,
        )
        assertEquals(
            "four utterances must have attempted four starts — without the reset the disable " +
                "would land on the 3rd attempt and there would be only 3 starts",
            4,
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
    private fun fakeDyingServer(): Path = fakeBinary(
        "whisper-server",
        """
        #!/usr/bin/env python3
        import sys

        with open("starts.log", "a") as f:
            f.write("FAKE-STARTUP mode=dies\n")
        sys.exit(1)
        """.trimIndent(),
    )

    /**
     * A whisper-server stand-in that is ALIVE but always rejects the inference with HTTP 500 —
     * the "the server is healthy, this inference failed" case (a 5xx must not count as a
     * startup failure).
     */
    private fun fake500Server(): Path = fakeBinary(
        "whisper-server",
        """
        #!/usr/bin/env python3
        import sys
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        port = int(sys.argv[sys.argv.index("--port") + 1])

        with open("starts.log", "a") as f:
            f.write("FAKE-STARTUP mode=500\n")

        class Handler(BaseHTTPRequestHandler):
            def _send(self, code, body):
                data = body.encode()
                self.send_response(code)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                if self.path == "/health":
                    self._send(200, '{"status": "ok"}')
                else:
                    self._send(404, "not found")

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length") or 0))
                if self.path != "/inference":
                    self._send(404, "not found")
                    return
                self._send(500, "inference failed")

            def log_message(self, *args):
                pass

        ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
        """.trimIndent(),
    )

    /**
     * A whisper-server stand-in that is ALIVE and answers one /inference with 200 "ok", then
     * dies — the success phase of the reset test. Stateless on purpose: it never reads shared
     * state to decide what to do (cross-process file state is not reliable in this environment).
     */
    private fun fakeOnceServer(): Path = fakeBinary(
        "whisper-server-once",
        """
        #!/usr/bin/env python3
        import os, sys
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        port = int(sys.argv[sys.argv.index("--port") + 1])

        with open("starts.log", "a") as f:
            f.write("FAKE-STARTUP mode=once\n")

        class Handler(BaseHTTPRequestHandler):
            def _send(self, code, body):
                data = body.encode()
                self.send_response(code)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                if self.path == "/health":
                    self._send(200, '{"status": "ok"}')
                else:
                    self._send(404, "not found")

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length") or 0))
                if self.path != "/inference":
                    self._send(404, "not found")
                    return
                self._send(200, "ok")
                os._exit(1)  # die after the one good request: the next utterance must restart

            def log_message(self, *args):
                pass

        ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
        """.trimIndent(),
    )

    /** Writes [python] to an executable [name] in the test dir and returns its path. */
    private fun fakeBinary(name: String, python: String): Path {
        val script = dir!!.resolve(name)
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

    /**
     * Blocks until the engine's OWN view of the warm server is dead — the exact
     * `handle.process.isAlive` check [WhisperServerEngine]'s fast path performs — so the next
     * transcribe deterministically replaces it with a fresh start. Waiting on the kernel side
     * (e.g. kill -0) is not enough: after the kernel reaps the process the JVM may still report
     * isAlive=true for a short window, and in that window the fast path hands back the dead
     * handle (observed in this environment).
     */
    private fun waitUntilEngineSeesDeath(what: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val handle = engineHandle()
            if (handle == null || !engineHandleProcess(handle).isAlive) return
            Thread.sleep(20)
        }
        error("$what must be dead in the engine's view within 10 s")
    }

    /** The engine's current [ServerHandle] (private field), or null when there is none. */
    private fun engineHandle(): Any? {
        val field = WhisperServerEngine::class.java.getDeclaredField("handle")
        field.isAccessible = true
        return field.get(WhisperServerEngine)
    }

    private fun engineHandleProcess(handle: Any): java.lang.Process {
        val field = handle.javaClass.getDeclaredField("process")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(handle) as java.lang.Process
    }

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
