package com.example.copilotvoice.settings

import com.example.copilotvoice.VoiceBundle
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * A small pill-shaped label indicating a model row's status.
 *
 * @param status the status to display; determines the text and colour.
 */
class StatusBadge(status: ModelStatus) : JLabel(
    when (status) {
        ModelStatus.ACTIVE -> VoiceBundle["model.status.active"]
        ModelStatus.INSTALLED -> VoiceBundle["model.status.installed"]
        ModelStatus.NOT_DOWNLOADED -> VoiceBundle["model.status.notdownloaded"]
    }
) {
    private val badgeColor: JBColor = when (status) {
        ModelStatus.ACTIVE -> JBColor(0x1a7f37, 0x3fb950)
        ModelStatus.INSTALLED -> JBColor(0x6e6e73, 0x48484a)
        ModelStatus.NOT_DOWNLOADED -> JBColor(0x9e9ea2, 0x6e6e73)
    }

    init {
        isOpaque = false
        foreground = Color.WHITE
        border = JBUI.Borders.empty(3, 9)
        horizontalAlignment = SwingConstants.CENTER
        isFocusable = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = badgeColor
            g2.fillRoundRect(0, 0, width, height, height - 1, height - 1)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
