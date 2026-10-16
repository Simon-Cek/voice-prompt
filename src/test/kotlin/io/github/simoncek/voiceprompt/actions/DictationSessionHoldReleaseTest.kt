package io.github.simoncek.voiceprompt.actions

import com.intellij.testFramework.LightPlatformTestCase
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.settings.RecordingMode
import java.awt.Frame
import java.awt.event.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review N1: a key release (or window deactivation) must cancel a start that is still in
 * progress — the beep window, before the capture line is open — not only an already-running
 * recording. Before the fix the gate was `isRecording` only, so a release during that start
 * window was ignored and an accidental tap began a full 60 s recording.
 *
 * The stop decision is a pure predicate shared by `DictationSession.onKeyReleased` and
 * `onWindowDeactivated`, so these tests drive it with controlled inputs: no audio, no beeps,
 * and no dependence on the beep window length. The wiring test in
 * [DictationSessionHoldReleaseWiringTest] proves the handlers actually use this predicate.
 */
class HoldReleasePredicateTest {

    /** Alt+Shift+Z, the plugin's default push-to-talk combo (plugin.xml). */
    private val combo = setOf(KeyEvent.VK_Z, KeyEvent.VK_ALT, KeyEvent.VK_SHIFT)

    @Test
    fun `a release while a start is in progress stops the session`() {
        // N1 proper: not recording yet (beep window) but a start is in progress -> must stop.
        // This case FAILS if the fix is reverted to `isRecording` only.
        assertTrue(
            shouldStopOnRelease(
                RecordingMode.HOLD,
                isRecording = false,
                isStarting = true,
                keyCode = KeyEvent.VK_Z,
                comboKeys = combo,
            ),
        )
    }

    @Test
    fun `a deactivate while a start is in progress stops the session`() {
        // The window-deactivation safety net must cover the start window too.
        assertTrue(shouldStopOnDeactivate(RecordingMode.HOLD, isRecording = false, isStarting = true))
    }

    @Test
    fun `a release while recording still stops`() {
        assertTrue(shouldStopOnRelease(RecordingMode.HOLD, true, false, KeyEvent.VK_Z, combo))
        assertTrue(shouldStopOnRelease(RecordingMode.HOLD, true, true, KeyEvent.VK_Z, combo))
    }

    @Test
    fun `a release with nothing live does not stop`() {
        assertFalse(shouldStopOnRelease(RecordingMode.HOLD, false, false, KeyEvent.VK_Z, combo))
        assertFalse(shouldStopOnDeactivate(RecordingMode.HOLD, false, false))
    }

    @Test
    fun `a release of an unrelated key does not stop`() {
        // The session is live, but the released key is not part of the shortcut.
        assertFalse(shouldStopOnRelease(RecordingMode.HOLD, true, true, KeyEvent.VK_X, combo))
    }

    @Test
    fun `a release never stops in tap mode`() {
        // Tap is press-to-start / press-to-stop: a release is not a stop in either state.
        assertFalse(shouldStopOnRelease(RecordingMode.TAP, true, true, KeyEvent.VK_Z, combo))
        assertFalse(shouldStopOnRelease(RecordingMode.TAP, false, true, KeyEvent.VK_Z, combo))
        assertFalse(shouldStopOnDeactivate(RecordingMode.TAP, false, true))
    }
}

/**
 * Review N1 wiring: proves `DictationSession.onKeyReleased` actually routes through the shared
 * predicate and fires `stop()` when a session is live (a start in progress) — with no audio, no
 * beeps, and no dependence on the beep window length. The `starting` flag is forced via the
 * [DictationSession.setStartingForTest] seam and the `stop()` it fires is captured by
 * [DictationSession.stopWorkDispatcher] without running the blocking tail.
 */
class DictationSessionHoldReleaseWiringTest : LightPlatformTestCase() {

    private lateinit var s: DictationSession

    override fun setUp() {
        super.setUp()
        // Review N2: a fresh session per test, so the per-session stop claim starts unclaimed
        // and is independent of other test classes. Disposed in tearDown (AGENTS.md rule 2).
        s = DictationSession()
    }

    override fun tearDown() {
        try {
            s.dispose()
        } finally {
            super.tearDown()
        }
    }

    fun test_release_during_start_calls_stop() {
        val originalMode = CopilotVoiceSettings.instance.mode
        CopilotVoiceSettings.instance.mode = RecordingMode.HOLD

        // Use whatever shortcut the action actually has (Alt+Shift+Z from plugin.xml) and
        // release its main key, so the test does not hard-code a particular combo.
        val stroke = s.currentShortcut()?.firstKeyStroke
            ?: error("expected the push-to-talk action to expose a keyboard shortcut in the test")
        val event = KeyEvent(
            Frame(),
            KeyEvent.KEY_RELEASED,
            0L,
            0,
            stroke.keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

        var stopCalled = false
        s.stopWorkDispatcher = { _ -> stopCalled = true }
        try {
            s.setStartingForTest(true) // a start is "in progress" (beep window) — no audio opened
            s.onKeyReleased(event)
            assertTrue(
                "a key release while a start is in progress must call stop() (review N1)",
                stopCalled,
            )
        } finally {
            s.setStartingForTest(false)
            s.stopWorkDispatcher = null
            CopilotVoiceSettings.instance.mode = originalMode
        }
    }

    fun test_release_with_nothing_live_does_not_call_stop() {
        val originalMode = CopilotVoiceSettings.instance.mode
        CopilotVoiceSettings.instance.mode = RecordingMode.HOLD

        val stroke = s.currentShortcut()?.firstKeyStroke
            ?: error("expected the push-to-talk action to expose a keyboard shortcut in the test")
        val event = KeyEvent(
            Frame(),
            KeyEvent.KEY_RELEASED,
            0L,
            0,
            stroke.keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

        var stopCalled = false
        s.stopWorkDispatcher = { _ -> stopCalled = true }
        try {
            // Neither recording nor starting: the release must be a no-op.
            s.onKeyReleased(event)
            assertFalse("a release with no live session must not call stop()", stopCalled)
        } finally {
            s.stopWorkDispatcher = null
            CopilotVoiceSettings.instance.mode = originalMode
        }
    }
}
