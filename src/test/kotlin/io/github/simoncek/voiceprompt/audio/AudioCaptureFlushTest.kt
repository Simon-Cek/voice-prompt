package io.github.simoncek.voiceprompt.audio

import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review N5: a keep-ready line was only stopped, not closed — and per the Java Sound
 * contract a stopped [TargetDataLine] retains its buffered data, so the first read of the
 * next clip could replay the tail of the previous one (and awaitFirstSample would report
 * "ready" on that stale audio).
 *
 * The tests drive [AudioCapture.start] / [AudioCapture.stop] against a fake line that models
 * that contract (stop() retains data, only flush() discards it, read() never blocks),
 * injected through the [AudioCapture.lineFactoryForTest] seam — no real audio device, no
 * beeps, no sleeps; the only wait is [AudioCapture.awaitFirstSample]'s bounded timeout.
 */
class AudioCaptureFlushTest {

    private lateinit var capture: AudioCapture

    /** A sample value that cannot be confused with the 0x0101 fed for the "real" clip audio. */
    private val staleValue = 0x7B7B.toShort()

    @Before
    fun setUp() {
        capture = AudioCapture()
    }

    @After
    fun tearDown() {
        // End any live capture (joins the reader thread) and release the daemon executor
        // (AGENTS.md rule 2) so no reader thread outlives the test.
        capture.stop(0)
        capture.release()
        capture.lineFactoryForTest = null
    }

