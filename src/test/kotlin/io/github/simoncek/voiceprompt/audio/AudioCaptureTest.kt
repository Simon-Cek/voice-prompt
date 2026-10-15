package io.github.simoncek.voiceprompt.audio

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the "ready" contract (review #11, item 1): [AudioCapture.awaitFirstSample]
 * reports whether audio actually arrived, without needing a real audio device. The capture's
 * [AudioCapture.sampleState] is driven directly — DATA means the read loop delivered audio,
 * STOPPED means the capture ended before audio arrived, AWAITING means it is still waiting.
 */
class AudioCaptureTest {

    private lateinit var capture: AudioCapture

    @Before
    fun setUp() {
        capture = AudioCapture()
    }

    @After
    fun tearDown() {
        // Release the daemon executor the constructor starts (AGENTS.md rule 2).
        capture.release()
    }

    @Test
    fun `awaitFirstSample reports true once audio has arrived`() {
        capture.sampleState = AudioCapture.DATA
        assertTrue(capture.awaitFirstSample(100))
    }

    @Test
    fun `awaitFirstSample reports false when the capture was stopped before audio`() {
        capture.sampleState = AudioCapture.STOPPED
        assertFalse(capture.awaitFirstSample(100))
    }

    @Test
    fun `awaitFirstSample times out to false while still awaiting`() {
        // AWAITING is the initial state; with no audio the wait must give up after the deadline.
        assertFalse(capture.awaitFirstSample(80))
    }
}
