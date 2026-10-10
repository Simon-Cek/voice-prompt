package com.example.copilotvoice.settings

import com.example.copilotvoice.VoiceBundle
import com.example.copilotvoice.stt.ModelManager
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBLabel
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.Toolkit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class ModelRow(
    model: ModelManager.ModelOption,
    private val status: ModelStatus,
    private val isLast: Boolean,
    private val installedCount: () -> Int,
    private val onUse: () -> Unit,
    private val onDownload: () -> Unit,
    private val onDelete: () -> Unit,
) : JPanel(GridBagLayout()) {

    init {
        isOpaque = false
        border = JBUI.Borders.compound(
            if (isLast) JBUI.Borders.empty() else JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(8, 10),
        )
        minimumSize = Dimension(0, JBUI.scale(30))
        maximumSize = Dimension(Integer.MAX_VALUE, JBUI.scale(44))

        val name = ModelNameLabel(model.displayName, "${model.id} · ${model.note}")
        val size = JBLabel(model.sizeLabel).apply { fixedSize(JBUI.scale(64), JBUI.scale(44)) }
        val badgeLabel = StatusBadge(status)
        val badge = LeftCenterPanel(badgeLabel).apply {
            fixedSize(BADGE_COLUMN_WIDTH, JBUI.scale(44))
        }
        val actions = JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(
                JPanel().apply { isOpaque = false },
                GridBagConstraints().apply {
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                },
            )
            when (status) {
                ModelStatus.ACTIVE -> add(styled(deleteButton()), buttonConstraints())
                ModelStatus.INSTALLED -> {
                    add(styled(useButton()), buttonConstraints())
                    add(styled(deleteButton()), buttonConstraints())
                }
                ModelStatus.NOT_DOWNLOADED -> add(styled(downloadButton()), buttonConstraints())
            }
            fixedSize(JBUI.scale(192), JBUI.scale(44))
        }

        add(name, cell(weightX = 1.0, fill = GridBagConstraints.HORIZONTAL, rightInset = 10))
        add(size, cell(rightInset = 10))
        add(badge, cell(rightInset = 10))
        add(actions, cell())
    }

    private fun useButton(): JButton = JButton(VoiceBundle["model.use"]).apply {
        addActionListener { onUse() }
    }

    private fun downloadButton(): JButton = JButton(VoiceBundle["model.download"]).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { onDownload() }
    }

    private fun deleteButton(): JButton = JButton(VoiceBundle["model.delete"]).apply {
        when {
            installedCount() <= 1 -> {
                isEnabled = false
                toolTipText = VoiceBundle["model.delete.disabled.only"]
            }
            status == ModelStatus.ACTIVE -> {
                isEnabled = false
                toolTipText = VoiceBundle["model.delete.disabled.active"]
            }
            else -> addActionListener { onDelete() }
        }
    }

    private fun styled(button: JButton): JButton = button.apply {
        isFocusable = false
    }

    private fun buttonConstraints(): GridBagConstraints = GridBagConstraints().apply {
        anchor = GridBagConstraints.EAST
        insets = JBUI.insetsLeft(JBUI.scale(6))
    }

    private fun cell(
        weightX: Double = 0.0,
        fill: Int = GridBagConstraints.NONE,
        rightInset: Int = 0,
    ): GridBagConstraints = GridBagConstraints().apply {
        anchor = GridBagConstraints.WEST
        this.weightx = weightX
        this.fill = fill
        if (rightInset > 0) insets = JBUI.insetsRight(rightInset)
    }

    private fun <T : JComponent> T.fixedSize(width: Int, height: Int): T {
        val size = Dimension(width, height)
        preferredSize = size
        minimumSize = size
        maximumSize = size
        return this
    }

    private companion object {
        val BADGE_COLUMN_WIDTH: Int by lazy {
            val widest = ModelStatus.values().maxOf { StatusBadge(it).preferredSize.width }
            widest + JBUI.scale(8)
        }
    }
}

private class ModelNameLabel(
    private val nameText: String,
    private val noteText: String,
) : JLabel() {
    private val spacing = JBUI.scale(6)
    private val nameFont: Font
    private val noteFont: Font
    private val nameColor: Color

    init {
        isOpaque = false
        isFocusable = false
        text = ""
        verticalAlignment = SwingConstants.CENTER
        noteFont = font
        nameFont = font.deriveFont(Font.BOLD)
        nameColor = foreground
    }

    override fun getPreferredSize(): Dimension {
        val nameMetrics = Toolkit.getDefaultToolkit().getFontMetrics(nameFont)
        val noteMetrics = Toolkit.getDefaultToolkit().getFontMetrics(noteFont)
        val width = nameMetrics.stringWidth(nameText) + spacing + noteMetrics.stringWidth(noteText)
        val height = maxOf(nameMetrics.height, noteMetrics.height)
        return Dimension(width, height)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun getMaximumSize(): Dimension = Dimension(Integer.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val nameMetrics = g2.getFontMetrics(nameFont)
            val noteMetrics = g2.getFontMetrics(noteFont)
            val baseline = maxOf(nameMetrics.ascent, noteMetrics.ascent)
            val fullWidth = nameMetrics.stringWidth(nameText) + spacing + noteMetrics.stringWidth(noteText)
            if (fullWidth <= width) {
                g2.font = nameFont
                g2.color = nameColor
                g2.drawString(nameText, 0, baseline)
                g2.font = noteFont
                g2.color = JBColor.GRAY
                g2.drawString(noteText, nameMetrics.stringWidth(nameText) + spacing, baseline)
            } else {
                val nameWidth = nameMetrics.stringWidth(nameText)
                if (nameWidth + spacing <= width) {
                    g2.font = nameFont
                    g2.color = nameColor
                    g2.drawString(nameText, 0, baseline)
                    val available = width - nameWidth - spacing
                    if (available > 0) {
                        g2.font = noteFont
                        g2.color = JBColor.GRAY
                        g2.drawString(truncate(noteText, noteMetrics, available), nameWidth + spacing, baseline)
                    }
                } else {
                    g2.font = nameFont
                    g2.color = nameColor
                    g2.drawString(truncate(nameText, nameMetrics, width), 0, baseline)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    private fun truncate(text: String, metrics: FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = metrics.stringWidth(ellipsis)
        if (maxWidth < ellipsisWidth) return ""
        val limit = maxWidth - ellipsisWidth
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > limit) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }
}

private class LeftCenterPanel(private val child: JComponent) : JPanel() {
    init {
        layout = null
        isOpaque = false
        add(child)
    }

    override fun doLayout() {
        val size = child.preferredSize
        child.setBounds(0, (height - size.height) / 2, size.width, size.height)
    }
}
