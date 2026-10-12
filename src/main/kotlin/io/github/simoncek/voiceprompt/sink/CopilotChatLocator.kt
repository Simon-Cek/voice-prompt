package io.github.simoncek.voiceprompt.sink

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

/** A strategy for locating the Copilot chat tool window and its text input. */
interface ChatLocator {
    fun findToolWindow(project: Project?): ToolWindow?
    fun findInput(window: ToolWindow): ChatInput?
}

/**
 * Picks the [ChatLocator] for the installed Copilot version and delegates discovery to it. The exact
 * tool-window id and the input widget type vary across Copilot plugin versions, so a specific version
 * can register a known-good locator via [resolve]. Until one does, every version uses the lenient
 * locator that works across the versions we've seen.
 */
object CopilotChatLocator {

    private val log = Logger.getInstance(CopilotChatLocator::class.java)

    fun locator(): ChatLocator = resolve(CopilotVersion.installed())

    /**
     * Choose a locator for [version]. When a Copilot release breaks injection, add a known-good
     * locator for it here (e.g. `if (version?.startsWith("1.9") == true) return V19ChatLocator()`);
     * otherwise the lenient locator handles it.
     *
     * [version] is diagnostics only (see [CopilotVersion]) and may be null on any platform build —
     * the hook must keep working without it, so never gate the locator's availability on it.
     */
    private fun resolve(version: String?): ChatLocator {
        // Extension point for version-specific locators.
        return LenientChatLocator()
    }

    fun findToolWindow(project: Project?): ToolWindow? = locator().findToolWindow(project)

    fun findInput(window: ToolWindow): ChatInput? {
        log.info("findInput: copilot=${CopilotVersion.describe()}")
        return locator().findInput(window)
    }
}

/**
 * Deliberately lenient discovery: a list of candidate tool-window ids first, then a title scan; and
 * both Swing and editor-backed inputs are recognised. This is the default for every Copilot version.
 */
class LenientChatLocator : ChatLocator {

    private val log = Logger.getInstance(LenientChatLocator::class.java)

    private val CANDIDATE_IDS = listOf(
        "GitHub Copilot Chat",
        "Copilot Chat",
        "Copilot",
        "com.github.copilot.chat",
        "copilot",
    )

    override fun findToolWindow(project: Project?): ToolWindow? {
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

    override fun findInput(window: ToolWindow): ChatInput? {
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
