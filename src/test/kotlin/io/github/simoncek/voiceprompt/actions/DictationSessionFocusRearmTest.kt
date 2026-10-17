package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.settings.RecordingMode
import java.awt.Frame
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Review N3: the auto-repeat guard was re-armed only by an observed key release. When a
 * focus loss stops the recording (Alt-Tab, another application), the key-up goes to that
 * application and is never observed, so the next genuine press was swallowed as an
 * "auto-repeat" and the user had to press twice.
 *
 * Now the guard is re-armed at a focus boundary that crossed the *application* boundary
 * (`WindowEvent.oppositeWindow == null`) — on deactivation and, as a second chance, on
 * activation. An in-app window switch (dialog, tool window) must NOT re-arm: the key-up is
 * still delivered inside the IDE, and re-arming there would let an auto-repeat re-trigger
 * while the key is still held (TAP would toggle, HOLD could restart the recording the focus
 * loss just stopped). A stop without any focus boundary — the 60 s duration cap — must not
 * re-arm either, or the next auto-repeat would restart the recording.
 *
 * All tests drive the session's entry points directly with synthetic key and window events:
 * no real windows, no microphone, no beeps, no sleeps. Presses dispatch through
 * [DictationSession.startWorkDispatcher] (captured, never run), so the start work — beeps
 * and mic open — never runs.
 */
class DictationSessionFocusRearmTest : LightPlatformTestCase() {

    private lateinit var s: DictationSession

    override fun setUp() {
        super.setUp()
        // A fresh session per test: the per-session facts (starting, the stop claim) start
        // clean and independent of other test classes. Disposed in tearDown (AGENTS.md rule 2).
        s = DictationSession()
    }

    override fun tearDown() {
        try {
            // Leave the shared DictationStatus on IDLE (all facts false) for the next class.
            s.setAudioConfirmedForTest(false)
            s.dispose()
        } finally {
            // Leave no phase application queued on the EDT for the next test class.
            drainEdt()
            super.tearDown()
        }
    }

    /**
     * The light-test EDT only processes its queue when the test thread pumps it, and
     * [PlatformTestUtil.waitWithEventsDispatching] returns immediately when the predicate
     * already holds — so force the EDT to process everything queued before the sentinel, so
     * that after it returns the queue is drained.
     */
    private fun drainEdt() {
        val drained = java.util.concurrent.atomic.AtomicBoolean(false)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { drained.set(true) }
        PlatformTestUtil.waitWithEventsDispatching("the EDT should drain its queue", { drained.get() }, 10)
    }

    /** A keyboard press of the push-to-talk shortcut — a KeyEvent input event, as the guard requires. */
    private fun pressEvent(): AnActionEvent {
        val stroke = s.currentShortcut()?.firstKeyStroke
            ?: error("expected the push-to-talk action to expose a keyboard shortcut in the test")
        val keyEvent = KeyEvent(
            Frame(),
            KeyEvent.KEY_PRESSED,
            0L,
            0,
            stroke.keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )
        // createFromInputEvent is the overload that exists on every supported line (242+);
        // the ActionUiKind-taking createEvent(...) is 243+ and would break the 242 CI leg.
        // The production code only reads inputEvent and project from the event (N3 guard).
        return AnActionEvent.createFromInputEvent(
            keyEvent,
            ActionPlaces.UNKNOWN,
            Presentation(),
            DataContext.EMPTY_CONTEXT,
        )
    }

    /** A window event with an explicit opposite window: null = not in this JVM. */
    private fun windowEvent(id: Int, oppositeWindow: Window?): WindowEvent =
        WindowEvent(Frame(), id, oppositeWindow)

    /**
     * Drives one keyboard press and reports whether the guard let it through (a start was
     * dispatched). The captured start work is never run (no beeps, no mic), and the "start in
     * progress" fact is cleared again, so every press starts from the same session state.
     */
    private fun pressHonored(): Boolean {
        var dispatched = false
        s.startWorkDispatcher = { dispatched = true }
        try {
            s.onShortcutPressed(pressEvent())
        } finally {
            s.startWorkDispatcher = null
            s.setStartingForTest(false)
        }
        return dispatched
    }

