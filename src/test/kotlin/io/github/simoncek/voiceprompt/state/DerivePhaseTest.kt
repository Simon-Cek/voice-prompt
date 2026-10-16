package io.github.simoncek.voiceprompt.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Review N4: the full truth table for [derivePhase] — the single place where the live facts
 * (audio confirmed, a start in progress, clips in flight, the last failure reason) become a
 * phase. [derivePhase] is pure and takes the facts as parameters, so the whole table is
 * testable without the IntelliJ runtime and without a session.
 *
 * The two invariants the status bar relies on:
 *  1. the phase is the highest-priority fact that holds — RECORDING > OPENING > TRANSCRIBING >
 *     PROBLEM > IDLE — so an event that ends one thing can never hide something still live;
 *  2. the failure reason is carried only while the phase is PROBLEM, so a stale reason can
 *     never leak into another phase (the same invariant [DictationPhaseTracker] keeps).
 */
class DerivePhaseTest {

    /** The four facts, exactly as the session supplies them to [derivePhase]. */
    private data class Facts(
        val audioConfirmed: Boolean,
        val starting: Boolean,
        val activeTranscriptions: Int,
        val problem: String?,
    )

    @Test
    fun `the full truth table of the four facts`() {
        val cases: List<Pair<Facts, DictationPhase>> = listOf(
            // nothing live:
            Facts(false, false, 0, null) to DictationPhase.IDLE,
            Facts(false, false, 0, "Mic did not respond") to DictationPhase.PROBLEM,
            // a clip is in flight:
            Facts(false, false, 1, null) to DictationPhase.TRANSCRIBING,
            Facts(false, false, 1, "Mic did not respond") to DictationPhase.TRANSCRIBING,
            Facts(false, false, 3, null) to DictationPhase.TRANSCRIBING,
            // a start is in progress (the beep window):
            Facts(false, true, 0, null) to DictationPhase.OPENING,
            Facts(false, true, 0, "Mic did not respond") to DictationPhase.OPENING,
            Facts(false, true, 2, null) to DictationPhase.OPENING,
            Facts(false, true, 2, "Mic did not respond") to DictationPhase.OPENING,
            // the mic is open and audio is flowing — it beats everything:
            Facts(true, false, 0, null) to DictationPhase.RECORDING,
            Facts(true, false, 0, "Mic did not respond") to DictationPhase.RECORDING,
            Facts(true, false, 1, null) to DictationPhase.RECORDING,
            Facts(true, false, 1, "Mic did not respond") to DictationPhase.RECORDING,
            Facts(true, true, 0, null) to DictationPhase.RECORDING,
            Facts(true, true, 0, "Mic did not respond") to DictationPhase.RECORDING,
            Facts(true, true, 1, null) to DictationPhase.RECORDING,
            Facts(true, true, 1, "Mic did not respond") to DictationPhase.RECORDING,
        )
        for ((facts, expected) in cases) {
            val derived = derivePhase(
                facts.audioConfirmed,
                facts.starting,
                facts.activeTranscriptions,
                facts.problem,
            )
            assertEquals(
                "facts=$facts must derive ${expected.name}, not ${derived.phase.name}",
                expected,
                derived.phase,
            )
            // Invariant 2: the reason rides only on PROBLEM.
            val expectedProblem = if (expected == DictationPhase.PROBLEM) facts.problem else null
            assertEquals("facts=$facts", expectedProblem, derived.problem)
        }
    }

    @Test
    fun `a live recording beats a clip in flight and a failure reason`() {
        val derived = derivePhase(true, false, 2, "Mic did not respond")
        assertEquals(DictationPhase.RECORDING, derived.phase)
        assertNull(derived.problem)
    }

    @Test
    fun `a start in progress beats a clip in flight and a failure reason`() {
        val derived = derivePhase(false, true, 2, "Mic did not respond")
        assertEquals(DictationPhase.OPENING, derived.phase)
        assertNull(derived.problem)
    }

    @Test
    fun `a clip in flight beats a stale failure reason`() {
        val derived = derivePhase(false, false, 1, "Mic did not respond")
        assertEquals(DictationPhase.TRANSCRIBING, derived.phase)
        assertNull(derived.problem)
    }

    @Test
    fun `the failure reason is carried only while the phase is PROBLEM`() {
        assertEquals("Mic did not respond", derivePhase(false, false, 0, "Mic did not respond").problem)
        assertNull(derivePhase(false, false, 1, "Mic did not respond").problem)
        assertNull(derivePhase(false, true, 0, "Mic did not respond").problem)
        assertNull(derivePhase(true, false, 0, "Mic did not respond").problem)
    }
}
