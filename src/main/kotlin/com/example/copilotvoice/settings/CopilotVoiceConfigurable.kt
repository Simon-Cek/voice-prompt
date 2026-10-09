package com.example.copilotvoice.settings

import com.example.copilotvoice.VoiceBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

class CopilotVoiceConfigurable : Configurable {

    private val autoSendCheckbox = JBCheckBox(VoiceBundle["settings.auto.send"])
    private val holdRadio = JRadioButton(VoiceBundle["recordingMode.hold"])
    private val tapRadio = JRadioButton(VoiceBundle["recordingMode.tap"])
    private val soundCheckbox = JBCheckBox(VoiceBundle["sound.enabled"])

    private val panel: JComponent = buildRootPanel()

    init {
        ButtonGroup().apply {
            add(holdRadio)
            add(tapRadio)
        }
    }

    override fun getDisplayName(): String = VoiceBundle["settings.title"]

    override fun createComponent(): JComponent = panel

    override fun isModified(): Boolean {
        val s = CopilotVoiceSettings.instance
        return autoSendCheckbox.isSelected != s.autoSend ||
            selectedMode() != s.mode ||
            soundCheckbox.isSelected != s.soundEnabled
    }

    override fun apply() {
        val s = CopilotVoiceSettings.instance
        s.autoSend = autoSendCheckbox.isSelected
        s.mode = selectedMode()
        s.soundEnabled = soundCheckbox.isSelected
    }

    override fun reset() {
        val s = CopilotVoiceSettings.instance
        autoSendCheckbox.isSelected = s.autoSend
        holdRadio.isSelected = s.mode == RecordingMode.HOLD
        tapRadio.isSelected = s.mode == RecordingMode.TAP
        soundCheckbox.isSelected = s.soundEnabled
    }

    private fun selectedMode(): RecordingMode =
        if (tapRadio.isSelected) RecordingMode.TAP else RecordingMode.HOLD

    private fun buildRootPanel(): JComponent {
        val root = JPanel(GridBagLayout())
        root.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        var row = 0
        root.add(section("General", buildGeneralPanel()), sectionCell(row))
        row++
        // Step 2: root.add(section("Model", buildModelPanel()), sectionCell(row)); row++
        // Absorb leftover vertical space so the sections stay pinned to the top instead of centering.
        val filler = JPanel().apply { preferredSize = Dimension(0, 0) }
        val fillerCell = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            weighty = 1.0
            fill = GridBagConstraints.VERTICAL
        }
        root.add(filler, fillerCell)
        return root
    }

    private fun sectionCell(row: Int) = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(0, 0, 12, 0)
    }

    private fun section(title: String, content: JComponent): JComponent {
        val panel = JPanel(BorderLayout())
        val border = BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8), title)
        border.titleFont = border.titleFont.deriveFont(border.titleFont.size2D * 1.5f)
        panel.border = border
        panel.add(content, BorderLayout.CENTER)
        return panel
    }

    private fun buildGeneralPanel(): JComponent {
        val modePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        modePanel.add(holdRadio)
        modePanel.add(tapRadio)
        return FormBuilder.createFormBuilder()
            .addComponent(autoSendCheckbox)
            .addComponent(soundCheckbox)
            .addLabeledComponent(VoiceBundle["recordingMode.title"], modePanel)
            .panel
    }
}
