package com.example.copilotvoice.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "CopilotVoiceSettings", storages = [Storage("copilot-voice.xml")])
class CopilotVoiceSettings : PersistentStateComponent<CopilotVoiceSettings.State> {

    class State {
        var autoSend: Boolean = false
        var mode: RecordingMode = RecordingMode.HOLD
        var soundEnabled: Boolean = true
    }

    private val state = State()

    var autoSend: Boolean
        get() = state.autoSend
        set(value) {
            state.autoSend = value
        }

    var mode: RecordingMode
        get() = state.mode
        set(value) {
            state.mode = value
        }

    var soundEnabled: Boolean
        get() = state.soundEnabled
        set(value) {
            state.soundEnabled = value
        }

    override fun getState(): State = state

    override fun loadState(newState: State) {
        XmlSerializerUtil.copyBean(newState, this.state)
    }

    companion object {
        val instance: CopilotVoiceSettings
            get() = ApplicationManager.getApplication().getService(CopilotVoiceSettings::class.java)
    }
}
