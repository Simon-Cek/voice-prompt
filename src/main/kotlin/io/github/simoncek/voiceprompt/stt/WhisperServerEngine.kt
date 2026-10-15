package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The review-P2 engine: instead of spawning a fresh `whisper-cli` (and reloading the model and
 * reinitialising Vulkan) on every utterance, one long-lived `whisper-server` process hosts the
 * model and answers `POST /inference` requests. The server is started lazily on the first
 * transcription, restarted when the model or the GPU/thread settings change, stopped after
 * [IDLE_TIMEOUT_MS] of inactivity, and killed on [shutdown] (IDE shutdown / plugin unload).
 *
 * When the server is not available (platform bundles no binary — e.g. Windows for now — or it
 * fails to start), [transcribe] falls back to the one-shot [WhisperCliEngine] path, so dictation
 * keeps working exactly as before.
 *
 * Review P3: a request that fails on the GPU-backed server is retried once on a CPU-restarted
 * server (`--no-gpu`) before the CLI fallback — the same GPU→CPU safety net as
 * [WhisperCliEngine.transcribe].
 *
 * Protocol (whisper.cpp v1.9.5 server, verified against the bundled build): multipart/form-data
 * `POST /inference` with a `file` part (the WAV) plus text fields (`response_format=text`,
 * `no_timestamps=true`, `suppress_nst=true`, `language`); the body is the transcript. `GET
 * /health` returns 200 `{"status":"ok"}` once the model is loaded. Closing the connection aborts
 * the in-flight inference (the server polls for it), and a process-wide mutex serialises
 * concurrent requests on the server side.
 */
object WhisperServerEngine {

    private val log = Logger.getInstance(WhisperServerEngine::class.java)

    /**
     * The inference itself ran past [WhisperCliEngine.DEFAULT_TIMEOUT_MS] on the server: the model
     * is simply slow for this audio. Falling back to the CLI would just re-run (and re-load) the
     * same inference and time out again, so callers must not fall back on this one.
     */
    private class InferenceTimeoutException(message: String) : IOException(message)

    /** What a running server was started with; a change in any of it means a restart. */
    private data class ServerKey(val model: Path, val useGpu: Boolean, val threads: Int)

    private class ServerHandle(val process: java.lang.Process, val port: Int, val key: ServerKey, val logFile: File)

    /** Serialises start/stop/replace of the server process (same shape as [WhisperRuntime.LOCK]). */
    private val LOCK = Any()

    @Volatile
    private var handle: ServerHandle? = null

    /** Wall time of the last successful inference; the idle reaper stops the server past [IDLE_TIMEOUT_MS]. */
    @Volatile
    private var lastUsed = System.currentTimeMillis()

