package io.github.simoncek.voiceprompt.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * A thin rounded horizontal bar showing the live microphone input level.
 *
 * Set [level] between `0.0` and `1.0`, or use [updateLevel] with a `0`–`100` value.
 * The meter is safe to update from a background thread; the repaint is marshalled to the EDT.
 */
class LevelMeter : JComponent() {
    var level: Double = 0.0
        set(value) {
            field = value.coerceIn(0.0, 1.0)
            UIUtil.invokeLaterIfNeeded { repaint() }
        }

    init {
        isOpaque = false
        isFocusable = false
        preferredSize = JBUI.size(120, 6)
        maximumSize = Dimension(JBUI.scale(120), Integer.MAX_VALUE)
    }

    /** Set the meter level using the legacy 0–100 scale. */
    fun updateLevel(level: Int) {
        this.level = level / 100.0
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor.border()
            g2.fillRoundRect(0, 0, width, height, height, height)

            if (level > 0.0) {
                g2.color = JBColor(0x4CAF50, 0x5FB865)
                val filledWidth = (width * level).toInt().coerceAtLeast(height)
                g2.fillRoundRect(0, 0, filledWidth, height, height, height)
            }
        } finally {
            g2.dispose()
        }
    }
}
