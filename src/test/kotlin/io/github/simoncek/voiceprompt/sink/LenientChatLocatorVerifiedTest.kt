package io.github.simoncek.voiceprompt.sink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Review N18: an editor-backed input is only *verified* when the component or an ancestor
 * identifies as Copilot's — the editor tier matches any visible, writable field in the window
 * (a search box included), and auto-sending into that is the issue #7 risk.
 * [LenientChatLocator.identifiesAsCopilot] is the decision the tier feeds into
 * `ChatInput.Editor.verified`; this pins it with plain Swing trees (no platform, no real
 * components) and in both directions — identified (verified) and not (the sink then suppresses
 * the auto-send Enter and leaves it to the user).
 */
class LenientChatLocatorVerifiedTest {

    // A container that identifies as Copilot's: its class name carries "copilot".
    private class CopilotComposerPanel : JPanel()

    // A field that identifies as Copilot's by its own class name.
    private class CopilotChatInputField : JTextField()

    private val locator = LenientChatLocator()

    @Test
    fun `a field under a copilot-named ancestor is identified`() {
        val field = JTextField()
        CopilotComposerPanel().add(field)
        assertTrue("review N18: a Copilot-named ancestor must verify the field", locator.identifiesAsCopilot(field))
    }

    @Test
    fun `a copilot-named field is identified even under plain ancestors`() {
        val field = CopilotChatInputField()
        JPanel().add(field)
        assertTrue("review N18: the field's own Copilot class name must verify it", locator.identifiesAsCopilot(field))
    }

    @Test
    fun `a field under plain ancestors is not identified`() {
        val field = JTextField()
        JPanel().apply { add(JPanel().apply { add(field) }) }
        assertFalse(
            "review N18: an unidentifiable field must stay unverified (auto-send suppressed)",
            locator.identifiesAsCopilot(field),
        )
    }

    @Test
    fun `a parentless field is not identified`() {
        assertFalse("review N18: no marker in the tree means unverified", locator.identifiesAsCopilot(JTextField()))
    }
}
