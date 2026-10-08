package com.example.copilotvoice.sink

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.text.JTextComponent

/** A discovered chat input, either a Swing text field or an IntelliJ [com.intellij.openapi.editor.Editor]. */
sealed class ChatInput {
    abstract val component: JComponent

    data class Swing(val field: JTextComponent) : ChatInput() {
        override val component: JComponent = field
    }

    data class Editor(
        val editor: com.intellij.openapi.editor.Editor,
        override val component: JComponent,
    ) : ChatInput()
}

/**
 * Locates the GitHub Copilot chat tool window and its text input. The exact tool-window id and the
 * input widget type vary across Copilot plugin versions, so discovery is deliberately lenient: a list
 * of candidate ids first, then a title scan; and both Swing and editor-backed inputs are recognised.
 */
object CopilotChatLocator {

    private val log = Logger.getInstance(CopilotChatLocator::class.java)

    private val CANDIDATE_IDS = listOf(
        "GitHub Copilot Chat",
        "Copilot Chat",
        "Copilot",
        "com.github.copilot.chat",
        "copilot",
    )

    fun findToolWindow(project: Project?): ToolWindow? {
        if (project == null) return null
        val manager = ToolWindowManager.getInstance(project)
        for (id in CANDIDATE_IDS) {
            val window = manager.getToolWindow(id)
            if (window != null && hasComponent(window)) return window
        }
        val copilotWindows = manager.toolWindowIds
            .mapNotNull { manager.getToolWindow(it) }
            .filter { it.title?.lowercase()?.contains("copilot") == true }
        return copilotWindows.firstOrNull { it.title?.lowercase()?.contains("chat") == true }
            ?: copilotWindows.firstOrNull()
    }

    fun findInput(window: ToolWindow): ChatInput? {
        val root = window.contentManager.contents.firstNotNullOfOrNull { it.component } ?: return null
        log.info("findInput: scanning tool window '${window.title}' (id=${window.id})")

        val editable = UIUtil.findComponentsOfType(root, JTextComponent::class.java)
            .filter { it.isEditable && it.isEnabled }
        for (c in editable) {
            log.info(
                "  candidate ${c.javaClass.name} showing=${c.isShowing} visible=${c.isVisible} size=${c.width}x${c.height}",
            )
        }

        // Prefer Copilot's own chat input widget. Among matches, prefer one actually on screen (isShowing):
        // the tool window can hold hidden twin composers that are isVisible but not showing.
        val copilot = editable.filter { c ->
            val n = c.javaClass.name.lowercase()
            "copilot" in n && ("input" in n || "chat" in n || "textarea" in n)
        }.ifEmpty { editable.filter { it.javaClass.name.lowercase().contains("copilot") } }

        copilot.firstOrNull { it.isShowing }?.let {
            log.info("findInput: SELECTED Copilot input ${it.javaClass.name} (showing)")
            return ChatInput.Swing(it)
        }
        copilot.firstOrNull { it.isVisible }?.let {
            log.info("findInput: SELECTED Copilot input ${it.javaClass.name} (visible, not showing)")
            return ChatInput.Swing(it)
        }

        // Fallbacks for other plugin versions / layouts.
        val editorField = UIUtil.findComponentsOfType(root, EditorTextField::class.java)
            .firstOrNull { it.isVisible && it.isEnabled && it.editor?.document?.isWritable == true }
        if (editorField != null) {
            val ed = editorField.editor ?: return null
            log.info("findInput: SELECTED Editor-backed field ${ed.javaClass.name}")
            return ChatInput.Editor(ed, editorField)
        }

        editable.firstOrNull { it.isShowing }?.let {
            log.info("findInput: SELECTED (fallback first-editable showing) ${it.javaClass.name}")
            return ChatInput.Swing(it)
        }
        editable.firstOrNull { it.isVisible }?.let {
            log.info("findInput: SELECTED (fallback first-editable visible) ${it.javaClass.name}")
            return ChatInput.Swing(it)
        }

        log.info("findInput: NO input found")
        return null
    }

    private fun hasComponent(window: ToolWindow): Boolean =
        window.contentManager.contents.any { it.component != null }
}
