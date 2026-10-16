package io.github.simoncek.voiceprompt.state

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.awt.EventQueue

/**
 * The single source of truth for what dictation is doing right now ([DictationPhase]).
 *
 * The push-to-talk action is the only writer. The status bar widget (one instance per project
 * window), the microphone test guard and the settings page are read-only views — none of them
 * owns or infers state, so they can't disagree with what the recorder is actually doing.
 *
 * Registered as an application service (plugin.xml `<applicationService>`), so the platform
 * disposes it at shutdown. Phase changes arriving from worker threads (the capture thread, the
 * transcription task) are marshalled to the EDT before listeners are invoked, because listeners
 * update Swing.
 */
class DictationStatus : Disposable {

    private val tracker = DictationPhaseTracker()

    /** The current phase; safe to read from any thread. */
    val phase: DictationPhase get() = tracker.phase

    /** A short reason while [phase] is PROBLEM (e.g. "Mic did not respond"); null otherwise. */
    val problem: String? get() = tracker.problem

    /** True while the capture line is open (the microphone is busy with dictation). */
    val isCapturing: Boolean get() = tracker.phase.isCapturing

    /** Registers a listener; it is always invoked on the EDT. */
    fun addListener(listener: (DictationPhase) -> Unit) {
        tracker.addListener(listener)
    }

    fun removeListener(listener: (DictationPhase) -> Unit) {
        tracker.removeListener(listener)
    }

    /**
     * Applies the phase derived by [compute] — and [compute] is evaluated on the EDT at apply
     * time, not when the caller asked for the update. A request queued behind a newer one (or
     * behind a synchronous one) therefore never lands a stale value on top of a newer one: the
     * last application always reflects the newest facts.
     *
     * The caller passes a *reader* of the facts, never a phase computed on its own thread: an
     * EDT-immediate call can be overtaken by an older queued one, which would then apply the
     * stale value over the newer one.
     */
    fun applyDerivedPhase(compute: () -> DerivedPhase) {
        if (EventQueue.isDispatchThread()) {
            applyDerived(compute)
        } else {
            ApplicationManager.getApplication().invokeLater { applyDerived(compute) }
        }
    }

    private fun applyDerived(compute: () -> DerivedPhase) {
        val derived = compute()
        tracker.setPhase(derived.phase, derived.problem)
    }

    override fun dispose() {
        tracker.clear()
    }

    companion object {
        val instance: DictationStatus
            get() = ApplicationManager.getApplication().getService(DictationStatus::class.java)
    }
}
