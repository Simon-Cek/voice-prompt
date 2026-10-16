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
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.audio.AudioCapture
import io.github.simoncek.voiceprompt.audio.Beeps
import io.github.simoncek.voiceprompt.audio.MicrophoneTester
import io.github.simoncek.voiceprompt.audio.SpeechGate
import io.github.simoncek.voiceprompt.audio.WavFile
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.settings.ProcessingMode
import io.github.simoncek.voiceprompt.settings.RecordingMode
import io.github.simoncek.voiceprompt.sink.ChatSinkChain
import io.github.simoncek.voiceprompt.sink.CopilotVersion
import io.github.simoncek.voiceprompt.state.DictationStatus
import io.github.simoncek.voiceprompt.state.derivePhase
import io.github.simoncek.voiceprompt.stt.GpuHealth
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
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Review N1: whether a key release in [mode] should end the session.
 *
 * A session is *live* while it is already recording **or** while a start is still in progress
 * (the beep window, before the capture line is open). Before N1 the check was `isRecording`
 * only, so a release during that start window was ignored and an accidental tap began a full
 * 60 s recording. [keyCode] must be one of the shortcut's keys ([comboKeys]).
 */
internal fun shouldStopOnRelease(
    mode: RecordingMode,
    isRecording: Boolean,
    isStarting: Boolean,
    keyCode: Int,
    comboKeys: Set<Int>,
): Boolean = mode == RecordingMode.HOLD && (isRecording || isStarting) && keyCode in comboKeys

/**
 * Review N1: whether a window deactivation in [mode] should end the session (the safety net for
 * a lost key-up). A session is live while recording or while a start is still in progress.
 */
internal fun shouldStopOnDeactivate(
    mode: RecordingMode,
    isRecording: Boolean,
    isStarting: Boolean,
): Boolean = mode == RecordingMode.HOLD && (isRecording || isStarting)

/**
 * Review §3: the per-clip cancellation handle (the per-clip object from review N4, extended).
 *
 * The platform's [Task] has no `cancel()` of its own — its [ProgressIndicator] is the
 * cancellation seam — and a clip whose task has not started yet has no indicator to cancel.
 * So [DictationSession.cancelTranscription] reaches every live clip through its handle: it
 * sets [cancelRequested], which [DictationSession.transcribeBody] checks first (so a clip
 * whose task has not started yet bails the moment it does, before the engine ever runs), and
 * cancels [indicator] once the task has started.
 *
 * The handle also carries the exactly-once release flag (review N4): [releaseOnce] lets
 * whichever exit path fires first — `transcribeBody`'s finally, the task's
 * onCancel/onFinished/onThrowable, or a failed queue()/WAV hand-off — take the clip, and a
 * duplicate release is a no-op.
 */
internal class TranscriptionHandle {

    private val released = AtomicBoolean(false)

    /** Set when the task starts ([Task.Backgroundable.run]); [cancel] then cancels the running task. */
    @Volatile
    var indicator: ProgressIndicator? = null

    /** Set by [cancel]; checked at the start of `transcribeBody` and while it waits its turn. */
    @Volatile
    var cancelRequested: Boolean = false
        private set

    fun cancel() {
        cancelRequested = true
        indicator?.cancel()
    }

