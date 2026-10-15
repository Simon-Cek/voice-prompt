package io.github.simoncek.voiceprompt.sink

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent

/**
 * Fallback strategy: focus the chat window, put the transcript on the clipboard and dispatch a
 * paste. The user's previous clipboard contents are captured first and restored as soon as the
 * paste has been verified, so we don't clobber what they had copied.
 *
 * Success is only reported when the paste verifiably landed (the target's text grew); a paste
 * with no effect fails this sink so the chain falls through to the balloon (review #7 — this sink
 * used to always report success). Keeping the transcript on the clipboard only for the verified
 * paste minimises the window in which clipboard-history tools can capture it; that exposure is
 * inherent to the paste strategy and is why the direct [SwingInjectionSink] is tried first.
 */
class ClipboardPasteSink : ChatSink {

    override fun isAvailable(project: Project?): Boolean = CopilotChatLocator.findToolWindow(project) != null

    override fun insert(project: Project?, text: String, autoSend: Boolean, onComplete: (Result<Unit>) -> Unit) {
        val window = project?.let { CopilotChatLocator.findToolWindow(it) }
            ?: return onComplete(Result.failure(NoCopilotWindowException()))
        window.show()
        val input = CopilotChatLocator.findInput(window)
            ?: return onComplete(Result.failure(InputNotFoundException()))

        // The target's text length, however it is backed — Swing field or editor document —
        // used to verify that the paste actually landed (review #7).
        val lengthOf: () -> Int
        when (input) {
            is ChatInput.Swing -> lengthOf = { input.field.text.length }
            is ChatInput.Editor -> lengthOf = { input.editor.document.textLength }
        }
        val target = input.component

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val original: Transferable? = try {
            clipboard.getContents(null)
        } catch (e: Exception) {
            null
        }
        clipboard.setContents(StringSelection(text), null)

        val before = lengthOf()
        target.requestFocusInWindow()
        dispatchPaste(target)

        // Verify the paste landed. A paste into a component that does not handle it is a no-op —
        // reporting success for that is how this sink used to swallow the transcript (review #7).
        // One retry round covers handlers that apply the paste asynchronously.
        fun checkPasted(last: Boolean) {
            if (lengthOf() != before) {
                if (autoSend && input.verified) {
                    // After the paste has been verified and on a later EDT round, so Copilot's
                    // listeners have had their turn (review #7).
                    ApplicationManager.getApplication().invokeLater { dispatchEnter(target) }
                }
                restoreClipboard(clipboard, original)
                onComplete(Result.success(Unit))
                return
            }
            if (last) {
                restoreClipboard(clipboard, original)
                onComplete(Result.failure(InputNotFoundException("the paste had no effect")))
                return
            }
            ApplicationManager.getApplication().invokeLater { checkPasted(last = true) }
        }
        checkPasted(last = false)
    }

    private fun restoreClipboard(clipboard: Clipboard, original: Transferable?) {
        try {
            if (original != null) clipboard.setContents(original, null) else clipboard.setContents(null, null)
        } catch (_: Exception) {
        }
    }

    private fun dispatchPaste(component: JComponent) {
        val mods = InputEvent.CTRL_DOWN_MASK
        component.dispatchEvent(
            KeyEvent(component, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mods, KeyEvent.VK_V, KeyEvent.CHAR_UNDEFINED),
        )
        component.dispatchEvent(
            KeyEvent(component, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), mods, KeyEvent.VK_V, KeyEvent.CHAR_UNDEFINED),
        )
    }

    private fun dispatchEnter(component: JComponent) {
        component.dispatchEvent(
            KeyEvent(component, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED),
        )
        component.dispatchEvent(
            KeyEvent(component, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED),
        )
    }
}
