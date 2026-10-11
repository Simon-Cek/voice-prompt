package com.example.copilotvoice.audio

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import java.awt.EventQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live state of the dictation capture, shared between the push-to-talk action (which starts and
 * stops the recording) and the UI that must not open a second capture line while one is running
 * (the microphone test in Settings).
 *
 * The flag itself is a plain [AtomicBoolean] so reads from any thread are safe; UI listeners are
 * always invoked on the EDT, even when the state changes from the capture thread.
 */
object DictationState {

    private val recording = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** Whether a dictation recording is in progress. */
    val isRecording: Boolean get() = recording.get()

    fun addListener(parent: Disposable, listener: (Boolean) -> Unit) {
        listeners += listener
        Disposer.register(parent, Disposable { listeners -= listener })
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    /**
     * Sets the state. Called by the dictation action on the thread that started or stopped the
     * capture; listeners may update Swing because they run on the EDT.
     */
    fun set(value: Boolean) {
        if (recording.getAndSet(value) == value) return
        val invoke = { listeners.forEach { it(value) } }
        if (EventQueue.isDispatchThread()) {
            invoke()
        } else {
            ApplicationManager.getApplication().invokeLater(invoke)
        }
    }
}
