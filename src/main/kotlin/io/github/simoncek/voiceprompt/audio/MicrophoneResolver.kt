package io.github.simoncek.voiceprompt.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/** The named microphone matches no mixer (or none of them captures at the requested format). */
class MicrophoneNotFoundException(message: String) : LineUnavailableException(message)

/**
 * Resolves a named input device to a [TargetDataLine] at [format].
 *
 * Capture ([AudioCapture]) and the mic test ([MicrophoneTester]) must resolve the same device
 * the same way (review #9): the two paths used to differ, and on Windows several mixers share
 * similar names, so a plain first-name-match may be a Port mixer rather than the real device.
 * Candidates therefore have to match by name AND pass `isLineSupported` for a capture line at
 * [format] — that check is what separates the real device from a name-similar stub.
 */
object MicrophoneResolver {

    /** [mixerName] null/blank means the system default microphone (settings store null for it). */
    fun resolve(mixerName: String?, format: AudioFormat): TargetDataLine {
        if (mixerName.isNullOrEmpty()) return AudioSystem.getTargetDataLine(format)
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
        val candidates = AudioSystem.getMixerInfo().filter { it.name.equals(mixerName, ignoreCase = true) }
        for (mixerInfo in candidates) {
            val mixer = AudioSystem.getMixer(mixerInfo)
            if (mixer.isLineSupported(lineInfo)) {
                // javax.sound is not generic here: getLine() returns the Line supertype, so the
                // safe cast (a mixer that "supports" our line type cannot hand back another one).
                return mixer.getLine(lineInfo) as? TargetDataLine
                    ?: throw MicrophoneNotFoundException("Mixer line is not a capture line: ${mixerInfo.name}")
            }
            // This name match does not capture at our format (e.g. a Port mixer): release it and
            // keep looking, so a later same-named real device still wins.
            runCatching { mixer.close() }
        }
        throw MicrophoneNotFoundException("Microphone not found: $mixerName")
    }
}
