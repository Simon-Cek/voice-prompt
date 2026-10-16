package io.github.simoncek.voiceprompt.audio

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Review N13: [Beeps.shutdown] runs from `DictationSession.dispose` on every IDE shutdown and
 * dynamic plugin unload, so it must be idempotent and never throw. A beep that loses the race
 * against the shutdown (a stop tone during unload) must not surface a
 * RejectedExecutionException either — it returns an already-completed future, so the caller's
 * bounded `get` is unaffected.
 *
 * Deterministic: [Beeps.stopExecutorForTest] stops the executor without playing a tone
 * (no real audio in tests).
 */
class BeepsShutdownTest {

    @Test
    fun `shutdown is idempotent and never throws`() {
        Beeps.shutdown()
        Beeps.shutdown()
    }

    @Test
    fun `a beep after shutdown completes immediately instead of throwing`() {
        Beeps.stopExecutorForTest() // create the executor (if needed) and stop it, no tone

        val future = Beeps.recordingStop() // must not throw RejectedExecutionException

        assertNull("a skipped beep completes immediately with no result", future.get())
    }
}
