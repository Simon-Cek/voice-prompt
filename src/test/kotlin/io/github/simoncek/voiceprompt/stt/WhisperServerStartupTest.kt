package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.Socket
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
 * Review N8, deterministic: the whisper-server START (spawn + the /health wait) must not wedge
 * [WhisperServerEngine.shutdown] or ignore the task's Cancel button, and a cancelled or aborted
 * start must not set the 30 s startup backoff.
 *
 * Driven with a python3 fake server that is never (or only late) healthy, so the engine's real
 * spawn + /health-wait path runs against a process the test controls; the fake logs every start
 * to `starts.log` (its working directory is the binary's parent dir, set by the engine).
 * Linux/macOS only, like the other fake-server tests.
 */
class WhisperServerStartupTest {

    private var dir: Path? = null
    private var wav: File? = null

    @Before
    fun setUp() {
        Assume.assumeFalse("the fake server needs a POSIX shell", SystemInfo.isWindows)
        Assume.assumeTrue(
            "python3 is available for the fake server",
            runCatching { ProcessBuilder("python3", "--version").start().destroy() }.isSuccess,
        )
        val d = Files.createTempDirectory("vp-server-startup-test")
        d.toFile().deleteOnExit()
        dir = d
        val w = File(d.toFile(), "recording.wav")
        w.writeBytes(byteArrayOf(1, 2, 3, 4))
        w.deleteOnExit()
        wav = w
        WhisperServerEngine.resetForTest()
    }

    @After
    fun tearDown() {
        runCatching { WhisperServerEngine.shutdown() }
        runCatching { WhisperServerEngine.resetForTest() }
        dir?.toFile()?.deleteRecursively()
    }

    /** (a) a dispose in progress must never spawn a server that outlives it. */
    @Test
    fun `a transcribe after shutdown launches no server`() {
        WhisperServerEngine.shutdown()
        val outcome = runInThread { transcribe(fakeServer("never_ready"), null) }
        assertTrue(
            "a transcribe after shutdown must fail, but got: ${outcome.getOrNull()}",
            outcome.exceptionOrNull() != null,
        )
        assertTrue(
            "it must be the no-server path (IllegalStateException), was: ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is IllegalStateException,
        )
        assertEquals("no server may have launched after shutdown", 0, startSequence().size)
    }

    /** (b) a cancel is not a startup failure: no backoff, so the next call launches again. */
    @Test
    fun `a cancelled start sets no backoff and the next call launches again`() {
        val indicator = CancellingIndicator()
        val r = AtomicReference<Result<String>?>()
        val t = Thread { r.set(runCatching { transcribe(fakeServer("never_ready"), indicator.indicator) }) }
        t.start()
        assertTrue("the fake must have launched", awaitUntil(5_000) { startSequence().size == 1 })
        indicator.cancelNow = true
        t.join(10_000)
        assertFalse("the transcribe must have ended", t.isAlive)
        assertTrue(
            "cancel must surface as ProcessCanceledException, was: ${r.get()}",
            r.get()?.exceptionOrNull() is ProcessCanceledException,
        )
        // N8 #2: with the backoff wrongly set, this second call would skip the server and throw
        // (allowFallback=false) instead of launching again.
        indicator.cancelNow = false
        val second = runInThread { transcribe(fakeServer("ready"), indicator.indicator) }
        assertEquals("the next call must launch the server and succeed", "ok", second.getOrNull())
        assertEquals("both attempts must have launched", 2, startSequence().size)
    }

    /** (c) a concurrent caller for the same key waits for the single in-flight start. */
    @Test
    fun `a second caller with the same key waits for the single start`() {
        WhisperServerEngine.startupTimeoutMsForTest = 8_000
        val binary = fakeServer("ready_after_delay")
        val r1 = AtomicReference<Result<String>?>()
        val r2 = AtomicReference<Result<String>?>()
        val t1 = Thread { r1.set(runCatching { transcribe(binary, null) }) }
        t1.start()
        assertTrue("the fake must have launched", awaitUntil(5_000) { startSequence().size == 1 })
        // t1 is inside the /health wait (the fake is not ready yet): a second caller for the
        // same key must WAIT for that single start, not spawn a second server (N8 #4).
        val t2 = Thread { r2.set(runCatching { transcribe(binary, null) }) }
        t2.start()
        t1.join(20_000)
        t2.join(20_000)
        assertFalse("both callers must have finished", t1.isAlive || t2.isAlive)
        assertEquals("the first caller must succeed", "ok", r1.get()?.getOrNull())
        assertEquals("the waiting caller must succeed too", "ok", r2.get()?.getOrNull())
        assertEquals("exactly one server process may be launched", 1, startSequence().size)
    }

    /** (d) shutdown during startup returns quickly and kills the starting server. */
    @Test
    fun `shutdown during startup returns quickly and kills the starting server`() {
        val binary = fakeServer("never_ready")
        val r = AtomicReference<Result<String>?>()
        val t = Thread { r.set(runCatching { transcribe(binary, null) }) }
        t.start()
        assertTrue("the fake must have launched", awaitUntil(5_000) { startSequence().size == 1 })
        // N8: shutdown() must not block on the /health wait — it kills the in-flight start.
        val t0 = System.currentTimeMillis()
        WhisperServerEngine.shutdown()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("shutdown() blocked on the startup wait: ${elapsed} ms", elapsed < 5_000)
        assertTrue(
            "the starting server must be dead after shutdown",
            awaitUntil(3_000) { !portAlive() },
        )
        t.join(10_000)
        assertFalse("the transcribe must have ended", t.isAlive)
        assertTrue(
            "the interrupted start must surface as a cancellation, was: ${r.get()}",
            r.get()?.exceptionOrNull() is ProcessCanceledException,
        )
    }

    /** (e) the Cancel button works during the /health wait and kills the starting server. */
    @Test
    fun `cancel during startup aborts the start and kills the server`() {
        val indicator = CancellingIndicator()
        val binary = fakeServer("never_ready")
        val r = AtomicReference<Result<String>?>()
        val t = Thread { r.set(runCatching { transcribe(binary, indicator.indicator) }) }
        t.start()
        assertTrue("the fake must have launched", awaitUntil(5_000) { startSequence().size == 1 })
        indicator.cancelNow = true
        t.join(10_000)
        assertFalse("the transcribe must have ended", t.isAlive)
        assertTrue(
            "cancel must surface as ProcessCanceledException, was: ${r.get()}",
            r.get()?.exceptionOrNull() is ProcessCanceledException,
        )
        assertTrue(
            "the starting server must be dead after cancel",
            awaitUntil(3_000) { !portAlive() },
        )
    }

    // --- helpers ---

    private fun transcribe(binary: Path, indicator: ProgressIndicator?): String =
        WhisperServerEngine.transcribeInternal(
            wavFile = wav!!,
            model = Paths.get("/models/ggml-tiny.en.bin"),
            language = WhisperLanguage.ENGLISH.code,
            timeoutMs = 30_000,
            useGpu = false,
            threads = 0,
            indicator = indicator,
            serverBinary = binary,
            logFile = File(dir!!.toFile(), "server.log"),
            // No CLI fallback: the server path alone must produce the answer or throw, so the
            // CLI cannot mask what the server path actually did.
            allowFallback = false,
        )

    /**
     * A whisper-server stand-in. Modes:
     * - `never_ready`: `/health` always 503 — the start never completes, so a stop/cancel decides.
     * - `ready`: `/health` 200 immediately — a normal start.
     * - `ready_after_delay`: `/health` 503 for ~1.5 s, then 200 — a slow start two callers race on.
     * Every start appends a `FAKE-STARTUP` line (with its port) to `starts.log`.
     */
    private fun fakeServer(mode: String): Path {
        val python = """
            #!/usr/bin/env python3
            import sys, time
            from http.server import BaseHTTPRequestHandler, HTTPServer

            MODE = "$mode"
            args = sys.argv[1:]
            port = int(args[args.index("--port") + 1])
            started = time.monotonic()

            with open("starts.log", "a") as f:
                f.write("FAKE-STARTUP mode=" + MODE + " port=" + str(port) + "\n")

            class Handler(BaseHTTPRequestHandler):
                def _send(self, code, body):
                    data = body.encode()
                    self.send_response(code)
                    self.send_header("Content-Type", "text/plain")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)

                def do_GET(self):
                    if self.path != "/health":
                        self._send(404, "not found")
                    elif MODE == "ready":
                        self._send(200, '{"status": "ok"}')
                    elif MODE == "ready_after_delay" and time.monotonic() - started >= 1.5:
                        self._send(200, '{"status": "ok"}')
                    else:
                        self._send(503, "not ready yet")

                def do_POST(self):
                    self.rfile.read(int(self.headers.get("Content-Length") or 0))
                    if self.path != "/inference":
                        self._send(404, "not found")
                    else:
                        self._send(200, "ok")

                def log_message(self, *args):
                    pass

            HTTPServer(("127.0.0.1", port), Handler).serve_forever()
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

    private fun portAlive(): Boolean = runCatching {
        val port = startSequence().first().substringAfter("port=").trim().toInt()
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    }.getOrDefault(false)

    /** Bounded wait on an observed condition (the sanctioned deterministic-wait shape). */
    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
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

    /**
     * A ProgressIndicator the test flips mid-flight to simulate the user's Cancel button. The
     * platform interface is wide, so this is a reflective proxy: once flipped, `checkCanceled()`
     * throws [ProcessCanceledException]; everything else is a no-op default.
     */
    private class CancellingIndicator {
        @Volatile var cancelNow = false

        val indicator: ProgressIndicator = Proxy.newProxyInstance(
            ProgressIndicator::class.java.classLoader,
            arrayOf(ProgressIndicator::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "checkCanceled" -> {
                        if (cancelNow) throw ProcessCanceledException()
                        null
                    }
                    "isCanceled" -> cancelNow
                    "toString" -> "CancellingIndicator"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> args?.get(0) === proxy
                    else -> defaultValueFor(method.returnType)
                }
            },
        ) as ProgressIndicator
    }
}

/** Default return value for the no-op methods of the [WhisperServerStartupTest] proxy indicator. */
private fun defaultValueFor(type: Class<*>): Any? = when (type) {
    Boolean::class.javaPrimitiveType -> false
    Byte::class.javaPrimitiveType -> 0.toByte()
    Char::class.javaPrimitiveType -> ' '
    Short::class.javaPrimitiveType -> 0.toShort()
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    else -> null
}
