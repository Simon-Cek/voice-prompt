package io.github.simoncek.voiceprompt.actions

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Review N16: a clip whose transcription task never runs — cancelled before start (a project
 * close, or the platform dropping a queued task) — must give back its in-flight slot and its
 * WAV exactly like a clip that ran. The task's onCancel/onFinished/onThrowable hooks all call
 * [DictationSession.finishClip]; this pins that end-point's contract: the WAV is removed, the
 * phase returns to IDLE, and finishing an already-ended clip (the platform fires more than one
 * hook) is a safe no-op that cannot double-release the counter.
 *
 * Deterministic: no task queue, no model, no microphone — the clip is claimed exactly as
 * [DictationSession.finishCapturedClip] claims it before the hand-off, then ended through the
 * hook's end-point.
 */
class DictationSessionClipCleanupTest : LightPlatformTestCase() {

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

    private fun wav(): File = File.createTempFile("voice-prompt-n16", ".wav")

    /**
     * The light-test EDT only processes its queue when the test thread pumps it, and
     * [waitWithEventsDispatching] returns immediately when the predicate already holds — so an
     * application a worker thread queued can sit unapplied, and even leak into the next test
     * class. This forces the EDT to process everything queued before the sentinel, so after it
     * returns the queue is drained and the phase is final.
     */
    private fun drainEdt() {
        val drained = AtomicBoolean(false)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { drained.set(true) }
        PlatformTestUtil.waitWithEventsDispatching(
            "the EDT should drain its queue",
            { drained.get() },
            10,
        )
    }

    private fun awaitPhase(expected: DictationPhase) {
        drainEdt()
        assertEquals(
            "the phase should be ${expected.name} (was ${status.phase.name})",
            expected,
            status.phase,
        )
    }

    fun test_a_clip_whose_task_never_ran_gives_back_its_wav_and_slot() {
        val wav = wav()
        assertTrue(wav.exists())
        val handle = s.claimTranscriptionForTest()
        awaitPhase(DictationPhase.TRANSCRIBING)

        s.finishClip(handle, wav)

        assertFalse(
            "a clip whose task never ran must not leave its WAV in the temp directory (review N16)",
            wav.exists(),
        )
        awaitPhase(DictationPhase.IDLE)
    }

    fun test_finishing_an_already_finished_clip_is_a_no_op() {
        val wav = wav()
        val handle = s.claimTranscriptionForTest()

        // The platform fires more than one hook (onCancel then onFinished); the second finish
        // must not double-release the counter.
        s.finishClip(handle, wav)
        s.finishClip(handle, wav)
        awaitPhase(DictationPhase.IDLE)

        // A re-claim after the double finish still reaches TRANSCRIBING — with a negative
        // counter (a double decrement) it would sit at zero and the phase would never come back.
        val secondWav = wav()
        val second = s.claimTranscriptionForTest()
        awaitPhase(DictationPhase.TRANSCRIBING)
        s.finishClip(second, secondWav)
        awaitPhase(DictationPhase.IDLE)
    }
}
