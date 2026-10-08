package com.example.copilotvoice.sink

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
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

    override fun insert(project: Project?, text: String, autoSend: Boolean): Result<Unit> {
        val p = project ?: return Result.failure(NoCopilotWindowException())
        val window = CopilotChatLocator.findToolWindow(p) ?: return Result.failure(NoCopilotWindowException())
        window.show()
        val input = CopilotChatLocator.findInput(window) ?: return Result.failure(InputNotFoundException())

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
        if (autoSend) dispatchEnter(input.component)
        return Result.success(Unit)
    }

    private fun appendToSwing(field: JTextComponent, text: String) {
        val existing = field.text
        val next = when {
            existing.isBlank() -> text
            existing.endsWith(" ") || existing.endsWith("\n") -> existing + text
            else -> "$existing $text"
        }
        log.info("appendToSwing: before='${existing}' after='${next}' (len ${field.document.length} -> ${next.length})")
        field.text = next
        field.caretPosition = field.document.length
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
