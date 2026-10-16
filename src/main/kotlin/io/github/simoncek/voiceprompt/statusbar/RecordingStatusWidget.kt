package io.github.simoncek.voiceprompt.statusbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.IconLikeCustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.actions.PushToTalkAction
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JComponent

/**
 * Persistent, non-expiring indicator of what dictation is doing (review §3).
 *
 * Five faces, each a 16×16 icon, a 16×16 spinner and the label — "Voice Prompt" while
 * idle, then "Opening mic…" / "Recording" / "Transcribing…" — with a phase tooltip on
 * hover. A slot occupies space only while its face shows content in it: IDLE and RECORDING
 * are icon + text with no gap between, OPENING is icon + spinner + text, and TRANSCRIBING
 * is spinner + text with no leading gap (BoxLayout skips a hidden slot, and the view's
 * width is pinned to the face's actual content). The PROBLEM face uses the platform warning
 * glyph and shows its short reason (e.g. "Mic did not respond") so the bar never sits on
 * "Opening mic…" after a failed start. Only the idle
 * face is hideable — via the "Show status bar indicator when idle" setting — and hiding it
 * is a visibility toggle, never a removal: [RecordingStatusWidget.install] adds the widget
 * once, and review N15 proved that the platform disposes a widget its bar removes, which
 * would permanently kill the indicator. The label stays until the phase changes: no
 * auto-hide, unlike the old balloon (issue #5).
 *
 * A pure view of [DictationStatus]: it never owns or infers state, so it cannot disagree with
 * the recorder. The platform creates one instance per project window (see
 * [RecordingStatusWidgetFactory]); all instances watch the same application-level state, so
 * the status bar of every open project window stays in step.
 *
 * Clicking: the idle icon and the PROBLEM face open the plugin's settings page (from a problem
 * you can go fix the device/model); while OPENING or RECORDING a click stops the recording (the
 * manual escape hatch for a stuck recording if a key release was lost); while TRANSCRIBING a
 * click cancels the transcription (review §3: every live clip — running and queued — through
 * its per-clip [io.github.simoncek.voiceprompt.actions.TranscriptionHandle]).
 */
class RecordingStatusWidget(private val project: Project) : IconLikeCustomStatusBarWidget {

    /**
     * One face of the widget (review §3): a pure description of what a phase looks like —
     * the icon slot's content, whether the spinner slot spins, whether the widget is visible
     * at all, the label, the tooltip and what a click does. [faceFor] is the single source
     * for all of these; [applyFace] is the only place that turns a [Face] into Swing.
     *
     * Pure on purpose: it touches no component, no listener, no EDT — the unit test can walk
     * the whole phase × setting table without a StatusBar.
     */
    internal data class Face(
        val icon: IconId,
        val spinner: Boolean,
        val visible: Boolean,
        val text: String,
        val tooltip: String,
        val click: ClickAction,
    )

    /**
     * The icon a face shows in the icon slot (review §3) — an id, not a loaded [Icon], so
     * [faceFor] stays pure (no application) and the face test can assert the icon by id.
     */
    internal enum class IconId {
        IDLE_MIC,
        OPENING_MIC,
        RECORDING_MIC,
        /** TRANSCRIBING: the icon slot stays empty — the spinner carries the face. */
        NONE,
        PROBLEM;

        /**
         * The loaded icon — `icons/<name>.svg` (light theme) and `icons/<name>_dark.svg`
         * (dark theme), the theme pair picked by the platform's icon loader, so no color is
         * hardcoded for a specific theme (AGENTS.md rule 3). Lazy, so a test that only walks
         * [faceFor] never forces icon loading, which needs a live application.
         */
        val icon: Icon? by lazy {
            when (this) {
                IDLE_MIC -> IconLoader.getIcon("/icons/mic_idle.svg", RecordingStatusWidget::class.java.classLoader)
                OPENING_MIC -> IconLoader.getIcon("/icons/mic_opening.svg", RecordingStatusWidget::class.java.classLoader)
                RECORDING_MIC -> IconLoader.getIcon("/icons/mic_recording.svg", RecordingStatusWidget::class.java.classLoader)
                NONE -> null
                PROBLEM -> AllIcons.General.BalloonWarning
            }
        }
    }

    /** What a click does in each face. */
    internal enum class ClickAction { OPEN_SETTINGS, STOP_RECORDING, CANCEL_TASK }

    override fun ID(): String = WIDGET_ID

    private val status = DictationStatus.instance
    private val settings = CopilotVoiceSettings.instance

