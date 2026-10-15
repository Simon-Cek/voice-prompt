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

    @Test
    fun `a 400 ms clip at 16 kHz is exactly the minimum`() {
        val atMinimum = 16000 * 400 / 1000
        assertFalse(SpeechGate.durationMs(atMinimum - 1, 16000) >= SpeechGate.MIN_DURATION_MS)
        assertTrue(SpeechGate.durationMs(atMinimum, 16000) >= SpeechGate.MIN_DURATION_MS)
    }
}
