package com.example.copilotvoice.settings

import com.example.copilotvoice.VoiceBundle
import com.example.copilotvoice.audio.AudioCapture
import com.example.copilotvoice.stt.ModelManager
import com.example.copilotvoice.stt.WhisperLanguage
import com.example.copilotvoice.stt.WhisperRuntime
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSlider

class CopilotVoiceConfigurable : BoundConfigurable(VoiceBundle["settings.title"]) {

    // --- controls (created once; arranged in [createPanel]) ---
    private val autoSendCheckbox = JCheckBox(VoiceBundle["settings.auto.send"])
    private val holdRadio = JRadioButton(VoiceBundle["recordingMode.hold"])
    private val tapRadio = JRadioButton(VoiceBundle["recordingMode.tap"])
    private val soundCheckbox = JCheckBox(VoiceBundle["sound.enabled"])
    private val inputDeviceCombo = JComboBox<String>()
    private val processingCombo = JComboBox<String>()
    private val languageCombo = JComboBox<String>()
    private val processingHint = JLabel("")
    private var languageHint: JEditorPane? = null
    private val threadsLabel = JLabel("")
    private val testButton = JButton(VoiceBundle["settings.mic.test"]).apply { isFocusable = false }
    private val modelsPanel = JPanel(GridBagLayout())
    private val maxThreads = WhisperRuntime.physicalThreads()
    private val threadsSlider = JSlider(1, maxThreads, maxThreads)

    /** The model id the user intends to make active (pending until Apply). */
    private var pendingActiveId: String? = null

    companion object {
        private val SYSTEM_DEFAULT = VoiceBundle["settings.input.default"]
        private const val NOTIFICATION_GROUP_ID = "CopilotVoice"
    }

    init {
        ButtonGroup().apply {
            add(holdRadio)
            add(tapRadio)
        }
        testButton.addActionListener { testMic() }
        for (mode in ProcessingMode.values()) processingCombo.addItem(processingLabel(mode))
        for (lang in WhisperLanguage.values()) languageCombo.addItem(lang.displayName)
        threadsSlider.addChangeListener { updateThreadsLabel() }

        val comboWidth = 250
        inputDeviceCombo.preferredSize = Dimension(comboWidth, inputDeviceCombo.preferredSize.height)
        processingCombo.preferredSize = Dimension(comboWidth, processingCombo.preferredSize.height)
        languageCombo.preferredSize = Dimension(comboWidth, languageCombo.preferredSize.height)
    }

    override fun getPreferredFocusedComponent(): JComponent = inputDeviceCombo

    override fun createPanel(): DialogPanel = panel {
        group(VoiceBundle["settings.dictation"]) {
            row(VoiceBundle["recordingMode.title"]) {
                cell(
                    JPanel(FlowLayout(FlowLayout.LEFT, 16, 0)).apply {
                        isOpaque = false
                        add(holdRadio)
                        add(tapRadio)
                    },
                )
            }
            row {
                cell(soundCheckbox)
            }
            row {
                cell(autoSendCheckbox)
                rowComment(VoiceBundle["settings.auto.send.hint"])
            }
            row(VoiceBundle["settings.input.title"]) {
                cell(inputDeviceCombo)
                cell(testButton)
                cell(
                    LevelMeter().apply {
                        val w = 120
                        preferredSize = Dimension(w, preferredSize.height)
                        maximumSize = Dimension(w, Integer.MAX_VALUE)
                    },
                )
            }
        }
        group(VoiceBundle["settings.model"]) {
            row {
                cell(modelsPanel).align(Align.FILL)
            }
            row(VoiceBundle["language.title"]) {
                val comboCell = cell(languageCombo)
                comboCell.comment(VoiceBundle["language.englishOnly.hint"])
                languageHint = comboCell.comment
            }
        }
        group(VoiceBundle["settings.performance"]) {
            row(VoiceBundle["processing.title"]) {
                cell(processingCombo)
            }
            row {
                cell(processingHint)
            }
            row(VoiceBundle["threads.title"]) {
                cell(threadsSlider).align(Align.FILL)
                cell(threadsLabel)
            }
        }
    }

