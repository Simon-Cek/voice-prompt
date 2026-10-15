package io.github.simoncek.voiceprompt.actions

import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.audio.AudioCapture
import io.github.simoncek.voiceprompt.audio.Beeps
import io.github.simoncek.voiceprompt.audio.DictationState
import io.github.simoncek.voiceprompt.audio.WavFile
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.settings.ProcessingMode
import io.github.simoncek.voiceprompt.settings.RecordingMode
import io.github.simoncek.voiceprompt.sink.ChatSinkChain
import io.github.simoncek.voiceprompt.sink.CopilotVersion
import io.github.simoncek.voiceprompt.stt.ModelManager
import io.github.simoncek.voiceprompt.stt.WhisperCliEngine
import io.github.simoncek.voiceprompt.stt.WhisperLanguage
import io.github.simoncek.voiceprompt.stt.WhisperRuntime
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.io.File

class PushToTalkAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        PttState.ensureKeyWatcherInstalled()
        PttState.onShortcutPressed(event)
    }

    private object PttState {
        const val ACTION_ID = "io.github.simon-cek.voiceprompt.PushToTalk"
        const val NOTIFICATION_GROUP_ID = "CopilotVoice"

        private val log = Logger.getInstance(PushToTalkAction::class.java)

        private val capture = AudioCapture().also { it.onMaxDurationReached = { stop() } }
        @Volatile private var watcherInstalled = false
        private val chatSink = ChatSinkChain.default()
        @Volatile private var listeningNotification: Notification? = null
        /**
         * The project the current recording belongs to, captured when the recording starts. The
         * stop paths (key release, max duration) carry no event project of their own, so delivery
         * and notifications must target this project — not just the first-opened one.
         */
        @Volatile private var recordingProject: Project? = null
        /** Ignores auto-repeat re-fires of the shortcut while the key is still held. */
        private val triggerGuard = ShortcutTriggerGuard()

        val isRecording: Boolean get() = capture.isRecording()

        fun ensureKeyWatcherInstalled() {
            if (watcherInstalled) return
            watcherInstalled = true
            Toolkit.getDefaultToolkit().addAWTEventListener(object : AWTEventListener {
                override fun eventDispatched(event: AWTEvent) {
                    when (event) {
                        is KeyEvent -> if (event.id == KeyEvent.KEY_RELEASED) onKeyReleased(event)
                        is WindowEvent -> if (event.id == WindowEvent.WINDOW_DEACTIVATED) onWindowDeactivated()
                    }
                }
            }, AWTEvent.KEY_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK)
        }

        private fun onKeyReleased(e: KeyEvent) {
            val stroke = currentShortcut()?.firstKeyStroke ?: return
            if (e.keyCode == stroke.keyCode) triggerGuard.mainKeyReleased()
            // Hold mode: the recording ends as soon as *any* key of the shortcut is released.
            // A release is a single event, so — unlike the old press counter — auto-repeat
            // cannot wedge it "still held".
            if (CopilotVoiceSettings.instance.mode == RecordingMode.HOLD && isRecording &&
                e.keyCode in shortcutKeyCodes(stroke.keyCode, stroke.modifiers)
            ) {
                stop()
            }
        }

        private fun onWindowDeactivated() {
            // Safety net for a lost key-up (e.g. the Alt key activating a menu before its
            // release is seen): in Hold mode we would otherwise wait forever for a release
            // that never comes and stay stuck in the "listening" state.
            if (CopilotVoiceSettings.instance.mode == RecordingMode.HOLD && isRecording) stop()
        }

        private fun activeProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()

        private fun currentShortcut(): KeyboardShortcut? =
            ActionManager.getInstance().getAction(ACTION_ID)?.shortcutSet?.shortcuts
                ?.filterIsInstance<KeyboardShortcut>()?.firstOrNull()

        fun onShortcutPressed(event: AnActionEvent) {
            // A keyboard trigger that arrives while the shortcut's main key has not been released
            // since the previous trigger is an auto-repeat — or a restart attempt after the 60 s
            // cap while the key is still held — and is ignored. Mouse, menu and programmatic
            // triggers carry no KeyEvent input event and always pass.
            if (event.inputEvent is KeyEvent && !triggerGuard.consume()) return
            val project = event.project
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
            recordingProject = project
            DictationState.set(true)
            if (CopilotVoiceSettings.instance.soundEnabled) Beeps.recordingStart()
            listeningNotification = notify(project, VoiceBundle["notification.listening"], NotificationType.INFORMATION)
        }

        fun stop(project: Project? = null) {
            // Prefer the project the recording started in: the stop triggers (key release, max
            // duration) carry no event project of their own, and the first-opened project would
            // be the wrong target in a multi-project window.
            val target = recordingProject ?: project
            recordingProject = null
            if (CopilotVoiceSettings.instance.soundEnabled) Beeps.recordingStop()
            dismissListening()
            val samples = capture.stop()
            DictationState.set(false)
            if (samples.isEmpty()) return
            try {
                val wavFile = File(System.getProperty("java.io.tmpdir"), "copilot-voice-${System.currentTimeMillis()}.wav")
                WavFile.write(wavFile, samples, AudioCapture.DEFAULT_FORMAT)
                transcribeInBackground(target, wavFile)
            } catch (t: Exception) {
                notify(target, VoiceBundle.message("notification.wav.failed", t.message ?: t.javaClass.simpleName), NotificationType.WARNING)
            }
        }

        private fun transcribeInBackground(project: Project?, wavFile: File) {
            val requestedModelId = CopilotVoiceSettings.instance.modelName
            val resolved = ModelManager.resolveActive(null, requestedModelId)
            val task = object : Task.Backgroundable(project, VoiceBundle["notification.transcribing"], true) {
                override fun run(indicator: ProgressIndicator) {
                    // Resolve the model here, off the EDT: restoring the bundled model copies and
                    // checksums a ~75 MB file.
                    var model = resolved
                    var activeModelId = requestedModelId
                    if (model == null) {
                        if (requestedModelId == ModelManager.BUNDLED_MODEL) {
                            // The selected model is the bundled one but hasn't been extracted to
                            // disk yet (fresh install): extract it. This is the expected first-use
                            // path, not a fallback, so no "we fell back" notification.
                            val bundled = ModelManager.installBundledModel()
                            if (bundled != null) {
                                model = bundled
                                activeModelId = ModelManager.BUNDLED_MODEL
                                log.info("extracting bundled model for first use: $bundled")
                            }
                        } else {
                            // The selected model isn't installed (e.g. no network to download it):
                            // fall back to the model bundled inside the plugin.
                            val bundled = ModelManager.installBundledModel()
                            if (bundled != null) {
                                model = bundled
                                activeModelId = ModelManager.BUNDLED_MODEL
                                log.info("selected model '$requestedModelId' not installed; using bundled $bundled")
                                notify(project, VoiceBundle["notification.bundled.fallback"], NotificationType.INFORMATION)
                            }
                        }
                    }
                    if (model == null) {
                        log.warn("no active model found; modelsDir=${ModelManager.modelsDir()}")
                        notify(
                            project,
                            VoiceBundle.message("notification.no.model", ModelManager.modelsDir()),
                            NotificationType.WARNING,
                            openSettingsAction(project),
                        )
                        wavFile.delete()
                        return
                    }
                    try {
                        log.info("transcribe starting: model=$model wav=${wavFile.absolutePath}")
                        val language = if (ModelManager.isEnglishOnly(activeModelId)) WhisperLanguage.ENGLISH.code else CopilotVoiceSettings.instance.language
                        val text = WhisperCliEngine().transcribe(
                            wavFile, model,
                            language = language, useGpu = useGpu(), threads = threads(),
                            indicator = indicator,
                        )
                        log.info("transcribe done: len=${text.length} blank=${text.isBlank()}")
                        if (text.isBlank()) {
                            notify(project, VoiceBundle["notification.no.speech"], NotificationType.INFORMATION)
                        } else {
                            deliverToChat(project, text)
                        }
                    } catch (t: Throwable) {
                        if (indicator.isCanceled) {
                            log.info("transcription cancelled by user")
                        } else {
                            log.error("transcribe threw", t)
                            notify(project, VoiceBundle.message("notification.transcribe.failed", t.message ?: t.javaClass.simpleName), NotificationType.ERROR)
                        }
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

        /** CPU threads for whisper: the user's setting, or half the logical processor count. */
        private fun threads(): Int {
            val set = CopilotVoiceSettings.instance.threads
            return if (set > 0) set else WhisperRuntime.defaultThreads()
        }

        private fun deliverToChat(project: Project?, text: String) {
            ApplicationManager.getApplication().invokeLater {
                try {
                    log.info("deliverToChat on EDT: inserting ${text.length} chars")
                    val result = chatSink.insert(project, text, autoSend = CopilotVoiceSettings.instance.autoSend)
                    if (result.isSuccess) {
                        log.info("deliverToChat: sink insert succeeded")
                    } else {
                        log.warn("deliverToChat: all sinks failed (${CopilotVersion.describe()}) -> balloon", result.exceptionOrNull())
                        fallbackBalloon(project, text)
                    }
                } catch (t: Throwable) {
                    log.error("deliverToChat threw (${CopilotVersion.describe()}) -> balloon", t)
                    fallbackBalloon(project, text)
                }
            }
        }

        private fun fallbackBalloon(project: Project?, text: String) {
            notify(project, VoiceBundle.message("notification.insert.failed", CopilotVersion.describe()) + text, NotificationType.INFORMATION)
        }

        /** Balloon action that opens the Voice Prompt settings page (plan Day 2 step 6). */
        private fun openSettingsAction(project: Project?): NotificationAction =
            NotificationAction.create(VoiceBundle["notification.no.model.openSettings"]) { event, _ ->
                // The page is preselected by its display name; that must match the configurable's
                // display name (the "settings.title" bundle key used in plugin.xml).
                ShowSettingsUtil.getInstance().showSettingsDialog(event.project ?: project, VoiceBundle["settings.title"])
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

        private fun notify(
            project: Project?,
            content: String,
            type: NotificationType,
            vararg actions: NotificationAction,
        ): Notification? {
            val target = project ?: activeProject() ?: return null
            val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
            val notification = group.createNotification(content, type)
            for (action in actions) {
                notification.addAction(action)
            }
            if (java.awt.EventQueue.isDispatchThread()) {
                notification.notify(target)
            } else {
                ApplicationManager.getApplication().invokeLater { notification.notify(target) }
            }
            return notification
        }
    }
}
