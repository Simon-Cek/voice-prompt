package io.github.simoncek.voiceprompt.sink

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.text.JTextComponent

/**
 * Primary strategy: locate the chat input component and write the transcript into it directly — no
 * clipboard side effects. Works for both Swing text fields and IntelliJ editor-backed inputs.
 */
class SwingInjectionSink : ChatSink {

    private val log = Logger.getInstance(SwingInjectionSink::class.java)

    override fun isAvailable(project: Project?): Boolean = CopilotChatLocator.findToolWindow(project) != null

    override fun insert(
        project: Project?,
        text: String,
        autoSend: Boolean,
        isCancelled: () -> Boolean,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val p = project ?: return onComplete(Result.failure(NoCopilotWindowException()))
        val window = CopilotChatLocator.findToolWindow(p) ?: return onComplete(Result.failure(NoCopilotWindowException()))

        // Copilot can create its composer lazily, after the tool window has been shown (review #7):
        // search inside the "window is shown" callback, and retry once if the content is still
        // under construction — the very first dictation with a closed chat used to find nothing.
        // The settlement token travels with every deferred round (review N19).
        val content = window.contentManager.contents.firstOrNull()
        when {
            content == null -> window.show { attempt(p, window, text, autoSend, last = false, isCancelled, onComplete) }
            content.component == null -> window.activate { attempt(p, window, text, autoSend, last = false, isCancelled, onComplete) }
            else -> {
                window.show()
                attempt(p, window, text, autoSend, last = false, isCancelled, onComplete)
            }
        }
    }

    private fun attempt(
        p: Project,
        window: ToolWindow,
        text: String,
        autoSend: Boolean,
        last: Boolean,
        isCancelled: () -> Boolean,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val input = CopilotChatLocator.findInput(window)
        if (input != null) {
            onComplete(inject(p, input, text, autoSend, isCancelled))
            return
        }
        if (last) {
            onComplete(Result.failure(InputNotFoundException()))
            return
        }
        // One retry after the event queue has settled — the composer may still be under
        // construction.
        ApplicationManager.getApplication().invokeLater {
            attempt(p, window, text, autoSend, last = true, isCancelled, onComplete)
        }
    }

    private fun inject(p: Project, input: ChatInput, text: String, autoSend: Boolean, isCancelled: () -> Boolean): Result<Unit> {
        if (isCancelled()) {
            // Review N19: the chain has already settled — the safety timer fired and the
            // transcript reached the user via the balloon. Inserting now would deliver it
            // twice (and the Enter below would be the issue #7 risk). Back out; the chain
            // discards this result.
            log.warn("insert: backed out — the chain settled before this sink committed")
            return Result.failure(SinkCancelledException())
        }
        when (input) {
            is ChatInput.Swing -> {
                log.info("insert: Swing branch, target=${input.field.javaClass.name}")
                appendToSwing(input.field, text)
            }
            is ChatInput.Editor -> {
                log.info("insert: Editor branch, target=${input.editor.javaClass.name}")
                appendToEditor(p, input.editor, text)
            }
        }
        input.component.requestFocusInWindow()
        if (autoSend) {
            if (input.verified) {
                // Dispatch Enter on a *later* EDT round: Copilot's document listeners may still be
                // running from the insert, and an Enter dispatched before them can be lost or
                // misrouted (review #7).
                ApplicationManager.getApplication().invokeLater {
                    runCatching { dispatchEnter(input.component) }
                        .onFailure { log.warn("failed to dispatch the auto-send Enter", it) }
                }
            } else {
                // The input came from the locator's generic fallback: it may be any editable in a
                // Copilot-titled window (a search box, for instance). Injecting is fine; sending
                // from here is the risk the review flags (issue #7), so leave the Enter to the user.
                log.warn("autoSend suppressed: '${input.component.javaClass.name}' is not a verified Copilot composer")
            }
        }
        return Result.success(Unit)
    }

    private fun appendToSwing(field: JTextComponent, text: String) {
        val document = field.document
        val existing = field.text
        val prefix = when {
            existing.isBlank() -> ""
            existing.endsWith(" ") || existing.endsWith("\n") -> ""
            else -> " "
        }
        // Log lengths only — never the transcript or the user's existing prompt (AGENTS.md rule 8).
        log.info("appendToSwing: len ${existing.length} -> ${existing.length + prefix.length + text.length}")
        // Insert at the end rather than replacing the whole document: replacing wipes the undo
        // history and fires a replace-all event that can confuse Copilot's own listeners
        // (review #7). A null AttributeIterator is a plain text insert — the same call
        // JTextArea's own paste() makes.
        document.insertString(document.length, prefix + text, null)
        field.caretPosition = document.length
    }

    private fun appendToEditor(project: Project, editor: Editor, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            val offset = document.textLength
            val prefix = when {
                offset == 0 -> ""
                document.charsSequence[offset - 1].isWhitespace() -> ""
                else -> " "
            }
            log.info("appendToEditor: inserting at offset=$offset, docLen before=${document.textLength}")
            document.insertString(offset, prefix + text)
        }
        editor.caretModel.moveToOffset(editor.document.textLength)
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
