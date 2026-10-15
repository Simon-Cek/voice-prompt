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
}

/** Whether the capture line is open (the microphone is busy with dictation). */
val DictationPhase.isCapturing: Boolean
    get() = this == DictationPhase.OPENING || this == DictationPhase.RECORDING

/**
 * Thread-safe holder of the current [DictationPhase] with listener notification.
 *
 * Deliberately pure — no platform imports — so the transition logic can be unit tested without
 * the IntelliJ runtime. Listeners are invoked on the calling thread; the application service
 * ([DictationStatus]) wraps this with EDT marshalling.
 */
class DictationPhaseTracker {

    @Volatile
    var phase: DictationPhase = DictationPhase.IDLE
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
     * Updates the phase; a no-op when unchanged. A listener that throws is skipped and the rest
     * still run, so one broken view cannot wedge the others.
     */
    fun setPhase(newPhase: DictationPhase) {
        if (newPhase == phase) return
        phase = newPhase
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
