package io.github.simoncek.voiceprompt.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.audio.AudioCapture
import io.github.simoncek.voiceprompt.audio.Beeps
import io.github.simoncek.voiceprompt.audio.SpeechGate
import io.github.simoncek.voiceprompt.audio.WavFile
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.settings.ProcessingMode
import io.github.simoncek.voiceprompt.settings.RecordingMode
import io.github.simoncek.voiceprompt.sink.ChatSinkChain
import io.github.simoncek.voiceprompt.sink.CopilotVersion
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import io.github.simoncek.voiceprompt.stt.ModelManager
import io.github.simoncek.voiceprompt.stt.WhisperCliEngine
import io.github.simoncek.voiceprompt.stt.WhisperLanguage
import io.github.simoncek.voiceprompt.stt.WhisperRuntime
import io.github.simoncek.voiceprompt.stt.WhisperServerEngine
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The owner of the live dictation session: the global key watcher (an AWT event listener), the
 * audio capture line and the in-flight transcription.
 *
 * This used to be the `PttState` Kotlin object. A Kotlin object lives as long as the plugin's
 * class loader, and the AWT event listener registered with the [Toolkit] keeps a strong reference
 * to it — so on a dynamic plugin unload or update the JVM could never unload the class loader
 * (review #6). As an application service the platform disposes it in both cases, and [dispose]
 * releases every resource the session holds: the AWT listener, the capture line and any
 * whisper-cli process still running (AGENTS.md rule 2).
 *
 * Registered in plugin.xml (`<applicationService>`); look it up via [instance].
 */
class DictationSession : Disposable {

    private val log = Logger.getInstance(DictationSession::class.java)

    private val capture = AudioCapture().also { it.onMaxDurationReached = { stop() } }
    @Volatile private var keyWatcher: AWTEventListener? = null
    private val chatSink = ChatSinkChain.default()
    /** The shared dictation phase; the status bar widget and the mic test are read-only views. */
    private val status = DictationStatus.instance
    /**
     * The project the current recording belongs to, captured when the recording starts. The
     * stop paths (key release, max duration) carry no event project of their own, so delivery
     * and notifications must target this project — not just the first-opened one.
     */
    @Volatile private var recordingProject: Project? = null
    /** Ignores auto-repeat re-fires of the shortcut while the key is still held. */
    private val triggerGuard = ShortcutTriggerGuard()
    /**
     * Guards the asynchronous start sequence (issue #10): set for the whole
     * [startInternal] run and released on every exit path, so a second press while the first
     * is still opening is ignored instead of double-opening the mic.
     */
    private val starting = AtomicBoolean(false)
    /** Set by [stop] while a start is still in progress, so the start aborts before opening the mic. */
    @Volatile private var startCancelled = false
    /** Bounded wait for the start tone before the mic opens (review #10 suggests 400 ms). */
    private val START_BEEP_WAIT_MS = 400L
    /**
     * Review #11: the slow devices report 1–2 s between press and first audio — 5 s of
     * silence means the microphone is not really there, so we stop waiting and say so.
     */
    private val FIRST_SAMPLE_TIMEOUT_MS = 5_000L

    /** Whether the global AWT key watcher is installed (tests and diagnostics). */
    val isKeyWatcherInstalled: Boolean get() = keyWatcher != null

    val isRecording: Boolean get() = capture.isRecording()

    /**
     * Installs the global AWT key listener exactly once (idempotent), so key release and focus
     * loss reach us even when they happen outside the IDE's event flow. Removed again in
     * [dispose] — which is what lets the plugin class loader be unloaded.
     */
    fun installKeyWatcher() {
        if (keyWatcher != null) return
        val listener = object : AWTEventListener {
            override fun eventDispatched(event: AWTEvent) {
                when (event) {
                    is KeyEvent -> if (event.id == KeyEvent.KEY_RELEASED) onKeyReleased(event)
                    is WindowEvent -> if (event.id == WindowEvent.WINDOW_DEACTIVATED) onWindowDeactivated()
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK)
        keyWatcher = listener
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
        // "Opening mic…" in the status bar while the start sequence runs.
        status.setPhase(DictationPhase.OPENING)
        // Issue #10: the whole start sequence (start tone + awaiting it + opening the mic) runs
        // on a pooled thread: the tone must finish *before* the mic opens (afterwards it bleeds
        // into the capture, and the headset profile switch triggered by the mic-open swallows a
        // late tone), and both steps block — so this must not run on the EDT. A second press
        // while the first is still opening is ignored; a release in between cancels via [stop].
        if (!starting.compareAndSet(false, true)) return
        startCancelled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                startInternal(project)
            } finally {
                starting.set(false)
            }
        }
    }

    private fun startInternal(project: Project?) {
        // Start tone first, before the mic opens (issue #10). Awaited with a bound so a slow or
        // wedged audio device cannot stall dictation — the tone is best-effort feedback.
        if (CopilotVoiceSettings.instance.soundEnabled) {
            try {
                Beeps.recordingStart().get(START_BEEP_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // Timeout or (unexpectedly) failure: proceed; the tone is not worth a lost dictation.
            }
        }
        if (startCancelled) {
            // A stop() ran while we were opening (a quick tap): the session is already torn down.
            abortCancelledStart()
            return
        }
        val device = CopilotVoiceSettings.instance.inputDevice
        val fellBack = try {
            capture.start(device)
            false
        } catch (t: Exception) {
            if (startCancelled) {
                abortCancelledStart()
                return
            }
            if (device == null) {
                status.setPhase(DictationPhase.IDLE)
                notify(project, VoiceBundle.message("notification.mic.open.failed", t.message ?: t.javaClass.simpleName), NotificationType.ERROR)
                return
            }
            log.warn("input device '$device' unavailable; retrying with system default", t)
            try {
                capture.start()
            } catch (t2: Exception) {
                if (startCancelled) {
                    abortCancelledStart()
                    return
                }
                status.setPhase(DictationPhase.IDLE)
                notify(project, VoiceBundle.message("notification.mic.open.failed", t2.message ?: t2.javaClass.simpleName), NotificationType.ERROR)
                return
            }
            true
        }
        if (fellBack) {
            notify(project, VoiceBundle.message("notification.mic.fallback", device!!), NotificationType.WARNING)
        }
        // Review #11: the widget shows "Opening mic…" until audio is actually flowing — on the
        // slow machines this delay is real, so "Recording" must mean "you can speak now". A key
        // release in this window already ran stop() and set startCancelled.
        val ready = capture.awaitFirstSample(FIRST_SAMPLE_TIMEOUT_MS)
        if (startCancelled) {
            capture.stop()
            abortCancelledStart()
            return
        }
        if (!ready) {
            // The mic opened but never delivered audio: close the line, surface PROBLEM with a
            // short reason (the widget must not sit on "Opening mic…" forever), and tell the user.
            capture.stop()
            log.warn("microphone opened but produced no audio within ${FIRST_SAMPLE_TIMEOUT_MS} ms")
            val reason = VoiceBundle["statusbar.problem.mic.no.response"]
            status.setPhase(DictationPhase.PROBLEM, reason)
            notify(project, VoiceBundle["notification.mic.no.audio"], NotificationType.ERROR)
            return
        }
        recordingProject = project
        status.setPhase(DictationPhase.RECORDING)
    }

    /** A stop() arrived while the start sequence was still opening: tear down, back to Idle. */
    private fun abortCancelledStart() {
        log.info("start cancelled while in progress")
        status.setPhase(DictationPhase.IDLE)
    }

    fun stop(project: Project? = null) {
        // Prefer the project the recording started in: the stop triggers (key release, max
        // duration) carry no event project of their own, and the first-opened project would
        // be the wrong target in a multi-project window.
        val target = recordingProject ?: project
        recordingProject = null
        val cancelling = starting.get()
        if (cancelling) {
            // A start sequence is still opening the mic (issue #10): cancel it, so it does not
            // start a recording this stop is already tearing down.
            startCancelled = true
        }
        // Review #11, item 4 (opt-in): keep the line open-but-stopped for the next start so it
        // is instant. When this stop is cancelling an in-flight start, keep it off — a line the
        // user just aborted should not be held open.
        val keepSeconds = if (cancelling) 0L else CopilotVoiceSettings.instance.keepMicReadySeconds.toLong()
        val samples = capture.stop(keepSeconds)
        // Stop tone only AFTER the capture line is closed (issue #10): before, it could bleed
        // into the tail of the captured clip.
        if (CopilotVoiceSettings.instance.soundEnabled) Beeps.recordingStop()
        if (samples.isEmpty()) {
            status.setPhase(DictationPhase.IDLE)
            return
        }
        // Pre-inference gates (issue #8): a quick accidental tap or a silent clip must not spawn
        // a whisper-cli process and send a hallucinated transcript.
        val sampleRate = AudioCapture.DEFAULT_FORMAT.sampleRate.toInt()
        if (SpeechGate.durationMs(samples.size, sampleRate) < SpeechGate.MIN_DURATION_MS) {
            log.info("discarding clip: shorter than ${SpeechGate.MIN_DURATION_MS} ms")
            noSpeech(target)
            status.setPhase(DictationPhase.IDLE)
            return
        }
        val rms = SpeechGate.rms(samples)
        if (rms < SpeechGate.MIN_RMS) {
            log.info("discarding clip: rms=$rms below the ${SpeechGate.MIN_RMS} speech floor")
            noSpeech(target)
            status.setPhase(DictationPhase.IDLE)
            return
        }
        // The capture line is closed and whisper is about to run: "Transcribing…".
        status.setPhase(DictationPhase.TRANSCRIBING)
        try {
            val wavFile = File(System.getProperty("java.io.tmpdir"), "copilot-voice-${System.currentTimeMillis()}.wav")
            WavFile.write(wavFile, samples, AudioCapture.DEFAULT_FORMAT)
            transcribeInBackground(target, wavFile)
        } catch (t: Exception) {
            status.setPhase(DictationPhase.IDLE)
            notify(target, VoiceBundle.message("notification.wav.failed", t.message ?: t.javaClass.simpleName), NotificationType.WARNING)
        }
    }

    private fun transcribeInBackground(project: Project?, wavFile: File) {
        val requestedModelId = CopilotVoiceSettings.instance.modelName
        val resolved = ModelManager.resolveActive(null, requestedModelId)
        val task = object : Task.Backgroundable(project, VoiceBundle["notification.transcribing"], true) {
            override fun run(indicator: ProgressIndicator) {
                try {
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
                        val text = WhisperServerEngine.transcribe(
                            wavFile, model,
                            language = language, useGpu = useGpu(), threads = threads(),
                            indicator = indicator,
                        )
                        log.info("transcribe done: len=${text.length} blank=${text.isBlank()}")
                        if (text.isBlank()) {
                            noSpeech(project)
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
                } finally {
                    // Every exit path (delivered, no model, no speech, cancelled, failed)
                    // returns to IDLE so the status bar widget hides again.
                    status.setPhase(DictationPhase.IDLE)
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
                // Sinks may complete asynchronously — e.g. only after the tool window has finished
                // showing (review #7) — so the result, and with it the balloon fallback, arrives
                // through the callback.
                chatSink.insert(project, text, autoSend = CopilotVoiceSettings.instance.autoSend) { result ->
                    if (result.isSuccess) {
                        log.info("deliverToChat: sink insert succeeded")
                    } else {
                        log.warn("deliverToChat: all sinks failed (${CopilotVersion.describe()}) -> balloon", result.exceptionOrNull())
                        fallbackBalloon(project, text)
                    }
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

    /** "No speech" balloon — shared by the pre-inference gates (issue #8) and the blank-transcript path. */
    private fun noSpeech(project: Project?) {
        notify(project, VoiceBundle["notification.no.speech"], NotificationType.INFORMATION)
    }

    /** Balloon action that opens the Voice Prompt settings page (plan Day 2 step 6). */
    private fun openSettingsAction(project: Project?): NotificationAction =
        NotificationAction.create(VoiceBundle["notification.no.model.openSettings"]) { event, _ ->
            // The page is preselected by its display name; that must match the configurable's
            // display name (the "settings.title" bundle key used in plugin.xml).
            ShowSettingsUtil.getInstance().showSettingsDialog(event.project ?: project, VoiceBundle["settings.title"])
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

    /**
     * Releases everything the session holds (AGENTS.md rule 2; review #6):
     *  - the AWT key watcher — while installed it pins the plugin class loader,
     *  - the audio capture line (an in-flight recording is discarded) and any pending
     *    "keep microphone ready" close plus its daemon executor (review #11, item 4),
     *  - any whisper-cli process still running.
     *
     * Called by the platform on IDE shutdown and on dynamic plugin unload. Idempotent — it must
     * not throw if called more than once.
     */
    override fun dispose() {
        keyWatcher?.let { watcher ->
            runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(watcher) }
                .onFailure { log.warn("failed to remove the AWT key watcher on dispose", it) }
        }
        keyWatcher = null
        runCatching { capture.stop() }
            .onFailure { log.warn("failed to close the audio capture line on dispose", it) }
        runCatching { capture.release() }
            .onFailure { log.warn("failed to release the audio capture resources on dispose", it) }
        WhisperCliEngine.killActiveProcesses()
        // The long-lived whisper-server (review P2) outlives any single utterance, so it gets an
        // explicit stop here as well — otherwise it would outlive the plugin (review #6).
        WhisperServerEngine.shutdown()
    }

    companion object {
        private const val ACTION_ID = "io.github.simon-cek.voiceprompt.PushToTalk"
        private const val NOTIFICATION_GROUP_ID = "CopilotVoice"

        val instance: DictationSession
            get() = ApplicationManager.getApplication().getService(DictationSession::class.java)
    }
}
