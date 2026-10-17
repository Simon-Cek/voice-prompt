package io.github.simoncek.voiceprompt.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.JBUI
import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import io.github.simoncek.voiceprompt.state.DerivedPhase
import java.awt.Dimension
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Review §3 / N15: the widget's bar lifecycle and spinner discipline, against a fake
 * [StatusBar] that records every call.
 *
 * The fake encodes the platform contract it is verified against (IdeStatusBarImpl,
 * IU-2025.3.6.1, checked in the platform jars): `addWidget` installs the widget, and
 * `removeWidget` removes the widget's component AND disposes the widget. That encoding is
 * an assumption about the platform — pinned here so that if the platform (or our reading of
 * it) changes, these tests fail loudly instead of the widget silently breaking. In the real
 * bar the widget's component sits directly in the bar's panel (a WidgetBean in a
 * GridBagLayout, which skips invisible components), so a hidden idle face leaves no gap —
 * the final confirmation of that is the sandbox visual check.
 *
 * N15: the old `render()` hid the idle face with `bar.removeWidget(ID())` — which disposes
 * the widget — so the next "show" (the setting flipped back on) could never render it again:
 * the indicator was dead for good. The fixed `render()` toggles visibility only.
 */
class RecordingStatusWidgetLifecycleTest : LightPlatformTestCase() {

    /**
     * A [StatusBar] spy. Records every call, and encodes the platform contract: addWidget
     * installs the widget; removeWidget disposes it (review N15).
     */
    private class StatusBarSpy {
        val calls = CopyOnWriteArrayList<String>()

        @Volatile
        var widget: StatusBarWidget? = null

        @Volatile
        var disposed = false

        private val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "addWidget" -> {
                    val w = args!!.first() as StatusBarWidget
                    calls.add("addWidget")
                    widget = w
                    // The platform installs the widget it adds (StatusBar.addWidget).
                    w.install(proxy as StatusBar)
                }

                "removeWidget" -> {
                    calls.add("removeWidget")
                    val w = widget
                    if (w != null) {
                        // The platform contract (IdeStatusBarImpl): a removed widget is
                        // disposed — which is exactly what permanently kills it (N15).
                        disposed = true
                        w.dispose()
                    }
                }

                "getWidget" -> widget
                "toString" -> "StatusBarSpy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args != null && args.firstOrNull() === proxy
                else -> when (method.returnType) {
                    java.lang.Boolean::class.javaPrimitiveType -> false
                    java.lang.Integer::class.javaPrimitiveType -> 0
                    java.lang.Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        }

        val statusBar: StatusBar = Proxy.newProxyInstance(
            StatusBar::class.java.classLoader,
            arrayOf(StatusBar::class.java),
            handler,
        ) as StatusBar
    }

    private lateinit var spy: StatusBarSpy
    private lateinit var widget: RecordingStatusWidget
    private lateinit var settings: CopilotVoiceSettings

    override fun setUp() {
        super.setUp()
        settings = CopilotVoiceSettings.instance
        settings.showIdleIndicator = true
        spy = StatusBarSpy()
        widget = RecordingStatusWidget(project)
        // What the platform does when it builds the window's status bar: add the widget
        // (the spy records it and installs it, as the platform does).
        spy.statusBar.addWidget(widget)
        // The first render (IDLE + the idle indicator on by default) makes the face visible.
        awaitEdt("the widget should install and render its first face") {
            widget.getComponent().isVisible
        }
    }

    override fun tearDown() {
        try {
            // Terminal for the widget (the platform disposes widgets it removes).
            widget.dispose()
            drainEdt()
        } finally {
            super.tearDown()
        }
    }

    private fun setPhase(phase: DictationPhase, problem: String? = null) {
        DictationStatus.instance.applyDerivedPhase { DerivedPhase(phase, problem) }
    }

    private fun awaitEdt(message: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(message, { condition() }, 10)
    }

    /** Runs [action] on the EDT (the settings listeners fire on the caller's thread), then waits for [condition]. */
    private fun onEdtAndWait(message: String, action: () -> Unit, condition: () -> Boolean) {
        ApplicationManager.getApplication().invokeLater { action() }
        awaitEdt(message, condition)
    }

    /** Forces the EDT to process everything queued (see the phase-derivation test's helper). */
    private fun drainEdt() {
        val drained = AtomicBoolean(false)
        ApplicationManager.getApplication().invokeLater { drained.set(true) }
        PlatformTestUtil.waitWithEventsDispatching(
            "the EDT should drain its queue",
            { drained.get() },
            10,
        )
    }

