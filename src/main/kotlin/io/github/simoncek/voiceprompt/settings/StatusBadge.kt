package io.github.simoncek.voiceprompt.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import javax.swing.SwingConstants

class StatusBadge(status: ModelStatus, text: String = status.label) : JBLabel(text) {
    private val badgeColor: JBColor = when (status) {
        ModelStatus.ACTIVE -> JBColor(0x1a7f37, 0x3fb950)
        ModelStatus.INSTALLED -> JBColor(0x6e6e73, 0x48484a)
        ModelStatus.BUNDLED -> JBColor(0x6e6e73, 0x48484a)
        ModelStatus.NOT_DOWNLOADED -> JBColor(0x9e9ea2, 0x6e6e73)
        ModelStatus.DOWNLOADING -> JBColor(0x9e9ea2, 0x6e6e73)
    }

    init {
        isOpaque = false
        foreground = Color.WHITE
        border = JBUI.Borders.empty(3, 9)
        horizontalAlignment = SwingConstants.CENTER
        isFocusable = false
        if (status == ModelStatus.DOWNLOADING) {
            // Hold the widest label's width so the pill doesn't shrink as the text changes ("Downloading" -> "42%").
            val height = preferredSize.height
            val size = Dimension(CONSTANT_WIDTH, height)
            preferredSize = size
            minimumSize = size
            maximumSize = size
        }
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

    private companion object {
        /** Widest status label, so every badge (any text, e.g. a live %) is the same width. */
        val CONSTANT_WIDTH: Int by lazy {
            val probe = JBLabel("")
            probe.border = JBUI.Borders.empty(3, 9)
            val insets = probe.border.getBorderInsets(probe)
            val metrics = Toolkit.getDefaultToolkit().getFontMetrics(probe.font)
            val widestText = ModelStatus.values().maxOf { metrics.stringWidth(it.label) }
            widestText + insets.left + insets.right
        }
    }
}