    @Test
    fun `a fresh start flushes the line before it starts`() {
        val fake = FakeTargetDataLine()
        capture.lineFactoryForTest = { _, _ -> fake }
        try {
            capture.start(null)
            val calls = fake.calls
            val flush = calls.indexOf("flush")
            val start = calls.indexOf("start")
            assertTrue("start() must flush a fresh line (a no-op in principle)", flush >= 0)
            assertTrue("the flush must come before the line start (review N5)", flush < start)
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `a reused keep-ready line is flushed before it starts again`() {
        val fake = FakeTargetDataLine()
        capture.lineFactoryForTest = { _, _ -> fake }
        try {
            // First clip, kept ready: the line is stopped but stays open.
            capture.start(null)
            fake.feed(800)
            assertTrue("the first clip must deliver audio", capture.awaitFirstSample(2_000))
            assertEquals("the first clip must be captured", 800, capture.stop(5).size)

            // The next start reuses the keep-ready line: it must be flushed before start.
            capture.start(null)
            val calls = fake.calls
            assertEquals("both starts must run", 2, calls.count { it == "start" })
            assertEquals("each start must flush the line (review N5)", 2, calls.count { it == "flush" })
            assertTrue(
                "the second flush must come before the second start (review N5)",
                calls.lastIndexOf("flush") < calls.lastIndexOf("start"),
            )
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `a reused keep-ready line does not replay the previous clip`() {
        val fake = FakeTargetDataLine()
        capture.lineFactoryForTest = { _, _ -> fake }
        try {
            // First clip.
            capture.start(null)
            fake.feed(800)
            assertTrue("the first clip must deliver audio", capture.awaitFirstSample(2_000))
            assertEquals("the first clip must be captured", 800, capture.stop(5).size)

            // The line was kept ready: per the Java Sound contract it still holds buffered
            // data — here the tail of the previous clip, with a distinctive sample value.
            fake.feed(800, staleValue)
            assertEquals(
                "a stopped line must retain its buffered data (Java Sound contract)",
                1600,
                fake.available(),
            )

            // The next start reuses the line. The stale tail must not leak into the new clip.
            capture.start(null)
            // Bounded wait (the only one the tests use): lets the read loop perform its
            // first read — which, once the line is clean, delivers nothing.
            capture.awaitFirstSample(200)
            val clip = capture.stop(0)
            assertFalse(
                "the stale tail of the previous clip must not be replayed (review N5)",
                clip.any { it == staleValue },
            )
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `a keep-ready line that cannot be verified clean is replaced with a fresh one`() {
        // Its flush() is a no-op, so available() stays > 0 and it cannot be trusted.
        val bad = FakeTargetDataLine(noOpFlush = true)
        val fresh = FakeTargetDataLine()
        var factoryCalls = 0
        capture.lineFactoryForTest = { _, _ -> if (factoryCalls++ == 0) bad else fresh }
        try {
            // First clip on the untrustworthy line, kept ready.
            capture.start(null)
            bad.feed(800)
            assertTrue("the first clip must deliver audio", capture.awaitFirstSample(2_000))
            assertEquals("the first clip must be captured", 800, capture.stop(5).size)

            // The line still holds the tail (its flush is a no-op).
            bad.feed(800, staleValue)
            assertTrue("the untrustworthy line must still hold its data", bad.available() > 0)

            // The next start must discard it and take a fresh line from the factory.
            capture.start(null)
            assertTrue("the discarded line must be closed", "close" in bad.calls)
            assertTrue("the fresh line must be the one started", "start" in fresh.calls)
            assertTrue("the capture must be live on the fresh line", capture.isRecording())

            capture.awaitFirstSample(200)
            val clip = capture.stop(0)
            assertFalse(
                "the stale tail must not be replayed through the untrustworthy line (review N5)",
                clip.any { it == staleValue },
            )
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `a fresh line whose flush fails still starts and records`() {
        val fake = FakeTargetDataLine(flushFailure = LineUnavailableException("flush failed"))
        capture.lineFactoryForTest = { _, _ -> fake }
        try {
            // The flush is a no-op on a fresh line — its failure must not fail the start.
            capture.start(null)
            assertTrue("a fresh line whose flush throws must still start", capture.isRecording())

            fake.feed(800)
            assertTrue("audio must still be captured", capture.awaitFirstSample(2_000))
            assertEquals("the clip must be captured", 800, capture.stop(0).size)
        } finally {
            capture.stop(0)
        }
    }
}

/**
 * A [TargetDataLine] that models the Java Sound contract around the keep-ready window
 * (review N5): audio delivered by the fake "driver" ([feed]) accumulates in the line's
 * buffer; [stop] retains it (that is the whole of the N5 bug); only [flush] discards it.
 * [read] never blocks — it returns what is buffered or reports underflow (-1) — so the
 * tests have no timing dependence beyond [AudioCapture.awaitFirstSample]'s bounded wait.
 * [internal] (not private) so [MicTimingLogTest] drives the same fake.
 */
internal class FakeTargetDataLine(
    private val noOpFlush: Boolean = false,
    private val flushFailure: Throwable? = null,
) : TargetDataLine {

    val calls = CopyOnWriteArrayList<String>()
    private val buffered = ArrayDeque<ByteArray>()
    @Volatile private var opened = false
    @Volatile private var running = false

    /** Simulate the microphone delivering [frames] of audio into the line's buffer. */
    fun feed(frames: Int, value: Short = 0x0101) {
        val bytes = ByteArray(frames * 2)
        var i = 0
        while (i < bytes.size) {
            bytes[i] = (value.toInt() and 0xFF).toByte()           // low byte first: little-endian
            bytes[i + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        synchronized(buffered) { buffered.addLast(bytes) }
    }

    override fun available(): Int = synchronized(buffered) { buffered.sumOf { it.size } }

    override fun open(format: AudioFormat?, bufferSize: Int) {
        calls.add("open")
        opened = true
    }

    override fun open(format: AudioFormat?) {
        open(format, 0)
    }

    override fun close() {
        calls.add("close")
        opened = false
        running = false
    }

    override fun start() {
        calls.add("start")
        running = true
    }

    override fun stop() {
        calls.add("stop")
        running = false
        // The Java Sound contract at the heart of N5: a stopped line RETAINS its buffered data.
    }

    override fun flush() {
        calls.add("flush")
        flushFailure?.let { throw it }
        if (!noOpFlush) {
            synchronized(buffered) { buffered.clear() }
        }
    }

    override fun read(buffer: ByteArray?, offset: Int, numBytes: Int): Int {
        calls.add("read")
        val chunk: ByteArray
        val take: Int
        synchronized(buffered) {
            val first = buffered.firstOrNull() ?: return -1
            take = minOf(first.size, numBytes)
            chunk = first.copyOfRange(0, take)
            if (take < first.size) {
                buffered[0] = first.copyOfRange(take, first.size)
            } else {
                buffered.removeFirst()
            }
        }
        System.arraycopy(chunk, 0, buffer!!, offset, take)
        return take
    }

    override fun getFormat(): AudioFormat = AudioCapture.DEFAULT_FORMAT

    override fun isOpen(): Boolean = opened

    override fun isRunning(): Boolean = running

    override fun isControlSupported(controlType: Control.Type?): Boolean = false

    override fun getControl(controlType: Control.Type?): Control? = null

    // The rest of the Line/DataLine contract: the fake is never used for these.
    override fun open() {
        open(AudioCapture.DEFAULT_FORMAT)
    }

    override fun drain() {
        // No-op: the fake "driver" only delivers what the test fed.
    }

    override fun isActive(): Boolean = running

    override fun getBufferSize(): Int = 1600

    override fun getFramePosition(): Int = 0

    override fun getLongFramePosition(): Long = 0L

    override fun getMicrosecondPosition(): Long = 0L

    override fun getLevel(): Float = Float.NEGATIVE_INFINITY

    override fun getLineInfo(): Line.Info = Line.Info(TargetDataLine::class.java)

    override fun getControls(): Array<Control> = emptyArray()

    override fun addLineListener(listener: LineListener?) {
        // No-op: the tests do not observe line events.
    }

    override fun removeLineListener(listener: LineListener?) {
        // No-op.
    }
}