    /**
     * N15: hiding the idle face must not kill the widget. The old render() removed the
     * widget from the bar (disposing it), so flipping the setting back on rendered nothing
     * ever again. The fixed render() toggles visibility — the widget stays alive, visible
     * again, and still tracking the phase.
     */
    fun test_hiding_then_showing_the_idle_face_keeps_the_widget_updating() {
        setPhase(DictationPhase.IDLE)
        onEdtAndWait(
            "the idle face should be hidden when the idle indicator is off",
            { settings.showIdleIndicator = false },
            { !widget.getComponent().isVisible },
        )
        assertFalse("hiding must not dispose the widget (review N15)", spy.disposed)

        // Flipping the setting back on brings the same live widget back...
        onEdtAndWait(
            "the idle face should be visible again when the idle indicator is on",
            { settings.showIdleIndicator = true },
            { widget.getComponent().isVisible },
        )
        assertEquals(VoiceBundle["statusbar.idle"], widget.labelText())

        // ... and it must still track the phase — a dead (disposed) widget would not.
        setPhase(DictationPhase.OPENING)
        awaitEdt(
            "the widget must update after the hide/show cycle",
        ) {
            widget.labelText() == VoiceBundle["statusbar.opening"]
        }
    }

    /**
     * Review N15: across a full hide → show → phase cycle, the widget must never call
     * addWidget or removeWidget itself — the platform added it once (the spy recorded that
     * single add), and every hiding is a visibility toggle.
     */
    fun test_render_never_adds_or_removes_the_widget() {
        val platformAdds = spy.calls.count { it == "addWidget" }
        assertEquals("the platform adds the widget exactly once", 1, platformAdds)

        // A full cycle: hidden idle, shown idle, an active face, and back to idle.
        setPhase(DictationPhase.IDLE)
        onEdtAndWait("hide", { settings.showIdleIndicator = false }, { !widget.getComponent().isVisible })
        onEdtAndWait("show", { settings.showIdleIndicator = true }, { widget.getComponent().isVisible })
        setPhase(DictationPhase.RECORDING)
        awaitEdt("active face") { widget.labelText() == VoiceBundle["statusbar.recording"] }
        setPhase(DictationPhase.IDLE)
        awaitEdt("back to idle") { widget.labelText() == VoiceBundle["statusbar.idle"] }

        assertEquals(
            "render() must never add the widget — the platform owns that",
            platformAdds,
            spy.calls.count { it == "addWidget" },
        )
        assertEquals("render() must never remove the widget (review N15)", 0, spy.calls.count { it == "removeWidget" })
        assertFalse("the widget must never be disposed by the bar (review N15)", spy.disposed)
    }

    /**
     * Review §3: the spinner animates only in OPENING and TRANSCRIBING — in every other face
     * it is hidden and suspended, so no animation timer runs at IDLE or RECORDING.
     *
     * The decision is asserted on the widget's own state ([RecordingStatusWidget.spinnerResumed]),
     * not on [RecordingStatusWidget.spinnerRunning]: the platform deliberately skips animation
     * in unit-test mode, and what that leaves observable differs by line — on 242 the animator
     * never starts in tests, so isRunning() stays false whatever the widget decided; on 253 a
     * hidden animator keeps running, so it reflects the decision. The decision itself is the
     * widget's contract and is identical on both lines. The "must not be running" direction is
     * additionally asserted against the platform state, where it holds on both lines (a
     * suspended icon is not running; on 242 nothing ever runs in tests).
     */
    fun test_spinner_is_suspended_when_not_shown() {
        setPhase(DictationPhase.IDLE)
        awaitEdt("the IDLE face must land") { widget.labelText() == VoiceBundle["statusbar.idle"] }
        assertFalse("the IDLE face must not resume the spinner", widget.spinnerResumed)
        assertFalse("the IDLE face must not animate the spinner", widget.spinnerRunning())

        setPhase(DictationPhase.OPENING)
        awaitEdt("the OPENING face must land") { widget.labelText() == VoiceBundle["statusbar.opening"] }
        assertTrue("the OPENING face must resume the spinner", widget.spinnerResumed)

        setPhase(DictationPhase.TRANSCRIBING)
        awaitEdt("the TRANSCRIBING face must land") { widget.labelText() == VoiceBundle["statusbar.transcribing"] }
        assertTrue("the TRANSCRIBING face must resume the spinner", widget.spinnerResumed)

        setPhase(DictationPhase.RECORDING)
        awaitEdt("the RECORDING face must land") { widget.labelText() == VoiceBundle["statusbar.recording"] }
        assertFalse("the RECORDING face must suspend the spinner", widget.spinnerResumed)
        assertFalse("the RECORDING face must not animate the spinner", widget.spinnerRunning())
    }

