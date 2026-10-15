package io.github.simoncek.voiceprompt.state

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What dictation is doing right now. A single shared state, updated by the push-to-talk action
 * (the only writer) and read by the views: the status bar widget in every project window and the
 * microphone test, which must not open a second capture line while one is running.
 */
enum class DictationPhase {
    /** Nothing is happening; the capture line is closed. */
    IDLE,

    /** The microphone line is being opened. */
    OPENING,

    /** Audio is being captured. */
    RECORDING,

    /** The capture has stopped and transcription is running. */
    TRANSCRIBING,

    /**
     * The session ended in a failure the user should see. [DictationPhaseTracker.problem]
     * carries a short human-readable reason (e.g. "Mic did not respond").
     */
    PROBLEM,
}

/** Whether the capture line is open (the microphone is busy with dictation). */
val DictationPhase.isCapturing: Boolean
    get() = this == DictationPhase.OPENING || this == DictationPhase.RECORDING

/**
 * Thread-safe holder of the current [DictationPhase] (and, while the phase is [DictationPhase.PROBLEM],
 * a short [problem] reason) with listener notification.
 *
 * Deliberately pure — no platform imports — so the transition logic can be unit tested without
 * the IntelliJ runtime. Listeners are invoked on the calling thread with the new phase; the
 * application service ([DictationStatus]) wraps this with EDT marshalling.
 */
class DictationPhaseTracker {

    @Volatile
    var phase: DictationPhase = DictationPhase.IDLE
        private set

    /** Short reason, non-null only while [phase] is [DictationPhase.PROBLEM]. */
    @Volatile
    var problem: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<(DictationPhase) -> Unit>()

    /**
     * Registers [listener]. Idempotent: re-adding an already-registered listener is a no-op, so
     * a view that re-subscribes on every install (the status bar widget) can't receive duplicates.
     */
    fun addListener(listener: (DictationPhase) -> Unit) {
        if (!listeners.contains(listener)) listeners += listener
    }

    fun removeListener(listener: (DictationPhase) -> Unit) {
        listeners -= listener
    }

    /**
     * Updates the phase (and the [problem] reason while it is PROBLEM); a no-op when neither
     * changed. A listener that throws is skipped and the rest still run, so one broken view
     * cannot wedge the others. The reason is forced to null for every non-PROBLEM phase, so a
     * stale reason can never leak into IDLE/OPENING/…
     */
    fun setPhase(newPhase: DictationPhase, problem: String? = null) {
        val normalized = if (newPhase == DictationPhase.PROBLEM) problem else null
        if (newPhase == phase && normalized == this.problem) return
        phase = newPhase
        this.problem = normalized
        for (listener in listeners) {
            try {
                listener(newPhase)
            } catch (_: Throwable) {
                // A broken view must not break the other views or the recorder.
            }
        }
    }

    /** Drops all listeners (on dispose). */
    fun clear() {
        listeners.clear()
    }
}
