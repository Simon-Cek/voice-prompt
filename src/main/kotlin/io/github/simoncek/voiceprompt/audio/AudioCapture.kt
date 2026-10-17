package io.github.simoncek.voiceprompt.audio

import com.intellij.openapi.diagnostic.Logger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture(
    private val format: AudioFormat = DEFAULT_FORMAT,
    private val maxDurationSeconds: Long = MAX_DURATION_SECONDS,
) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
        const val MAX_DURATION_SECONDS = 60L

        private const val CHUNK_FRAMES = 800
        private const val UNDERFLOW_SLEEP_MS = 10L
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
        private const val DEFAULT_NO_DATA_LOSS_MS = 2_000L

        // Review N7: the line buffer is requested in BYTES — TargetDataLine.open(format, size)
        // takes a byte count, and the old 1,600 was a frame count (50 ms, exactly one read
        // chunk), so a scheduling hiccup (GC, a saturated core) could overrun the buffer and
        // drop audio silently. A bigger buffer cannot delay the first sample: read() blocks
        // for one chunk regardless of the buffer size — and the start-to-first-sample gap is
        // debug-logged on each awaitFirstSample success to keep that honest (review N7).
        private const val BUFFER_MS = 250L

        // [sampleState] transitions: AWAITING -> DATA (first read() delivered audio) or
        // AWAITING -> STOPPED (the capture ended before audio arrived).
        const val AWAITING = 0
        const val DATA = 1
        const val STOPPED = 2

        val DEFAULT_FORMAT: AudioFormat by lazy {
            AudioFormat(SAMPLE_RATE.toFloat(), BITS_PER_SAMPLE, CHANNELS, true, false)
        }
    }

    private val log = Logger.getInstance(AudioCapture::class.java)

    /**
     * Review N7: the line buffer in BYTES — about [BUFFER_MS] of audio, computed from the
     * capture format (not a hardcoded rate/bit-depth) and aligned to a whole number of frames.
     */
    private val bufferBytes: Int = ((format.sampleRate * BUFFER_MS / 1000).toInt()) * format.frameSize

    /** Wall time (ms) [start] began; the awaitFirstSample success logs the gap to the first sample. */
    @Volatile private var startWallMs = 0L

    private val buffer = ByteArrayOutputStream()
    private val recording = AtomicBoolean(false)
    private var readerThread: Thread? = null

    /**
     * The resolved capture line, kept across stops so the next start can skip the mixer
     * enumeration (review #11, item 3: "cache the resolved Mixer" — a line is its mixer's, so
     * caching the line is strictly stronger). [lineDevice] is the name it was resolved for:
     * a settings change misses on it, and a failed open invalidates both.
     */
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var lineDevice: String? = null

    /** Bumped on every start/stop/release so a pending keep-ready close can only ever hit its own line. */
    @Volatile private var lineGeneration = 0

    /**
     * Who may flip it in production is the read loop (DATA) and [start]/[stop] (the rest). The
     * setter is [internal] so the awaitFirstSample contract can be unit tested by driving the
     * state directly, without a real audio device.
     */
    internal @Volatile var sampleState: Int = AWAITING
        internal set

    /**
     * Test seam (review N5): a factory for the capture line, so the flush-before-start
     * contract can be proven against a fake line without a real audio device. Null (the
     * default) = [MicrophoneResolver.resolve]. Tests must reset it to null in tearDown.
     */
    internal @Volatile var lineFactoryForTest: ((mixerName: String?, format: AudioFormat) -> TargetDataLine)? = null

    /**
     * Test seam (review N6): the clock the read loop uses to time the no-data loss window (ms).
     * Null (the default) = [System.currentTimeMillis]. Tests reset it to null in tearDown.
     */
    internal @Volatile var clockMsForTest: (() -> Long)? = null

    /**
     * Review N6: how long a confirmed live capture (audio already flowing) may go without a
     * positive read before the read loop reports a device loss. A test seam like
     * [clockMsForTest]; the default is about two seconds.
     */
    internal @Volatile var noDataLossMs: Long = DEFAULT_NO_DATA_LOSS_MS

    /**
     * Review N6: set by the read loop when the device stopped delivering mid-recording — the
     * next [stop] must close the line instead of keeping it ready (a dead line is never cached
     * or reused). Consumed (cleared) by [stop].
     */
    @Volatile private var deviceLost = false

    private fun clockMs(): Long = clockMsForTest?.invoke() ?: System.currentTimeMillis()

    // One daemon thread for the deferred "close the kept-ready line" — never a new thread
    // per event (the review's #10 rule for Beeps applies here too), and daemon so it cannot
    // hold the IDE shutdown or the plugin class loader (review #6).
    private val keepReadyExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "copilot-voice-mic-keepready").apply { isDaemon = true }
    }
    @Volatile private var keepReadyTask: ScheduledFuture<*>? = null

    /** Invoked from the capture thread when [maxDurationSeconds] of audio has been collected. */
    var onMaxDurationReached: (() -> Unit)? = null

    /**
     * Invoked from the capture thread when the microphone stops delivering mid-recording
     * (review N6): a confirmed live capture went silent for [noDataLossMs], the line was closed
     * by the OS, or a read threw. The session surfaces PROBLEM and stops the capture.
     */
    var onDeviceLost: (() -> Unit)? = null

    fun isRecording(): Boolean = recording.get()

    @Synchronized
    fun start(mixerName: String? = null) {
        if (!recording.compareAndSet(false, true)) {
            throw IllegalStateException("Already recording")
        }
        startWallMs = System.currentTimeMillis()
        keepReadyTask?.cancel(false)
        lineGeneration++
        val opened = try {
            openLine(mixerName)
        } catch (t: Throwable) {
            recording.set(false)
            throw t
        }
        var dataLine = opened.line
        try {
            // Review N5: a keep-ready line was only stopped, not closed — per the Java Sound
            // contract a stopped line retains its buffered data, so the first read of this
            // clip would replay the tail of the previous one (and awaitFirstSample would
            // report "ready" on that stale audio). A fresh line is flushed as a harmless
            // no-op; a reused line must be verifiably empty, or it is discarded and a fresh
            // one resolved (one retry, no loop) — only a failed fresh open fails the start.
            if (opened.reused) {
                if (!cleanReusedLine(dataLine)) {
                    runCatching { dataLine.close() }
                    if (line === dataLine) line = null
                    dataLine = resolveFreshLine(mixerName)
                    bestEffortFlush(dataLine)
                }
            } else {
                bestEffortFlush(dataLine)
            }
            buffer.reset()
            sampleState = AWAITING
            // Mic timing log (review #11, start delay): numbers only — the line.start() phase.
            val startPhaseBeginMs = System.currentTimeMillis()
            dataLine.start()
            log.debug("capture line started: ${System.currentTimeMillis() - startPhaseBeginMs} ms")
        } catch (t: Throwable) {
            runCatching { dataLine.close() }
            if (line === dataLine) line = null
            recording.set(false)
            throw t
        }
        val thread = Thread({ readLoop(dataLine) }, "copilot-voice-capture")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    /**
     * Review #11, item 1: "ready" is not `line.start()` returned — on the slow devices this
     * plugin is used on, the first audio arrives later still. Blocks until the read loop's
     * first non-empty read() (or [timeoutMs], whichever comes first), and reports whether
     * audio actually arrived (false when it was stopped or timed out).
     */
    fun awaitFirstSample(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            when (sampleState) {
                DATA -> {
                    // Review N7: the start-to-first-sample gap, so the larger line buffer can
                    // be verified (Windows and Linux) not to delay it — numbers only.
                    log.debug("first sample after ${System.currentTimeMillis() - startWallMs} ms " +
                        "(requested buffer ${bufferBytes} bytes)")
                    return true
                }
                STOPPED -> return false
                else -> Unit
            }
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(20)
        }
    }

    /**
     * @param keepReadySeconds review #11, item 4 (opt-in, 0 = off): keep the line OPEN —
     *   just stopped — so the next start skips lookup + open and is instant; it is closed
     *   again after [keepReadySeconds] of idle, or immediately by the next start/stop/release.
     */
    @Synchronized
    fun stop(keepReadySeconds: Long = 0): ShortArray {
        if (!recording.getAndSet(false)) return ShortArray(0)
        // Before joining the reader: a start thread waiting in awaitFirstSample() must not
        // block out the timeout when this stop is what cancelled it.
        sampleState = STOPPED
        readerThread?.let { thread ->
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(STOP_JOIN_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        readerThread = null
        val dataLine = line
        lineGeneration++
        // Review N6: a line the device lost mid-recording is never kept ready — a dead line must
        // not be cached or reused — so the close path applies even if keep-ready was requested.
        val keepReady = keepReadySeconds > 0 && dataLine != null && !deviceLost
        deviceLost = false
        if (keepReady) {
            runCatching { dataLine.stop() }
            scheduleKeepReadyClose(dataLine, keepReadySeconds)
        } else {
            runCatching { dataLine?.stop(); dataLine?.close() }
            line = null
        }
        val bytes = buffer.toByteArray()
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shorts.remaining()).also { shorts.get(it) }
    }

    /**
     * Releases the audio resources unconditionally (plugin dispose, review #6): cancels any
     * pending keep-ready close, shuts its executor down and closes the line even if a
     * keep-ready window is still running.
     */
    fun release() {
        keepReadyTask?.cancel(false)
        keepReadyExecutor.shutdownNow()
        synchronized(this) {
            // Review N6: end the capture before the line goes — a reader mid-loop must not
            // misread our own teardown as a device loss (its loss guard checks [recording]).
            recording.set(false)
            lineGeneration++
            runCatching { line?.close() }
            line = null
        }
    }

    /**
     * The result of [openLine]: the line, and whether it is a previously owned one ([reused] —
     * which may hold stale buffered data, review N5) or a freshly resolved one.
     */
    private data class OpenedLine(val line: TargetDataLine, val reused: Boolean)

    /**
     * Opens a capture line for [mixerName] (null = system default), reusing the previously
     * resolved line when it is still the right device and still openable.
     */
    private fun openLine(mixerName: String?): OpenedLine {
        val cached = line
        if (cached != null && lineDevice == mixerName) {
            if (cached.isOpen) {
                // The keep-ready window: the line is open (but stopped) for this device.
                return OpenedLine(cached, reused = true)
            }
            try {
                val openBeginMs = System.currentTimeMillis()
                cached.open(format, bufferBytes)
                // Review N7 + the mic timing log (review #11, start delay): numbers only —
                // the open phase, and whether a driver rounds or ignores the buffer size.
                log.debug("kept line re-opened: open ${System.currentTimeMillis() - openBeginMs} ms, " +
                    "requested buffer ${bufferBytes} bytes, driver reports ${cached.bufferSize} bytes")
                return OpenedLine(cached, reused = true)
            } catch (t: Throwable) {
                // The device went away (unplugged?) or the driver wedged: drop the cache and
                // resolve fresh — the caller's fallback logic still applies.
                log.warn("cached microphone line unusable, resolving fresh", t)
                runCatching { cached.close() }
                line = null
            }
        }
        return OpenedLine(resolveFreshLine(mixerName), reused = false)
    }

    /** The fresh path: [lineFactoryForTest] (tests) or [MicrophoneResolver.resolve], then open. */
    private fun resolveFreshLine(mixerName: String?): TargetDataLine {
        val factory = lineFactoryForTest
        val dataLine = if (factory != null) factory(mixerName, format) else MicrophoneResolver.resolve(mixerName, format)
        val openBeginMs = System.currentTimeMillis()
        dataLine.open(format, bufferBytes)
        // Review N7 + the mic timing log (review #11, start delay): numbers only — the open
        // phase duration, and whether a driver rounds or ignores the requested buffer size.
        log.debug("capture line opened: open ${System.currentTimeMillis() - openBeginMs} ms, " +
            "requested buffer ${bufferBytes} bytes, driver reports ${dataLine.bufferSize} bytes")
        line = dataLine
        lineDevice = mixerName
        return dataLine
    }

    /**
     * Review N5, fresh line: the flush is a no-op in principle (a fresh line has no buffered
     * data) — never fail the start over it.
     */
    private fun bestEffortFlush(dataLine: TargetDataLine) {
        runCatching { dataLine.flush() }.onFailure {
            log.debug("flush of a fresh line failed; ignoring it (a fresh line has no buffered data)", it)
        }
    }

    /**
     * Review N5, keep-ready line: it was only stopped, not closed, and per the Java Sound
     * contract a stopped line retains its buffered data. Flush it and verify it is empty; if
     * the flush throws or data remains, the line cannot be trusted to be clean. Only byte
     * counts are logged — never audio content (AGENTS.md rule 8).
     */
    private fun cleanReusedLine(dataLine: TargetDataLine): Boolean {
        val staleBytes = runCatching { dataLine.available() }.getOrNull()
        if (!runCatching { dataLine.flush() }.isSuccess) {
            log.debug("flush of the keep-ready line failed; replacing it with a fresh line")
            return false
        }
        val remainingBytes = runCatching { dataLine.available() }.getOrNull()
        if (remainingBytes == null) {
            log.debug("cannot verify the keep-ready line is empty after flush; replacing it")
            return false
        }
        if (remainingBytes > 0) {
            log.debug("$remainingBytes bytes survived the flush of the keep-ready line; replacing it")
            return false
        }
        if ((staleBytes ?: 0) > 0) {
            log.debug("discarded $staleBytes stale bytes from the keep-ready line")
        }
        return true
    }

    private fun scheduleKeepReadyClose(dataLine: TargetDataLine, seconds: Long) {
        keepReadyTask?.cancel(false)
        val generation = lineGeneration
        keepReadyTask = keepReadyExecutor.schedule({
            // Only close if no start/stop has touched the line since (the generation covers
            // the race where this task is already running while a new start() begins).
            synchronized(this) {
                if (lineGeneration == generation && line === dataLine) {
                    runCatching { dataLine.close() }
                    line = null
                }
            }
        }, seconds, TimeUnit.SECONDS)
    }

    private fun readLoop(dataLine: TargetDataLine) {
        val chunk = ByteArray(CHUNK_FRAMES * format.frameSize)
        val maxBytes = (maxDurationSeconds * format.sampleRate * format.frameSize).toInt()
        var lastPositiveReadMs = clockMs()
        try {
            while (recording.get()) {
                val read = try {
                    dataLine.read(chunk, 0, chunk.size)
                } catch (t: Exception) {
                    // The OS took the line away mid-read (an unplug, a profile switch): end the
                    // capture as a device loss rather than letting the reader thread die silently
                    // (review N6). A normal stop()/release() has already cleared [recording], so
                    // it is never misread as a loss.
                    if (recording.get()) {
                        deviceLost = true
                        onDeviceLost?.invoke()
                    }
                    break
                }
                if (read > 0) {
                    lastPositiveReadMs = clockMs()
                    if (sampleState == AWAITING) sampleState = DATA
                    buffer.write(chunk, 0, read)
                    if (buffer.size() >= maxBytes) {
                        onMaxDurationReached?.invoke()
                        break
                    }
                } else {
                    // Review N6: a non-positive read on a live line. A healthy mic underflows
                    // (read < 0) and that is normal — sleep (which also kills the CPU spin a bare
                    // retry caused) and retry. But a line the OS has stopped or closed keeps
                    // returning 0 (or -1) without blocking, so also watch the line's open state
                    // and the time since the last positive read: once a confirmed live capture —
                    // audio already flowing, so before that awaitFirstSample's timeout owns the
                    // failure — has been silent too long, the device is gone: end the capture and
                    // let the session surface PROBLEM.
                    if (recording.get()) {
                        if (!dataLine.isOpen) {
                            deviceLost = true
                            onDeviceLost?.invoke()
                            break
                        }
                        if (sampleState == DATA && clockMs() - lastPositiveReadMs >= noDataLossMs) {
                            deviceLost = true
                            onDeviceLost?.invoke()
                            break
                        }
                    }
                    Thread.sleep(UNDERFLOW_SLEEP_MS)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
