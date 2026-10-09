package com.example.copilotvoice.settings

import com.example.copilotvoice.VoiceBundle
import com.example.copilotvoice.stt.ModelManager
import com.example.copilotvoice.stt.WhisperLanguage
import com.example.copilotvoice.stt.WhisperRuntime
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSeparator
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class CopilotVoiceConfigurable : Configurable {

    private val autoSendCheckbox = JBCheckBox(VoiceBundle["settings.auto.send"])
    private val holdRadio = JRadioButton(VoiceBundle["recordingMode.hold"])
    private val tapRadio = JRadioButton(VoiceBundle["recordingMode.tap"])
    private val soundCheckbox = JBCheckBox(VoiceBundle["sound.enabled"])
    private val inputDeviceCombo = JComboBox<String>()
    private val availableCombo = JComboBox<String>()
    private val activeCombo = JComboBox<String>()
    private val processingCombo = JComboBox<String>()
    private val processingHint = JLabel("")
    private val languageCombo = JComboBox<String>()
    private val threadsSpinner = JSpinner()
    private val threadsHint = JLabel("")
    private val downloadButton = JButton(VoiceBundle["model.download"])
    private val deleteButton = JButton(VoiceBundle["model.delete"])

    /** Model ids backing the [activeCombo] rows, in the same order; [NO_MODEL] when nothing is installed. */
    private var activeIds: List<String> = emptyList()

    companion object {
        /** Sentinel combo value representing "use the system default microphone". */
        private val SYSTEM_DEFAULT = VoiceBundle["settings.input.default"]
        /** Sentinel id backing the [activeCombo] placeholder row when nothing is installed. */
        private const val NO_MODEL = "__none__"
        private const val NOTIFICATION_GROUP_ID = "CopilotVoice"
    }

    private val panel: JComponent = buildRootPanel()

    init {
        ButtonGroup().apply {
            add(holdRadio)
            add(tapRadio)
        }
        availableCombo.addActionListener { updateModelButtons() }
        downloadButton.addActionListener { downloadSelectedModel() }
        deleteButton.addActionListener { deleteSelectedModel() }
        for (mode in ProcessingMode.values()) processingCombo.addItem(processingLabel(mode))
        for (lang in WhisperLanguage.values()) languageCombo.addItem(lang.displayName)
        val maxThreads = WhisperRuntime.physicalThreads()
        threadsSpinner.model = SpinnerNumberModel(
            maxThreads, 1, maxThreads, 1,
        )
        refreshModels()
    }

    override fun getDisplayName(): String = VoiceBundle["settings.title"]

    override fun createComponent(): JComponent = panel

    override fun isModified(): Boolean {
        val s = CopilotVoiceSettings.instance
        return autoSendCheckbox.isSelected != s.autoSend ||
            selectedMode() != s.mode ||
            soundCheckbox.isSelected != s.soundEnabled ||
            selectedInputDevice() != s.inputDevice ||
            selectedActiveId() != s.modelName ||
            selectedProcessing() != s.processingMode ||
            selectedLanguage() != s.language ||
            selectedThreads() != effectiveThreads(s)
    }

    override fun apply() {
        val s = CopilotVoiceSettings.instance
        s.autoSend = autoSendCheckbox.isSelected
        s.mode = selectedMode()
        s.soundEnabled = soundCheckbox.isSelected
        s.inputDevice = selectedInputDevice()
        selectedActiveId()?.let { s.modelName = it }
        s.processingMode = selectedProcessing()
        s.language = selectedLanguage()
        s.threads = selectedThreads()
    }

    override fun reset() {
        val s = CopilotVoiceSettings.instance
        rebuildInputDevices()
        autoSendCheckbox.isSelected = s.autoSend
        holdRadio.isSelected = s.mode == RecordingMode.HOLD
        tapRadio.isSelected = s.mode == RecordingMode.TAP
        soundCheckbox.isSelected = s.soundEnabled
        inputDeviceCombo.selectedItem = if (s.inputDevice == null) SYSTEM_DEFAULT else s.inputDevice
        processingCombo.selectedItem = processingLabel(s.processingMode)
        languageCombo.selectedIndex = WhisperLanguage.values().indexOf(WhisperLanguage.fromCode(s.language))
        processingHint.text = if (WhisperRuntime.gpuBackendAvailable()) "" else VoiceBundle["processing.gpu.hint"]
        val maxThreads = WhisperRuntime.physicalThreads()
        (threadsSpinner.model as SpinnerNumberModel).setValue(effectiveThreads(s).coerceIn(1, maxThreads))
        threadsHint.text = VoiceBundle.message("threads.hint", maxThreads)
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

    private fun selectedThreads(): Int = (threadsSpinner.value as? Number)?.toInt() ?: 0

    /** The stored threads, or the max physical core count when unset (0). */
    private fun effectiveThreads(s: CopilotVoiceSettings): Int =
        if (s.threads > 0) s.threads else WhisperRuntime.physicalThreads()

    /** The model id backing the currently selected "available" row, or null. */
    private fun selectedAvailableId(): String? {
        val index = availableCombo.selectedIndex
        return if (index in ModelManager.AVAILABLE_MODELS.indices) ModelManager.AVAILABLE_MODELS[index].id else null
    }

    /** The model id backing the currently selected "active" row, or null when none is installed. */
    private fun selectedActiveId(): String? {
        val id = activeIds.getOrNull(activeCombo.selectedIndex) ?: return null
        return if (id == NO_MODEL) null else id
    }

    /** Capture device name, or null when the "system default" sentinel is selected. */
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

    /**
     * Rebuilds both model combos. "Available" lists the whole catalog with install markers;
     * "Active" lists only installed models. [preferActiveId] (e.g. a model just downloaded) is
     * selected as active when installed; otherwise the current active model, then the first
     * installed one.
     */
    private fun refreshModels(preferActiveId: String? = null) {
        val prevAvailableId = selectedAvailableId()
        availableCombo.removeAllItems()
        for (option in ModelManager.AVAILABLE_MODELS) {
            val label = "${option.displayName} (${option.id}, ${option.sizeLabel})"
            availableCombo.addItem(if (ModelManager.isInstalled(option.id)) "$label \u2014 installed" else label)
        }
        val prevIndex = ModelManager.AVAILABLE_MODELS.indexOfFirst { it.id == prevAvailableId }
        availableCombo.selectedIndex = if (prevIndex >= 0) prevIndex else 0

        activeCombo.removeAllItems()
        val installed = ModelManager.AVAILABLE_MODELS.filter { ModelManager.isInstalled(it.id) }
        activeIds = installed.map { it.id }
        for (option in installed) activeCombo.addItem("${option.displayName} (${option.id})")
        if (installed.isEmpty()) {
            activeIds = listOf(NO_MODEL)
            activeCombo.addItem(VoiceBundle["model.active.none"])
        }

        val currentActive = CopilotVoiceSettings.instance.modelName
        val chosen = (preferActiveId ?: currentActive).takeIf { it in activeIds }
            ?: activeIds.firstOrNull { it != NO_MODEL }
        activeCombo.selectedIndex = activeIds.indexOf(chosen).coerceAtLeast(0)
        updateModelButtons()
    }

    /** Enables download/delete for the currently selected available model. */
    private fun updateModelButtons() {
        val id = selectedAvailableId()
        val installed = id != null && ModelManager.isInstalled(id)
        downloadButton.isEnabled = !installed
        deleteButton.isEnabled = installed
    }

    private fun downloadSelectedModel() {
        val id = selectedAvailableId() ?: return
        downloadButton.isEnabled = false
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

    private fun deleteSelectedModel() {
        val id = selectedAvailableId() ?: return
        val file = ModelManager.modelFile(id)
        if (!Files.isRegularFile(file)) return
        val confirmed = Messages.showYesNoDialog(
            panel,
            VoiceBundle.message("model.delete.confirm", id),
            VoiceBundle["model.delete.title"],
            null,
        )
        if (confirmed == Messages.YES) {
            Files.deleteIfExists(file)
            refreshModels()
        }
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

    private fun buildRootPanel(): JComponent {
        val root = JPanel(GridBagLayout())
        root.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        var row = 0
        root.add(section("General", buildGeneralPanel()), sectionCell(row))
        row++
        val separatorCell = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 0, 12, 0)
        }
        root.add(JSeparator(), separatorCell)
        row++
        root.add(section("Model", buildModelPanel()), sectionCell(row))
        row++
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
            .addLabeledComponent(VoiceBundle["settings.input.title"], inputDeviceCombo)
            .panel
    }

    private fun buildModelPanel(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        buttons.add(downloadButton)
        buttons.add(deleteButton)
        val availableRow = JPanel(BorderLayout(8, 0))
        availableRow.add(availableCombo, BorderLayout.CENTER)
        availableRow.add(buttons, BorderLayout.EAST)
        val threadsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        threadsRow.add(threadsSpinner)
        threadsRow.add(threadsHint)
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(VoiceBundle["model.available.title"], availableRow)
            .addLabeledComponent(VoiceBundle["model.active.title"], activeCombo)
            .addLabeledComponent(VoiceBundle["processing.title"], processingCombo)
            .addComponent(processingHint)
            .addLabeledComponent(VoiceBundle["language.title"], languageCombo)
            .addLabeledComponent(VoiceBundle["threads.title"], threadsRow)
            .panel
    }
}
