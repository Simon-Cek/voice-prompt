package io.github.simoncek.voiceprompt.actions

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Review N6: when the capture's reader thread reports the microphone stopped delivering, the
 * session must surface PROBLEM with a short reason, tell the user that the captured part still
 * transcribes, and tear the session down through the normal stop path — without stomping a
 * transcription that is still live (the N4 priority: TRANSCRIBING beats PROBLEM).
 *
 * The light-test thread is the EDT, so [DictationSession.onMicLost]'s phase update applies when
 * the test pumps the queue ([drainEdt]). [DictationSession.stop] is driven through the
 * stopWorkDispatcher seam, so the blocking tail (beeps, capture close, WAV, transcription) never
 * runs — no microphone, no beeps.
 */
class DictationSessionMicLostTest : LightPlatformTestCase() {

    private lateinit var s: DictationSession
    private lateinit var status: DictationStatus

    override fun setUp() {
        super.setUp()
        s = DictationSession()
        status = DictationStatus.instance
    }

    override fun tearDown() {
        try {
            // Leave the shared status clean for the next test class (a new session's claim would).
            s.clearProblemForTest()
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
     * application a worker thread queued can sit unapplied. This forces the EDT to process
     * everything queued before the sentinel, so after it returns the queue is drained.
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

    fun test_on_mic_lost_lands_problem_and_stops_the_session() {
        var stopDispatched = false
        s.stopWorkDispatcher = { stopDispatched = true }
        try {
            // No live recording, no clip in flight — the mic simply stopped delivering.
            s.onMicLost()

            awaitPhase(DictationPhase.PROBLEM)
            assertEquals(
                "the problem must carry the mic-lost reason",
                VoiceBundle["statusbar.problem.mic.lost"],
                status.problem,
            )
            assertTrue("onMicLost must tear the session down through the stop path", stopDispatched)

            // A new session's claim clears the previous failure — here via the test seam.
            s.clearProblemForTest()
            awaitPhase(DictationPhase.IDLE)
        } finally {
            s.stopWorkDispatcher = null
        }
    }

    fun test_on_mic_lost_keeps_a_live_transcription_then_lands_problem() {
        s.stopWorkDispatcher = { } // the teardown this loss dispatches is not what this test is about
        try {
            // A clip from an earlier session is still transcribing.
            val live = s.claimTranscriptionForTest()
            awaitPhase(DictationPhase.TRANSCRIBING)

            // The mic stops delivering mid-recording. PROBLEM must not stomp the live clip.
            s.onMicLost()
            awaitPhase(DictationPhase.TRANSCRIBING) // the live clip beats PROBLEM

            // ... and only when the clip finishes does the PROBLEM surface.
            s.releaseTranscriptionForTest(live)
            awaitPhase(DictationPhase.PROBLEM)
            assertEquals(
                "the problem must carry the mic-lost reason",
                VoiceBundle["statusbar.problem.mic.lost"],
                status.problem,
            )

            s.clearProblemForTest()
            awaitPhase(DictationPhase.IDLE)
        } finally {
            s.stopWorkDispatcher = null
        }
    }
}