    override fun isModified(): Boolean {
        val s = CopilotVoiceSettings.instance
        return autoSendCheckbox.isSelected != s.autoSend ||
            selectedMode() != s.mode ||
            soundCheckbox.isSelected != s.soundEnabled ||
            selectedInputDevice() != s.inputDevice ||
            pendingActiveId != s.modelName ||
            selectedProcessing() != s.processingMode ||
            selectedLanguage() != s.language ||
            threadsSlider.value != effectiveThreads(s)
    }

    override fun apply() {
        val s = CopilotVoiceSettings.instance
        s.autoSend = autoSendCheckbox.isSelected
        s.mode = selectedMode()
        s.soundEnabled = soundCheckbox.isSelected
        s.inputDevice = selectedInputDevice()
        s.modelName = pendingActiveId ?: ModelManager.DEFAULT_MODEL
        s.processingMode = selectedProcessing()
        s.language = selectedLanguage()
        s.threads = threadsSlider.value
    }

    override fun reset() {
        val s = CopilotVoiceSettings.instance
        rebuildInputDevices()
        autoSendCheckbox.isSelected = s.autoSend
        holdRadio.isSelected = s.mode == RecordingMode.HOLD
        tapRadio.isSelected = s.mode == RecordingMode.TAP
        soundCheckbox.isSelected = s.soundEnabled
        inputDeviceCombo.selectedItem = s.inputDevice ?: SYSTEM_DEFAULT
        processingCombo.selectedIndex = ProcessingMode.values().indexOf(s.processingMode)
        languageCombo.selectedIndex = WhisperLanguage.values().indexOf(WhisperLanguage.fromCode(s.language))
        processingHint.text = if (WhisperRuntime.gpuBackendAvailable()) "" else VoiceBundle["processing.gpu.hint"]
        threadsSlider.value = effectiveThreads(s)
        updateThreadsLabel()
        pendingActiveId = s.modelName
        refreshModels()
    }

    private fun selectedMode(): RecordingMode =
        if (tapRadio.isSelected) RecordingMode.TAP else RecordingMode.HOLD

    private fun processingLabel(mode: ProcessingMode): String = when (mode) {
        ProcessingMode.AUTOMATIC -> VoiceBundle["processing.automatic"]
        ProcessingMode.CPU -> VoiceBundle["processing.cpu"]
        ProcessingMode.GPU -> VoiceBundle["processing.gpu"]
    }

    private fun selectedProcessing(): ProcessingMode {
        val idx = processingCombo.selectedIndex
        return ProcessingMode.values().getOrElse(idx) { ProcessingMode.AUTOMATIC }
    }

    private fun selectedLanguage(): String {
        val idx = languageCombo.selectedIndex
        return WhisperLanguage.values().getOrElse(idx) { WhisperLanguage.AUTO }.code
    }

    private fun updateThreadsLabel() {
        threadsLabel.text = "${threadsSlider.value} ($maxThreads)"
    }

    private fun effectiveThreads(s: CopilotVoiceSettings): Int =
        if (s.threads > 0) s.threads else WhisperRuntime.physicalThreads()

    /** The stored device name, or null when the "system default" sentinel is selected. */
    private fun selectedInputDevice(): String? {
        val sel = inputDeviceCombo.selectedItem as? String
        return if (sel == SYSTEM_DEFAULT) null else sel
    }

