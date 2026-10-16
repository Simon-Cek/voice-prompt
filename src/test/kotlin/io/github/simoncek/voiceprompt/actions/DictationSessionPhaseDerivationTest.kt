package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import io.github.simoncek.voiceprompt.stt.ModelManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Review N4: the phase is derived from the live facts ([derivePhase]) in one place, so an
 * event that ends *one* thing — a clip finishing, a discarded clip, a cancelled start — can no
 * longer stomp the phase of something still live.
 *
 * Each scenario sets up a live fact (a confirmed recording, a clip in flight) and then drives
 * the exit path that used to write IDLE unconditionally: the phase must keep the live fact.
 * No microphone and no beeps — the exit paths are driven through the internal seams
 * ([DictationSession.finishCapturedClip], [DictationSession.setAudioConfirmedForTest],
 * [DictationSession.startWorkDispatcher]), and the engine is faked where a clip runs.
 *
 * Each test uses a fresh [DictationSession] (disposed in [tearDown]) so the per-session facts
 * (starting, audioConfirmed, lastProblem, the transcription counter) start clean.
 */
class DictationSessionPhaseDerivationTest : LightPlatformTestCase() {

    private lateinit var s: DictationSession
    private lateinit var status: DictationStatus

    override fun setUp() {
        super.setUp()
        s = DictationSession()
        status = DictationStatus.instance
    }

    override fun tearDown() {
        try {
            s.dispose()
        } finally {
            // Leave no phase application queued on the EDT for the next test class.
            drainEdt()
            super.tearDown()
        }
    }

    /**
     * The light-test EDT only processes its queue when the test thread pumps it, and
     * [waitWithEventsDispatching] returns immediately when the predicate already holds — so an
     * application a worker thread queued can sit unapplied, and even leak into the next test
     * class. This forces the EDT to process everything queued before the sentinel, so after it
     * returns the queue is drained and the phase is final.
     */
    private fun drainEdt() {
        val drained = java.util.concurrent.atomic.AtomicBoolean(false)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { drained.set(true) }
        PlatformTestUtil.waitWithEventsDispatching(
            "the EDT should drain its queue",
            { drained.get() },
            10,
        )
    }

    /** The phase lands on the EDT, so drain it until [expected] holds (or the test fails). */
    private fun awaitPhase(expected: DictationPhase) {
        drainEdt()
        assertEquals(
            "the phase should be ${expected.name} (was ${status.phase.name})",
            expected,
            status.phase,
        )
    }

    /** Keeps the real model resolution in [transcribeBody] a fast hit (no bundled-model extraction). */
    private fun installDummyModel() {
        val modelFile = ModelManager.modelFile(CopilotVoiceSettings.instance.modelName).toFile()
        modelFile.parentFile?.mkdirs()
        modelFile.writeText("dummy")
    }

