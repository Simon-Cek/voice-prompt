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
 * When the server is not available (platform bundles no binary, or the start fails),
 * [transcribe] falls back to the one-shot [WhisperCliEngine] path, so dictation keeps working
 * exactly as before.
 *
 * Review P3: a request that fails on the GPU-backed server is retried once on a CPU-restarted
 * server (`--no-gpu`) before the CLI fallback — the same GPU→CPU safety net as
 * [WhisperCliEngine.transcribe].
 *
 * Review N9 sharpens that net:
 *  - the CPU retry CONFIRMS the failure — the model is blacklisted for the GPU ([GpuHealth])
 *    only when the CPU run SUCCEEDS. If the CPU fails too, nothing is remembered and the error
 *    goes up (no loop, no hidden real error);
 *  - the blacklist applies from the NEXT call onwards: a blacklisted model starts directly on
 *    the CPU key, so the failure-and-restart dance happens once, not on every utterance;
 *  - failures that are NOT the GPU's fault keep the warm server and do not retry: an HTTP 4xx
 *    (the server rejected this request), a timeout (the model is slow, not dead), and a user
 *    cancel (the in-flight inference dies with the closed connection — the real server polls
 *    for it);
 *  - a GPU-keyed server that DIES during startup (not a timeout, not a cancel) tries the CPU
 *    key once and blacklists if it comes up; that GPU attempt sets no startup backoff (only the
 *    CPU attempt's failure does).
 *
 * Review N8: the start — spawning plus the /health wait (up to [STARTUP_TIMEOUT_MS]) — runs
 * OUTSIDE the lock. [shutdown], the idle reaper and a concurrent stop therefore kill the
 * in-flight process instead of blocking on it; the wait honours the task's Cancel button; and
 * a cancelled or aborted start never sets the [STARTUP_BACKOFF_MS] backoff — only a real
 * failure (the process died, the wait timed out) does.
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

    /**
     * Review N9 #2: a GPU-keyed server DIED during startup (not a timeout, not a cancel) — a
     * GPU-failure candidate. Unlike a plain startup failure it sets no startup backoff: the
     * caller immediately tries the CPU key, and only a CPU-side failure is a real startup
     * failure worth backing off from.
     */
    private class GpuStartupDeath(val logFile: File) : Exception("whisper-server died on the GPU during startup")

    /** What a running server was started with; a change in any of it means a restart. */
    private data class ServerKey(val model: Path, val useGpu: Boolean, val threads: Int)

    private class ServerHandle(val process: java.lang.Process, val port: Int, val key: ServerKey, val logFile: File)

    /** Review N9 #2: the /health wait outcome — ready, or the process DIED during startup. */
    private class StartupResult(val process: java.lang.Process?, val died: Boolean)

    /** Review N8: a spawn-in-progress (see [starting]); the caller that spawned owns [process]. */
    private class Starting(val key: ServerKey) {
        @Volatile var process: java.lang.Process? = null
        @Volatile var port = 0
    }

    /** The decision-section outcome of [ensureRunning]: what to stop, and who owns the start. */
    private class Claim(val starter: Starting?, val oldProcess: java.lang.Process?, val foreign: Starting?)

    /** Serialises start/stop/replace of the server process (same shape as [WhisperRuntime.LOCK]). */
    private val LOCK = Any()

    @Volatile
    private var handle: ServerHandle? = null

    /**
     * Review N8: a server that has been spawned but not yet answered /health. It is published
     * BEFORE the slow wait so [stopCurrent]/[shutdown] can kill it and a same-key caller can wait
     * on it — all without blocking on the wait itself.
     */
    @Volatile
    private var starting: Starting? = null

    /** Review N8 #1: set by [shutdown]; a start in flight must not outlive the plugin. */
    @Volatile
    private var shuttingDown = false

    /** Wall time of the last successful inference; the idle reaper stops the server past [IDLE_TIMEOUT_MS]. */
    @Volatile
    private var lastUsed = System.currentTimeMillis()

    /** Wall time of the last failed startup; while the backoff window is open we skip straight to the CLI. */
    @Volatile
    private var lastStartupFailure = 0L

    /** Review N8 #7: the /health wait bound (default [STARTUP_TIMEOUT_MS]); tests override it. */
    internal var startupTimeoutMsForTest: Long? = null
    private val startupTimeoutMs: Long get() = startupTimeoutMsForTest ?: STARTUP_TIMEOUT_MS

    // Review N13: created on first use, not on object init — [shutdown] (called from
    // DictationSession.dispose on every unload, including CLI-only ones) checks isInitialized()
    // without touching the values, so a plugin that never used the long-lived server creates no
    // HttpClient and no reaper thread at all.
    private val clientLazy: Lazy<HttpClient> = lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    private val client: HttpClient get() = clientLazy.value

    private val idleReaperLazy: Lazy<ScheduledExecutorService> = lazy {
        val reaper = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "whisper-server-idle-reaper").apply { isDaemon = true }
        }
        // Daemon so it can never keep the JVM alive; stopped with the plugin in [shutdown].
        reaper.scheduleWithFixedDelay(
            { runCatching { reapIdle() }.onFailure { log.warn("idle reaper pass failed", it) } },
            IDLE_CHECK_PERIOD_MS, IDLE_CHECK_PERIOD_MS, TimeUnit.MILLISECONDS,
        )
        reaper
    }

    private val idleReaper: ScheduledExecutorService get() = idleReaperLazy.value

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
        /**
         * The task's indicator: its Cancel button aborts the run; the server stays warm
         * (review N9 — the in-flight inference dies with the closed connection). Null for
         * unattended calls.
         */
        indicator: ProgressIndicator? = null,
        /** Review N9: the processing-settings key scoping the GPU blacklist (see [GpuHealth]); null derives one. */
        processingKey: String? = null,
    ): String = transcribeInternal(
        wavFile, model, language, timeoutMs, useGpu, threads, indicator,
        serverBinary(), defaultLogFile(), processingKey = processingKey,
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
        /** Review N9: the processing-settings key scoping the GPU blacklist (see [GpuHealth]); null derives one. */
        processingKey: String? = null,
    ): String {
        val key = processingKey ?: GpuHealth.processingKey(if (useGpu) "GPU" else "CPU", threads)
        // Review N9: a confirmed GPU failure for this model goes straight to the CPU — the
        // fail-and-restart dance happens once, not on every utterance.
        val gpu = useGpu && !GpuHealth.isBlacklisted(model, key)
        val serverKey = ServerKey(model, gpu, threads)
        if (serverBinary != null && !inStartupBackoff()) {
            val active = try {
                ensureRunning(serverKey, serverBinary, logFile, indicator)
            } catch (e: GpuStartupDeath) {
                // Review N9 #2: a GPU-keyed server DIED during startup (not a timeout, not a
                // cancel). Try the CPU key once; if it comes up, the GPU failure is confirmed
                // for this model. The GPU attempt itself set no startup backoff.
                log.warn("whisper-server died on the GPU during startup (log: ${e.logFile}); trying the CPU key once")
                val cpu = try {
                    ensureRunning(ServerKey(model, false, threads), serverBinary, logFile, indicator)
                } catch (c: ProcessCanceledException) {
                    throw c
                }
                if (cpu != null) {
                    if (GpuHealth.noteFailure(model, key)) notifyGpuBlacklisted(model)
                    cpu
                } else {
                    // The CPU startup failed too (it marked its own backoff): the normal
                    // did-not-start handling below (ISE or CLI fallback) applies.
                    null
                }
            }
            if (active != null) {
                try {
                    val raw = request(active, wavFile, language, timeoutMs, indicator)
                    lastUsed = System.currentTimeMillis()
                    return WhisperCliEngine().normalizeTranscript(raw)
                } catch (e: ProcessCanceledException) {
                    // Review N9: cancel — keep the warm server. The in-flight inference dies with
                    // the closed connection (the real server polls for it).
                    throw e
                } catch (e: InferenceTimeoutException) {
                    // Review N9: a slow inference — the server is fine; keep it warm for the next
                    // utterance. (Falling back to the CLI would just re-run the same inference.)
                    throw e
                } catch (t: Throwable) {
                    if (isRequestProblem(t)) {
                        // Review N9: the server rejected THIS request (HTTP 4xx) — a request
                        // problem, not a GPU failure. Keep the warm server; do not retry.
                        if (!allowFallback) {
                            log.warn("whisper-server rejected the request; rethrowing (fallback disabled)", t)
                            throw t
                        }
                        log.warn("whisper-server rejected the request (${t.message}); falling back to whisper-cli")
                    } else {
                        // Review N9 #1: a GPU-failure candidate (HTTP 5xx, a dead process, an
                        // I/O error). Confirm it: retry the utterance once on the CPU key, and
                        // remember the GPU failure only if the CPU run succeeds.
                        val cpuText = retryOnCpuIfGpuServer(
                            active, t, model, threads, key, serverBinary, logFile, indicator,
                            wavFile, language, timeoutMs,
                        )
                        if (cpuText != null) return cpuText
                        if (!allowFallback) {
                            log.warn("whisper-server request failed; rethrowing (fallback disabled)", t)
                            throw t
                        }
                        // Server died mid-request, bad response, …: this utterance still has to work.
                        log.warn("whisper-server request failed; falling back to whisper-cli", t)
                    }
                }
            } else if (!allowFallback) {
                throw IllegalStateException("whisper-server did not start (log: ${logFile.absolutePath})")
            }
        } else if (!allowFallback) {
            throw IllegalStateException("no whisper-server available for this platform (log: ${logFile.absolutePath})")
        }
        return WhisperCliEngine().transcribe(wavFile, model, language, timeoutMs, useGpu, threads, indicator, key)
    }

    /** Review N9: HTTP 4xx — the server rejected this request; it is not a GPU failure. */
    private fun isRequestProblem(t: Throwable): Boolean =
        t is WhisperCliEngine.TranscriptionException && (t.httpStatus ?: 0) in 400..499

    /**
     * Review N9 #1: the request failed on a GPU-keyed server (HTTP 5xx, a dead process, an I/O
     * error). Confirm it is a GPU problem: retry the utterance once on the CPU key, and remember
     * the GPU failure ([GpuHealth]) only if the CPU run SUCCEEDS.
     *
     * Returns the CPU's transcript, or null when there was no GPU to blame (a CPU-keyed server)
     * or the CPU path also failed — the caller then reports [failure] / falls back.
     */
    private fun retryOnCpuIfGpuServer(
        failed: ServerHandle,
        failure: Throwable,
        model: Path,
        threads: Int,
        processingKey: String,
        serverBinary: Path,
        logFile: File,
        indicator: ProgressIndicator?,
        wavFile: File,
        language: String,
        timeoutMs: Long,
    ): String? {
        if (!failed.key.useGpu) {
            // A CPU-keyed server failed: there is no GPU to blame — no retry, no blacklist.
            // A dead process clears itself; a live one stays warm for the next request.
            if (!failed.process.isAlive) stopCurrent()
            log.warn("whisper-server request failed on the CPU (${failure.message})")
            return null
        }
        // Review N9 #1: don't classify by the exception type alone — for an I/O error, verify
        // against the process (dead? exit code?).
        val processState = if (failed.process.isAlive) {
            "the server process is still alive"
        } else {
            "the server process is dead (exit code ${runCatching { failed.process.exitValue() }.getOrNull() ?: "unknown"})"
        }
        log.warn(
            "whisper-server request failed on the GPU; $processState; error: ${failure.message}; " +
                "retrying once with --no-gpu",
        )
        stopCurrent()
        val cpuActive = try {
            ensureRunning(ServerKey(model, false, threads), serverBinary, logFile, indicator)
        } catch (c: ProcessCanceledException) {
            throw c
        }
        if (cpuActive == null) {
            // The CPU startup failed (it marked its own backoff): no confirmed GPU failure.
            return null
        }
        return try {
            val raw = request(cpuActive, wavFile, language, timeoutMs, indicator)
            lastUsed = System.currentTimeMillis()
            // Review N9 #1: the CPU run succeeded — the failure is confirmed as a GPU problem.
            if (GpuHealth.noteFailure(model, processingKey)) notifyGpuBlacklisted(model)
            WhisperCliEngine().normalizeTranscript(raw)
        } catch (c: ProcessCanceledException) {
            throw c
        } catch (c: InferenceTimeoutException) {
            throw c
        } catch (cpuFailure: Throwable) {
            // Review N9 #1: the CPU failed too — it is not a GPU problem; no blacklist, no loop.
            log.warn("whisper-server request also failed on the CPU (${cpuFailure.message}); no GPU blacklist")
            null
        }
    }

    /**
     * Returns the running handle for [key], starting or replacing the server when needed.
     *
     * Review N8: only the decision (what to replace) and the publish take [LOCK]. Spawning plus
     * the /health wait (up to [startupTimeoutMs]) run outside it, so [shutdown], the idle reaper
     * and a concurrent stop never block on a slow startup — they kill the in-flight process
     * instead — and the wait honours the task's Cancel button. Every path converges on
     * [resolveStart], which always clears the claim and never leaves an unpublished process
     * running.
     */
    private fun ensureRunning(
        key: ServerKey,
        serverBinary: Path,
        logFile: File,
        indicator: ProgressIndicator?,
    ): ServerHandle? {
        val claim = synchronized(LOCK) {
            // Review N8 #1: a dispose in progress must never spawn a server that outlives it.
            if (shuttingDown) return null
            // Fast path: a healthy server for this key.
            handle?.let { existing ->
                if (existing.key == key && existing.process.isAlive) return existing
            }
            val inFlight = starting
            if (inFlight != null && inFlight.key == key) {
                // Review N8 #4: a start for the same key is already in flight — wait for it
                // (bounded, honouring our own Cancel) instead of spawning a second server.
                Claim(null, null, null)
            } else {
                // A start for a different key (if any) loses: the caller that spawned it owns the
                // process and it is killed below. A dead or wrong-key handle is replaced the same way.
                val foreign = starting
                handle?.let { existing ->
                    if (!existing.process.isAlive) {
                        log.info("whisper-server died (log: ${existing.logFile}); restarting")
                    } else {
                        log.info("whisper-server restart: was ${existing.key}, now $key")
                    }
                }
                val oldProcess = handle?.process
                handle = null
                starting = null
                val starter = Starting(key)
                starting = starter
                Claim(starter, oldProcess, foreign)
            }
        }
        val starter = claim.starter ?: return awaitSameKeyStart(key, indicator)

        // Stop whatever we displaced OUTSIDE the lock (review N8 #5: destroy + waitFor is not
        // lock-safe). An in-flight start from a different key dies immediately.
        claim.oldProcess?.let { stopProcess(it) }
        claim.foreign?.process?.let { it.destroyForcibly() }

        var ready: java.lang.Process? = null
        var died = false
        var cancelled: ProcessCanceledException? = null
        try {
            starter.port = freePort()
            val process = startProcess(serverBinary, key, starter.port, logFile)
            starter.process = process
            val result = waitForHealth(starter.port, process, logFile, indicator, startupTimeoutMs)
            ready = result.process
            died = result.died
        } catch (e: ProcessCanceledException) {
            // Review N8 #2: a cancel is NOT a startup failure — no backoff. [resolveStart]
            // clears the claim and rethrows the cancel.
            cancelled = e
        } catch (t: Throwable) {
            log.warn("failed to start whisper-server at $serverBinary", t)
        }
        return resolveStart(starter, ready, died, cancelled, key, logFile)
    }

    /**
     * Review N8 #4: another caller is starting the same key. Wait for it to resolve — bounded by
     * the startup timeout plus a margin, honouring our own [indicator] — and return the published
     * handle, or null when the start failed (the caller then falls back as usual).
     */
    private fun awaitSameKeyStart(key: ServerKey, indicator: ProgressIndicator?): ServerHandle? {
        val deadline = System.currentTimeMillis() + startupTimeoutMs + 5_000
        while (System.currentTimeMillis() < deadline) {
            indicator?.checkCanceled()
            synchronized(LOCK) {
                handle?.let { if (it.key == key && it.process.isAlive) return it }
                if (starting == null) return null
            }
            Thread.sleep(50)
        }
        return null
    }

    /**
     * Spawns the server process (fast, no waiting). Review N8: split from the /health wait so
     * the slow part runs outside [LOCK].
     */
    private fun startProcess(serverBinary: Path, key: ServerKey, port: Int, logFile: File): java.lang.Process {
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
        return builder.start()
    }

    /**
     * Waits until the server answers `/health`. Returns [StartupResult]: the ready process, or
     * how the start failed — the process DIED during startup ([StartupResult.died], model load
     * failure, bad binary, …) or the wait timed out (the process was killed by the wait).
     * [startupTimeoutMs] covers a large model loading on a slow machine; the first dictation of
     * a session pays this once.
     *
     * A cancel from [indicator] kills the process and rethrows [ProcessCanceledException] — the
     * Cancel button must work during the wait (review N8).
     */
    private fun waitForHealth(
        port: Int,
        process: java.lang.Process,
        logFile: File,
        indicator: ProgressIndicator?,
        timeoutMs: Long,
    ): StartupResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                indicator?.checkCanceled()
                if (!process.isAlive) {
                    log.warn("whisper-server exited during startup (log: ${logFile.absolutePath})")
                    logTail(logFile)
                    return StartupResult(process, died = true)
                }
                if (isReady(port)) return StartupResult(process, died = false)
                Thread.sleep(250)
            }
            log.warn("whisper-server not ready after ${timeoutMs} ms (log: ${logFile.absolutePath})")
            process.destroyForcibly()
            // died=false: the process was alive until the wait killed it — a timeout, not a
            // startup death (review N9 #2: only a real death on the GPU key skips the backoff).
            return StartupResult(process, died = false)
        } catch (e: ProcessCanceledException) {
            process.destroyForcibly()
            throw e
        }
    }

    /**
     * The single resolution point of a start (review N8 #6): the claim is always cleared or
     * published, an unpublished process is always killed, and the backoff is marked only on a
     * REAL failure — a cancel or a shutdown never sets it (review N8 #2), and neither does a
     * GPU-keyed startup DEATH: that is a GPU-failure candidate the caller may retry on the CPU
     * key (review N9 #2), not a general startup failure.
     */
    private fun resolveStart(
        starter: Starting,
        ready: java.lang.Process?,
        died: Boolean,
        cancelled: ProcessCanceledException?,
        key: ServerKey,
        logFile: File,
    ): ServerHandle? {
        var published: ServerHandle? = null
        val outcome = synchronized(LOCK) {
            // The claim is ours only if nobody else cleared it (stop/shutdown/takeover).
            val mine = starting == starter
            if (mine) starting = null
            when {
                cancelled != null -> "cancelled"
                !mine || shuttingDown -> "aborted"
                ready == null -> {
                    if (key.useGpu && died) {
                        // Review N9 #2: a GPU-keyed server died at startup — no backoff (the
                        // caller tries the CPU key); surface it for that decision.
                        "gpu-died"
                    } else {
                        markStartupFailure()
                        "failed"
                    }
                }
                else -> {
                    published = ServerHandle(ready, starter.port, key, logFile)
                    handle = published
                    lastUsed = System.currentTimeMillis()
                    "published"
                }
            }
        }
        // All process work OUTSIDE the lock (review N8 #5).
        return when (outcome) {
            "published" -> {
                log.info("whisper-server started: 127.0.0.1:${published!!.port} key=$key log=${logFile.absolutePath}")
                published
            }
            "cancelled" -> {
                starter.process?.let { if (it.isAlive) it.destroyForcibly() }
                throw cancelled!!
            }
            "aborted" -> {
                // Review N8 #3: a stop/shutdown or a different-key claim won the race — kill what
                // we spawned and surface a cancellation.
                ready?.let { if (it.isAlive) it.destroyForcibly() }
                throw ProcessCanceledException()
            }
            "gpu-died" -> {
                // Review N9 #2: it died (already stopped by waitForHealth); the caller decides
                // the CPU-key retry. No backoff was set.
                throw GpuStartupDeath(logFile)
            }
            else -> {
                // "failed": it died during startup or the wait timed out (both already stopped by
                // waitForHealth) — or the spawn threw before a process existed.
                starter.process?.let { if (it.isAlive) it.destroyForcibly() }
                null
            }
        }
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
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!future.isDone) {
                // The Cancel button (and cooperative checks) surface here as ProcessCanceledException.
                indicator?.checkCanceled()
                if (System.currentTimeMillis() >= deadline) {
                    throw InferenceTimeoutException("whisper-server timed out after ${timeoutMs} ms")
                }
                Thread.sleep(50)
            }
            val response = future.get()
            if (response.statusCode() != 200) {
                // Review N9: carry the status so the caller can tell a rejected request (4xx, keep
                // the warm server) from a failed inference (5xx, a GPU-failure candidate).
                // The bundled whisper-server reports a failed inference as HTTP 500 with a JSON
                // error body (verified against v1.9.5 server.cpp), never as 200 + error text.
                throw WhisperCliEngine.TranscriptionException(
                    "whisper-server returned HTTP ${response.statusCode()}: ${response.body().take(200)}",
                    httpStatus = response.statusCode(),
                )
            }
            log.info("whisper-server inferred ${response.body().length} chars")
            return response.body()
        } finally {
            // Pre-1.1.0 finding 1: on every exit that leaves the inference still running — a user
            // Cancel (checkCanceled above) or the timeout — close the connection by cancelling the
            // future. The real server polls for the disconnect and aborts the in-flight inference,
            // so the next clip is not queued behind an abandoned one (before this fix a Cancel
            // left the connection open and the server kept the model busy). A finished future is
            // left alone: the success path already consumed it, and cancel on a completed future
            // is a no-op anyway.
            if (!future.isDone) future.cancel(true)
        }
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
     * Stops the current server (if any) and any start in flight. Idempotent. Called from
     * [DictationSession] on dispose so no inference process outlives the plugin (review #6), and
     * from the failure paths above.
     *
     * Review N8 #1: [shuttingDown] is set first (under the lock, atomic with the claim state),
     * so a start in progress can never outlive the plugin and no new one can begin.
     */
    fun shutdown() {
        synchronized(LOCK) { shuttingDown = true }
        stopCurrent()
        if (idleReaperLazy.isInitialized()) idleReaperLazy.value.shutdownNow()
    }

    /**
     * Captures the current state under the lock and stops whatever it points at OUTSIDE it
     * (review N8 #5): a running server gets a graceful 3 s, an in-flight start dies immediately.
     */
    private fun stopCurrent() {
        val (oldProcess, inFlight) = synchronized(LOCK) {
            val p = handle?.process
            handle = null
            val f = starting
            starting = null
            p to f
        }
        oldProcess?.let { stopProcess(it) }
        inFlight?.process?.let { it.destroyForcibly() }
    }

    /**
     * Test seam (review N8): this object is process-wide, and its state (a running server, an
     * in-flight start, the shutdown flag, the backoff, the timeout override) outlives individual
     * tests.
     */
    internal fun resetForTest() {
        val (oldProcess, inFlight) = synchronized(LOCK) {
            shuttingDown = false
            val p = handle?.process
            handle = null
            val f = starting
            starting = null
            p to f
        }
        if (idleReaperLazy.isInitialized()) idleReaperLazy.value.shutdownNow()
        oldProcess?.let { stopProcess(it) }
        inFlight?.process?.let { it.destroyForcibly() }
        startupTimeoutMsForTest = null
        lastStartupFailure = 0L
    }

    private fun stopProcess(process: java.lang.Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }.onFailure { log.warn("failed to stop the whisper-server process", it) }
    }

    private fun reapIdle() {
        // Review N8 #6: an in-flight start means a dictation is active — the reaper backs off.
        if (starting != null) return
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

    /**
     * Where the server's stdout/stderr go (one file, rewritten on each start). Named per
     * process (review N11): the home is shared across IDEs, so a single file would be truncated
     * by whichever IDE starts its server last, and on Windows the concurrent writes can be a
     * sharing violation.
     */
    internal fun defaultLogFile(): File {
        // Under the shared home (review P6), next to the runtime it describes.
        val dir = VoicePromptPaths.baseDir()
        java.nio.file.Files.createDirectories(dir)
        return File(dir.toFile(), "whisper-server-${ProcessHandle.current().pid()}.log")
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
