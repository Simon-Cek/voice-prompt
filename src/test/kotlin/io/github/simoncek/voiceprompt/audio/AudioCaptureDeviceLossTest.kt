package io.github.simoncek.voiceprompt.audio

import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.LineUnavailableException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Review N6: a microphone that stopped delivering mid-recording used to be recorded forever —
 * the read loop retried non-positive reads until the byte-based 60 s cap (or the user) ended
 * it, spinning the CPU on 0-returns. The read loop now reports the loss via
 * [AudioCapture.onDeviceLost] once a confirmed live capture has been silent past
 * [AudioCapture.noDataLossMs], the line is no longer open, or a read throws — and a normal
 * stop()/release() must never be misreported as one.
 *
 * The tests drive [AudioCapture] against [FakeDeviceLossLine] through the
 * [AudioCapture.lineFactoryForTest], [AudioCapture.clockMsForTest] and [AudioCapture.noDataLossMs]
 * seams — no real audio device, no beeps. The 1_000 fake-ms loss window is crossed or not in
 * fake time ([FakeDeviceLossLine.tickMs] per read), so the tests are deterministic beyond the
 * bounded waits they perform.
 */
class AudioCaptureDeviceLossTest {

    private lateinit var capture: AudioCapture
    private val lossFired = AtomicBoolean(false)

    @Before
    fun setUp() {
        capture = AudioCapture()
        lossFired.set(false)
        capture.noDataLossMs = 1_000 // the loss window, in fake ms (the fake clock ticks per read)
        capture.onDeviceLost = { lossFired.set(true) }
    }

    @After
    fun tearDown() {
        // End any live capture (joins the reader thread) and release the daemon executor
        // (AGENTS.md rule 2) so no reader thread outlives the test.
        capture.stop(0)
        capture.release()
        capture.lineFactoryForTest = null
        capture.clockMsForTest = null
        capture.onDeviceLost = null
    }

