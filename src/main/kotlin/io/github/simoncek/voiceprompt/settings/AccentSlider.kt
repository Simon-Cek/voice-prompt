package io.github.simoncek.voiceprompt.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JSlider
import javax.swing.plaf.basic.BasicSliderUI

/**
 * A horizontal slider with a thin rounded track that fills blue up to the thumb,
 * and a round light-grey thumb. No ticks, no labels.
 *
 * The IDE's default slider look (BasicSliderUI) draws a plain grey track with no
 * blue fill and offers no setting to change that, so this subclasses [BasicSliderUI]
 * and overrides [BasicSliderUI.paintTrack] / [BasicSliderUI.paintThumb] only.
 * Keyboard and mouse behavior come from [BasicSliderUI] unchanged.
 */
class AccentSlider(min: Int, max: Int, value: Int) : JSlider(min, max, value) {
    init {
        isOpaque = false
        paintTicks = false
        paintLabels = false
    }

    // The IDE re-creates the UI delegate on every theme change by calling updateUI().
    // Overriding it keeps our look in both Light and Dark themes.
    override fun updateUI() {
        setUI(AccentSliderUI(this))
    }

    // Align the slider's baseline with adjacent text rows, so the DSL grid's baseline
    // alignment lines the slider up with the row label and the value label.
    override fun getBaseline(width: Int, height: Int): Int {
        val fm = getFontMetrics(font)
        return (height - fm.height) / 2 + fm.ascent
    }

    override fun getBaselineResizeBehavior(): Component.BaselineResizeBehavior =
        Component.BaselineResizeBehavior.CENTER_OFFSET
}

private class AccentSliderUI(slider: JSlider) : BasicSliderUI(slider) {
    private val trackHeight get() = JBUI.scale(4)
    private val thumbDiameter get() = JBUI.scale(14)

    // (light theme, dark theme) pairs
    private val trackColor = JBColor(0xD3D5DB, 0x4E5157)
    private val fillColor = JBColor(0x3574F0, 0x3574F0)
    private val thumbColor = JBColor(0xE8EAED, 0xB0B3B8)
    private val disabledColor = JBColor(0xB0B3B8, 0x6F737A)

    override fun getThumbSize(): Dimension = Dimension(thumbDiameter, thumbDiameter)

    override fun paintTrack(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Swing insets the track by half a thumb width on each side; draw across the full
            // component width so the track starts at the same x as the cells above it.
            val x = trackRect.x - thumbDiameter / 2
            val w = trackRect.width + thumbDiameter
            val y = trackRect.y + (trackRect.height - trackHeight) / 2

            g2.color = trackColor
            g2.fillRoundRect(x, y, w, trackHeight, trackHeight, trackHeight)

            val filled = thumbRect.x + thumbRect.width / 2 - x      // blue part, up to the thumb centre
            if (filled > 0) {
                g2.color = if (slider.isEnabled) fillColor else disabledColor
                g2.fillRoundRect(x, y, filled, trackHeight, trackHeight, trackHeight)
            }
        } finally {
            g2.dispose()
        }
    }

    override fun paintThumb(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (slider.isEnabled) thumbColor else disabledColor
            g2.fillOval(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height)
        } finally {
            g2.dispose()
        }
    }

    override fun paintFocus(g: Graphics) {} // no dotted focus rectangle
}
