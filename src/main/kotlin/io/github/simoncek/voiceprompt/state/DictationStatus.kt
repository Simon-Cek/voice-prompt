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

    /** Updates the phase (and, for PROBLEM, a short reason); listeners run on the EDT. */
    fun setPhase(newPhase: DictationPhase, problem: String? = null) {
        val invoke = { tracker.setPhase(newPhase, problem) }
        if (EventQueue.isDispatchThread()) {
            invoke()
        } else {
            ApplicationManager.getApplication().invokeLater(invoke)
        }
    }

    override fun dispose() {
        tracker.clear()
    }

    companion object {
        val instance: DictationStatus
            get() = ApplicationManager.getApplication().getService(DictationStatus::class.java)
    }
}
