package com.example.copilotvoice.actions

import com.example.copilotvoice.VoiceBundle
import com.example.copilotvoice.audio.AudioCapture
import com.example.copilotvoice.audio.WavFile
import com.example.copilotvoice.stt.ModelManager
import com.example.copilotvoice.stt.WhisperCliEngine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File

class PushToTalkAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        PttState.ensureKeyWatcherInstalled()
        if (!PttState.isRecording) PttState.start(event.project)
    }

    private object PttState {
        const val ACTION_ID = "com.example.copilotvoice.PushToTalk"
        const val NOTIFICATION_GROUP_ID = "CopilotVoice"

        private val MODIFIER_MASK = InputEvent.SHIFT_MASK or
            InputEvent.CTRL_MASK or InputEvent.META_MASK or InputEvent.ALT_MASK

        private val capture = AudioCapture().also { it.onMaxDurationReached = { stop(activeProject()) } }
        @Volatile private var watcherInstalled = false
        private val downKeys = HashMap<Int, Int>()

        val isRecording: Boolean get() = capture.isRecording()

        fun ensureKeyWatcherInstalled() {
            if (watcherInstalled) return
            watcherInstalled = true
            Toolkit.getDefaultToolkit().addAWTEventListener(object : AWTEventListener {
                override fun eventDispatched(event: AWTEvent) {
                    val e = event as? KeyEvent ?: return
                    when (e.id) {
                        KeyEvent.KEY_PRESSED -> onKeyPressed(e)
                        KeyEvent.KEY_RELEASED -> onKeyReleased(e)
                    }
                }
            }, AWTEvent.KEY_EVENT_MASK)
        }

        private fun onKeyPressed(e: KeyEvent) {
            val wasDown = downKeys[e.keyCode] ?: 0
            downKeys[e.keyCode] = wasDown + 1
            if (wasDown > 0) return
            val shortcut = currentShortcut() ?: return
            if (matches(shortcut, e) && !isRecording) start(activeProject())
        }

        private fun onKeyReleased(e: KeyEvent) {
            downKeys[e.keyCode]?.let { count ->
                if (count <= 1) downKeys.remove(e.keyCode) else downKeys[e.keyCode] = count - 1
            }
            val shortcut = currentShortcut() ?: return
            if (isRecording && !comboStillHeld(shortcut)) stop(activeProject())
        }

        private fun activeProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()

        private fun currentShortcut(): KeyboardShortcut? =
            ActionManager.getInstance().getAction(ACTION_ID)?.shortcutSet?.shortcuts
                ?.filterIsInstance<KeyboardShortcut>()?.firstOrNull()

        private fun matches(shortcut: KeyboardShortcut, e: KeyEvent): Boolean {
            val stroke = shortcut.firstKeyStroke ?: return false
            if (e.keyCode != stroke.keyCode) return false
            val pressed = e.modifiers and MODIFIER_MASK
            return (pressed and stroke.modifiers) == stroke.modifiers
        }

        private fun comboStillHeld(shortcut: KeyboardShortcut): Boolean {
            val stroke = shortcut.firstKeyStroke ?: return false
            if (!isDown(stroke.keyCode)) return false
            val mods = stroke.modifiers
            if ((mods and InputEvent.CTRL_MASK) != 0 && !isDown(KeyEvent.VK_CONTROL)) return false
            if ((mods and InputEvent.SHIFT_MASK) != 0 && !isDown(KeyEvent.VK_SHIFT)) return false
            if ((mods and InputEvent.META_MASK) != 0 && !isDown(KeyEvent.VK_META)) return false
            if ((mods and InputEvent.ALT_MASK) != 0 && !isDown(KeyEvent.VK_ALT)) return false
            return true
        }

        private fun isDown(vararg codes: Int): Boolean = codes.any { (downKeys[it] ?: 0) > 0 }

        fun start(project: Project?) {
            try {
                capture.start()
            } catch (t: Exception) {
                notify(project, VoiceBundle.message("notification.mic.open.failed", t.message ?: t.javaClass.simpleName), NotificationType.ERROR)
                return
            }
            notify(project, VoiceBundle["notification.listening"], NotificationType.INFORMATION)
        }

        fun stop(project: Project?) {
            val samples = capture.stop()
            if (samples.isEmpty()) return
            try {
                val wavFile = File(System.getProperty("java.io.tmpdir"), "copilot-voice-${System.currentTimeMillis()}.wav")
                WavFile.write(wavFile, samples, AudioCapture.DEFAULT_FORMAT)
                transcribeInBackground(project, wavFile)
            } catch (t: Exception) {
                notify(project, VoiceBundle.message("notification.wav.failed", t.message ?: t.javaClass.simpleName), NotificationType.WARNING)
            }
        }

        private fun transcribeInBackground(project: Project?, wavFile: File) {
            val model = ModelManager.resolveActive(null, ModelManager.DEFAULT_MODEL)
            if (model == null) {
                notify(project, VoiceBundle.message("notification.no.model", ModelManager.modelsDir()), NotificationType.WARNING)
                wavFile.delete()
                return
            }
            val task = object : Task.Backgroundable(project, VoiceBundle["notification.transcribing"], true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val text = WhisperCliEngine().transcribe(wavFile, model)
                        if (text.isBlank()) {
                            notify(project, VoiceBundle["notification.no.speech"], NotificationType.INFORMATION)
                        } else {
                            notify(project, text, NotificationType.INFORMATION)
                        }
                    } catch (t: Exception) {
                        notify(project, VoiceBundle.message("notification.transcribe.failed", t.message ?: t.javaClass.simpleName), NotificationType.ERROR)
                    } finally {
                        wavFile.delete()
                    }
                }
            }
            task.queue()
        }

        private fun notify(project: Project?, content: String, type: NotificationType) {
            val target = project ?: activeProject() ?: return
            val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
            val notification = group.createNotification(content, type)
            if (java.awt.EventQueue.isDispatchThread()) {
                notification.notify(target)
            } else {
                ApplicationManager.getApplication().invokeLater { notification.notify(target) }
            }
        }
    }
}
