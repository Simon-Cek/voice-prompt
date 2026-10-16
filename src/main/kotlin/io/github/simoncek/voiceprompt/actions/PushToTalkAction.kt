package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * The push-to-talk action.
 *
 * All session state and resources (the AWT key watcher, the audio capture line, the in-flight
 * transcription) live in [DictationSession] — an application service the platform disposes on
 * IDE shutdown and dynamic plugin unload (review #6; AGENTS.md rule 2). This class only forwards
 * the trigger, so an action instance holds no state of its own.
 *
 * Review N12: the platform disables every action that lacks the [DumbAware] marker while the
 * IDE indexes, so on a large repo the dictation shortcut would die exactly when the user wants
 * it. [DictationSession] needs no smart project (it dispatches to pooled threads and reads only
 * the event's input/project), so carrying the marker is safe. (Verified against IU-2025.3.6.1:
 * the marker is `com.intellij.openapi.project.DumbAware`; `DumbAwareAction` there is an abstract
 * class requiring `isAvailable`, so the marker on [AnAction] is the minimal form.)
 * [getActionUpdateThread] stays on BGT — the platform's current default, keeping action
 * updates off the EDT.
 */
class PushToTalkAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val session = DictationSession.instance
        session.installKeyWatcher()
        session.onShortcutPressed(event)
    }

    companion object {
        /**
         * Stops the in-flight recording from anywhere (the status bar widget's click handler).
         * The manual escape hatch for the stuck-recording case (issue #5) if a key release was
         * lost.
         */
        fun stopRecording() {
            DictationSession.instance.stop()
        }

        /**
         * Cancels every in-flight and queued transcription from anywhere (the status bar
         * widget's click while TRANSCRIBING, review §3). Each live clip is reached through its
         * [TranscriptionHandle]: a running clip's [ProgressIndicator] is cancelled, and a clip
         * whose task has not started yet bails the moment its task does — the engine never
         * runs for a cancelled clip.
         */
        fun cancelTranscription() {
            DictationSession.instance.cancelTranscription()
        }
    }
}
