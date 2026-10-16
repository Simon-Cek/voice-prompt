package io.github.simoncek.voiceprompt.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechGateTest {

    @Test
    fun `duration is sample count scaled to milliseconds`() {
        assertEquals(400L, SpeechGate.durationMs(6400, 16000))
        assertEquals(200L, SpeechGate.durationMs(3200, 16000))
        assertEquals(1000L, SpeechGate.durationMs(16000, 16000))
    }

    @Test
    fun `the minimum duration matches the review recommendation`() {
        assertTrue(SpeechGate.MIN_DURATION_MS in 300L..600L)
    }

    @Test
    fun `rms of silence is zero`() {
        assertEquals(0f, SpeechGate.rms(ShortArray(16000)))
        assertEquals(0f, SpeechGate.rms(ShortArray(0)))
    }

    @Test
    fun `rms of a constant signal is its amplitude`() {
        assertEquals(2000f, SpeechGate.rms(ShortArray(16000) { 2000.toShort() }), 1f)
    }

    @Test
    fun `rms of a sine is amplitude over root two`() {
        val samples = ShortArray(16000) { i -> (10000.0 * sin(2.0 * PI * 440.0 * i / 16000)).toInt().toShort() }
        assertEquals(10000.0 / Math.sqrt(2.0), SpeechGate.rms(samples).toDouble(), 1.0)
    }

    @Test
    fun `the floor separates silence from speech`() {
        assertTrue("digital silence must be below the floor", SpeechGate.rms(ShortArray(16000) { 20.toShort() }) < SpeechGate.MIN_RMS)
        assertTrue("ambient-level noise must be below the floor", SpeechGate.rms(ShortArray(16000) { 60.toShort() }) < SpeechGate.MIN_RMS)
        assertTrue("normal speech level must be above the floor", SpeechGate.rms(ShortArray(16000) { 2500.toShort() }) > SpeechGate.MIN_RMS)
    }

    // Review N17: the gate must not discard a clip because its whole-clip average is diluted
    // below the floor by pauses and a quiet mic — the level is measured per 50 ms window.

    @Test
    fun `a clip whose average is diluted below the floor still passes the gate`() {
        // 10 s of ambient noise (40) with one 50 ms speech burst (1200): the whole-clip RMS is
        // ~94, below the floor — the pre-N17 gate discarded this clip — while the burst's own
        // window sits well above it.
        val sampleRate = 16000
        val clip = ShortArray(10 * sampleRate) { 40.toShort() }
        val window = (SpeechGate.WINDOW_MS * sampleRate / 1000).toInt()
        for (i in 0 until window) clip[window + i] = 1200.toShort()

        assertTrue(
            "the whole-clip average must be below the floor (the pre-N17 gate would discard this clip)",
            SpeechGate.rms(clip) < SpeechGate.MIN_RMS,
        )
        assertTrue(
            "review N17: the highest 50 ms window must carry the clip above the floor",
            SpeechGate.speechLevel(clip, sampleRate) >= SpeechGate.MIN_RMS,
        )
    }

    @Test
    fun `speechLevel is the highest window rms`() {
        // 2 s of 40 with one 50 ms window at 1200: every window but one measures 40, one 1200.
        val sampleRate = 16000
        val window = (SpeechGate.WINDOW_MS * sampleRate / 1000).toInt()
        val clip = ShortArray(2 * sampleRate) { 40.toShort() }
        for (i in 0 until window) clip[3 * window + i] = 1200.toShort()
        assertEquals(1200f, SpeechGate.speechLevel(clip, sampleRate), 1f)
    }

    @Test
    fun `speechLevel still rejects pure ambient noise`() {
        assertTrue(SpeechGate.speechLevel(ShortArray(2 * 16000) { 40.toShort() }, 16000) < SpeechGate.MIN_RMS)
    }

    @Test
    fun `speechLevel of a clean speech clip is its level`() {
        assertEquals(2500f, SpeechGate.speechLevel(ShortArray(16000) { 2500.toShort() }, 16000), 1f)
    }

    @Test
    fun `speechLevel of an empty clip is zero`() {
        assertEquals(0f, SpeechGate.speechLevel(ShortArray(0), 16000))
    }

    @Test
    fun `a clip shorter than one window is measured whole`() {
        assertEquals(1500f, SpeechGate.speechLevel(ShortArray(100) { 1500.toShort() }, 16000), 1f)
    }

    @Test
    fun `a 400 ms clip at 16 kHz is exactly the minimum`() {
        val atMinimum = 16000 * 400 / 1000
        assertFalse(SpeechGate.durationMs(atMinimum - 1, 16000) >= SpeechGate.MIN_DURATION_MS)
        assertTrue(SpeechGate.durationMs(atMinimum, 16000) >= SpeechGate.MIN_DURATION_MS)
    }
}
