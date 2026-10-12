package io.github.simoncek.voiceprompt.settings

import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.download.DownloadingActions
import io.github.simoncek.voiceprompt.download.ModelDownloadService
import io.github.simoncek.voiceprompt.stt.ModelManager
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class ModelRow(
    private val model: ModelManager.ModelOption,
    private var status: ModelStatus,
    private val isLast: Boolean,
    /** How many models are usable right now (on disk, or bundled and not deleted). */
    private val usableCount: () -> Int,
    private val onUse: () -> Unit,
    private val onDelete: () -> Unit,
    private val onRestore: (Boolean) -> Unit,
    private val parentDisposable: Disposable,
) : JPanel(GridBagLayout()) {

    private val downloads = ApplicationManager.getApplication().getService(ModelDownloadService::class.java)
    private var badge: StatusBadge = StatusBadge(status)
    private val badgeHolder = LeftCenterPanel(badge)
    private val actions = JPanel(GridBagLayout()).apply { isOpaque = false }
    private var downloadingUi: DownloadingActions? = null

    init {
        isOpaque = false
        border = JBUI.Borders.compound(
            if (isLast) JBUI.Borders.empty() else JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(8, 10),
        )
        minimumSize = Dimension(0, JBUI.scale(30))
        maximumSize = Dimension(Integer.MAX_VALUE, JBUI.scale(44))

        val name = ModelNameLabel(model.displayName, model.note)
        val size = JBLabel(model.sizeLabel).apply { fixedSize(JBUI.scale(64), JBUI.scale(44)) }
        badgeHolder.fixedSize(BADGE_COLUMN_WIDTH, JBUI.scale(44))
        actions.fixedSize(JBUI.scale(192), JBUI.scale(44))

        add(name, cell(weightX = 1.0, fill = GridBagConstraints.HORIZONTAL, rightInset = 10))
        add(size, cell(rightInset = 10))
        add(badgeHolder, cell(rightInset = 10))
        add(actions, cell())

        downloads.addListener(parentDisposable) { id ->
            if (id == model.id) sync()
        }

        refresh()
    }

    private fun effectiveStatus(): ModelStatus =
        if (downloads.percent(model.id) != null) ModelStatus.DOWNLOADING else status

    private fun sync() {
        val current = effectiveStatus()
        if (current == ModelStatus.DOWNLOADING && downloadingUi != null) {
            val percent = downloads.percent(model.id) ?: -1
            downloadingUi?.update(percent)
            badge.text = if (percent >= 0) "$percent%" else ModelStatus.DOWNLOADING.label
            badgeHolder.relayout()
        } else {
            refresh()
        }
    }

    private fun refresh() {
        val current = effectiveStatus()
        downloadingUi = null

        badge = StatusBadge(current)
        badgeHolder.setChild(badge)

        actions.removeAll()
        actions.add(
            JPanel().apply { isOpaque = false },
            GridBagConstraints().apply {
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            },
        )
        when (current) {
            ModelStatus.ACTIVE -> actions.add(deleteButton(), buttonConstraints())
            ModelStatus.INSTALLED -> {
                actions.add(useButton(), buttonConstraints())
                actions.add(deleteButton(), buttonConstraints())
            }
            ModelStatus.BUNDLED -> actions.add(restoreButton(), buttonConstraints())
            ModelStatus.NOT_DOWNLOADED -> actions.add(downloadButton(), buttonConstraints())
            ModelStatus.DOWNLOADING -> {
                downloadingUi = DownloadingActions { downloads.cancel(model.id) }
                downloadingUi?.update(downloads.percent(model.id) ?: -1)
                actions.add(downloadingUi!!, buttonConstraints())
            }
        }
        actions.revalidate()
        actions.repaint()
    }

    private fun useButton(): JButton = HoverButton(VoiceBundle["model.use"]).apply {
        addActionListener { onUse() }
    }

    private fun downloadButton(): JButton {
        val model = this@ModelRow.model
        return HoverButton(VoiceBundle["model.download"]).apply {
            putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
            addActionListener {
                status = ModelStatus.DOWNLOADING
                downloads.start(model.id, model.displayName, ModelManager.downloadUrl(model.id)) { success ->
                    status = if (success) ModelStatus.INSTALLED else ModelStatus.NOT_DOWNLOADED
                }
            }
        }
    }

    /** Restores the model bundled inside the plugin — instant, no network. */
    private fun restoreButton(): JButton = HoverButton(VoiceBundle["model.restore"]).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener {
            isEnabled = false
            text = VoiceBundle["model.restoring"]
            val task = object : Task.Backgroundable(null, VoiceBundle["model.restoring"], false) {
                private var success = false

                override fun run(indicator: ProgressIndicator) {
                    success = ModelManager.installBundledModel() != null
                }

                override fun onFinished() {
                    // The configurable clears the "bundled deleted" flag and rebuilds the rows,
                    // so the badge is recomputed from the shared settings state.
                    onRestore(success)
                }
            }
            task.queue()
        }
    }

    private fun deleteButton(): JButton = HoverButton(VoiceBundle["model.delete"]).apply {
        when {
            status == ModelStatus.ACTIVE -> {
                isEnabled = false
                toolTipText = VoiceBundle["model.delete.disabled.active"]
            }
            // The bundled model is always deletable: the copy inside the plugin jar remains,
            // and the row offers to Restore it — deletion is what produces the Bundled state.
            !this@ModelRow.model.bundled && usableCount() <= 1 -> {
                isEnabled = false
                toolTipText = VoiceBundle["model.delete.disabled.only"]
            }
            else -> addActionListener { onDelete() }
        }
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

internal class HoverButton(text: String) : JButton(text) {
    private var hovered = false

    init {
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (hovered && isEnabled) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(Color(0, 0, 0, 25), Color(255, 255, 255, 30))
                val inset = JBUI.scale(HOVER_INSET)
                val arc = JBUI.scale(HOVER_ARC)
                g2.fillRoundRect(inset, inset, width - inset * 2, height - inset * 2, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        // Tune these two values in runIde until the highlight sits inside the border.
        const val HOVER_INSET = 3   // pixels (scaled below)
        const val HOVER_ARC = 6     // corner diameter (scaled below)
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
        val nameMetrics = getFontMetrics(nameFont)
        val noteMetrics = getFontMetrics(noteFont)
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

private class LeftCenterPanel(initial: JComponent) : JPanel() {
    private var child: JComponent = initial

    init {
        layout = null
        isOpaque = false
        add(initial)
    }

    fun setChild(newChild: JComponent) {
        remove(child)
        child = newChild
        add(newChild)
        doLayout()
        revalidate()
        repaint()
    }

    /** Re-center the current child after its preferred size changed (e.g. its text). */
    fun relayout() {
        doLayout()
        revalidate()
        repaint()
    }

    override fun doLayout() {
        val size = child.preferredSize
        child.setBounds(0, (height - size.height) / 2, size.width, size.height)
    }
}
