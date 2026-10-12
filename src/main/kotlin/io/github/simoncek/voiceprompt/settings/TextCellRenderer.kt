package io.github.simoncek.voiceprompt.settings

import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList

/**
 * A text-only cell renderer for the settings dropdowns.
 *
 * Subclasses [SimpleListCellRenderer] directly instead of using its static `create(...)` factories:
 * those factories are deprecated (scheduled for removal) in 2026.x, while the class itself is not —
 * and the recommended DSL replacement (`textListCellRenderer`) does not exist in 242/243 and is
 * marked internal in 251–261, so it cannot be used while this plugin supports 242+.
 */
class TextCellRenderer<T>(private val textOf: (T) -> String) : SimpleListCellRenderer<T>() {

    override fun customize(list: JList<out T>, value: T, index: Int, selected: Boolean, hasFocus: Boolean) {
        text = value?.let(textOf) ?: ""
    }
}
