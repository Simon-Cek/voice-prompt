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
 * Review N9, deterministic: a CONFIRMED GPU failure — the GPU run failed and the CPU run of
 * the same utterance succeeded, or the GPU server died at startup and the CPU one came up —
 * is remembered per model, so the next transcription of that model goes straight to the CPU
 * instead of failing on the GPU and restarting on the CPU again.
 *
 * Failures that are NOT the GPU's fault must not touch the blacklist and must keep the warm
 * server: an HTTP 4xx (the server rejected this request), a timeout (the model is slow, not
 * dead), and a user cancel (the in-flight inference dies with the closed connection). A
 * failure that survives the CPU retry is not the GPU's fault either: no blacklist, the error
 * goes up, nothing loops.
 *
 * Driven with a fake whisper-server (a python3 ThreadingHTTPServer stand-in) whose MODE is
 * spliced in per test. It logs every start to `starts.log` and every /inference request to
 * `requests.log`; the tests assert exactly which starts and requests happened.
 * `allowFallback=false` keeps the CLI from masking what the server path actually did.
 */
class WhisperServerGpuBlacklistTest {

    private var dir: Path? = null
    private var wav: File? = null

    /** Distinct models so one test's blacklist entry can never shadow another test's. */
    private val modelA = Paths.get("/models/ggml-small.en.bin")
    private val modelB = Paths.get("/models/ggml-tiny.en.bin")

    /** The Processing settings for most tests, plus a "changed" variant (a different thread count). */
    private val key = GpuHealth.processingKey("AUTOMATIC", 0)
    private val keyChanged = GpuHealth.processingKey("AUTOMATIC", 4)

    @Before
    fun setUp() {
        Assume.assumeFalse("the fake server needs a POSIX shell", SystemInfo.isWindows)
        Assume.assumeTrue(
            "python3 is available for the fake server",
            runCatching { ProcessBuilder("python3", "--version").start().destroy() }.isSuccess,
        )
        val d = Files.createTempDirectory("vp-server-blacklist-test")
        d.toFile().deleteOnExit()
        dir = d
        val w = File(d.toFile(), "recording.wav")
        w.writeBytes(byteArrayOf(1, 2, 3, 4))
        w.deleteOnExit()
        wav = w
        WhisperServerEngine.resetForTest()
        GpuHealth.clearForTest()
    }

    @After
    fun tearDown() {
        runCatching { WhisperServerEngine.shutdown() }
        WhisperServerEngine.resetForTest()
        GpuHealth.clearForTest()
        dir?.toFile()?.deleteRecursively()
    }

    // ----- the fake server -----------------------------------------------------------

    /**
     * A whisper-server stand-in whose behaviour depends on the spliced MODE, the `--no-gpu`
     * launch flag, the model it was started with, and the request count:
     *
     *  - `gpu_500`: the GPU always 500s, the CPU answers (the confirmed-failure scenario);
     *  - `selective_500`: the GPU 500s for the `ggml-small` model only (per-model scope);
     *  - `all_500`: both backends 500 (the failure is not the GPU's fault);
     *  - `gpu_crash`: the GPU process dies on the first /inference;
     *  - `gpu_die`: the GPU process dies at startup, before serving;
     *  - `first_400`: the first GPU request is rejected (400), later ones answer;
     *  - `first_hang`: the first /inference blocks 30 s (the timeout / cancel scenarios).
     *
     * ThreadingHTTPServer so a blocked first request cannot hold the port against the second
     * call's request (the "the warm server still answers" assertions).
     */
    private fun fakeServer(mode: String): Path {
        val python = """
            #!/usr/bin/env python3
            import os, sys, time
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

            args = sys.argv[1:]
            no_gpu = "--no-gpu" in args
            port = int(args[args.index("--port") + 1])
            model = ""
            for i, a in enumerate(args):
                if a == "-m" and i + 1 < len(args):
                    model = args[i + 1]
            MODE = "{mode}"

            with open("starts.log", "a") as f:
                f.write("FAKE-SERVER gpu=" + ("false" if no_gpu else "true") + "\n")

            if MODE == "gpu_die" and not no_gpu:
                sys.stderr.write("fake GPU server dying at startup\n")
                os._exit(1)

            COUNT = {"n": 0}

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
                    with open("requests.log", "a") as f:
                        f.write("FAKE-REQUEST gpu=" + ("false" if no_gpu else "true") + "\n")
                    COUNT["n"] += 1
                    first = COUNT["n"] == 1
                    if MODE == "gpu_crash" and not no_gpu:
                        os._exit(1)
                    if first and MODE == "first_400" and not no_gpu:
                        self._send(400, "bad audio")
                        return
                    if first and MODE == "first_hang":
                        time.sleep(30)
                    if MODE == "selective_500" and not no_gpu and "ggml-small" in model:
                        self._send(500, "gpu broken for this model")
                    elif MODE == "gpu_500" and not no_gpu:
                        self._send(500, "gpu broken")
                    elif MODE == "all_500":
                        self._send(500, "broken")
                    elif no_gpu:
                        self._send(200, "cpu says hi")
                    else:
                        self._send(200, "gpu says hi")

                def log_message(self, *args):
                    pass

            ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
        """.trimIndent().replace("{mode}", mode)
        val script = dir!!.resolve("whisper-server")
        Files.writeString(script, python)
        val perms = Files.getPosixFilePermissions(script)
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(script, perms)
        return script
    }

