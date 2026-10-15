package io.github.simoncek.voiceprompt.audio

import com.intellij.openapi.diagnostic.Logger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short confirmation tones for recording start/stop. Synthesized once in-process (no bundled
 * assets) and played on a single daemon thread (issue #10: the old code spawned a fresh thread
 * and a fresh line per beep, and swallowed every failure).
 *
 * Each tone carries ~100 ms of leading silence (issue #10): output devices that sleep after
 * being idle (USB headsets, docks) drop their first ~100–300 ms of audio, and a Bluetooth or
 * Teams headset can interrupt output for a few hundred ms while it switches to the HFP profile
 * after the microphone opens. A bare 120 ms tone fired at that moment is swallowed — the lead
 * silence eats the wake-up time so the audible tone lands.
 */
object Beeps {

    private val log = Logger.getInstance(Beeps::class.java)

    private const val SAMPLE_RATE = 44_100
    private const val LEAD_SILENCE_MS = 100
    private const val FADE_MS = 10

    private val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)

    /** One thread for all beeps; daemon so a stuck drain cannot block the IDE shutdown. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "copilot-voice-beep").apply { isDaemon = true }
    }

    // Generated once (the review's fix for #10): lead silence for device wake, short fade in/out
    // so the tone does not click.
    private val startPcm = tone(880.0, 120)
    private val stopPcm = tone(440.0, 150)

    /** Play the start tone; the [Future] completes when the line has drained (or failed). */
    fun recordingStart(): Future<*> = executor.submit { play(startPcm) }

    /** Play the stop tone; the [Future] completes when the line has drained (or failed). */
    fun recordingStop(): Future<*> = executor.submit { play(stopPcm) }

    /**
     * Best-effort by contract: never throws (a beep failure must not break dictation), so the
     * submitted task always completes normally and the caller's bounded [Future.get] only times
     * out.
     */
    private fun play(pcm: ByteArray) {
        var line: SourceDataLine? = null
        try {
            line = AudioSystem.getSourceDataLine(format).apply { open(format); start() }
            line.write(pcm, 0, pcm.size)
            line.drain()
        } catch (t: Throwable) {
            log.warn("beep failed", t) // no longer silent (issue #10)
        } finally {
            runCatching { line?.close() }
        }
    }

    /**
     * 16-bit mono PCM at [SAMPLE_RATE]: [leadSilenceMs] of silence, then [durationMs] of
     * [freqHz] sine with a short fade in/out (issue #10).
     */
    internal fun tone(freqHz: Double, durationMs: Int, leadSilenceMs: Int = LEAD_SILENCE_MS): ByteArray {
        val frames = SAMPLE_RATE * durationMs / 1000
        val lead = SAMPLE_RATE * leadSilenceMs / 1000
        val fade = SAMPLE_RATE * FADE_MS / 1000
        val amplitude = 0.25
        return ByteArray((frames + lead) * 2).also { bytes ->
            var o = 0
            for (i in 0 until frames + lead) {
                val v = if (i < lead) {
                    0.0
                } else {
                    val n = i - lead
                    var amp = amplitude
                    if (n < fade) amp *= n.toDouble() / fade
                    val tail = frames - n
                    if (tail < fade) amp *= tail.toDouble() / fade
                    amp * sin(2.0 * PI * freqHz * n / SAMPLE_RATE)
                }
                val s = (v * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                bytes[o++] = (s.toInt() and 0xFF).toByte()
                bytes[o++] = ((s.toInt() shr 8) and 0xFF).toByte()
            }
        }
    }
}
