package io.github.simoncek.voiceprompt.actions

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.settings.RecordingMode
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import io.github.simoncek.voiceprompt.state.DerivedPhase
import java.awt.Frame
import java.awt.event.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Review N2: `stop()` must be idempotent per session. A duplicate stop — two keys of the chord
 * released within milliseconds, a release coinciding with a focus loss, or a status-bar click —
 * must run the teardown (`finishStop`) exactly once, and any `finishStop` that finds nothing
 * recording must not play the stop tone or drop a live phase.
 *
 * Each test uses a fresh [DictationSession] (disposed in [tearDown]) so the per-session stop
 * claim starts unclaimed and is independent of other test classes. No audio, no beeps, no
 * timing: the blocking tail is captured by [DictationSession.stopWorkDispatcher] and run on
 * demand, and the start sequence is no-oped by [DictationSession.startWorkDispatcher].
 */
class DictationSessionStopIdempotencyTest : LightPlatformTestCase() {

    private lateinit var s: DictationSession

    override fun setUp() {
        super.setUp()
        s = DictationSession()
    }

    override fun tearDown() {
        try {
            s.dispose()
        } finally {
            super.tearDown()
        }
    }

    fun test_double_stop_dispatches_the_teardown_once() {
        var dispatches = 0
        s.stopWorkDispatcher = { _ -> dispatches++ }
        try {
            s.stop()
            s.stop() // a duplicate: a second key of the chord, or a coinciding focus loss
            assertEquals(
                "a double stop must dispatch the teardown only once (review N2)",
                1,
                dispatches,
            )
        } finally {
            s.stopWorkDispatcher = null
        }
    }

    fun test_a_phantom_stop_does_not_drop_a_live_transcribing_phase() {
        val status = DictationStatus.instance
        // A previous clip is transcribing: the phase is live and must survive a phantom stop.
        status.applyDerivedPhase { DerivedPhase(DictationPhase.TRANSCRIBING) }
        var queued: (() -> Unit)? = null
        s.stopWorkDispatcher = { queued = it }
        try {
            s.stop()
            s.stop() // duplicate, dropped
            assertNotNull("the stop must be dispatched once (review N2)", queued)
            // Run the captured teardown. Nothing is recording (fresh session), so the review
            // N2 guard must drop it before the stop tone and the phase change.
            queued!!.invoke()
            // The seed and this assertion both ride the EDT, so pump it: deterministic, and it
            // is what the status bar would see.
            PlatformTestUtil.waitWithEventsDispatching(
                "a phantom stop must not drop a live TRANSCRIBING phase (review N2)",
                { status.phase == DictationPhase.TRANSCRIBING },
                10,
            )
        } finally {
            s.stopWorkDispatcher = null
            status.applyDerivedPhase { DerivedPhase(DictationPhase.IDLE) }
            // Let the cleanup land on the EDT so the next test class starts from IDLE.
            PlatformTestUtil.waitWithEventsDispatching("cleanup", { status.phase == DictationPhase.IDLE }, 10)
        }
    }

    fun test_a_new_start_releases_the_claim_so_the_next_stop_dispatches() {
        var dispatches = 0
        s.stopWorkDispatcher = { _ -> dispatches++ }
        s.startWorkDispatcher = { _ -> } // no-op: run start() — and its claim reset — without the beep + mic
        try {
            s.stop() // first stop: claims the claim, dispatches
            assertEquals("the first stop must dispatch (review N2)", 1, dispatches)
            s.start(null) // a new session: releases the claim
            s.stop() // the next stop: must dispatch, not be rejected
            assertEquals(
                "a new start() must release the stop claim so the next stop() dispatches (review N2)",
                2,
                dispatches,
            )
        } finally {
            s.stopWorkDispatcher = null
            s.startWorkDispatcher = null
        }
    }

    fun test_stop_during_opening_dispatches_once_and_cancels_the_start() {
        s.setStartingForTest(true) // a start is in progress (OPENING / the beep window)
        var dispatches = 0
        s.stopWorkDispatcher = { _ -> dispatches++ }
        try {
            s.stop()
            s.stop() // duplicate, dropped
            assertEquals(
                "a stop during OPENING must dispatch the teardown only once (review N2)",
                1,
                dispatches,
            )
            assertTrue(
                "a stop during OPENING must cancel the in-progress start (review N2)",
                s.isStartCancelledForTest(),
            )
        } finally {
            s.stopWorkDispatcher = null
            s.setStartingForTest(false)
        }
    }

    fun test_a_cancelled_start_cannot_leave_the_phase_on_recording() {
        val status = DictationStatus.instance
        // Pre-1.1.0 finding 6: startInternal has just confirmed the audio (audioConfirmed =
        // true), and a stop() that landed in that window has already won: it cancelled the
        // start and queued the teardown (captured — not run, so its own clear has not
        // happened yet either).
        s.setStartingForTest(true)
        s.setAudioConfirmedForTest(true)
        var queued: (() -> Unit)? = null
        s.stopWorkDispatcher = { queued = it }
        try {
            s.stop()
            assertTrue(
                "a stop in that window must cancel the in-progress start (review N2)",
                s.isStartCancelledForTest(),
            )
            assertNotNull("the teardown must be queued", queued)
            // Now the start sequence ends — the finally both dispatch paths share. Without the
            // fix it recomputes from the stale audioConfirmed=true and derives RECORDING for
            // a session that is already torn down: the widget sits on "Recording" until the
            // next event.
            s.endStartSequenceForTest()
            PlatformTestUtil.waitWithEventsDispatching(
                "a cancelled start must not leave the phase on RECORDING (finding 6)",
                { status.phase == DictationPhase.IDLE },
                10,
            )
        } finally {
            s.stopWorkDispatcher = null
            s.setStartingForTest(false)
            s.setAudioConfirmedForTest(false)
            status.applyDerivedPhase { DerivedPhase(DictationPhase.IDLE) }
            PlatformTestUtil.waitWithEventsDispatching("cleanup", { status.phase == DictationPhase.IDLE }, 10)
        }
    }

    fun test_a_key_release_after_max_duration_stop_dispatches_one_teardown() {
        val originalMode = CopilotVoiceSettings.instance.mode
        CopilotVoiceSettings.instance.mode = RecordingMode.HOLD
        s.setStartingForTest(true) // the session is live

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

        var dispatches = 0
        s.stopWorkDispatcher = { _ -> dispatches++ }
        try {
            // The max-duration auto-stop (the capture thread hit the 60 s cap) ...
            s.stop()
            // ... followed by the key release (the user let go of the chord):
            s.onKeyReleased(event)
            assertEquals(
                "a key release after the max-duration auto-stop must dispatch only one teardown (review N2)",
                1,
                dispatches,
            )
        } finally {
            s.stopWorkDispatcher = null
            s.setStartingForTest(false)
            CopilotVoiceSettings.instance.mode = originalMode
        }
    }
}
