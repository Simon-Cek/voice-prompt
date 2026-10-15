package io.github.simoncek.voiceprompt.audio

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.LineUnavailableException

class MicrophoneResolverTest {

    private val format = AudioFormat(16000f, 16, 1, true, false)

    @Test
    fun `a device name that matches no mixer is reported as not found`() {
        val e = assertThrows(MicrophoneNotFoundException::class.java) {
            MicrophoneResolver.resolve("definitely-no-such-mic-xyz", format)
        }
        assertTrue("message should name the device, got: ${e.message}", e.message!!.contains("definitely-no-such-mic-xyz"))
    }

    @Test
    fun `not-found is a LineUnavailableException so existing catch paths keep working`() {
        // MicrophoneTester routes on this: not-found (this subclass) before in-use (the base).
        assertTrue(MicrophoneNotFoundException("x") is LineUnavailableException)
    }
}