    @Volatile
    private var statusBar: StatusBar? = null

    private val phaseListener: (DictationPhase) -> Unit = { render() }
    private val settingsListener: () -> Unit = { render() }

    // Review §3: the view is [icon slot] [spinner slot] [label]. Each slot is a fixed
    // JBUI-scaled 16×16 square that occupies space only while the face shows content in
    // it — the user does not want the empty spinner gap between the mic and the text in
    // IDLE/RECORDING/PROBLEM, nor the empty icon gap before the spinner in TRANSCRIBING.
    // BoxLayout skips the hidden slot, and syncViewSizing pins the view to the face's
    // actual width so no hidden space is left anywhere.
    private val iconLabel = JLabel().apply { isOpaque = false }
    private val iconSlot = fixedSlot(iconLabel)

    private val spinner = AsyncProcessIcon("voice-prompt-spinner")
    private val spinnerSlot = fixedSlot(spinner)

    private val textLabel = JLabel().apply { isOpaque = false }

    /** The face currently applied to the view (null before the first render). */
    @Volatile
    private var lastFace: Face? = null

    /** Review §3: the reserved label width in text pixels — 0 until the first render. */
    private var reservedTextWidth = 0

    // BoxLayout (single horizontal row), not FlowLayout: when the bar is narrower than the
    // total widget budget, GridBag squeezes the widget below its preferred width, and a
    // wrapping layout drops the label to a second row — below the bar's height, so only the
    // icon stayed visible. A BoxLayout face clips at the edge at worst; it never wraps the
    // label out of sight, and its preferred size does not depend on its current size.
    private val view = object : JPanel() {
        override fun updateUI() {
            super.updateUI()
            // Re-assert the layout (super's UI installation must not be allowed to reset it),
            // then re-measure: the reserved label width is a function of the font (review §3),
            // so a look-and-feel (font) change re-measures it and reapplies the current face.
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            val face = lastFace
            if (face != null) {
                applyFace(face)
            } else {
                reserveTextWidth()
            }
        }
    }.apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                onClick()
            }
        })
        add(iconSlot)
        add(spinnerSlot)
        add(textLabel)
    }

    init {
        // Both callbacks arrive on the EDT (DictationStatus marshals phase changes, and the
        // settings checkbox fires from the settings UI), so render() may touch Swing.
        status.addListener(phaseListener)
        settings.addIdleIndicatorListener(settingsListener)
    }

    override fun install(statusBar: StatusBar) {
        // The platform calls this inside its own addWidget — record the bar and sync on the
        // next EDT pass so we never re-enter the bar's add/remove while it is mid-call.
        // Review N15: the platform owns the widget's add/remove lifecycle (and disposes a
        // widget it removes) — we never call addWidget/removeWidget ourselves.
        this.statusBar = statusBar
        ApplicationManager.getApplication().invokeLater { render() }
    }

    override fun getComponent(): JComponent = view

    override fun dispose() {
        status.removeListener(phaseListener)
        settings.removeIdleIndicatorListener(settingsListener)
        // Terminal for this instance (the platform disposes widgets it removes, review N15):
        // stop the spinner's animation timer so it cannot outlive the widget (AGENTS.md rule 2).
        spinner.dispose()
        statusBar = null
    }

    /**
     * A fixed-size 16×16 slot (review §3): while the face shows content in it, it is
     * exactly 16×16 — BorderLayout pins the content (a FlowLayout slot would wrap an
     * oversized child below it) — and when the face has none, [applyFace] hides it and
     * BoxLayout allocates it no space, so no empty gap appears in the face.
     */
    private fun fixedSlot(content: JComponent): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            val size = JBUI.scale(16)
            preferredSize = Dimension(size, size)
            minimumSize = Dimension(size, size)
            maximumSize = Dimension(size, size)
            add(content)
        }

    /** Draws the current face. EDT only (the listeners and [install] guarantee it). */
    private fun render() {
        // Not installed yet (constructed but the bar has not added us): nothing to draw.
        if (statusBar == null) return
        val face = faceFor(status.phase, settings.showIdleIndicator, status.problem)
        // Review N15: never add or remove the widget — the platform owns that lifecycle, and
        // removeWidget disposes the widget (verified in IdeStatusBarImpl, IU-2025.3.6.1),
        // which would leave a dead re-added instance. Hiding is a visibility toggle: the bar's
        // panel (GridBagLayout) skips invisible components, so a hidden idle face leaves no
        // gap — revalidate/repaint the parent so the bar relayouts around the empty slot.
        applyFace(face)
        view.isVisible = face.visible
        view.parent?.revalidate()
        view.parent?.repaint()
    }

    /** Applies [face] to the Swing view. EDT only (see [render]). */
    private fun applyFace(face: Face) {
        lastFace = face
        reserveTextWidth()
        iconLabel.icon = face.icon.icon
        // A slot exists only while the face has content for it (user requirement: no gap
        // where the spinner is hidden): BoxLayout skips the invisible slot and allocates
        // it no space, so the mic sits right next to the text in IDLE/RECORDING/PROBLEM
        // and the spinner leads TRANSCRIBING with no empty icon gap before it.
        iconSlot.isVisible = face.icon != IconId.NONE
        spinnerSlot.isVisible = face.spinner
        textLabel.text = fit(face.text, textLabel.getFontMetrics(textLabel.font))
        // The full text (an ellipsized PROBLEM reason included) stays in the tooltip.
        textLabel.toolTipText = face.tooltip
        view.toolTipText = face.tooltip
        // Review §3: the accessible name tracks the face — screen readers hear the phase,
        // not "panel".
        view.accessibleContext.accessibleName = face.text
        // Spinner slot: visible and running only for the OPENING and TRANSCRIBING faces —
        // suspend() stops the animation timer while hidden, so no timer runs in the other
        // faces (review §3).
        spinner.isVisible = face.spinner
        if (face.spinner) spinner.resume() else spinner.suspend()
        // The face width is its actual content (a slot may have just collapsed) — pin the
        // view to it so the bar neither squeezes the face nor leaves trailing space.
        syncViewSizing(iconSlot.isVisible, spinnerSlot.isVisible)
        view.revalidate()
        view.repaint()
    }

    /**
     * Review §3: the reserved label width — the widest of the four static labels and the
     * known PROBLEM reasons (the bundle keys the session can set), measured with the label's
     * current font. A longer reason is ellipsized into this width; the full text stays in
     * the tooltip. Recomputed on every render (it is a handful of stringWidth calls) and by
     * [view]'s updateUI when the font changes.
     */
    private fun reserveTextWidth() {
        val fm = textLabel.getFontMetrics(textLabel.font)
        reservedTextWidth = maxOf(
            fm.stringWidth(VoiceBundle["statusbar.idle"]),
            fm.stringWidth(VoiceBundle["statusbar.opening"]),
            fm.stringWidth(VoiceBundle["statusbar.recording"]),
            fm.stringWidth(VoiceBundle["statusbar.transcribing"]),
            fm.stringWidth(VoiceBundle["statusbar.problem"]),
            fm.stringWidth(VoiceBundle["statusbar.problem.mic.no.response"]),
            fm.stringWidth(VoiceBundle["statusbar.problem.mic.lost"]),
        )
        val insets = textLabel.insets.left + textLabel.insets.right
        // The height comes from the font, not from textLabel.preferredSize: before the
        // first render the label has no text, so its computed preferred size is (0, 0) —
        // and once we lock an explicit size onto it, that zero height sticks forever,
        // because an explicit preferred size overrides the UI's own computation. A
        // zero-height label draws no text under any layout manager: that is the
        // "icon only, no text" bug.
        val height = fm.getHeight() + textLabel.insets.top + textLabel.insets.bottom
        val size = Dimension(reservedTextWidth + insets, height)
        // The label keeps its reserved width at all times (review §3: the text is always
        // visible) — the bar may not shrink it, and a squeezed face must clip at the edge,
        // never wrap the label to a second, invisible row.
        textLabel.preferredSize = size
        textLabel.minimumSize = size
        textLabel.maximumSize = size
    }

    /**
     * Pins the view to exactly this face's width (review §3): one 16px slot per slot the
     * face shows (see [applyFace]) plus the reserved label and the view insets. The widget
     * is a fixed-width component for the bar's GridBagLayout: its minimum equals its full
     * face, so a narrow bar may not squeeze it (the old FlowLayout view had a (0, 0) minimum
     * and shrank until the label wrapped out of sight); if the bar cannot fit us, it absorbs
     * the deficit from its own elastic space (the info text), never by breaking this face.
     * And because the width matches the face's content exactly, BoxLayout packs the face
     * edge-to-edge — no trailing space where a slot collapsed. `view` is null before the
     * first render (the super-constructor's updateUI() runs first) — the first render
     * re-applies the sizing.
     */
    private fun syncViewSizing(showIcon: Boolean, showSpinner: Boolean) {
        val v = view ?: return
        val slot = JBUI.scale(16)
        val viewInsets = v.insets.left + v.insets.right
        val slots = (if (showIcon) 1 else 0) + (if (showSpinner) 1 else 0)
        val fixedWidth = slot * slots + textLabel.preferredSize.width + viewInsets
        v.minimumSize = Dimension(fixedWidth, 0)
        v.maximumSize = Dimension(fixedWidth, Int.MAX_VALUE)
    }

    /** Fits [text] into the reserved width, ellipsizing a long PROBLEM reason if needed. */
    private fun fit(text: String, fm: FontMetrics): String {
        if (reservedTextWidth == 0 || fm.stringWidth(text) <= reservedTextWidth) return text
        val ellipsis = "…"
        var cut = text
        while (cut.length > 1 && fm.stringWidth(cut + ellipsis) > reservedTextWidth) cut = cut.dropLast(1)
        return cut + ellipsis
    }

    private fun onClick() {
        when (clickAction(status.phase)) {
            ClickAction.OPEN_SETTINGS ->
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VoiceBundle["settings.title"])
            ClickAction.STOP_RECORDING -> PushToTalkAction.stopRecording()
            ClickAction.CANCEL_TASK -> PushToTalkAction.cancelTranscription()
        }
    }

    /** Test seam (review §3): whether the spinner's animation is currently running. */
    internal fun spinnerRunning(): Boolean = spinner.isRunning

    /** Test seam (review §3): the current label text. */
    internal fun labelText(): String = textLabel.text

    companion object {
        const val WIDGET_ID = "VoicePromptRecordingStatus"

        /**
         * The widget's face in [phase] (review §3) — pure: no component, no listener, no EDT.
         *
         *  - IDLE: a thin blue mic (the plugin icon's blue, deepened for light themes),
         *    "Voice Prompt", click → settings; hidden when the idle indicator is off.
         *  - OPENING: an amber mic and the platform spinner, "Opening mic…", click → stop.
         *  - RECORDING: a bold red mic, "Recording", click → stop.
         *  - TRANSCRIBING: the platform spinner only, "Transcribing…", click → cancel.
         *  - PROBLEM: the platform warning and the short reason, click → settings.
         *
         * [problem] is the PROBLEM reason ([DictationStatus.problem], null outside PROBLEM).
         */
        internal fun faceFor(phase: DictationPhase, showIdleIndicator: Boolean, problem: String?): Face =
            when (phase) {
                DictationPhase.IDLE -> Face(
                    icon = IconId.IDLE_MIC,
                    spinner = false,
                    visible = showIdleIndicator,
                    text = VoiceBundle["statusbar.idle"],
                    tooltip = VoiceBundle["statusbar.tooltip.ready"],
                    click = ClickAction.OPEN_SETTINGS,
                )

                DictationPhase.OPENING -> Face(
                    icon = IconId.OPENING_MIC,
                    spinner = true,
                    visible = true,
                    text = VoiceBundle["statusbar.opening"],
                    tooltip = VoiceBundle["statusbar.tooltip.opening"],
                    click = ClickAction.STOP_RECORDING,
                )

                DictationPhase.RECORDING -> Face(
                    icon = IconId.RECORDING_MIC,
                    spinner = false,
                    visible = true,
                    text = VoiceBundle["statusbar.recording"],
                    tooltip = VoiceBundle["statusbar.tooltip.recording"],
                    click = ClickAction.STOP_RECORDING,
                )

                DictationPhase.TRANSCRIBING -> Face(
                    icon = IconId.NONE,
                    spinner = true,
                    visible = true,
                    text = VoiceBundle["statusbar.transcribing"],
                    tooltip = VoiceBundle["statusbar.tooltip.transcribing"],
                    click = ClickAction.CANCEL_TASK,
                )

                DictationPhase.PROBLEM -> Face(
                    icon = IconId.PROBLEM,
                    spinner = false,
                    visible = true,
                    text = problem ?: VoiceBundle["statusbar.problem"],
                    tooltip = problem ?: VoiceBundle["statusbar.problem"],
                    click = ClickAction.OPEN_SETTINGS,
                )
            }

        /** What a click does in [phase] (review §3). */
        internal fun clickAction(phase: DictationPhase): ClickAction =
            faceFor(phase, showIdleIndicator = true, problem = null).click

        /** Whether the widget should occupy the status bar in [phase], given the idle setting. */
        internal fun isVisible(phase: DictationPhase, showIdleIndicator: Boolean): Boolean =
            faceFor(phase, showIdleIndicator, problem = null).visible
    }
}
