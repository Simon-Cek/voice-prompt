package io.github.simoncek.voiceprompt.statusbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.IconLikeCustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.actions.PushToTalkAction
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel

/**
 * Persistent, non-expiring indicator of what dictation is doing.
 *
 * Always a small icon followed by a text label that changes with the phase: "Voice Prompt"
 * while idle, then "Opening mic…" / "Recording" / "Transcribing…", with a phase tooltip on
 * hover. The PROBLEM face uses the platform warning glyph and shows its short reason (e.g.
 * "Mic did not respond") so the bar never sits on "Opening mic…" after a failed start. Only
 * the idle face is hideable — via the "Show status bar indicator when idle" setting — the
 * active and problem faces are always shown. The label stays until the phase changes: no
 * auto-hide, unlike the old balloon (issue #5).
 *
 * A pure view of [DictationStatus]: it never owns or infers state, so it cannot disagree with
 * the recorder. The platform creates one instance per project window (see
 * [RecordingStatusWidgetFactory]); all instances watch the same application-level state, so
 * the status bar of every open project window stays in step.
 *
 * Clicking: the idle icon and the PROBLEM face open the plugin's settings page (from a problem
 * you can go fix the device/model); while OPENING or RECORDING a click stops the recording (the
 * manual escape hatch for a stuck recording if a key release was lost); while TRANSCRIBING it
 * does nothing — the inference is already in flight.
 */
class RecordingStatusWidget(private val project: Project) : IconLikeCustomStatusBarWidget {

    override fun ID(): String = WIDGET_ID

    private val status = DictationStatus.instance
    private val settings = CopilotVoiceSettings.instance

    @Volatile
    private var statusBar: StatusBar? = null

    // One component, always icon + label: the mic icon stays in every phase and only the text
    // changes. The platform's TextPanel / IconPresentationComponent can only show one face
    // (icon OR text) for the widget's whole lifetime, so this widget is a CustomStatusBarWidget
    // that draws both itself (the platform wraps a JLabel and applies the theme border because
    // it is IconLike, so no color or border is hardcoded).
    private val view = JLabel().apply {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                onClick()
            }
        })
    }

    private val phaseListener: (DictationPhase) -> Unit = { render() }
    private val settingsListener: () -> Unit = { render() }

    /** What a click does in each phase. */
    enum class ClickAction { OPEN_SETTINGS, STOP_RECORDING, NONE }

    init {
        // Both callbacks arrive on the EDT (DictationStatus marshals phase changes, and the
        // settings checkbox fires from the settings UI), so render() may touch Swing.
        status.addListener(phaseListener)
        settings.addIdleIndicatorListener(settingsListener)
    }

    override fun install(statusBar: StatusBar) {
        // The platform calls this inside its own addWidget — record the bar and sync on the
        // next EDT pass so we never re-enter the bar's add/remove while it is mid-call.
        this.statusBar = statusBar
        ApplicationManager.getApplication().invokeLater { render() }
    }

    override fun getComponent(): JLabel = view

    override fun dispose() {
        status.removeListener(phaseListener)
        settings.removeIdleIndicatorListener(settingsListener)
        statusBar = null
    }

    /** Shows or hides the widget and draws the current phase. EDT only (see the listeners). */
    private fun render() {
        val bar = statusBar ?: return
        val phase = status.phase
        if (isVisible(phase, settings.showIdleIndicator)) {
            if (bar.getWidget(ID()) == null) bar.addWidget(this)
            applyContent(phase)
        } else if (bar.getWidget(ID()) != null) {
            bar.removeWidget(ID())
        }
    }

    private fun applyContent(phase: DictationPhase) {
        view.icon = iconFor(phase)
        // PROBLEM carries its own short reason (e.g. "Mic did not respond"); every other phase
        // has a fixed label.
        val problem = if (phase == DictationPhase.PROBLEM) status.problem else null
        view.text = if (phase == DictationPhase.IDLE) VoiceBundle["statusbar.idle"] else textFor(phase, problem)
        view.toolTipText = tooltipFor(phase, problem)
        view.revalidate()
        view.repaint()
    }

    private fun iconFor(phase: DictationPhase): Icon =
        if (phase == DictationPhase.PROBLEM) PROBLEM_ICON else MIC_ICON

    private fun onClick() {
        when (clickAction(status.phase)) {
            ClickAction.OPEN_SETTINGS ->
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VoiceBundle["settings.title"])
            ClickAction.STOP_RECORDING -> PushToTalkAction.stopRecording()
            // TRANSCRIBING: the inference is already running; there is nothing to cancel yet.
            ClickAction.NONE -> Unit
        }
    }

    companion object {
        const val WIDGET_ID = "VoicePromptRecordingStatus"

        /**
         * The idle mic icon. `icons/mic.svg` (light theme) and `icons/mic_dark.svg` (dark
         * theme) use the theme's standard neutral grey, and the platform's icon loader picks
         * the dark variant automatically in dark themes — so no color is hardcoded for a
         * specific theme (AGENTS.md rule 3). Lazy so that unit tests that only exercise the
         * pure decision functions below never force icon loading, which needs a live
         * application.
         */
        val MIC_ICON: Icon by lazy {
            IconLoader.getIcon("/icons/mic.svg", RecordingStatusWidget::class.java.classLoader)
        }

        /**
         * The PROBLEM face: the platform's standard warning glyph (theme-aware, no hardcoded
         * color). This is the icon the parked 5-state rework plans for PROBLEM, so it is
         * introduced here with the first PROBLEM state rather than reworked later.
         */
        val PROBLEM_ICON: Icon by lazy { AllIcons.General.BalloonWarning }

        /** Whether the widget should occupy the status bar in [phase], given the idle setting. */
        internal fun isVisible(phase: DictationPhase, showIdleIndicator: Boolean): Boolean =
            phase != DictationPhase.IDLE || showIdleIndicator

        internal fun clickAction(phase: DictationPhase): ClickAction = when (phase) {
            DictationPhase.IDLE, DictationPhase.PROBLEM -> ClickAction.OPEN_SETTINGS
            DictationPhase.OPENING, DictationPhase.RECORDING -> ClickAction.STOP_RECORDING
            DictationPhase.TRANSCRIBING -> ClickAction.NONE
        }

        private fun textFor(phase: DictationPhase, problem: String?): String = when (phase) {
            DictationPhase.OPENING -> VoiceBundle["statusbar.opening"]
            DictationPhase.RECORDING -> VoiceBundle["statusbar.recording"]
            DictationPhase.TRANSCRIBING -> VoiceBundle["statusbar.transcribing"]
            DictationPhase.PROBLEM -> problem ?: VoiceBundle["statusbar.problem"]
            DictationPhase.IDLE -> ""
        }

        private fun tooltipFor(phase: DictationPhase, problem: String?): String = when (phase) {
            DictationPhase.IDLE -> VoiceBundle["statusbar.tooltip.ready"]
            DictationPhase.OPENING -> VoiceBundle["statusbar.tooltip.opening"]
            DictationPhase.RECORDING -> VoiceBundle["statusbar.tooltip.recording"]
            DictationPhase.TRANSCRIBING -> VoiceBundle["statusbar.tooltip.transcribing"]
            DictationPhase.PROBLEM -> problem ?: VoiceBundle["statusbar.problem"]
        }
    }
}
