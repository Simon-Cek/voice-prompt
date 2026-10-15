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

        // Review #11, item 6: request ~100 ms of line buffer, not a whole second — a big
        // buffer delays the first read() that "ready" (awaitFirstSample) waits for.
        private val BUFFER_FRAMES = SAMPLE_RATE / 10

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

    // One daemon thread for the deferred "close the kept-ready line" — never a new thread
    // per event (the review's #10 rule for Beeps applies here too), and daemon so it cannot
    // hold the IDE shutdown or the plugin class loader (review #6).
    private val keepReadyExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "copilot-voice-mic-keepready").apply { isDaemon = true }
    }
    @Volatile private var keepReadyTask: ScheduledFuture<*>? = null

    /** Invoked from the capture thread when [maxDurationSeconds] of audio has been collected. */
    var onMaxDurationReached: (() -> Unit)? = null

    fun isRecording(): Boolean = recording.get()

    @Synchronized
    fun start(mixerName: String? = null) {
        if (!recording.compareAndSet(false, true)) {
            throw IllegalStateException("Already recording")
        }
        keepReadyTask?.cancel(false)
        lineGeneration++
        val dataLine = try {
            openLine(mixerName)
        } catch (t: Throwable) {
            recording.set(false)
            throw t
        }
        buffer.reset()
        sampleState = AWAITING
        try {
            dataLine.start()
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
                DATA -> return true
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
        if (keepReadySeconds > 0 && dataLine != null) {
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
            lineGeneration++
            runCatching { line?.close() }
            line = null
        }
    }

    /**
     * Opens a capture line for [mixerName] (null = system default), reusing the previously
     * resolved line when it is still the right device and still openable.
     */
    private fun openLine(mixerName: String?): TargetDataLine {
        val cached = line
        if (cached != null && lineDevice == mixerName) {
            if (cached.isOpen) {
                // The keep-ready window: the line is open (but stopped) for this device.
                return cached
            }
            try {
                cached.open(format, BUFFER_FRAMES)
                return cached
            } catch (t: Throwable) {
                // The device went away (unplugged?) or the driver wedged: drop the cache and
                // resolve fresh — the caller's fallback logic still applies.
                log.warn("cached microphone line unusable, resolving fresh", t)
                runCatching { cached.close() }
                line = null
            }
        }
        val dataLine = MicrophoneResolver.resolve(mixerName, format)
        dataLine.open(format, BUFFER_FRAMES)
        line = dataLine
        lineDevice = mixerName
        return dataLine
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
        try {
            while (recording.get()) {
                val read = dataLine.read(chunk, 0, chunk.size)
                if (read > 0) {
                    if (sampleState == AWAITING) sampleState = DATA
                    buffer.write(chunk, 0, read)
                    if (buffer.size() >= maxBytes) {
                        onMaxDurationReached?.invoke()
                        break
                    }
                } else if (read < 0) {
                    Thread.sleep(UNDERFLOW_SLEEP_MS)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