    // ----- helpers --------------------------------------------------------------------

    private fun transcribe(
        binary: Path,
        model: Path,
        useGpu: Boolean,
        processingKey: String,
        indicator: ProgressIndicator? = null,
        timeoutMs: Long = 20_000,
    ): String = WhisperServerEngine.transcribeInternal(
        wavFile = wav!!,
        model = model,
        language = "en",
        timeoutMs = timeoutMs,
        useGpu = useGpu,
        threads = 0,
        indicator = indicator,
        serverBinary = binary,
        logFile = File(dir!!.toFile(), "server.log"),
        // No CLI fallback: the server path alone must produce the answer or throw.
        allowFallback = false,
        processingKey = processingKey,
    )

    private fun startSequence(): List<String> =
        File(dir!!.toFile(), "starts.log").takeIf { it.exists() }?.readLines()
            ?.filter { it.startsWith("FAKE-SERVER") }
            ?: emptyList()

    private fun requestSequence(): List<String> =
        File(dir!!.toFile(), "requests.log").takeIf { it.exists() }?.readLines()
            ?.filter { it.startsWith("FAKE-REQUEST") }
            ?: emptyList()

    /** Bounded wait on an observed condition (the sanctioned deterministic-wait shape). */
    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    // ----- confirmed GPU failures -------------------------------------------------------

    @Test
    fun `gpu 500 then cpu success blacklists the model and the next call starts no new server`() {
        val binary = fakeServer("gpu_500")
        assertEquals("the CPU retry's answer must be returned", "cpu says hi", transcribe(binary, modelA, true, key))
        assertTrue("a confirmed GPU failure must blacklist the model", GpuHealth.isBlacklisted(modelA, key))
        assertEquals(
            "first call: the GPU server runs, fails, and the CPU server takes over",
            listOf("FAKE-SERVER gpu=true", "FAKE-SERVER gpu=false"),
            startSequence(),
        )

        assertEquals("the warm CPU server must answer", "cpu says hi", transcribe(binary, modelA, true, key))
        assertEquals(
            "the second call must not touch the GPU again — no new start at all",
            listOf("FAKE-SERVER gpu=true", "FAKE-SERVER gpu=false"),
            startSequence(),
        )
    }

    @Test
    fun `a gpu process crash during inference is confirmed the same way`() {
        val binary = fakeServer("gpu_crash")
        assertEquals("the CPU retry's answer must be returned", "cpu says hi", transcribe(binary, modelA, true, key))
        assertTrue("a confirmed GPU failure must blacklist the model", GpuHealth.isBlacklisted(modelA, key))
        assertEquals(
            "the GPU server died on the request and the CPU server took over",
            listOf("FAKE-SERVER gpu=true", "FAKE-SERVER gpu=false"),
            startSequence(),
        )
    }

    @Test
    fun `a gpu server dying at startup tries the cpu key and blacklists it`() {
        val binary = fakeServer("gpu_die")
        assertEquals("the CPU server must answer", "cpu says hi", transcribe(binary, modelA, true, key))
        assertTrue("a confirmed startup failure must blacklist the model", GpuHealth.isBlacklisted(modelA, key))
        assertEquals(
            "the GPU start died and the CPU start came up",
            listOf("FAKE-SERVER gpu=true", "FAKE-SERVER gpu=false"),
            startSequence(),
        )

        // Review N9 #2: the GPU attempt must NOT have set the startup backoff — with a backoff
        // the next call would skip the server and hit the ISE (allowFallback=false here).
        assertEquals("the next call must still be served by the server", "cpu says hi", transcribe(binary, modelA, true, key))
    }

    // ----- not the GPU's fault -----------------------------------------------------------

    @Test
    fun `gpu failure and cpu failure blacklists nothing and reports the error`() {
        val binary = fakeServer("all_500")
        val failure = try {
            transcribe(binary, modelA, true, key)
            error("expected the GPU+CPU failures to throw")
        } catch (t: WhisperCliEngine.TranscriptionException) {
            t
        }
        assertTrue("the error must carry the server's HTTP failure: ${failure.message}", failure.message!!.contains("HTTP 500"))
        assertFalse("a failure that survives the CPU retry is not a GPU problem", GpuHealth.isBlacklisted(modelA, key))
        assertEquals(
            "exactly one attempt per backend — no retry loop",
            listOf("FAKE-SERVER gpu=true", "FAKE-SERVER gpu=false"),
            startSequence(),
        )
    }