    /** Wall time of the last failed startup; while the backoff window is open we skip straight to the CLI. */
    @Volatile
    private var lastStartupFailure = 0L

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /** Daemon so it can never keep the JVM alive; stopped with the plugin in [shutdown]. */
    private val idleReaper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "whisper-server-idle-reaper").apply { isDaemon = true }
    }

    init {
        idleReaper.scheduleWithFixedDelay(
            { runCatching { reapIdle() }.onFailure { log.warn("idle reaper pass failed", it) } },
            IDLE_CHECK_PERIOD_MS, IDLE_CHECK_PERIOD_MS, TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Transcribes [wavFile] with the long-lived server, falling back to the one-shot CLI when the
     * server is unavailable or the request fails (same contract and exceptions as
     * [WhisperCliEngine.transcribe]; the transcript is normalised identically).
     */
    fun transcribe(
        wavFile: File,
        model: Path,
        language: String = WhisperLanguage.AUTO.code,
        timeoutMs: Long = WhisperCliEngine.DEFAULT_TIMEOUT_MS,
        useGpu: Boolean = false,
        threads: Int = 0,
        /** The task's indicator: its Cancel button aborts the run and stops the server. Null for unattended calls. */
        indicator: ProgressIndicator? = null,
    ): String = transcribeInternal(
        wavFile, model, language, timeoutMs, useGpu, threads, indicator,
        serverBinary(), defaultLogFile(),
    )

    /**
     * Test seam for [transcribe]: the server binary and the log file are supplied explicitly so
     * unit tests can run against a temp-dir runtime without the IDE's [com.intellij.openapi.application.PathManager].
     * [allowFallback]=false makes any server-side failure throw instead of falling back to the CLI,
     * so a test can assert on the server path in isolation (the fallback would otherwise mask a
     * broken request body — exactly the framing bug this engine had to ship through).
     */
    internal fun transcribeInternal(
        wavFile: File,
        model: Path,
        language: String,
        timeoutMs: Long,
        useGpu: Boolean,
        threads: Int,
        indicator: ProgressIndicator?,
        serverBinary: Path?,
        logFile: File,
        allowFallback: Boolean = true,
    ): String {
        val key = ServerKey(model, useGpu, threads)
        if (serverBinary != null && !inStartupBackoff()) {
            val active = synchronized(LOCK) { ensureRunning(key, serverBinary, logFile) }
            if (active != null) {
                try {
                    val raw = request(active, wavFile, language, timeoutMs, indicator)
                    lastUsed = System.currentTimeMillis()
                    return WhisperCliEngine().normalizeTranscript(raw)
                } catch (e: ProcessCanceledException) {
                    // Cancel button: kill the server so the in-flight inference dies with it.
                    stopCurrent()
                    throw e
                } catch (e: InferenceTimeoutException) {
                    stopCurrent()
                    throw e
                } catch (t: Throwable) {
                    stopCurrent()
                    // Review P3: the GPU-backed server failed on this machine (weak iGPU, flaky
                    // driver). Restart it on CPU and retry this utterance once before giving up
                    // on the server — the CLI fallback would re-load the model and lose the warm
                    // process. Timeouts and cancellations are handled above and do not retry.
                    if (useGpu) {
                        log.warn("whisper-server request failed on the GPU (${t.message}); retrying once with --no-gpu")
                        val cpuActive = synchronized(LOCK) {
                            ensureRunning(ServerKey(model, false, threads), serverBinary, logFile)
                        }
                        if (cpuActive != null) {
                            try {
                                val raw = request(cpuActive, wavFile, language, timeoutMs, indicator)
                                lastUsed = System.currentTimeMillis()
                                return WhisperCliEngine().normalizeTranscript(raw)
                            } catch (e: ProcessCanceledException) {
                                stopCurrent()
                                throw e
                            } catch (e: InferenceTimeoutException) {
                                stopCurrent()
                                throw e
                            } catch (cpuFailure: Throwable) {
                                stopCurrent()
                                log.warn("whisper-server request also failed on CPU (${cpuFailure.message}); giving up on the server")
                            }
                        }
                    }
                    if (!allowFallback) {
                        log.warn("whisper-server request failed; rethrowing (fallback disabled)", t)
                        throw t
                    }
                    // Server died mid-request, bad response, …: this utterance still has to work.
                    log.warn("whisper-server request failed; falling back to whisper-cli", t)
                }
            } else if (!allowFallback) {
                throw IllegalStateException("whisper-server did not start (log: ${logFile.absolutePath})")
            }
        } else if (!allowFallback) {
            throw IllegalStateException("no whisper-server available for this platform (log: ${logFile.absolutePath})")
        }
        return WhisperCliEngine().transcribe(wavFile, model, language, timeoutMs, useGpu, threads, indicator)
    }

    /** Returns the running handle for [key], starting or replacing the server when needed. */
    private fun ensureRunning(key: ServerKey, serverBinary: Path, logFile: File): ServerHandle? =
        synchronized(LOCK) {
            handle?.let { existing ->
                if (existing.key == key && existing.process.isAlive) return existing
            }
            handle?.let { existing ->
                if (!existing.process.isAlive) {
                    log.info("whisper-server died (log: ${existing.logFile}); restarting")
                } else {
                    log.info("whisper-server restart: was ${existing.key}, now $key")
                }
                stopProcess(existing.process)
            }
            handle = null
            val port = freePort()
            val process = try {
                launch(serverBinary, key, port, logFile)
            } catch (t: Throwable) {
                log.warn("failed to start whisper-server at $serverBinary", t)
                null
            }
            if (process == null) {
                markStartupFailure()
                return null
            }
            val started = ServerHandle(process, port, key, logFile)
            handle = started
            lastUsed = System.currentTimeMillis()
            log.info("whisper-server started: 127.0.0.1:$port key=$key log=${logFile.absolutePath}")
            started
        }

    /**
     * Starts the server and waits until it answers `/health`, or returns null when it dies during
     * startup (model load failure, bad binary, …). [STARTUP_TIMEOUT_MS] covers a large model
     * loading on a slow machine; the first dictation of a session pays this once.
     */
    private fun launch(serverBinary: Path, key: ServerKey, port: Int, logFile: File): java.lang.Process? {
        val dir = WhisperRuntime.runtimeDir(serverBinary).let { File(it) }
        // A long-lived process with its streams parked in a log file: ProcessBuilder is the
        // honest tool here (GeneralCommandLine has no file-redirect API, and the capturing
        // handler is for one-shot runs).
        val builder = ProcessBuilder(buildServerArgs(serverBinary, key.model, key.useGpu, key.threads, port))
            .directory(dir)
            .redirectOutput(logFile)
            .redirectError(logFile)
        if (SystemInfo.isLinux || SystemInfo.isMac) {
            // The server links the bundled libwhisper/libggml shared objects that sit next to it.
            builder.environment()["LD_LIBRARY_PATH"] = dir.absolutePath
        }
        val process = builder.start()
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                log.warn("whisper-server exited during startup (log: ${logFile.absolutePath})")
                logTail(logFile)
                return null
            }
            if (isReady(port)) return process
            Thread.sleep(250)
        }
        log.warn("whisper-server not ready after ${STARTUP_TIMEOUT_MS} ms (log: ${logFile.absolutePath})")
        stopProcess(process)
        return null
    }

    /** One inference over the server's HTTP endpoint, with cancellation and timeout handling. */
    private fun request(handle: ServerHandle, wavFile: File, language: String, timeoutMs: Long, indicator: ProgressIndicator?): String {
        val body = buildMultipart(wavFile, language)
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:${handle.port}/inference"))
            // Backstop only; the deadline loop below owns the timeout (it also serves the indicator).
            .timeout(Duration.ofMillis(timeoutMs + 5_000))
            .header("Content-Type", body.contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes))
            .build()
        val future = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!future.isDone) {
            // The Cancel button (and cooperative checks) surface here as ProcessCanceledException.
            indicator?.checkCanceled()
            if (System.currentTimeMillis() >= deadline) {
                future.cancel(true)
                throw InferenceTimeoutException("whisper-server timed out after ${timeoutMs} ms")
            }
            Thread.sleep(50)
        }
        val response = future.get()
        if (response.statusCode() != 200) {
            throw WhisperCliEngine.TranscriptionException(
                "whisper-server returned HTTP ${response.statusCode()}: ${response.body().take(200)}",
            )
        }
        log.info("whisper-server inferred ${response.body().length} chars")
        return response.body()
    }

    /** True while the server reports the model loaded (200 from `/health`). */
    private fun isReady(port: Int): Boolean = try {
        val health = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/health"))
            .timeout(Duration.ofMillis(2_000))
            .GET()
            .build()
        client.send(health, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    } catch (t: Throwable) {
        false
    }

    /**
     * Stops the current server (if any). Idempotent. Called from [DictationSession] on dispose so
     * no inference process outlives the plugin (review #6), and from the failure paths above.
     */
    fun shutdown() {
        synchronized(LOCK) {
            handle?.let { stopProcess(it.process) }
            handle = null
        }
        idleReaper.shutdownNow()
    }

    private fun stopCurrent() = synchronized(LOCK) {
        handle?.let { stopProcess(it.process) }
        handle = null
    }

    private fun stopProcess(process: java.lang.Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }.onFailure { log.warn("failed to stop the whisper-server process", it) }
    }

    private fun reapIdle() {
        if (System.currentTimeMillis() - lastUsed > IDLE_TIMEOUT_MS) {
            log.info("whisper-server idle for over ${IDLE_TIMEOUT_MS / 60_000} min; stopping it")
            stopCurrent()
        }
    }

    private fun inStartupBackoff(): Boolean =
        System.currentTimeMillis() - lastStartupFailure < STARTUP_BACKOFF_MS

    private fun markStartupFailure() {
        lastStartupFailure = System.currentTimeMillis()
    }

    private fun freePort(): Int {
        // Bind nothing: we only ask the OS for a free port, then hand it to the server (127.0.0.1).
        val socket = ServerSocket(0)
        try {
            return socket.localPort
        } finally {
            socket.close()
        }
    }

    /** Last few log lines, for a startup that died — the server's stderr lands here. */
    private fun logTail(logFile: File) {
        runCatching {
            if (logFile.exists()) {
                log.warn("whisper-server log: " +
                    logFile.readLines().takeLast(5).joinToString(" | ") { it.take(200) })
            }
        }
    }

    /** The server binary for this platform, or null when the platform bundle ships none. */
    internal fun serverBinary(): Path? = WhisperRuntime.serverPath()

    /** Where the server's stdout/stderr go (one file, rewritten on each start). */
    internal fun defaultLogFile(): File {
        val dir = runCatching {
            java.nio.file.Paths.get(com.intellij.openapi.application.PathManager.getSystemPath(), "copilot-voice")
        }.getOrNull() ?: java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "copilot-voice")
        runCatching { java.nio.file.Files.createDirectories(dir) }
        return File(dir.toFile(), "whisper-server.log")
    }

    // --- argument + body construction (pure, unit-tested) ---

    /**
     * whisper-server launch arguments. Mirrors [WhisperCliEngine.buildArguments]: GPU on by
     * default (opt out with `--no-gpu`), `-t` only for a concrete count, model via `-m`.
     */
    internal fun buildServerArgs(
        serverBinary: Path,
        model: Path,
        useGpu: Boolean,
        threads: Int,
        port: Int,
    ): List<String> {
        val base = listOf(
            serverBinary.toString(),
            "-m", model.toString(),
            "--host", "127.0.0.1",
            "--port", port.toString(),
        )
        val withThreads = if (threads > 0) base + listOf("-t", threads.toString()) else base
        return if (useGpu) withThreads else withThreads + listOf("--no-gpu")
    }

    /** One multipart/form-data body: the WAV as `file`, the rest as text fields. */
    class MultipartBody(val bytes: ByteArray, val contentType: String)

    /**
     * Builds the `/inference` request body. `response_format=text` keeps the reply plain text (the
     * same shape as whisper-cli's stdout, so [WhisperCliEngine.normalizeTranscript] applies
     * unchanged); `no_timestamps`/`suppress_nst` mirror the CLI's `-nt`/`--suppress-nst`.
     */
    internal fun buildMultipart(wavFile: File, language: String): MultipartBody {
        val boundary = "vp" + UUID.randomUUID().toString().replace("-", "")
        val audio = wavFile.readBytes()
        val out = ByteArrayOutputStream()

        /**
         * One RFC 7578 part: `--boundary`, the header lines, one blank line, the content, `\r\n`.
         * The blank line CLOSES the header block, so every header of the part must precede it —
         * a blank line before a later header (the file part has two) would make that header part
         * of the body, and the server's decoder would reject the audio.
         */
        fun part(headers: List<String>, content: ByteArray) {
            out.write(("--$boundary\r\n").toByteArray(StandardCharsets.US_ASCII))
            for (h in headers) out.write((h + "\r\n").toByteArray(StandardCharsets.US_ASCII))
            out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.write(content)
            out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        }

        part(
            listOf(
                "Content-Disposition: form-data; name=\"file\"; filename=\"${wavFile.name}\"",
                "Content-Type: application/octet-stream",
            ),
            audio,
        )

        for ((name, value) in listOf(
            "response_format" to "text",
            "no_timestamps" to "true",
            "suppress_nst" to "true",
            "language" to language,
        )) {
            part(listOf("Content-Disposition: form-data; name=\"$name\""), value.toByteArray(StandardCharsets.UTF_8))
        }
        out.write(("--$boundary--\r\n").toByteArray(StandardCharsets.US_ASCII))
        return MultipartBody(out.toByteArray(), "multipart/form-data; boundary=$boundary")
    }

    // (An `object` cannot carry a `companion object`, so the tunables are top-level privates.)
    private const val STARTUP_TIMEOUT_MS = 60_000L
    private const val IDLE_TIMEOUT_MS = 10 * 60_000L
    private const val IDLE_CHECK_PERIOD_MS = 30_000L
    /** After a failed startup, don't hammer the start button on every dictation for a bit. */
    private const val STARTUP_BACKOFF_MS = 30_000L
}
