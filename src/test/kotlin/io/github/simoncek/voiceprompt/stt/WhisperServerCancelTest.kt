package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
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
 * Pre-1.1.0 finding 1 (regression): a user Cancel (or the inference timeout) must close the
 * client's connection so the server aborts the in-flight inference. Before the fix, `request()`
 * threw out of its wait loop on a Cancel without cancelling the `sendAsync` future, so the
 * connection stayed open and the server kept the model busy — the next clip was then queued
 * behind the abandoned inference (measured: 7.8 s behind a slow one vs 1.1 s when the connection
 * closed).
 *
 * Driven with a python3 stand-in that mirrors the real server's behaviour: its FIRST inference is
 * slow and it polls for the client's disconnect each step (the bundled whisper-server aborts an
 * in-flight inference when the connection closes), recording `reqN ABORTED` vs `reqN COMPLETED`.
 * The test cancels the first (slow) request mid-inference, then asserts the server saw the
 * disconnect (aborted it) and that the SECOND (fast) clip was not queued behind the abandoned one.
 * Linux/macOS only, like the other fake-server tests.
 */
class WhisperServerCancelTest {

    private var dir: Path? = null
    private var wav: File? = null

    @Before
    fun setUp() {
        Assume.assumeFalse("the fake server needs a POSIX shell", SystemInfo.isWindows)
        Assume.assumeTrue(
            "python3 is available for the fake server",
            runCatching { ProcessBuilder("python3", "--version").start().destroy() }.isSuccess,
        )
        val d = Files.createTempDirectory("vp-server-cancel-test")
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

    /** The finding: a Cancel closes the connection, the server aborts, and the next clip is not delayed. */
    @Test
    fun `a cancel closes the connection and the server aborts the abandoned inference`() {
        val binary = fakeServer()
        val indicator = CancellingIndicator()

        // req1: a slow inference the user cancels part-way through.
        val r1 = AtomicReference<Result<String>?>()
        val t1 = Thread { r1.set(runCatching { transcribe(binary, indicator.indicator) }) }
        t1.start()
        // Wait until req1's slow inference has actually begun (the server logged it), so the cancel
        // lands mid-inference — not before it started or after it finished.
        assertTrue(
            "the first (slow) inference must have started before the cancel",
            awaitUntil(8_000) { events().any { it.startsWith("req1 STARTED") } },
        )
        // The user hits Cancel: the client must close the connection (the fix), which the server
        // polls for and acts on by aborting the in-flight inference.
        indicator.cancelNow = true
        t1.join(10_000)
        assertFalse("the cancelled transcribe must have ended", t1.isAlive)
        assertTrue(
            "cancel must surface as ProcessCanceledException, was: ${r1.get()}",
            r1.get()?.exceptionOrNull() is ProcessCanceledException,
        )
        // The server must have SEEN the disconnect and aborted req1 rather than completing it.
        // This is the regression: without the connection close the server runs req1 to completion.
        assertTrue(
            "the server must detect the client disconnect and abort the abandoned inference",
            awaitUntil(6_000) { events().any { it.startsWith("req1 ABORTED") } },
        )
        // And the next clip must not be queued behind it: served within its own (fast) inference.
        val t0 = System.currentTimeMillis()
        val r2 = runInThread { transcribe(binary, null) }
        val elapsed = System.currentTimeMillis() - t0
        assertEquals("the next clip must succeed", "ok", r2.getOrNull())
        assertTrue(
            "the next clip must not wait behind the abandoned inference (${elapsed} ms)",
            elapsed < 2_000,
        )
        assertTrue(
            "the server must have served the second (fast) inference",
            events().any { it.startsWith("req2 COMPLETED") },
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

    /** The fake server's recorded events, in order (reqN STARTED / ABORTED / COMPLETED). */
    private fun events(): List<String> {
        val file = File(dir!!.toFile(), "events.log")
        return if (file.exists()) file.readLines() else emptyList()
    }

    /**
     * A whisper-server stand-in that mirrors the abort-on-disconnect behaviour of the bundled
     * server: `/health` is ready immediately; the FIRST `/inference` is slow (the "long clip") and
     * polls for the client's disconnect each step, aborting early when the connection closes; the
     * SECOND and later inferences are fast (the "next short clip"). Every inference is logged to
     * `events.log` so the test can assert on whether it was aborted or completed.
     */
    private fun fakeServer(): Path {
        val python = """
            #!/usr/bin/env python3
            import sys, time, socket
            from http.server import BaseHTTPRequestHandler, HTTPServer

            args = sys.argv[1:]
            port = int(args[args.index("--port") + 1])
            SLOW = 3.0   # first inference duration (the abandoned "long clip")
            FAST = 0.2   # later inference duration (the "next short clip")
            STEP = 0.1   # disconnect poll interval

            with open("starts.log", "a") as f:
                f.write("FAKE-STARTUP port=" + str(port) + "\n")

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
                    else:
                        self._send(200, '{"status": "ok"}')

                def client_disconnected(self):
                    # A non-blocking peek read: b'' (EOF) means the client closed the socket;
                    # BlockingIOError means it is still connected (just not sending data now).
                    try:
                        self.connection.setblocking(False)
                        try:
                            peek = self.connection.recv(1, socket.MSG_PEEK)
                        finally:
                            self.connection.setblocking(True)
                        return peek == b''
                    except (BlockingIOError, InterruptedError):
                        return False
                    except OSError:
                        return True

                def do_POST(self):
                    if self.path != "/inference":
                        self._send(404, "not found")
                        return
                    length = int(self.headers.get("Content-Length") or 0)
                    remaining = length
                    while remaining > 0:
                        chunk = self.rfile.read(min(remaining, 65536))
                        if not chunk:
                            break
                        remaining -= len(chunk)
                    # Which inference is this? 1st = slow (the long clip), later = fast.
                    try:
                        with open("reqcount") as f:
                            n = int(f.read() or "0")
                    except (FileNotFoundError, ValueError):
                        n = 0
                    with open("reqcount", "w") as f:
                        f.write(str(n + 1))
                    duration = SLOW if n == 0 else FAST
                    with open("events.log", "a") as f:
                        f.write("req%d STARTED\n" % (n + 1))
                    deadline = time.monotonic() + duration
                    aborted = False
                    while time.monotonic() < deadline:
                        time.sleep(STEP)
                        if self.client_disconnected():
                            aborted = True
                            break
                    with open("events.log", "a") as f:
                        f.write("req%d %s\n" % (n + 1, "ABORTED (client disconnected)" if aborted else "COMPLETED"))
                    if aborted:
                        return  # the client is gone; the server aborts and sends nothing
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

/** Default return value for the no-op methods of the [WhisperServerCancelTest] proxy indicator. */
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
