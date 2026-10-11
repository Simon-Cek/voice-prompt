package io.github.simoncek.voiceprompt.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short confirmation tones for recording start/stop. Synthesized in-process,
 * so no bundled audio assets are required to ship.
 */
object Beeps {

    fun recordingStart() = play(880.0, 120)

    fun recordingStop() = play(440.0, 150)

    private fun play(frequencyHz: Double, durationMs: Int) {
        val thread = Thread({
            try {
                val sampleRate = 44_100
                val frames = (sampleRate * durationMs / 1000).toInt()
                val amplitude = 0.25
                val samples = ShortArray(frames) { i ->
                    (amplitude * Short.MAX_VALUE * sin(2.0 * PI * frequencyHz * i / sampleRate)).toInt().toShort()
                }
                val buffer = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                buffer.asShortBuffer().put(samples)
                val bytes = buffer.array()
                val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format)
                line.start()
                line.write(bytes, 0, bytes.size)
                line.drain()
                line.close()
            } catch (_: Throwable) {
                // Audio feedback is best-effort; never fail the recording path.
            }
        }, "copilot-voice-beep")
        thread.isDaemon = true
        thread.start()
    }
}
