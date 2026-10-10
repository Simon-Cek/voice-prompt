package com.example.copilotvoice.settings

import com.example.copilotvoice.VoiceBundle
import com.example.copilotvoice.stt.ModelManager
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * One row in the Speech model table: name + note on the left, and on the right
 * the download size, a status badge, and a context action button
 * (Use / Download). Active rows show only the Active badge.
 *
 * @param model the model being described.
 * @param status the row's [ModelStatus].
 * @param onUse invoked when the "Use" button is clicked.
 * @param onDownload invoked when the "Download" button is clicked.
 * @param onDelete invoked when the "Delete" button is clicked.
 */
class ModelRow(
    model: ModelManager.ModelOption,
    status: ModelStatus,
    onUse: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) : JPanel(BorderLayout()) {

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(8, 10),
        )

        val nameHtml = "<html><b>${model.displayName}</b> &nbsp;<font color='#888888'>${model.id} · ${model.note}</font></html>"
        add(JBLabel(nameHtml), BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply { isOpaque = false }
        right.add(JBLabel(model.sizeLabel))
        right.add(StatusBadge(status))

        when (status) {
            ModelStatus.ACTIVE -> { /* active row: no action button */ }
            ModelStatus.INSTALLED -> {
                val use = JButton(VoiceBundle["model.use"])
                use.isFocusable = false
                use.addActionListener { onUse() }
                right.add(use)

                val delete = JButton(VoiceBundle["model.delete"])
                delete.isFocusable = false
                delete.addActionListener { onDelete() }
                right.add(delete)
            }
            ModelStatus.NOT_DOWNLOADED -> {
                val download = JButton(VoiceBundle["model.download"])
                download.isFocusable = false
                download.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                download.addActionListener { onDownload() }
                right.add(download)
            }
        }
        add(right, BorderLayout.EAST)
    }
}