    fun test_a_finished_clip_does_not_hide_a_live_recording() {
        installDummyModel()
        s.engineTranscribe = { _, _, _, _ -> "hello" }
        s.deliverTranscript = { _, _ -> }

        // The session is live: the mic is open and audio is flowing.
        s.setAudioConfirmedForTest(true)
        awaitPhase(DictationPhase.RECORDING)
        // An earlier clip is in flight (claimed, as finishCapturedClip claims it before the
        // hand-off). RECORDING beats TRANSCRIBING on priority.
        val clip = s.claimTranscriptionForTest()
        awaitPhase(DictationPhase.RECORDING)

        // ... and now that clip finishes. The pre-N4 code wrote IDLE on this exit, hiding the
        // live RECORDING; the derived phase must keep it.
        val wav = File(System.getProperty("java.io.tmpdir"), "vp-n4-${System.nanoTime()}.wav")
            .apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) }
        val t = Thread { s.transcribeBody(EmptyProgressIndicator(), null, wav, clip) }
        t.start()
        t.join(10_000)
        assertFalse("the transcription thread should finish", t.isAlive)

        awaitPhase(DictationPhase.RECORDING) // the finished clip must not hide the live recording

        // Only ending the session brings the phase back.
        s.setAudioConfirmedForTest(false)
        awaitPhase(DictationPhase.IDLE)
    }

    fun test_discarded_clips_do_not_hide_a_live_transcription() {
        // A clip from an earlier session is still transcribing.
        val live = s.claimTranscriptionForTest()
        awaitPhase(DictationPhase.TRANSCRIBING)

        // New clips that the pre-inference gates discard: empty, too short (6 ms < 400 ms), and
        // silent (437 ms but rms 0 < 100). Each of these exit paths used to write IDLE
        // unconditionally.
        s.finishCapturedClip(null, ShortArray(0))
        s.finishCapturedClip(null, ShortArray(100))
        s.finishCapturedClip(null, ShortArray(7_000) { 0 })

        awaitPhase(DictationPhase.TRANSCRIBING) // the live clip must not be hidden

        s.releaseTranscriptionForTest(live)
        awaitPhase(DictationPhase.IDLE)
    }

    fun test_a_cancelled_start_does_not_hide_a_live_transcription() {
        val sound = CopilotVoiceSettings.instance.soundEnabled
        CopilotVoiceSettings.instance.soundEnabled = false // no beeps in the test
        try {
            // A clip from an earlier session is still transcribing.
            val live = s.claimTranscriptionForTest()
            awaitPhase(DictationPhase.TRANSCRIBING)

            // A new start begins (OPENING) — its work is held by the dispatcher, so the start
            // is "in progress" without a microphone.
            var heldStart: (() -> Unit)? = null
            s.startWorkDispatcher = { heldStart = it }
            s.stopWorkDispatcher = { } // the teardown this stop would dispatch is not what this test is about
            s.start(null)
            awaitPhase(DictationPhase.OPENING) // OPENING beats TRANSCRIBING while the start is live

            // A quick release: the stop lands while the start is still in progress and
            // cancels it (review N2's decision, which N4 must not change).
            s.stop()
            assertTrue("the stop must cancel the in-progress start (review N2)", s.isStartCancelledForTest())

            // The held start work now runs and sees the cancellation. The pre-N4 code wrote
            // IDLE here (abortCancelledStart), hiding the live TRANSCRIBING.
            heldStart?.invoke()
            awaitPhase(DictationPhase.TRANSCRIBING) // the live clip must not be hidden

            s.releaseTranscriptionForTest(live)
            awaitPhase(DictationPhase.IDLE)
        } finally {
            s.startWorkDispatcher = null
            s.stopWorkDispatcher = null
            CopilotVoiceSettings.instance.soundEnabled = sound
        }
    }

    fun test_a_failed_hand_off_still_releases_the_clip() {
        // A clip that passes both gates (437 ms, rms 1000) is handed to the task queue — and
        // the hand-off fails, so the task never runs. The clip must still be released, or the
        // counter never drops and the phase can never return to IDLE.
        s.transcribeHandoff = { _, _, _ -> false }
        try {
            s.finishCapturedClip(null, ShortArray(7_000) { 1_000 })
            awaitPhase(DictationPhase.IDLE)
        } finally {
            s.transcribeHandoff = null
        }
    }

    fun test_a_clip_is_released_exactly_once() {
        // Two clips in flight.
        val first = s.claimTranscriptionForTest()
        val second = s.claimTranscriptionForTest()
        awaitPhase(DictationPhase.TRANSCRIBING)

        // Release the first clip twice — as if both transcribeBody's finally and the task's
        // onFinished fired. The duplicate release must be a no-op, so the counter cannot go
        // negative and the second clip must stay visible.
        s.releaseTranscriptionForTest(first)
        s.releaseTranscriptionForTest(first)
        awaitPhase(DictationPhase.TRANSCRIBING)

        s.releaseTranscriptionForTest(second)
        awaitPhase(DictationPhase.IDLE)

        // A re-claim after everything is released still reaches TRANSCRIBING (with a negative
        // counter it would sit at zero and the phase would never come back).
        val third = s.claimTranscriptionForTest()
        awaitPhase(DictationPhase.TRANSCRIBING)
        s.releaseTranscriptionForTest(third)
        awaitPhase(DictationPhase.IDLE)
    }
}
