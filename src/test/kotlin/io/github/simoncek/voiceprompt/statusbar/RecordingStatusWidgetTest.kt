package io.github.simoncek.voiceprompt.statusbar

import io.github.simoncek.voiceprompt.state.DictationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the widget's pure decision functions (visibility and click routing). The
 * widget itself needs a live StatusBar, which is covered by manual sandbox verification.
 * These tests never touch the icon, so they run without an IntelliJ application.
 */
class RecordingStatusWidgetTest {

    @Test
    fun `idle is visible only when the idle indicator is enabled`() {
        assertTrue(RecordingStatusWidget.isVisible(DictationPhase.IDLE, showIdleIndicator = true))
        assertFalse(RecordingStatusWidget.isVisible(DictationPhase.IDLE, showIdleIndicator = false))
    }

    @Test
    fun `active phases are always visible`() {
        for (phase in listOf(DictationPhase.OPENING, DictationPhase.RECORDING, DictationPhase.TRANSCRIBING)) {
            assertTrue(RecordingStatusWidget.isVisible(phase, showIdleIndicator = true))
            assertTrue(RecordingStatusWidget.isVisible(phase, showIdleIndicator = false))
        }
    }

    @Test
    fun `click opens settings only when idle`() {
        assertEquals(RecordingStatusWidget.ClickAction.OPEN_SETTINGS, RecordingStatusWidget.clickAction(DictationPhase.IDLE))
    }

    @Test
    fun `click stops the recording while opening or recording`() {
        assertEquals(RecordingStatusWidget.ClickAction.STOP_RECORDING, RecordingStatusWidget.clickAction(DictationPhase.OPENING))
        assertEquals(RecordingStatusWidget.ClickAction.STOP_RECORDING, RecordingStatusWidget.clickAction(DictationPhase.RECORDING))
    }

    @Test
    fun `click does nothing while transcribing`() {
        assertEquals(RecordingStatusWidget.ClickAction.NONE, RecordingStatusWidget.clickAction(DictationPhase.TRANSCRIBING))
    }
}
