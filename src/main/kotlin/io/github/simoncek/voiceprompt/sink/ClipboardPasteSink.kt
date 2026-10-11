package io.github.simoncek.voiceprompt.sink

import com.intellij.openapi.project.Project
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ActionListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Fallback strategy: focus the chat window, put the transcript on the clipboard and dispatch a paste.
 * The user's previous clipboard contents are captured first and restored after the IDE has consumed the
 * paste, so we don't clobber what they had copied.
 */
class ClipboardPasteSink : ChatSink {

    override fun isAvailable(project: Project?): Boolean = CopilotChatLocator.findToolWindow(project) != null

    override fun insert(project: Project?, text: String, autoSend: Boolean): Result<Unit> {
        val window = project?.let { CopilotChatLocator.findToolWindow(it) }
            ?: return Result.failure(NoCopilotWindowException())
        window.show()
        val target = CopilotChatLocator.findInput(window)?.component
            ?: window.contentManager.contents.firstNotNullOfOrNull { it.component }
            ?: return Result.failure(InputNotFoundException())

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val original: Transferable? = try {
            clipboard.getContents(null)
        } catch (e: Exception) {
            null
        }
        clipboard.setContents(StringSelection(text), null)

        target.requestFocusInWindow()
        dispatchPaste(target)
        if (autoSend) dispatchEnter(target)

        Timer(CLIPBOARD_RESTORE_DELAY_MS, ActionListener { restoreClipboard(clipboard, original) }).apply {
            isRepeats = false
            start()
        }
        return Result.success(Unit)
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

    companion object {
        private const val CLIPBOARD_RESTORE_DELAY_MS = 500
    }
}