    /** Bounded wait — the only wait the tests use (like awaitFirstSample's timeout). */
    private fun await(condition: () -> Boolean, what: String, timeoutMs: Long = 3_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    /** One fake 50 ms per read: 20 no-data reads cross the 1_000 ms window. */
    private fun drive(fake: FakeDeviceLossLine) {
        fake.tickMs = 50
        capture.lineFactoryForTest = { _, _ -> fake }
        capture.clockMsForTest = { fake.clock }
    }

    @Test
    fun `a live capture that goes silent is reported as a device loss`() {
        val fake = FakeDeviceLossLine()
        drive(fake)
        try {
            capture.start(null)
            fake.feed(800)
            await({ fake.positiveReads >= 1 }, "the first chunk of audio")

            // The device stops delivering: reads return 0, the fake clock keeps ticking.
            fake.silent = true
            await({ lossFired.get() }, "the device-loss report")

            val clip = capture.stop(0)
            assertEquals("audio captured before the silence must be kept", 800, clip.size)
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `a line the OS closes is reported as a device loss even before the first sample`() {
        val fake = FakeDeviceLossLine()
        drive(fake)
        try {
            capture.start(null)
            // No audio at all: the capture is still AWAITING, so the silence-window check (which
            // is gated on DATA) must not fire — only the closed line may.
            await({ fake.readCount >= 2 }, "the read loop to be live")
            fake.close() // the device goes away mid-capture
            await({ lossFired.get() }, "the device-loss report")

            // The read loop must have ended the capture — no more reads after the loss.
            val readsAtLoss = fake.readCount
            Thread.sleep(100) // several 10 ms read cycles if the loop were still alive
            assertEquals("the read loop must end the capture on a loss", readsAtLoss, fake.readCount)
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `a read that throws is reported as a device loss, not a silent thread death`() {
        val fake = FakeDeviceLossLine()
        drive(fake)
        try {
            capture.start(null)
            fake.feed(800)
            await({ fake.positiveReads >= 1 }, "the first chunk of audio")

            fake.throwNext = LineUnavailableException("the device is gone")
            await({ lossFired.get() }, "the device-loss report")

            val clip = capture.stop(0)
            assertEquals("audio captured before the failure must be kept", 800, clip.size)
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `a sub-threshold stall does not report a loss, and audio resets the window`() {
        val fake = FakeDeviceLossLine()
        drive(fake)
        try {
            capture.start(null)
            fake.feed(800)
            await({ fake.positiveReads >= 1 }, "the first chunk of audio")
            var readsAfter = fake.readCount

            // A stall below the 1_000 ms window (12 reads x 50 fake ms = 600), then audio.
            fake.silent = true
            await({ fake.readCount >= readsAfter + 12 }, "the first stall (600 fake ms)")
            fake.feed(800)
            fake.silent = false
            await({ fake.positiveReads >= 2 }, "the second chunk of audio")
            readsAfter = fake.readCount

            // A second stall, then audio: each positive read must reset the window, or the
            // accumulated silence would report a loss.
            fake.silent = true
            await({ fake.readCount >= readsAfter + 12 }, "the second stall (600 fake ms)")
            fake.feed(800)
            fake.silent = false
            await({ fake.positiveReads >= 3 }, "the third chunk of audio")

            val clip = capture.stop(0)
            assertFalse("sub-threshold stalls must not report a loss (review N6)", lossFired.get())
            assertEquals("all three chunks must be captured", 2400, clip.size)
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `underflow below the window between chunks does not report a loss`() {
        val fake = FakeDeviceLossLine()
        drive(fake)
        try {
            capture.start(null)
            fake.feed(800)
            await({ fake.positiveReads >= 1 }, "the first chunk of audio")
            var readsAfter = fake.readCount

            // A healthy mic underflows (read < 0) while it catches up: 12 reads = 600 fake ms
            // < 1_000. Underflow is normal and must not be reported as a loss.
            await({ fake.readCount >= readsAfter + 12 }, "12 underflow reads")
            fake.feed(800)
            await({ fake.positiveReads >= 2 }, "the second chunk of audio")
            readsAfter = fake.readCount

            // ... and underflows again below the window — a positive read, not silence, ends it.
            await({ fake.readCount >= readsAfter + 12 }, "another 12 underflow reads")

            val clip = capture.stop(0)
            assertFalse("underflow below the window must not report a loss (review N6)", lossFired.get())
            assertEquals("both chunks must be captured", 1600, clip.size)
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `silence longer than the window before the first sample does not report a loss`() {
        val fake = FakeDeviceLossLine()
        drive(fake)
        try {
            capture.start(null)
            // No audio at all: 30 reads = 1_500 fake ms of silence — past the 1_000 ms window —
            // but the capture is still AWAITING, so the loss window must not be active (before
            // the first sample, awaitFirstSample's timeout owns the failure).
            await({ fake.readCount >= 30 }, "30 silent reads (1_500 fake ms)")
            assertFalse("silence before the first sample must not report a loss (review N6)", lossFired.get())
            assertTrue("the capture must still be live", capture.isRecording())

            val clip = capture.stop(0)
            assertEquals("no audio was delivered, so nothing is captured", 0, clip.size)
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `a normal stop does not report a device loss`() {
        val fake = FakeDeviceLossLine()
        drive(fake)
        try {
            capture.start(null)
            fake.feed(800)
            await({ fake.positiveReads >= 1 }, "the first chunk of audio")

            val clip = capture.stop(0)
            assertFalse("a normal stop must not be reported as a loss (review N6)", lossFired.get())
            assertEquals("the captured audio must be kept", 800, clip.size)
        } finally {
            capture.stop(0)
        }
    }

    @Test
    fun `a normal release does not report a device loss`() {
        val fake = FakeDeviceLossLine()
        drive(fake)
        capture.start(null)
        fake.feed(800)
        await({ fake.positiveReads >= 1 }, "the first chunk of audio")

        // Dispose ends the capture and closes the line — the reader's loss guard reads the
        // recording flag (which release clears), so it cannot report a loss for our own teardown.
        capture.release()
        Thread.sleep(100) // let the reader thread finish its current cycle
        assertFalse("a normal release must not be reported as a loss (review N6)", lossFired.get())
    }

    @Test
    fun `a lost line is never kept ready, and the next start takes a fresh line`() {
        val bad = FakeDeviceLossLine()
        val fresh = FakeDeviceLossLine()
        bad.tickMs = 50
        var factoryCalls = 0
        capture.lineFactoryForTest = { _, _ -> if (factoryCalls++ == 0) bad else fresh }
        capture.clockMsForTest = { bad.clock }
        try {
            capture.start(null) // the first line from the factory — the one that will be lost
            bad.feed(800)
            await({ bad.positiveReads >= 1 }, "the first chunk of audio")

            bad.silent = true
            await({ lossFired.get() }, "the device-loss report")

            // Keep-ready is requested, but a line the device lost is closed, not cached.
            capture.stop(5)
            assertTrue("the lost line must be closed, not kept ready (review N6)", "close" in bad.calls)

            // The next start must take a fresh line from the factory.
            capture.start(null)
            assertEquals("the factory must be consulted again for the next line", 2, factoryCalls)
            assertTrue("the fresh line must be the one started", "start" in fresh.calls)
            assertTrue("the capture must be live on the fresh line", capture.isRecording())
        } finally {
            capture.stop(0)
        }
    }
}