    /** Exactly-once release (review N4): the first call wins, later calls are no-ops. */
    fun releaseOnce(): Boolean = released.compareAndSet(false, true)
}

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

    private val capture = AudioCapture().also {
        it.onMaxDurationReached = { stop() }
        // Review N6: the capture's reader thread reports the mic stopped delivering — surface
        // PROBLEM and tear the session down through the normal stop path.
        it.onDeviceLost = { onMicLost() }
    }
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
    /**
     * Review N4: one of the facts the phase is derived from ([derivePhase]). True while the
     * capture line is open and audio is actually flowing: set on [startInternal]'s success
     * path, ended when [finishStop] closes the line. Set and ended exactly once per session,
     * so "Recording" always means the mic is open — and ending one recording can never be
     * confused with another session's.
     */
    @Volatile private var audioConfirmed = false
    /**
     * Review N4: one of the facts the phase is derived from ([derivePhase]). The last failure
     * reason, non-null while the phase is PROBLEM: set on [startInternal]'s no-audio path,
     * cleared by the next [start] claim, so a new session never inherits the previous failure
     * and an old one cannot linger after a new session begins.
     */
    @Volatile private var lastProblem: String? = null
    /** Set by [stop] while a start is still in progress, so the start aborts before opening the mic. */
    @Volatile private var startCancelled = false
    /**
     * Review N2: at most one stop per session. Claimed by the first [stop] and reset by the next
     * [start], so a duplicate stop (two keys of the chord released within milliseconds, or a
     * release coinciding with a focus loss or a status-bar click) is dropped instead of running a
     * second [finishStop] — that would play the stop tone again and could drop a live
     * TRANSCRIBING to IDLE.
     */
    private val stopClaimed = AtomicBoolean(false)
    /**
     * Review N2: makes the claim/reset sequence atomic — [start] claims [starting] and resets
     * [stopClaimed] together, [stop] claims [stopClaimed] and decides [startCancelled] together.
     * Without it a stop() landing between start()'s claim and its reset of [stopClaimed] would be
     * silently dropped. No blocking work is done inside the lock; [finishStop] is dispatched only
     * after it is released.
     */
    private val sessionLock = ReentrantLock()
    /** Bounded wait for the start tone before the mic opens (review #10 suggests 400 ms). */
    private val START_BEEP_WAIT_MS = 400L
    /**
     * Review #11: the slow devices report 1–2 s between press and first audio — 5 s of
     * silence means the microphone is not really there, so we stop waiting and say so.
     */
    private val FIRST_SAMPLE_TIMEOUT_MS = 5_000L
    /**
     * Review P5: at most one transcription at a time — held across the whole body of
     * [transcribeBody] (model resolution, inference, delivery), so a queued clip never runs
     * under a live one. A live clip's cancel or failure would otherwise kill the shared
     * whisper-server (or double a whisper-cli on the fallback path) out from under the other.
     * Fair: the longest-waiting clip gets the lock next, so clips queue in order instead of
     * racing for it.
     */
    private val transcriptionLock = ReentrantLock(true)
    /**
     * Review P5: transcriptions currently running (or waiting for the lock). Review N4: a clip
     * is claimed in [finishCapturedClip] before the task queue runs it and released exactly
     * once by
     * [releaseTranscription] (whichever exit path takes it first), and the shared phase is
     * derived from this counter — so an earlier clip finishing must neither hide a live
     * recording nor the status bar widget while a later one is still running.
     */
    private val activeTranscriptions = AtomicInteger(0)
    /**
     * Review §3: every live transcription clip — running and queued — with its
     * [TranscriptionHandle]. [cancelTranscription] reaches each live clip through its handle;
     * a released clip removes its handle, so a later cancel cannot reach a dead clip.
     */
    private val activeHandles = CopyOnWriteArraySet<TranscriptionHandle>()
    /** Wait-loop poll while a queued transcription holds its turn (review P5). */
    private val TRANSCRIPTION_QUEUE_POLL_MS = 100L

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
                    // Review N3: both focus boundaries reach the handlers, which re-arm the
                    // auto-repeat guard only when the boundary crossed the *application*
                    // boundary (the opposite window is not in this JVM).
                    is WindowEvent -> when (event.id) {
                        WindowEvent.WINDOW_DEACTIVATED -> onWindowDeactivated(event)
                        WindowEvent.WINDOW_ACTIVATED -> onWindowActivated(event)
                        else -> Unit
                    }
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK)
        keyWatcher = listener
    }

    internal fun onKeyReleased(e: KeyEvent) {
        val stroke = currentShortcut()?.firstKeyStroke ?: return
        if (e.keyCode == stroke.keyCode) triggerGuard.mainKeyReleased()
        // Hold mode: the recording ends as soon as *any* key of the shortcut is released.
        // A release is a single event, so — unlike the old press counter — auto-repeat
        // cannot wedge it "still held". Review N1: "live" also covers a start that is still
        // in progress (isStarting), not only an already-running recording.
        if (shouldStopOnRelease(
                CopilotVoiceSettings.instance.mode,
                isRecording,
                starting.get(),
                e.keyCode,
                shortcutKeyCodes(stroke.keyCode, stroke.modifiers),
            )
        ) {
            stop()
        }
    }

    /**
     * WINDOW_DEACTIVATED.
     *
     * Review N3: when [WindowEvent.getOppositeWindow] is null, the boundary crossed the
     * application — the key-up of a still-held key went to another application and will never
     * be seen here — so the auto-repeat guard is re-armed and the next fresh press in the IDE
     * is honoured (before N3 it was swallowed as an "auto-repeat" and the user had to press
     * twice). An in-app switch (dialog, tool window) keeps the key-up inside the IDE and must
     * not re-arm, or an auto-repeat could re-trigger while the key is still held: TAP would
     * toggle start/stop, and HOLD could restart the recording the focus loss just stopped.
     * Holding the key across an alt-tab round trip can re-arm in both modes — exotic, and
     * still preferable to swallowing the next real press.
     *
     * The stop decision is unchanged (review N1): *any* deactivation stops a live session,
     * in-app or cross-app.
     */
    internal fun onWindowDeactivated(e: WindowEvent) {
        if (e.oppositeWindow == null) triggerGuard.rearm()
        // Safety net for a lost key-up (e.g. the Alt key activating a menu before its
        // release is seen): in Hold mode we would otherwise wait forever for a release
        // that never comes and stay stuck in the "listening" state. Review N1: also covers
        // a start that is still in progress.
        if (shouldStopOnDeactivate(CopilotVoiceSettings.instance.mode, isRecording, starting.get())) stop()
    }

    /**
     * WINDOW_ACTIVATED — the second chance for the review-N3 re-arm when the deactivation
     * event was missed: the same application-boundary rule as [onWindowDeactivated].
     * Activation itself never stops a session.
     */
    internal fun onWindowActivated(e: WindowEvent) {
        if (e.oppositeWindow == null) triggerGuard.rearm()
    }

    private fun activeProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()

    internal fun currentShortcut(): KeyboardShortcut? =
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
        // Issue #10: the whole start sequence (start tone + awaiting it + opening the mic) runs
        // on a pooled thread: the tone must finish *before* the mic opens (afterwards it bleeds
        // into the capture, and the headset profile switch triggered by the mic-open swallows a
        // late tone), and both steps block — so this must not run on the EDT. A second press
        // while the first is still opening is ignored; a release in between cancels via [stop].
        // Review N2: claim the start and reset the stop claim atomically, so a stop() (a quick
        // release) cannot land between the two and be silently dropped. No blocking work in the
        // lock; the start sequence is dispatched after it is released.
        // Review N4: the "Opening…" phase is not written here — it is derived from the
        // [starting] fact once it is claimed ([recomputePhase]) — and a new session clears the
        // previous failure reason, so the phase cannot sit on an old PROBLEM once this start
        // is live.
        var started = false
        sessionLock.lock()
        try {
            if (starting.compareAndSet(false, true)) {
                startCancelled = false
                stopClaimed.set(false) // a new session may be stopped once
                lastProblem = null // a new session does not inherit the previous failure
                started = true
            }
        } finally {
            sessionLock.unlock()
        }
        if (!started) return
        // Review N14: dictation takes the microphone. A mic test still running from the settings
        // page (the shortcut can fire even over the modal dialog) is stopped now — the test side
        // already refuses to start while dictation captures (MicrophoneTester.start), so the two
        // capture paths are mutually exclusive in both directions. Safe on the EDT: stop() only
        // sets flags and schedules the line close on a pooled thread.
        MicrophoneTester.stopAll()
        // OPENING — derived: [starting] is true now, and it beats an in-flight TRANSCRIBING.
        recomputePhase()
        // Test seam first (review N2: a test runs start() — and its claim reset — without the
        // beep + mic open), else the production pooled-thread path.
        startWorkDispatcher?.invoke {
            try {
                startInternal(project)
            } finally {
                // Review N4: only this outermost finally clears [starting] and recomputes —
                // the branches inside [startInternal] never do, so a new session cannot claim
                // [starting] in the gap and have this finally stomp it (which would also break
                // the N2 stop-during-start decision, which reads [starting] under [sessionLock]).
                starting.set(false)
                recomputePhase()
            }
        } ?: ApplicationManager.getApplication().executeOnPooledThread {
            try {
                startInternal(project)
            } finally {
                starting.set(false)
                recomputePhase()
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
                // Review N4: no phase write here — [start]'s finally recomputes from the facts
                // (nothing confirmed, [starting] cleared): IDLE, unless a clip is still
                // transcribing, which a blind IDLE write would have hidden.
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
                // Review N4: as above — the phase is derived in [start]'s finally, not written.
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
            // Review N4: the PROBLEM phase is derived from the [lastProblem] fact in
            // [start]'s finally — and only while nothing more live (a clip) is running.
            capture.stop()
            log.warn("microphone opened but produced no audio within ${FIRST_SAMPLE_TIMEOUT_MS} ms")
            lastProblem = VoiceBundle["statusbar.problem.mic.no.response"]
            notify(project, VoiceBundle["notification.mic.no.audio"], NotificationType.ERROR)
            return
        }
        recordingProject = project
        // Review N4: RECORDING is derived from this fact; recompute directly (the user may
        // react before [start]'s finally runs — [starting] is still true here, and
        // [audioConfirmed] wins on priority either way).
        audioConfirmed = true
        recomputePhase()
    }

    /** A stop() arrived while the start sequence was still opening: the session is already torn down. */
    private fun abortCancelledStart() {
        log.info("start cancelled while in progress")
        // Review N4: no phase write here — [start]'s finally clears [starting] and recomputes:
        // IDLE if nothing else is live, or TRANSCRIBING if an earlier clip is still running
        // (a blind IDLE write would have hidden it). The previous session's failure reason was
        // already cleared by this session's claim, so a cancelled start cannot resurrect it.
    }

    /**
     * Review N6: the capture's reader thread reports the microphone stopped delivering
     * mid-recording (a device loss). Surface PROBLEM with a short reason, tell the user (the
     * captured part still transcribes through the normal stop path), and tear the session down.
     * [stop]'s claim (review N2) makes this safe even if the user releases the key at the same
     * instant — the second stop is dropped.
     */
    internal fun onMicLost() {
        lastProblem = VoiceBundle["statusbar.problem.mic.lost"]
        recomputePhase()
        notify(recordingProject, VoiceBundle["notification.mic.lost"], NotificationType.ERROR)
        stop()
    }

    /**
     * Ends the recording and hands the clip to transcription.
     *
     * Review P4: the stop tail is blocking — `capture.stop()` joins the reader thread (up to
     * 2 s), the WAV write is disk I/O, and the model resolve reads the disk — yet this runs
     * on the EDT for a key-triggered stop (TAP, status-bar click) and on the AWT event thread
     * for Hold-mode releases. Only the cheap state transitions happen on the calling thread;
     * the rest is queued to a pooled thread, exactly like the start sequence (issue #10).
     * Callers see no behaviour change: the stop tone still plays after the capture line is
     * closed (issue #10), and the phase transitions still land on the EDT (`DictationStatus`
     * and [notify] marshal themselves).
     */
    fun stop(project: Project? = null) {
        // Review N2: claim the stop atomically together with the "is a start in progress"
        // decision, so a duplicate is dropped and an in-progress start is cancelled in one
        // atomic step. No blocking work in the lock; the teardown is dispatched after it.
        var claimed = false
        var cancelling = false
        sessionLock.lock()
        try {
            if (stopClaimed.compareAndSet(false, true)) {
                claimed = true
                cancelling = starting.get()
                if (cancelling) {
                    // A start sequence is still opening the mic (issue #10): cancel it, so it
                    // does not start a recording this stop is already tearing down.
                    startCancelled = true
                }
            }
        } finally {
            sessionLock.unlock()
        }
        if (!claimed) return
        // Prefer the project the recording started in: the stop triggers (key release, max
        // duration) carry no event project of their own, and the first-opened project would
        // be the wrong target in a multi-project window.
        val target = recordingProject ?: project
        recordingProject = null
        // Review #11, item 4 (opt-in): keep the line open-but-stopped for the next start so it
        // is instant. When this stop is cancelling an in-flight start, keep it off — a line the
        // user just aborted should not be held open.
        val keepSeconds = if (cancelling) 0L else CopilotVoiceSettings.instance.keepMicReadySeconds.toLong()
        // Test seam first (review P4's regression test captures it), else the production path.
        // Fresh lambda literals at both sites: the platform's executeOnPooledThread takes a
        // Runnable (SAM conversion needs a literal, not a typed function value).
        stopWorkDispatcher?.invoke { finishStop(target, keepSeconds) }
            ?: ApplicationManager.getApplication().executeOnPooledThread { finishStop(target, keepSeconds) }
    }

    /**
     * The blocking stop tail (review P4): closing the capture (a join of up to 2 s), the stop
     * tone, the pre-inference gates, the WAV write and the transcription hand-off. Runs on a
     * pooled thread via [stop]; [stopWorkDispatcher] routes it elsewhere in tests.
     */
    private fun finishStop(target: Project?, keepSeconds: Long) {
        // Review N2: a stop that finds nothing recording (a duplicate that slipped past
        // stopClaimed, or a start that was cancelled before it opened the mic) must not play the
        // stop tone or touch the phase — that would be a second beep and could hide a live
        // TRANSCRIBING. A genuine stop always sees the capture still recording here.
        if (!capture.isRecording()) {
            log.debug("finishStop dropped: the capture is not recording (a duplicate stop or a cancelled start)")
            return
        }
        val samples = capture.stop(keepSeconds)
        // Review N4: the capture line is closed now — this recording has ended. End it in the
        // facts, whatever happens to its clip below. Cleared here (not in stop()): the line is
        // still open until capture.stop() returns, so the mic-test guard keeps seeing
        // isCapturing during the drain; and no newer session can confirm audio before this line
        // is released (AudioCapture.start() requires the recording flag to be down), so a live
        // RECORDING can never be stomped.
        audioConfirmed = false
        // Stop tone only AFTER the capture line is closed (issue #10): before, it could bleed
        // into the tail of the captured clip.
        if (CopilotVoiceSettings.instance.soundEnabled) Beeps.recordingStop()
        finishCapturedClip(target, samples)
    }

    /**
     * The post-capture tail of a stop (review N4): the empty-clip and speech gates, the WAV
     * write and the transcription hand-off. [samples] is what the now-closed capture delivered.
     *
     * Internal so the N4 test can drive the discard and hand-off paths without a microphone.
     */
    internal fun finishCapturedClip(target: Project?, samples: ShortArray) {
        if (samples.isEmpty()) {
            // Review N4: IDLE unless something else is live — e.g. an earlier clip still
            // transcribing, which a blind IDLE write would have hidden.
            recomputePhase()
            return
        }
        // Pre-inference gates (issue #8): a quick accidental tap or a silent clip must not spawn
        // a whisper-cli process and send a hallucinated transcript.
        val sampleRate = AudioCapture.DEFAULT_FORMAT.sampleRate.toInt()
        if (SpeechGate.durationMs(samples.size, sampleRate) < SpeechGate.MIN_DURATION_MS) {
            log.info("discarding clip: shorter than ${SpeechGate.MIN_DURATION_MS} ms")
            noSpeech(target)
            recomputePhase()
            return
        }
        // Review N17: the gate asks "is there speech anywhere in this clip" — the highest 50 ms
        // window level, not the whole-clip average, which a quiet mic and the clip's pauses
        // would dilute below the floor and discard a real utterance.
        val level = SpeechGate.speechLevel(samples, sampleRate)
        if (level < SpeechGate.MIN_RMS) {
            log.info("discarding clip: level=$level (highest ${SpeechGate.WINDOW_MS} ms window) below the ${SpeechGate.MIN_RMS} speech floor")
            noSpeech(target)
            recomputePhase()
            return
        }
        // The capture line is closed and whisper is about to run: "Transcribing…".
        // Review N4: the clip is in-flight from here, before the task queue runs it — claimed
        // here and released exactly once by whichever exit path takes it first
        // ([releaseTranscription]).
        val handle = claimTranscription()
        try {
            val wavFile = File(System.getProperty("java.io.tmpdir"), "copilot-voice-${System.currentTimeMillis()}.wav")
            WavFile.write(wavFile, samples, AudioCapture.DEFAULT_FORMAT)
            transcribeInBackground(target, wavFile, handle)
        } catch (t: Exception) {
            // Review N4: the WAV write failed after the claim — release the clip here, or the
            // counter never drops and the phase can never return to IDLE.
            releaseTranscription(handle)
            notify(target, VoiceBundle.message("notification.wav.failed", t.message ?: t.javaClass.simpleName), NotificationType.WARNING)
        }
    }

    /**
     * Test seam for [stop] (review P4): routes the queued stop work to a custom dispatcher so
     * a test can prove the blocking tail is *queued* rather than run on the calling thread.
     * Null (the default) = the production pooled-thread path.
     */
    internal var stopWorkDispatcher: ((() -> Unit) -> Unit)? = null

    /**
     * Test seam (review N1): set the "start in progress" flag without running [start], so the
     * release/deactivate handlers can be driven deterministically — no audio, no beeps, and no
     * dependence on the beep window length. [stopWorkDispatcher] still captures the `stop()` the
     * handler fires, so the blocking tail never runs.
     */
    internal fun setStartingForTest(value: Boolean) {
        starting.set(value)
    }

    /**
     * Test seam for [start] (review N2): routes the queued start work to a custom dispatcher so
     * a test can run [start] — and its release of the stop claim — without the beep + mic open.
     * Null (the default) = the production pooled-thread path.
     */
    internal var startWorkDispatcher: ((() -> Unit) -> Unit)? = null

    /**
     * Test seam (review N2): read the "start cancelled" flag, so a test can prove a stop() that
     * landed while a start was in progress set it — without running [startInternal].
     */
    internal fun isStartCancelledForTest(): Boolean = startCancelled

    /**
     * Test seam (review N4): set the "audio confirmed" fact exactly as [startInternal]'s
     * success path does, and recompute the phase — no beep, no microphone.
     */
    internal fun setAudioConfirmedForTest(value: Boolean) {
        audioConfirmed = value
        recomputePhase()
    }

    /**
     * Test seam (review N6): clear the [lastProblem] fact and recompute the phase — so a test
     * that drives [onMicLost] can leave the shared [DictationStatus] on IDLE for the next test
     * class, exactly as a new session's claim would.
     */
    internal fun clearProblemForTest() {
        lastProblem = null
        recomputePhase()
    }

    /**
     * Test seam (review N4): claim a transcription slot exactly as [finishCapturedClip] does
     * before the hand-off — so a test that drives [transcribeBody] directly (review P5) keeps
     * the in-flight counter balanced.
     */
    internal fun claimTranscriptionForTest(): TranscriptionHandle = claimTranscription()

    /**
     * Test seam (review N4): release a claimed clip exactly as the production exit paths do —
     * so a test can prove the exactly-once semantics (a duplicate release must be a no-op).
     */
    internal fun releaseTranscriptionForTest(handle: TranscriptionHandle) = releaseTranscription(handle)

    /**
     * Test seam (review N4): replaces the task-queue hand-off in [transcribeInBackground] so a
     * test can make it fail — the task never runs — and prove the clip is still released and
     * the phase can return to IDLE. Returns true when the clip was accepted by the hand-off.
     * Null (the default) = the real Task.Backgroundable path.
     */
    internal var transcribeHandoff: ((project: Project?, wavFile: File, handle: TranscriptionHandle) -> Boolean)? = null

    private fun transcribeInBackground(project: Project?, wavFile: File, handle: TranscriptionHandle) {
        // Test seam first (review N4: a test makes the hand-off fail — the task never runs —
        // and proves the clip is still released), else the production path.
        var failed: Exception? = null
        val handoff = transcribeHandoff
        val queued = if (handoff != null) {
            handoff(project, wavFile, handle)
        } else {
            try {
                queueTranscriptionTask(project, wavFile, handle)
                true
            } catch (t: Exception) {
                failed = t
                false
            }
        }
        if (!queued) {
            // Review N4: the task never runs (a failed queue() in production, a failed hand-off
            // in the test) — release the clip and remove the WAV here, or the counter never
            // drops and the phase can never return to IDLE. Same end-point as the task hooks
            // (review N16); the failed start additionally says so.
            finishClip(handle, wavFile)
            val reason = failed?.message ?: failed?.javaClass?.simpleName ?: "the transcription task could not be started"
            notify(project, VoiceBundle.message("notification.wav.failed", reason), NotificationType.WARNING)
        }
    }

    private fun queueTranscriptionTask(project: Project?, wavFile: File, handle: TranscriptionHandle) {
        val task = object : Task.Backgroundable(project, VoiceBundle["notification.transcribing"], true) {
            override fun run(indicator: ProgressIndicator) {
                // Review §3: capture the clip's indicator the moment the task starts, so
                // [cancelTranscription] can cancel a running clip (the platform Task has no
                // cancel() of its own). The cancelRequested flag covers the queue-to-start
                // gap — [transcribeBody] checks it first.
                handle.indicator = indicator
                transcribeBody(indicator, project, wavFile, handle)
            }
            // Review N4: the clip is released exactly once by whichever exit path takes it
            // first (releaseOnce in [releaseTranscription]); a duplicate is a no-op.
            // Review N16: these hooks are the exit for a clip whose task never ran (cancelled
            // before start — a project close, or the platform dropping a queued task). They
            // must also remove the WAV, which [transcribeBody] never got to do: [finishClip]
            // is idempotent, so a hook firing after transcribeBody's finally already ended a
            // clip that did run is a no-op.
            override fun onFinished() {
                finishClip(handle, wavFile)
            }

            override fun onCancel() {
                finishClip(handle, wavFile)
            }

            override fun onThrowable(t: Throwable) {
                finishClip(handle, wavFile)
            }
        }
        task.queue()
    }

    /**
     * Review N4: the phase is never written — it is derived from the facts, in one place, by
     * [derivePhase]: RECORDING > OPENING > TRANSCRIBING > PROBLEM > IDLE. Every state change
     * (a start claimed, audio confirmed, a clip claimed or released, a failure reason set or
     * cleared) ends here, so an event that ends one thing — a clip finishing, a discarded
     * clip, a cancelled start — can no longer stomp the phase of something still live.
     *
     * The facts are read when the update is applied on the EDT
     * ([DictationStatus.applyDerivedPhase]), not when this is called — a request queued
     * behind a newer one never applies a stale value.
     */
    private fun recomputePhase() {
        status.applyDerivedPhase {
            derivePhase(audioConfirmed, starting.get(), activeTranscriptions.get(), lastProblem)
        }
    }

    /**
     * Review N4: claim one transcription slot — called in [finishCapturedClip] before the
     * hand-off to the task queue, so the phase is TRANSCRIBING while the clip waits its turn,
     * not only while it runs. Returns the clip's [TranscriptionHandle] (its exactly-once
     * release flag, review N4, and its [TranscriptionHandle.cancel] seam, review §3), for
     * [releaseTranscription].
     */
    private fun claimTranscription(): TranscriptionHandle {
        val handle = TranscriptionHandle()
        activeHandles.add(handle)
        activeTranscriptions.incrementAndGet()
        recomputePhase()
        return handle
    }

    /**
     * Review N4: release one transcription clip exactly once — the first of [transcribeBody]'s
     * finally, the task's onCancel/onFinished/onThrowable, or a failed queue()/WAV hand-off to
     * take it — and recompute the phase from the facts. A duplicate release is a no-op, so the
     * counter can never be double-decremented; and a clip whose task never runs is still
     * released by its failed hand-off path, so the counter can never be stuck above zero and
     * the phase can always return to IDLE.
     *
     * Review §3: the clip's handle also leaves [activeHandles], so a later
     * [cancelTranscription] cannot reach a clip that is no longer live.
     */
    private fun releaseTranscription(handle: TranscriptionHandle) {
        if (handle.releaseOnce()) {
            activeTranscriptions.decrementAndGet()
            activeHandles.remove(handle)
            recomputePhase()
        }
    }

    /**
     * Review N16: the single end-point for a clip whose task never ran — cancelled before
     * start (a project close, the platform dropping a queued task), so [transcribeBody] never
     * executed and neither its finally nor the failed-hand-off path cleaned up. It gives the
     * clip back the same way the other exits do: release the in-flight slot exactly once
     * (review N4) and remove the WAV. Both steps are idempotent ([releaseOnce] / [File.delete]),
     * so every task hook ([Task.onFinished], [Task.onCancel], [Task.onThrowable]) calls this
     * unconditionally and a hook that fires after a clip that did run is a no-op.
     *
     * Internal so the N16 test can drive the never-ran exit directly, without a task queue.
     */
    internal fun finishClip(handle: TranscriptionHandle, wavFile: File) {
        releaseTranscription(handle)
        runCatching { wavFile.delete() }
    }

    /**
     * Review §3: cancel every live transcription — running and queued — from anywhere (the
     * status bar widget's click while TRANSCRIBING). Each live clip is reached through its
     * [TranscriptionHandle]: a running clip's [ProgressIndicator] is cancelled (its
     * `checkCanceled`/the engine unwind it), and a clip whose task has not started yet has
     * [TranscriptionHandle.cancelRequested] set and bails the moment its task does start
     * ([transcribeBody] checks it first) — so the engine never runs for a cancelled clip.
     * A no-op when nothing is live.
     */
    fun cancelTranscription() {
        activeHandles.forEach { it.cancel() }
    }

    /**
     * The full transcription of one clip (review P5): model resolution, the engine call (with
     * its P2 server path and P3 GPU→CPU net) and the delivery — held under [transcriptionLock],
     * so at most one transcription runs at a time and the clips finish in the order they
     * ended. [indicator]'s Cancel button is honoured while a clip waits its turn, not only
     * during inference.
     *
     * Review N4: the clip's in-flight slot is claimed in [finishCapturedClip] before the task
     * queue runs this (the phase is TRANSCRIBING while the clip waits its turn, not only while
     * it runs) and released by the finally below, exactly once.
     *
     * Review §3: [handle.cancelRequested] is checked before anything runs — a cancel that
     * arrived while the clip was queued (before its task started, so its handle had no
     * indicator to cancel) bails here, and the engine never runs for a cancelled clip.
     *
     * Internal so the concurrency test can drive two clips directly, without a task queue.
     */
    internal fun transcribeBody(indicator: ProgressIndicator, project: Project?, wavFile: File, handle: TranscriptionHandle) {
        var locked = false
        try {
            // Review §3: a cancel requested before this clip's task started surfaces here, at
            // the very start — before model resolution or the engine call.
            if (handle.cancelRequested) throw ProcessCanceledException()
            while (!transcriptionLock.tryLock()) {
                // A Cancel pressed while the clip is queued surfaces here the moment it happens
                // — we do not sit out the predecessor's inference to find out. The handle flag
                // covers cancels whose indicator the clip never saw (e.g. a task that has not
                // started yet).
                indicator.checkCanceled()
                if (handle.cancelRequested) throw ProcessCanceledException()
                Thread.sleep(TRANSCRIPTION_QUEUE_POLL_MS)
            }
            locked = true
            indicator.checkCanceled()
            val requestedModelId = CopilotVoiceSettings.instance.modelName
            // Review P4: resolve the model here, off the EDT — it is disk I/O, and on first
            // use restoring the bundled model copies and checksums a ~75 MB file. (This used
            // to run on the calling thread, in `stop()`'s path, despite the comment claiming
            // otherwise.)
            val resolved = ModelManager.resolveActive(null, requestedModelId)
            var model = resolved
            var activeModelId = requestedModelId
            if (model == null) {
                if (requestedModelId == ModelManager.BUNDLED_MODEL) {
                    // The selected model is the bundled one but hasn't been extracted to disk
                    // yet (fresh install): extract it. This is the expected first-use path, not
                    // a fallback, so no "we fell back" notification.
                    val bundled = ModelManager.installBundledModel()
                    if (bundled != null) {
                        model = bundled
                        activeModelId = ModelManager.BUNDLED_MODEL
                        log.info("extracting bundled model for first use: $bundled")
                    }
                } else {
                    // The selected model isn't installed (e.g. no network to download it): fall
                    // back to the model bundled inside the plugin.
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
                // The WAV is removed by the finally below (review N16: one deletion point for
                // every exit of this method).
                return
            }
            try {
                log.info("transcribe starting: model=$model wav=${wavFile.absolutePath}")
                val language = if (ModelManager.isEnglishOnly(activeModelId)) WhisperLanguage.ENGLISH.code else CopilotVoiceSettings.instance.language
                // Review N9: the confirmed-GPU-failure blacklist is scoped to the current
                // Processing settings — the user moving the Processing dropdown or the thread
                // count is a deliberate "try the GPU again", so [GpuHealth.sync] clears it on a
                // change. The key also scopes the blacklist checks in the engines.
                val processingKey = GpuHealth.processingKey(CopilotVoiceSettings.instance.processingMode.name, threads())
                GpuHealth.sync(processingKey)
                val text = engineTranscribe?.invoke(wavFile, model, language, indicator)
                    ?: WhisperServerEngine.transcribe(
                        wavFile, model,
                        language = language, useGpu = useGpu(), threads = threads(),
                        indicator = indicator,
                        processingKey = processingKey,
                    )
                log.info("transcribe done: len=${text.length} blank=${text.isBlank()}")
                if (text.isBlank()) {
                    noSpeech(project)
                } else {
                    deliverTranscript?.invoke(project, text) ?: deliverToChat(project, text)
                }
            } catch (t: Throwable) {
                // A user cancel unwinds the engine as a throw (its checkCanceled, or a
                // backend that surfaces the cancel): no error notification, just the log —
                // the flag covers the case where the indicator never learned of the cancel.
                if (indicator.isCanceled || handle.cancelRequested) {
                    log.info("transcription cancelled by user")
                } else {
                    log.error("transcribe threw", t)
                    notify(project, VoiceBundle.message("notification.transcribe.failed", t.message ?: t.javaClass.simpleName), NotificationType.ERROR)
                }
            }
        } finally {
            if (locked) {
                transcriptionLock.unlock()
            }
            // Review N16: the WAV is a transient artifact — every exit of this method (success,
            // engine failure, the no-model return, a queued-cancel throw at the top) removes it
            // here, in one place. A clip whose task never ran is covered by the task hooks via
            // [finishClip] instead.
            runCatching { wavFile.delete() }
            // Review N4: release this clip exactly once (idempotent — the task's
            // onCancel/onFinished or a failed hand-off may have taken it first), and recompute
            // the phase from the facts: IDLE only when the last clip is gone and nothing else
            // is live — an earlier clip ending first must not hide a live RECORDING or a later
            // TRANSCRIBING.
            releaseTranscription(handle)
        }
    }

    /**
     * Test seam for [transcribeBody] (review P5): substitutes the engine call so the
     * concurrency test can hold one clip mid-"inference" without a model, a binary or a
     * server. Null (the default) = the real [WhisperServerEngine.transcribe] path (P2 + P3).
     */
    internal var engineTranscribe: ((wavFile: File, model: Path, language: String, indicator: ProgressIndicator) -> String)? = null

    /**
     * Test seam for [transcribeBody] (review P5): substitutes [deliverToChat] so the
     * concurrency test can record delivery order without a Copilot tool window.
     * Null (the default) = the real sink chain + balloon fallback.
     */
    internal var deliverTranscript: ((project: Project?, text: String) -> Unit)? = null

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
     *  - any whisper-cli process still running,
     *  - the long-lived whisper-server and the beep executor's daemon thread (review N13).
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
        // The beep executor's daemon thread would outlive the plugin and pin its class loader
        // (review N13); a no-op if no beep was ever played.
        Beeps.shutdown()
    }

    companion object {
        private const val ACTION_ID = "io.github.simon-cek.voiceprompt.PushToTalk"
        private const val NOTIFICATION_GROUP_ID = "CopilotVoice"

        val instance: DictationSession
            get() = ApplicationManager.getApplication().getService(DictationSession::class.java)
    }
}
