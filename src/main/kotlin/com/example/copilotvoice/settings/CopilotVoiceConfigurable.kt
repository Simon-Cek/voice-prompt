package com.example.copilotvoice.settings

import com.example.copilotvoice.VoiceBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import javax.swing.JComponent

class CopilotVoiceConfigurable : Configurable {

    private val autoSendCheckbox = JBCheckBox(VoiceBundle["settings.auto.send"])

    override fun getDisplayName(): String = VoiceBundle["settings.title"]

    override fun createComponent(): JComponent = autoSendCheckbox

    override fun isModified(): Boolean = autoSendCheckbox.isSelected != CopilotVoiceSettings.instance.autoSend

    override fun apply() {
        CopilotVoiceSettings.instance.autoSend = autoSendCheckbox.isSelected
    }

    override fun reset() {
        autoSendCheckbox.isSelected = CopilotVoiceSettings.instance.autoSend
    }
}