    /** Repopulate the combo: "system default" first, then every capture (input) mixer. */
    private fun rebuildInputDevices() {
        inputDeviceCombo.removeAllItems()
        inputDeviceCombo.addItem(SYSTEM_DEFAULT)
        for (info in AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).getTargetLineInfo().isNotEmpty()) {
                inputDeviceCombo.addItem(info.name)
            }
        }
        if (inputDeviceCombo.selectedItem == null) {
            inputDeviceCombo.selectedItem = SYSTEM_DEFAULT
        }
    }

    // --- speech models (per-row Use / Download / Delete) ---

    private fun statusOf(option: ModelManager.ModelOption): ModelStatus {
        if (!ModelManager.isInstalled(option.id)) return ModelStatus.NOT_DOWNLOADED
        return if (option.id == pendingActiveId) ModelStatus.ACTIVE else ModelStatus.INSTALLED
    }

    private fun useModel(id: String) {
        if (!ModelManager.isInstalled(id)) return
        pendingActiveId = id
        refreshModels()
    }

    private fun refreshModels(preferActiveId: String? = null) {
        preferActiveId?.let { pendingActiveId = it }
        rebuildModelRows()
        updateLanguageForModel()
    }

    private fun rebuildModelRows() {
        modelsPanel.removeAll()
        var y = 0
        for (option in ModelManager.AVAILABLE_MODELS) {
            val constraints = GridBagConstraints().apply {
                gridx = 0
                gridy = y
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            }
            modelsPanel.add(
                ModelRow(
                    option,
                    statusOf(option),
                    onUse = { useModel(option.id) },
                    onDownload = { downloadModel(option.id) },
                    onDelete = { deleteModel(option.id) },
                ),
                constraints,
            )
            y++
        }
        modelsPanel.revalidate()
        modelsPanel.repaint()
    }

    /**
     * Locks the language picker to English while the active model is English-only (whisper `.en`),
     * and unlocks it when a multilingual model is active, when the spoken language can be chosen.
     */
    private fun updateLanguageForModel() {
        val active = pendingActiveId
        val englishOnly = active == null || ModelManager.isEnglishOnly(active)
        languageCombo.isEnabled = !englishOnly
        languageHint?.text = if (englishOnly) VoiceBundle["language.englishOnly.hint"] else VoiceBundle["language.multilingual.hint"]
        if (englishOnly) {
            languageCombo.selectedIndex = WhisperLanguage.values().indexOf(WhisperLanguage.ENGLISH)
        }
    }

    private fun downloadModel(id: String) {
        val task = object : Task.Backgroundable(
            null,
            VoiceBundle.message("model.downloading", id),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    ModelManager.download(id, indicator)
                    ApplicationManager.getApplication().invokeLater {
                        refreshModels(preferActiveId = id)
                        notifyApp(VoiceBundle.message("model.downloaded", id), NotificationType.INFORMATION)
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        refreshModels()
                        if (!indicator.isCanceled) {
                            notifyApp(
                                VoiceBundle.message("model.download.failed", t.message ?: t.javaClass.simpleName),
                                NotificationType.ERROR,
                            )
                        }
                    }
                }
            }
        }
        task.queue()
    }

    private fun deleteModel(id: String) {
        val file = ModelManager.modelFile(id)
        if (!Files.isRegularFile(file)) return
        val confirmed = Messages.showYesNoDialog(
            modelsPanel,
            VoiceBundle.message("model.delete.confirm", id),
            VoiceBundle["model.delete.title"],
            null,
        )
        if (confirmed == Messages.YES) {
            Files.deleteIfExists(file)
            if (pendingActiveId == id) pendingActiveId = null
            refreshModels()
        }
    }

    /** Captures ~500 ms from the selected input and reports the peak level as a sanity check. */
    private fun testMic() {
        val deviceName = selectedInputDevice()
        val task = object : Task.Backgroundable(null, VoiceBundle["settings.mic.test"], true) {
            override fun run(indicator: ProgressIndicator) {
                val capture = AudioCapture()
                try {
                    capture.start(deviceName)
                    Thread.sleep(500)
                    val samples = capture.stop()
                    val peak = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                    val level = (peak * 100 / Short.MAX_VALUE).toInt()
                    ApplicationManager.getApplication().invokeLater {
                        notifyApp(VoiceBundle.message("mic.test.ok", level), NotificationType.INFORMATION)
                    }
                } catch (t: Throwable) {
                    runCatching { capture.stop() }
                    ApplicationManager.getApplication().invokeLater {
                        notifyApp(
                            VoiceBundle.message("mic.test.failed", t.message ?: t.javaClass.simpleName),
                            NotificationType.ERROR,
                        )
                    }
                }
            }
        }
        task.queue()
    }

    private fun notifyApp(content: String, type: NotificationType) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
        val notification = group.createNotification(content, type)
        if (java.awt.EventQueue.isDispatchThread()) {
            notification.notify(project)
        } else {
            ApplicationManager.getApplication().invokeLater { notification.notify(project) }
        }
    }
}
