package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * The push-to-talk action.
 *
 * All session state and resources (the AWT key watcher, the audio capture line, the in-flight
 * transcription) live in [DictationSession] — an application service the platform disposes on
 * IDE shutdown and dynamic plugin unload (review #6; AGENTS.md rule 2). This class only forwards
 * the trigger, so an action instance holds no state of its own.
 */
class PushToTalkAction : AnAction() {

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
    }
}
