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

    /**
     * Length of the analysis window, in ms (review N17). The gate measures level per window and
     * passes if *any* window clears [MIN_RMS] — so pauses elsewhere in the clip, or a quiet
     * mic, cannot dilute real speech below the floor.
     */
    const val WINDOW_MS = 50L

    /** The duration of [sampleCount] samples captured at [sampleRate] Hz, in whole ms. */
    fun durationMs(sampleCount: Int, sampleRate: Int): Long = sampleCount * 1000L / sampleRate

    /**
     * Review N17: the speech level of 16-bit PCM [samples] captured at [sampleRate] Hz — the
     * highest [rms] over any [WINDOW_MS] window (windows are non-overlapping). The gate asks
     * "is there speech *anywhere* in this clip", which the max window answers: a whole-clip
     * average sits below the floor when real speech is short relative to the clip's pauses and
     * the mic is quiet, and gets discarded. 0 for an empty array; a clip shorter than one
     * window is measured whole (the duration gate runs first in production, so this only keeps
     * the function total).
     */
    fun speechLevel(samples: ShortArray, sampleRate: Int): Float {
        if (samples.isEmpty()) return 0f
        val window = (WINDOW_MS * sampleRate / 1000).toInt().coerceAtLeast(1)
        var best = 0f
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + window, samples.size)
            best = maxOf(best, rms(samples, start, end))
            start += window
        }
        return best
    }

    /** Root-mean-square level of 16-bit PCM [samples]; 0 for an empty array. */
    fun rms(samples: ShortArray): Float = rms(samples, 0, samples.size)

    private fun rms(samples: ShortArray, start: Int, end: Int): Float {
        if (start >= end) return 0f
        var sum = 0.0
        for (i in start until end) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / (end - start)).toFloat()
    }
}
