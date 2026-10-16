package io.github.simoncek.voiceprompt.state

/**
 * Review N4: the result of deriving the dictation phase from the live facts ([derivePhase]).
 * [problem] is non-null only while [phase] is [DictationPhase.PROBLEM] — the same invariant
 * [DictationPhaseTracker] keeps, so a stale reason can never leak into another phase.
 */
data class DerivedPhase(
    val phase: DictationPhase,
    val problem: String? = null,
)

/**
 * Review N4: derives the dictation phase from the live facts — the single place where facts
 * become a phase, so the several exit paths that each end one thing (a clip finishing, a
 * discarded clip, a cancelled start) can no longer stomp the phase of something still live.
 *
 * Priority, highest first: RECORDING (a live capture beats everything — the status bar and the
 * mic-test guard must never say "Idle" while the mic is open) > OPENING (a start in progress) >
 * TRANSCRIBING (at least one clip in flight) > PROBLEM (the last failure reason) > IDLE.
 *
 * Pure and total: no platform imports, no shared state — the session supplies the facts, so the
 * whole truth table is unit-testable without the IntelliJ runtime.
 */
fun derivePhase(
    audioConfirmed: Boolean,
    starting: Boolean,
    activeTranscriptions: Int,
    problem: String?,
): DerivedPhase = when {
    audioConfirmed -> DerivedPhase(DictationPhase.RECORDING)
    starting -> DerivedPhase(DictationPhase.OPENING)
    activeTranscriptions > 0 -> DerivedPhase(DictationPhase.TRANSCRIBING)
    problem != null -> DerivedPhase(DictationPhase.PROBLEM, problem)
    else -> DerivedPhase(DictationPhase.IDLE)
}
