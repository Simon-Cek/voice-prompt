package io.github.simoncek.voiceprompt.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

        val DEFAULT_FORMAT: AudioFormat by lazy {
            AudioFormat(SAMPLE_RATE.toFloat(), BITS_PER_SAMPLE, CHANNELS, true, false)
        }
    }

    private val buffer = ByteArrayOutputStream()
    private val recording = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var line: TargetDataLine? = null

    /** Invoked from the capture thread when [maxDurationSeconds] of audio has been collected. */
    var onMaxDurationReached: (() -> Unit)? = null

    fun isRecording(): Boolean = recording.get()

    @Synchronized
    fun start(mixerName: String? = null) {
        if (!recording.compareAndSet(false, true)) {
            throw IllegalStateException("Already recording")
        }
        val dataLine = try {
            MicrophoneResolver.resolve(mixerName, format)
        } catch (t: Throwable) {
            recording.set(false)
            throw t
        }
        buffer.reset()
        line = dataLine
        try {
            dataLine.open(format, format.sampleRate.toInt())
            dataLine.start()
        } catch (t: Throwable) {
            runCatching { dataLine.close() }
            recording.set(false)
            throw t
        }
        val thread = Thread({ readLoop(dataLine) }, "copilot-voice-capture")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    @Synchronized
    fun stop(): ShortArray {
        if (!recording.getAndSet(false)) return ShortArray(0)
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
        line?.let { dataLine -> runCatching { dataLine.stop(); dataLine.close() } }
        line = null
        val bytes = buffer.toByteArray()
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shorts.remaining()).also { shorts.get(it) }
    }

    private fun readLoop(dataLine: TargetDataLine) {
        val chunk = ByteArray(CHUNK_FRAMES * format.frameSize)
        val maxBytes = (maxDurationSeconds * format.sampleRate * format.frameSize).toInt()
        try {
            while (recording.get()) {
                val read = dataLine.read(chunk, 0, chunk.size)
                if (read > 0) {
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
