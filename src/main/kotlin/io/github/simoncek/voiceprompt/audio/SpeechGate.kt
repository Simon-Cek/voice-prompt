package io.github.simoncek.voiceprompt.audio

import kotlin.math.sqrt

/**
 * Pre-inference gates for captured audio (issue #8). Whisper hallucinates on silence and noise
 * ("Thank you.", "(music)", …), and a quick accidental tap would otherwise spawn a whisper-cli
 * process and — with auto-send on — ship a bogus transcript with no review. Clips shorter than
 * [MIN_DURATION_MS] or below the [MIN_RMS] floor are treated as no speech *before* whisper-cli is
 * ever started.
 */
object SpeechGate {

    /** Clips shorter than this are accidental taps, not dictation (review #8: ~0.4 s). */
    const val MIN_DURATION_MS = 400L

    /**
     * 16-bit RMS floor, ~-50 dBFS. Below this the clip is digital silence or ambient noise, not
     * speech; speech at normal volume sits well above it.
     */
    const val MIN_RMS = 100.0f

    /** The duration of [sampleCount] samples captured at [sampleRate] Hz, in whole ms. */
    fun durationMs(sampleCount: Int, sampleRate: Int): Long = sampleCount * 1000L / sampleRate

    /** Root-mean-square level of 16-bit PCM [samples]; 0 for an empty array. */
    fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size).toFloat()
    }
}