    fun test_cross_app_focus_loss_re_arms_the_guard_for_the_next_fresh_press() {
        runInHoldMode {
            assertTrue("the first press must be honoured", pressHonored())
            // The focus loss crosses the application (oppositeWindow == null): the key-up
            // went to another application and will never be observed here. No release is
            // observed in between.
            s.onWindowDeactivated(windowEvent(WindowEvent.WINDOW_DEACTIVATED, null))
            assertTrue(
                "after a cross-app focus loss the next fresh press must be honoured (review N3)",
                pressHonored(),
            )
        }
    }

    fun test_cross_app_activation_re_arms_the_guard_for_the_next_fresh_press() {
        runInHoldMode {
            assertTrue("the first press must be honoured", pressHonored())
            s.onWindowActivated(windowEvent(WindowEvent.WINDOW_ACTIVATED, null))
            assertTrue(
                "after a cross-app activation the next fresh press must be honoured (review N3)",
                pressHonored(),
            )
        }
    }

    fun test_an_in_app_window_switch_does_not_re_arm_the_guard() {
        runInHoldMode {
            assertTrue("the first press must be honoured", pressHonored())
            // The opposite window is in this JVM: a dialog, a tool window, ...
            s.onWindowDeactivated(windowEvent(WindowEvent.WINDOW_DEACTIVATED, Frame()))
            s.onWindowActivated(windowEvent(WindowEvent.WINDOW_ACTIVATED, Frame()))
            assertFalse(
                "an in-app window switch must not re-arm: the key-up is still seen inside the IDE",
                pressHonored(),
            )
        }
    }

    fun test_a_stop_without_a_focus_boundary_does_not_re_arm_the_guard() {
        // The 60 s duration cap: the recording ends while the key is still held and no window
        // event is involved. Re-arming here would let the next auto-repeat restart the
        // recording — the failure mode the guard exists to kill.
        runInHoldMode {
            assertTrue("the first press must be honoured", pressHonored())
            assertFalse(
                "past the duration cap an auto-repeat must stay swallowed until the key-up is seen",
                pressHonored(),
            )
        }
    }

    fun test_an_observed_release_still_re_arms_the_guard() {
        runInHoldMode {
            assertTrue("the first press must be honoured", pressHonored())
            val stroke = s.currentShortcut()?.firstKeyStroke
                ?: error("expected the push-to-talk action to expose a keyboard shortcut in the test")
            s.onKeyReleased(
                KeyEvent(Frame(), KeyEvent.KEY_RELEASED, 0L, 0, stroke.keyCode, KeyEvent.CHAR_UNDEFINED),
            )
            assertTrue("a press after an observed release must be honoured again", pressHonored())
        }
    }

    fun test_a_cross_app_focus_loss_still_stops_a_live_session() {
        // The stop decision is unchanged (review N1) — only the re-arm is new.
        runInHoldMode {
            var stopCalled = false
            s.stopWorkDispatcher = { stopCalled = true }
            try {
                s.setStartingForTest(true) // a start is "in progress" (beep window) — no audio opened
                s.onWindowDeactivated(windowEvent(WindowEvent.WINDOW_DEACTIVATED, null))
                assertTrue(
                    "a cross-app focus loss must still stop a live session (review N1)",
                    stopCalled,
                )
            } finally {
                s.setStartingForTest(false)
                s.stopWorkDispatcher = null
            }
        }
    }

    fun test_an_in_app_focus_loss_still_stops_a_live_session() {
        // ... even when the boundary did not cross the application.
        runInHoldMode {
            var stopCalled = false
            s.stopWorkDispatcher = { stopCalled = true }
            try {
                s.setStartingForTest(true)
                s.onWindowDeactivated(windowEvent(WindowEvent.WINDOW_DEACTIVATED, Frame()))
                assertTrue(
                    "an in-app focus loss must still stop a live session (review N1)",
                    stopCalled,
                )
            } finally {
                s.setStartingForTest(false)
                s.stopWorkDispatcher = null
            }
        }
    }

    private fun runInHoldMode(body: () -> Unit) {
        val originalMode = CopilotVoiceSettings.instance.mode
        CopilotVoiceSettings.instance.mode = RecordingMode.HOLD
        try {
            body()
        } finally {
            CopilotVoiceSettings.instance.mode = originalMode
        }
    }
}
