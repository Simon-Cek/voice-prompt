package io.github.simoncek.voiceprompt.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review N7: the line buffer was requested in the wrong unit — the old `BUFFER_FRAMES`
 * (1,600 = SAMPLE_RATE / 10, a frame count) was passed to open(format, size), which takes
 * BYTES: that is 50 ms of buffer, exactly one 800-frame read chunk, so a scheduling hiccup
 * (GC, a saturated core) could overrun it and drop audio silently. The size is now derived
 * from the capture format in bytes (about 250 ms, aligned to whole frames) and both open
 * sites — the fresh line and the re-opened keep-ready line — must request it.
 *
 * The tests read the requested size straight off the fake line's open(format, size) call —
 * no real audio device, no beeps, no waits.
 */
class AudioCaptureLineSizeTest {

    private lateinit var capture: AudioCapture
    private lateinit var fake: FakeDeviceLossLine

    @Before
    fun setUp() {
        capture = AudioCapture()
        fake = FakeDeviceLossLine()
        capture.lineFactoryForTest = { _, _ -> fake }
    }

    @After
    fun tearDown() {
        capture.stop(0)
        capture.release()
        capture.lineFactoryForTest = null
    }

    /**
     * The review N7 contract: at least 200 ms of audio in BYTES, a whole number of frames,
     * and at least 4 read chunks (800 frames each — CHUNK_FRAMES in [AudioCapture]).
     */
    private fun assertSized(size: Int, what: String) {
        val frameSize = AudioCapture.DEFAULT_FORMAT.frameSize
        val minBytes = AudioCapture.SAMPLE_RATE * 200 / 1000 * frameSize
        val minChunks = 4 * 800 * frameSize
        assertTrue("$what requested $size bytes, below the 200 ms minimum ($minBytes)", size >= minBytes)
        assertTrue("$what requested $size bytes, below 4 read chunks ($minChunks)", size >= minChunks)
        assertEquals(
            "$what requested $size bytes, which is not a whole number of frames ($frameSize)",
            0,
            size % frameSize,
        )
    }

    @Test
    fun `a fresh capture line is opened with the byte-sized buffer`() {
        capture.start(null)
        assertEquals("the fresh line must be opened exactly once", 1, fake.openedSizes.size)
        assertSized(fake.openedSizes.first(), "the fresh line's open")
    }

    @Test
    fun `a re-opened keep-ready line is opened with the byte-sized buffer`() {
        capture.start(null)
        capture.stop(5) // keep-ready: the line is stopped, not closed, and stays cached
        fake.close()    // the keep-ready window expires: the driver drops the stopped line
        assertFalse("the keep-ready line must be closed before the re-open", fake.isOpen())

        // The cached line is closed, so the next start re-opens it (not re-resolves it).
        capture.start(null)
        assertEquals("the re-opened line must be opened exactly once more", 2, fake.openedSizes.size)
        assertSized(fake.openedSizes[1], "the re-opened line's open")
    }
}
