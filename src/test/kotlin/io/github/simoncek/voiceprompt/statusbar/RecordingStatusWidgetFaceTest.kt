package io.github.simoncek.voiceprompt.statusbar

import com.intellij.testFramework.LightPlatformTestCase
import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.state.DictationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Review §3: the widget's face table — [RecordingStatusWidget.faceFor] is the single pure
 * source of the icon slot's content, the spinner slot, visibility, label, tooltip and click
 * action. This test walks the full phase × idle-setting table (plus the PROBLEM reason
 * variants) and asserts every field, so a face can no longer change one of them without a
 * test noticing.
 *
 * LightPlatformTestCase only because the label/tooltip fields evaluate [VoiceBundle]
 * (DynamicBundle); the function under test itself is pure — no widget, no bar, no EDT.
 * Plain method names: the JUnit3-style runner rejects backticked names.
 */
class RecordingStatusWidgetFaceTest : LightPlatformTestCase() {

    private fun face(
        phase: DictationPhase,
        showIdle: Boolean = true,
        problem: String? = null,
    ): RecordingStatusWidget.Face = RecordingStatusWidget.faceFor(phase, showIdle, problem)

    fun test_idle_face_is_the_blue_mic_and_visible_only_when_enabled() {
        val shown = face(DictationPhase.IDLE, showIdle = true)
        assertEquals(RecordingStatusWidget.IconId.IDLE_MIC, shown.icon)
        assertFalse("the idle face has no spinner", shown.spinner)
        assertTrue("the idle face is visible when the idle indicator is on", shown.visible)
        assertEquals(VoiceBundle["statusbar.idle"], shown.text)
        assertEquals(VoiceBundle["statusbar.tooltip.ready"], shown.tooltip)
        assertEquals(RecordingStatusWidget.ClickAction.OPEN_SETTINGS, shown.click)

        // The hidden face is the same face minus visibility — the icon and text do not
        // change, and the setting only applies to IDLE (never to the active/problem faces).
        val hidden = face(DictationPhase.IDLE, showIdle = false)
        assertFalse("the idle face is hidden when the idle indicator is off", hidden.visible)
        assertEquals(shown.icon, hidden.icon)
        assertEquals(shown.text, hidden.text)
        assertEquals(shown.tooltip, hidden.tooltip)
        assertEquals(shown.click, hidden.click)
    }

    fun test_opening_face_is_the_amber_mic_with_the_spinner() {
        val f = face(DictationPhase.OPENING)
        assertEquals(RecordingStatusWidget.IconId.OPENING_MIC, f.icon)
        assertTrue("OPENING shows the platform spinner", f.spinner)
        assertTrue(f.visible)
        assertEquals(VoiceBundle["statusbar.opening"], f.text)
        assertEquals(VoiceBundle["statusbar.tooltip.opening"], f.tooltip)
        assertEquals(RecordingStatusWidget.ClickAction.STOP_RECORDING, f.click)
        // The idle setting does not apply to the active faces.
        assertEquals(f.visible, face(DictationPhase.OPENING, showIdle = false).visible)
    }

    fun test_recording_face_is_the_bold_red_mic_without_the_spinner() {
        val f = face(DictationPhase.RECORDING)
        assertEquals(RecordingStatusWidget.IconId.RECORDING_MIC, f.icon)
        assertFalse("RECORDING shows the mic only, no spinner", f.spinner)
        assertTrue(f.visible)
        assertEquals(VoiceBundle["statusbar.recording"], f.text)
        assertEquals(VoiceBundle["statusbar.tooltip.recording"], f.tooltip)
        assertEquals(RecordingStatusWidget.ClickAction.STOP_RECORDING, f.click)
    }

    fun test_transcribing_face_is_the_spinner_only_and_cancels_on_click() {
        val f = face(DictationPhase.TRANSCRIBING)
        assertEquals("TRANSCRIBING empties the icon slot — the spinner carries the face", RecordingStatusWidget.IconId.NONE, f.icon)
        assertTrue("TRANSCRIBING shows the platform spinner", f.spinner)
        assertTrue(f.visible)
        assertEquals(VoiceBundle["statusbar.transcribing"], f.text)
        assertEquals(VoiceBundle["statusbar.tooltip.transcribing"], f.tooltip)
        assertEquals("review §3: clicking cancels the transcription", RecordingStatusWidget.ClickAction.CANCEL_TASK, f.click)
    }

    fun test_problem_face_shows_the_reason_and_ignores_the_idle_setting() {
        val reason = VoiceBundle["statusbar.problem.mic.no.response"]
        val withReason = face(DictationPhase.PROBLEM, problem = reason)
        assertEquals(RecordingStatusWidget.IconId.PROBLEM, withReason.icon)
        assertFalse("the problem face has no spinner", withReason.spinner)
        assertTrue("the problem face is always visible", withReason.visible)
        assertEquals("the reason replaces the label", reason, withReason.text)
        assertEquals("the reason replaces the tooltip too", reason, withReason.tooltip)
        assertEquals(RecordingStatusWidget.ClickAction.OPEN_SETTINGS, withReason.click)

        // Without a reason the face falls back to the generic text — and the idle setting
        // still does not apply to it.
        val noReason = face(DictationPhase.PROBLEM, showIdle = false)
        assertEquals(VoiceBundle["statusbar.problem"], noReason.text)
        assertTrue(noReason.visible)
        assertEquals(RecordingStatusWidget.ClickAction.OPEN_SETTINGS, noReason.click)
    }

    /**
     * Review §3 (user requirement): every face carries a non-empty label — the bar shows
     * icon + text in every phase. No phase deliberately drops the text: IDLE shows
     * "Voice Prompt", OPENING / RECORDING / TRANSCRIBING show their phase, PROBLEM shows
     * its reason (or the generic fallback).
     */
    fun test_every_face_has_a_non_empty_label() {
        for (showIdle in listOf(true, false)) {
            for (problem in listOf<String?>(null, VoiceBundle["statusbar.problem.mic.no.response"])) {
                for (phase in DictationPhase.values()) {
                    val f = face(phase, showIdle, problem)
                    assertTrue(
                        "the $phase face (showIdle=$showIdle, problem=$problem) must carry a non-empty label",
                        f.text.isNotBlank(),
                    )
                }
            }
        }
    }
}
