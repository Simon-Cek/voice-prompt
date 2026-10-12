package io.github.simoncek.voiceprompt.settings

import io.github.simoncek.voiceprompt.stt.ModelManager
import io.github.simoncek.voiceprompt.stt.WhisperLanguage
import io.github.simoncek.voiceprompt.stt.WhisperRuntime
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
        /** Name of the selected capture device, or null for the system default microphone. */
        var inputDevice: String? = null
        /** Selected Whisper model id (e.g. "base.en"). */
        var modelName: String = ModelManager.DEFAULT_MODEL
        /** How to run inference: Automatic / CPU / GPU. */
        var processingMode: ProcessingMode = ProcessingMode.AUTOMATIC
        /** Language for whisper; "auto" = auto-detect (see WhisperLanguage). */
        var language: String = WhisperLanguage.AUTO.code
        /** CPU threads for whisper; default = half the logical processor count (0 = auto, legacy). */
        var threads: Int = WhisperRuntime.defaultThreads()
        /** The user explicitly deleted the bundled model; its row offers to Restore it from the plugin jar. */
        var bundledModelDeleted: Boolean = false
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

    var inputDevice: String?
        get() = state.inputDevice
        set(value) {
            state.inputDevice = value
        }

    var modelName: String
        get() = state.modelName
        set(value) {
            state.modelName = value
        }

    var processingMode: ProcessingMode
        get() = state.processingMode
        set(value) {
            state.processingMode = value
        }

    var language: String
        get() = state.language
        set(value) {
            state.language = value
        }

    var threads: Int
        get() = state.threads
        set(value) {
            state.threads = value
        }

    var bundledModelDeleted: Boolean
        get() = state.bundledModelDeleted
        set(value) {
            state.bundledModelDeleted = value
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
