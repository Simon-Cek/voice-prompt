package com.example.copilotvoice.download

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JProgressBar

class DownloadingActions(onCancel: () -> Unit) : JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)) {

    private val bar = JProgressBar(0, 100).apply {
        val size = Dimension(JBUI.scale(96), JBUI.scale(10))
        preferredSize = size
        minimumSize = size
        isStringPainted = false
        accessibleContext.accessibleName = "Download progress"
    }

    private val cancel = JButton(AllIcons.Actions.Close).apply {
        toolTipText = "Cancel download"
        accessibleContext.accessibleName = "Cancel download"
        // UNSURE: this property makes the button flat in the IDE look and feel.
        putClientProperty("JButton.buttonType", "borderless")
        addActionListener { onCancel() }
    }

    init {
        isOpaque = false
        add(bar)
        add(cancel)
    }

    fun update(percent: Int) {
        // -1 = unknown size
        if (percent < 0) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.value = percent.coerceIn(0, 100)
        }
    }
}
