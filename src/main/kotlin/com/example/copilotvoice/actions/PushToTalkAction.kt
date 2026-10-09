package com.example.copilotvoice.actions

import com.example.copilotvoice.VoiceBundle
import com.example.copilotvoice.audio.AudioCapture
import com.example.copilotvoice.audio.Beeps
import com.example.copilotvoice.audio.WavFile
import com.example.copilotvoice.settings.CopilotVoiceSettings
import com.example.copilotvoice.settings.ProcessingMode
import com.example.copilotvoice.settings.RecordingMode
import com.example.copilotvoice.sink.ChatSinkChain
import com.example.copilotvoice.stt.ModelManager
import com.example.copilotvoice.stt.WhisperCliEngine
import com.example.copilotvoice.stt.WhisperRuntime
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
        PttState.onShortcutPressed(event.project)
    }

    private object PttState {
        const val ACTION_ID = "com.example.copilotvoice.PushToTalk"
        const val NOTIFICATION_GROUP_ID = "CopilotVoice"

        private val log = Logger.getInstance(PushToTalkAction::class.java)

        private val capture = AudioCapture().also { it.onMaxDurationReached = { stop(activeProject()) } }
        @Volatile private var watcherInstalled = false
        private val downKeys = HashMap<Int, Int>()
        private val chatSink = ChatSinkChain.default()
        @Volatile private var listeningNotification: Notification? = null

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
        }

        private fun onKeyReleased(e: KeyEvent) {
            downKeys[e.keyCode]?.let { count ->
                if (count <= 1) downKeys.remove(e.keyCode) else downKeys[e.keyCode] = count - 1
            }
            if (CopilotVoiceSettings.instance.mode != RecordingMode.HOLD) return
            val shortcut = currentShortcut() ?: return
            if (isRecording && !comboStillHeld(shortcut)) stop(activeProject())
        }

        private fun activeProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()

        private fun currentShortcut(): KeyboardShortcut? =
            ActionManager.getInstance().getAction(ACTION_ID)?.shortcutSet?.shortcuts
                ?.filterIsInstance<KeyboardShortcut>()?.firstOrNull()

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

        fun onShortcutPressed(project: Project?) {
            when (CopilotVoiceSettings.instance.mode) {
                RecordingMode.TAP -> if (isRecording) stop(project) else start(project)
                RecordingMode.HOLD -> if (!isRecording) start(project)
            }
        }

        fun start(project: Project?) {
            val device = CopilotVoiceSettings.instance.inputDevice
            val fellBack = try {
                capture.start(device)
                false
            } catch (t: Exception) {
                if (device == null) {
                    notify(project, VoiceBundle.message("notification.mic.open.failed", t.message ?: t.javaClass.simpleName), NotificationType.ERROR)
                    return
                }
                log.warn("input device '$device' unavailable; retrying with system default", t)
                try {
                    capture.start()
                } catch (t2: Exception) {
                    notify(project, VoiceBundle.message("notification.mic.open.failed", t2.message ?: t2.javaClass.simpleName), NotificationType.ERROR)
                    return
                }
                true
            }
            if (fellBack) {
                notify(project, VoiceBundle.message("notification.mic.fallback", device!!), NotificationType.WARNING)
            }
            if (CopilotVoiceSettings.instance.soundEnabled) Beeps.recordingStart()
            listeningNotification = notify(project, VoiceBundle["notification.listening"], NotificationType.INFORMATION)
        }

        fun stop(project: Project?) {
            if (CopilotVoiceSettings.instance.soundEnabled) Beeps.recordingStop()
            dismissListening()
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
            val model = ModelManager.resolveActive(null, CopilotVoiceSettings.instance.modelName)
            if (model == null) {
                log.warn("no active model found; modelsDir=${ModelManager.modelsDir()}")
                notify(project, VoiceBundle.message("notification.no.model", ModelManager.modelsDir()), NotificationType.WARNING)
                wavFile.delete()
                return
            }
            log.info("transcribe starting: model=$model wav=${wavFile.absolutePath}")
            val task = object : Task.Backgroundable(project, VoiceBundle["notification.transcribing"], true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val text = WhisperCliEngine().transcribe(wavFile, model, language = CopilotVoiceSettings.instance.language, useGpu = useGpu(), threads = threads())
                        log.info("transcribe done: len=${text.length} blank=${text.isBlank()}")
                        if (text.isBlank()) {
                            notify(project, VoiceBundle["notification.no.speech"], NotificationType.INFORMATION)
                        } else {
                            deliverToChat(project, text)
                        }
                    } catch (t: Throwable) {
                        log.error("transcribe threw", t)
                        notify(project, VoiceBundle.message("notification.transcribe.failed", t.message ?: t.javaClass.simpleName), NotificationType.ERROR)
                    } finally {
                        wavFile.delete()
                    }
                }
            }
            task.queue()
        }

        /** Whether to run inference on the GPU for the user's Processing choice. */
        private fun useGpu(): Boolean {
            val mode = CopilotVoiceSettings.instance.processingMode
            return when (mode) {
                ProcessingMode.CPU -> false
                // GPU by default in whisper.cpp; we only opt out (CPU) when the user chose CPU or
                // no Vulkan backend is bundled for this platform.
                ProcessingMode.GPU, ProcessingMode.AUTOMATIC -> WhisperRuntime.gpuBackendAvailable()
            }
        }

        /** CPU threads for whisper: the user's setting, or the recommended physical core count. */
        private fun threads(): Int {
            val set = CopilotVoiceSettings.instance.threads
            return if (set > 0) set else WhisperRuntime.recommendedThreads()
        }

        private fun deliverToChat(project: Project?, text: String) {
            ApplicationManager.getApplication().invokeLater {
                try {
                    log.info("deliverToChat on EDT: inserting ${text.length} chars")
                    val result = chatSink.insert(project, text, autoSend = CopilotVoiceSettings.instance.autoSend)
                    if (result.isSuccess) {
                        log.info("deliverToChat: sink insert succeeded")
                    } else {
                        log.warn("deliverToChat: all sinks failed -> balloon", result.exceptionOrNull())
                        notify(project, text, NotificationType.INFORMATION)
                    }
                } catch (t: Throwable) {
                    log.error("deliverToChat threw -> balloon", t)
                    notify(project, text, NotificationType.INFORMATION)
                }
            }
        }

        private fun dismissListening() {
            val n = listeningNotification ?: return
            listeningNotification = null
            if (java.awt.EventQueue.isDispatchThread()) {
                n.expire()
            } else {
                ApplicationManager.getApplication().invokeLater { n.expire() }
            }
        }

        private fun notify(project: Project?, content: String, type: NotificationType): Notification? {
            val target = project ?: activeProject() ?: return null
            val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
            val notification = group.createNotification(content, type)
            if (java.awt.EventQueue.isDispatchThread()) {
                notification.notify(target)
            } else {
                ApplicationManager.getApplication().invokeLater { notification.notify(target) }
            }
            return notification
        }
    }
}