    @Test
    fun `an http 4xx keeps the warm server and does not retry`() {
        val binary = fakeServer("first_400")
        val failure = try {
            transcribe(binary, modelA, true, key)
            error("expected the rejected request to throw")
        } catch (t: WhisperCliEngine.TranscriptionException) {
            t
        }
        assertTrue("the error must carry the HTTP status: ${failure.message}", failure.message!!.contains("HTTP 400"))
        assertFalse("a 4xx is a request problem, not a GPU failure", GpuHealth.isBlacklisted(modelA, key))
        assertEquals("no CPU retry on a 4xx", listOf("FAKE-SERVER gpu=true"), startSequence())

        // The SAME warm server must answer the next call (no restart), and it is fine again.
        assertEquals("the warm server recovered", "gpu says hi", transcribe(binary, modelA, true, key))
        assertEquals("still exactly one server start after the second call", listOf("FAKE-SERVER gpu=true"), startSequence())
    }

    @Test
    fun `a timeout keeps the warm server and does not retry`() {
        val binary = fakeServer("first_hang")
        val failure = try {
            transcribe(binary, modelA, true, key, timeoutMs = 1_500)
            error("expected the slow inference to time out")
        } catch (t: Throwable) {
            t
        }
        assertTrue("the error must be the inference timeout: ${failure.message}", failure.message.toString().contains("timed out"))
        assertFalse("a timeout is not a GPU failure", GpuHealth.isBlacklisted(modelA, key))
        assertEquals("no CPU retry on a timeout", listOf("FAKE-SERVER gpu=true"), startSequence())

        // The SAME warm server must still answer (a new handler thread handles the request).
        assertEquals("the warm server answers the next call", "gpu says hi", transcribe(binary, modelA, true, key))
        assertEquals("still exactly one server start", listOf("FAKE-SERVER gpu=true"), startSequence())
    }

    @Test
    fun `a cancel keeps the warm server and does not retry`() {
        val binary = fakeServer("first_hang")
        val indicator = CancellingIndicator()
        val r = AtomicReference<Result<String>?>()
        val t = Thread { r.set(runCatching { transcribe(binary, modelA, true, key, indicator.indicator) }) }
        t.start()
        // The fake logs the /inference request BEFORE it hangs: cancel exactly when it is
        // in flight (bounded wait on the fake's own request log — no sleep guessing).
        assertTrue("the request must have reached the fake server", awaitUntil(10_000) { requestSequence().size >= 1 })
        indicator.cancelNow = true
        t.join(10_000)
        assertFalse("the transcribe must have ended", t.isAlive)
        assertTrue("the cancel must surface as a cancellation", r.get()?.exceptionOrNull() is ProcessCanceledException)
        assertFalse("a cancel is not a GPU failure", GpuHealth.isBlacklisted(modelA, key))
        assertEquals("no CPU retry on a cancel", listOf("FAKE-SERVER gpu=true"), startSequence())

        // The SAME warm server must still answer.
        assertEquals("the warm server answers the next call", "gpu says hi", transcribe(binary, modelA, true, key))
        assertEquals("still exactly one server start", listOf("FAKE-SERVER gpu=true"), startSequence())
    }

    // ----- scope: per model, per processing settings -------------------------------------

    @Test
    fun `another model is not blacklisted`() {
        val binary = fakeServer("selective_500")
        assertEquals("the CPU retry answers for modelA", "cpu says hi", transcribe(binary, modelA, true, key))
        assertTrue("modelA has a confirmed failure", GpuHealth.isBlacklisted(modelA, key))

        assertEquals("the GPU must still work for modelB", "gpu says hi", transcribe(binary, modelB, true, key))
        assertFalse("modelB has no confirmed failure", GpuHealth.isBlacklisted(modelB, key))
    }

    @Test
    fun `a changed processing setting clears the blacklist`() {
        val binary = fakeServer("gpu_500")
        assertEquals("the CPU retry answers", "cpu says hi", transcribe(binary, modelA, true, key))
        assertTrue("blacklisted under the original settings", GpuHealth.isBlacklisted(modelA, key))

        // The user changes the thread count: the GPU must be tried again for the same model…
        assertEquals("…and the CPU answers again (the fake still fails modelA's GPU)", "cpu says hi", transcribe(binary, modelA, true, keyChanged))
        assertTrue("the failure is confirmed again under the NEW settings", GpuHealth.isBlacklisted(modelA, keyChanged))
        assertFalse("the old settings key is no longer active", GpuHealth.isBlacklisted(modelA, key))
    }

    /**
     * A ProgressIndicator the test flips mid-flight to simulate the user's Cancel button. The
     * platform interface is wide, so this is a reflective proxy (the same shape as the N8
     * tests): once flipped, `checkCanceled()` throws [ProcessCanceledException].
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

/** Default return value for the no-op methods of the [WhisperServerGpuBlacklistTest] proxy indicator. */
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
