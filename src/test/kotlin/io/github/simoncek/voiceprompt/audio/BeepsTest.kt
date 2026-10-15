package io.github.simoncek.voiceprompt.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeepsTest {

    @Test
    fun `tones are lead silence plus the tone at the configured rate`() {
        val pcm = Beeps.tone(880.0, 120, leadSilenceMs = 100)
        val frames = 44_100 * 220 / 1000 // 100 ms lead + 120 ms tone
        assertEquals(frames * 2, pcm.size)
    }

    @Test
    fun `tones lead with the requested silence`() {
        val pcm = Beeps.tone(880.0, 120, leadSilenceMs = 100)
        val leadFrames = 44_100 * 100 / 1000 // 100 ms at 44.1 kHz
        for (i in 0 until leadFrames) {
            assertEquals(0.toShort(), shortAt(pcm, i))
        }
    }

    @Test
    fun `tones fade in and out so they do not click`() {
        val pcm = Beeps.tone(880.0, 120, leadSilenceMs = 0)
        assertEquals(0.toShort(), shortAt(pcm, 0)) // first frame: fade factor 0
        val last = pcm.size / 2 - 1
        assertTrue("last frame should be near the fade endpoint, got ${shortAt(pcm, last)}", abs(shortAt(pcm, last).toInt()) < 64)
    }

    @Test
    fun `the tone actually rings`() {
        val pcm = Beeps.tone(880.0, 120, leadSilenceMs = 0)
        var maxAbs = 0
        for (frame in 0 until pcm.size / 2) {
            maxAbs = maxOf(maxAbs, abs(shortAt(pcm, frame).toInt()))
        }
        assertTrue("expected a clearly audible tone, got max=$maxAbs", maxAbs > 2000)
    }

    private fun shortAt(pcm: ByteArray, frame: Int): Short {
        val o = frame * 2
        return ((pcm[o].toInt() and 0xFF) or (pcm[o + 1].toInt() shl 8)).toShort()
    }
}