    /**
     * Review §3 (the "only the icon is visible" bug): two defects made the text
     * disappear. (1) The label's reserved height was read from
     * textLabel.preferredSize while the label still had no text — (0, 0) — and that
     * explicit size locked in a zero-height label that draws nothing at any width.
     * (2) With a real height, the old FlowLayout view wrapped the label to a second row
     * whenever the bar squeezed the widget below its preferred width — below the bar's
     * fixed height, so only the icon stayed visible. Now the height comes from the font
     * and the widget is a fixed-width BoxLayout face: it clips at the edge at worst,
     * never wraps out of sight, and is never zero-height.
     *
     * The height asserted against is the bar's height, not the widget's preferred height:
     * a wrapping layout inflates its preferred height, which would let the bug hide inside
     * its own inflation.
     */
    fun test_a_narrow_bar_keeps_the_label_on_the_visible_row() {
        val view = widget.getComponent()
        val barHeight = JBUI.scale(24)

        // A wide bar: the widget gets the width it asked for, at the bar's height —
        // every part of the face must sit on the single visible row, and the label must
        // have a real (font) height — a zero-height label draws no text at all.
        view.size = Dimension(view.preferredSize.width, barHeight)
        view.doLayout()
        for (child in view.components) {
            val b = child.bounds
            assertTrue(
                "every part of the face must be visible at the bar's height: ${child.javaClass.simpleName} $b",
                b.x >= 0 && b.maxX <= view.size.width && b.maxY <= barHeight,
            )
        }
        val label = view.components.last()
        assertTrue(
            "the text label must have a non-zero height (the old code locked it to (width, 0)): ${label.bounds}",
            label.bounds.height > 0,
        )

        // A narrow bar cannot fit even the widget's minimum width, so it squeezes it below
        // that: the face must stay on the single visible row, clipped at the edge at worst.
        // The old FlowLayout view wrapped the label to a second, invisible row here.
        val squeezedWidth = (view.minimumSize.width * 2 / 3).coerceAtLeast(1)
        view.size = Dimension(squeezedWidth, barHeight)
        view.doLayout()
        val b = label.bounds
        assertTrue(
            "the label must stay on the visible row even when squeezed: label=$b view=${view.size}",
            b.maxY <= barHeight && b.x < view.size.width,
        )
    }

    /**
     * Review §3 (user requirement): a slot occupies space only while the face shows content
     * in it — no gap between the mic and the text in IDLE (the hidden spinner slot reserves
     * nothing), no leading gap before the spinner in TRANSCRIBING (the hidden icon slot
     * reserves nothing), and in OPENING icon, spinner and text sit adjacent. This pins both
     * the slot visibility and the view width: BoxLayout would leave trailing space if the
     * view kept a hidden slot's worth of width, and a visible-but-empty slot would leave
     * exactly the gap the user asked to remove.
     */
    fun test_empty_slots_do_not_reserve_space() {
        val view = widget.getComponent()
        val slot = JBUI.scale(16)
        val iconSlot = view.components[0]
        val spinnerSlot = view.components[1]
        val label = view.components[2]

        // IDLE: icon + text, adjacent — and the view is exactly as wide as that content.
        setPhase(DictationPhase.IDLE)
        awaitEdt("the IDLE face must land") { widget.labelText() == VoiceBundle["statusbar.idle"] }
        view.size = Dimension(view.preferredSize.width, JBUI.scale(24))
        view.doLayout()
        assertFalse("the IDLE face's empty spinner slot must not be shown", spinnerSlot.isVisible)
        assertEquals(
            "no gap may sit between the mic and the text in IDLE: icon=${iconSlot.bounds} label=${label.bounds}",
            iconSlot.bounds.x + iconSlot.bounds.width,
            label.bounds.x,
        )
        val viewInsets = view.insets.left + view.insets.right
        assertEquals(
            "the IDLE face's width must be exactly icon + text — no hidden slot space: ${view.preferredSize}",
            slot + label.preferredSize.width + viewInsets,
            view.preferredSize.width,
        )
        assertEquals(
            "the view must be a fixed-width component (minimum == content width): ${view.minimumSize}",
            view.preferredSize.width,
            view.minimumSize.width,
        )

        // TRANSCRIBING: spinner + text, adjacent — the hidden icon slot must leave no gap.
        setPhase(DictationPhase.TRANSCRIBING)
        awaitEdt("the TRANSCRIBING face must land") { widget.labelText() == VoiceBundle["statusbar.transcribing"] }
        view.size = Dimension(view.preferredSize.width, JBUI.scale(24))
        view.doLayout()
        assertFalse("the TRANSCRIBING face's empty icon slot must not be shown", iconSlot.isVisible)
        assertEquals(
            "no gap may sit before the spinner in TRANSCRIBING: spinner=${spinnerSlot.bounds}",
            0,
            spinnerSlot.bounds.x,
        )
        assertEquals(
            "no gap may sit between the spinner and the text: spinner=${spinnerSlot.bounds} label=${label.bounds}",
            spinnerSlot.bounds.x + spinnerSlot.bounds.width,
            label.bounds.x,
        )

        // OPENING: icon, spinner and text all present — all three adjacent, in order.
        setPhase(DictationPhase.OPENING)
        awaitEdt("the OPENING face must land") { widget.labelText() == VoiceBundle["statusbar.opening"] }
        view.size = Dimension(view.preferredSize.width, JBUI.scale(24))
        view.doLayout()
        assertEquals("OPENING shows the icon first: ${iconSlot.bounds}", 0, iconSlot.bounds.x)
        assertEquals(
            "no gap may sit between the mic and the spinner: icon=${iconSlot.bounds} spinner=${spinnerSlot.bounds}",
            iconSlot.bounds.x + iconSlot.bounds.width,
            spinnerSlot.bounds.x,
        )
        assertEquals(
            "no gap may sit between the spinner and the text: spinner=${spinnerSlot.bounds} label=${label.bounds}",
            spinnerSlot.bounds.x + spinnerSlot.bounds.width,
            label.bounds.x,
        )
    }
}
