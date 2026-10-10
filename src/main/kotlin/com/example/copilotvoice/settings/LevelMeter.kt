package com.example.copilotvoice.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import javax.swing.BorderFactory
import javax.swing.JProgressBar
import javax.swing.JPanel

import java.awt.BorderLayout

/**
 * A thin horizontal bar showing the live microphone input level (0–100).
 *
 * The panel is laid out immediately; the actual audio capture that drives
 * [update] is deferred (it requires a live `AudioFormat` on the EDT). Until
 * then the meter simply rests at 0, which is the correct "not recording" state.
 *
 * @property updateLevel called with a new 0–100 level when audio is being measured.
 */
class LevelMeter : JPanel(BorderLayout(0, 8)) {
    private val levelBar = JProgressBar(0, 100).apply {
        isOpaque = true
        isStringPainted = false
        foreground = JBColor(0x1a7f37, 0x3fb950)
        background = JBColor(0xeaeef2, 0x21262d)
        border = BorderFactory.createEmptyBorder(3, 0, 3, 0)
    }

    init {
        isOpaque = false
        add(levelBar, BorderLayout.CENTER)
        border = JBUI.Borders.empty(2, 0)
    }

    /** Set the meter level (0–100). No-op while not recording. */
    fun updateLevel(level: Int) {
        levelBar.value = level.coerceIn(0, 100)
    }
}
