package com.example.copilotvoice.settings

import com.example.copilotvoice.VoiceBundle
import com.example.copilotvoice.audio.AudioCapture
import com.example.copilotvoice.stt.ModelManager
import com.example.copilotvoice.stt.WhisperLanguage
import com.example.copilotvoice.stt.WhisperRuntime
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.panel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

class CopilotVoiceConfigurable : BoundSearchableConfigurable(
    VoiceBundle["settings.title"],
    "CopilotVoiceSettings",
    "CopilotVoiceSettings",
) {

    private val s = CopilotVoiceSettings.instance
    private val maxThreads = WhisperRuntime.physicalThreads()
    private val modelsPanel = JPanel(GridBagLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.compound(
        JBUI.Borders.emptyTop(JBUI.scale(8)),
        RoundedLineBorder(JBColor.border(), 6),
    )
}
    private val levelMeter = LevelMeter()
    private val languageEnabled = AtomicBooleanProperty(!ModelManager.isEnglishOnly(s.modelName))
    private var languageComment: JEditorPane? = null
    private var micComponent: JComponent? = null
    private val rowsDisposable: Disposable = Disposer.newDisposable()

    companion object {
        private val SYSTEM_DEFAULT = VoiceBundle["settings.input.default"]
        private const val NOTIFICATION_GROUP_ID = "CopilotVoice"
    }

    override fun getPreferredFocusedComponent(): JComponent? = micComponent

    override fun disposeUIResources() {
        Disposer.dispose(rowsDisposable)
        super.disposeUIResources()
    }

    override fun createPanel(): DialogPanel = panel {
        group(VoiceBundle["settings.dictation"], indent = false) {
            buttonsGroup {
                row(VoiceBundle["recordingMode.title"]) {
                    radioButton(VoiceBundle["recordingMode.hold"], RecordingMode.HOLD)
                    radioButton(VoiceBundle["recordingMode.tap"], RecordingMode.TAP)
                }
            }.bind({ s.mode }, { s.mode = it })

            row {
                checkBox(VoiceBundle["sound.enabled"])
                    .bindSelected({ s.soundEnabled }, { s.soundEnabled = it })
            }

            row {
                checkBox(VoiceBundle["settings.auto.send"])
                    .bindSelected({ s.autoSend }, { s.autoSend = it })
                    .comment(VoiceBundle["settings.auto.send.hint"])
            }

            row(VoiceBundle["settings.input.title"]) {
                val mic = comboBox(inputDeviceNames())
                micComponent = mic.component
                mic.bindItem(
                    { s.inputDevice ?: SYSTEM_DEFAULT },
                    { name -> s.inputDevice = if (name == SYSTEM_DEFAULT) null else name },
                ).widthGroup("voiceCombos")

                button(VoiceBundle["settings.mic.test"]) { testMic() }

                cell(levelMeter)
            }
        }

        group(VoiceBundle["settings.model"], indent = false) {
            row {
                cell(modelsPanel).align(Align.FILL)
            }

            row(VoiceBundle["language.title"]) {
                val language = comboBox(
                    WhisperLanguage.values().map { it.code },
                    SimpleListCellRenderer.create<String>("") { code ->
                        WhisperLanguage.fromCode(code).displayName
                    },
                )
                language.bindItem({ s.language }, { s.language = it ?: s.language })
                    .widthGroup("voiceCombos")
                    .enabledIf(languageEnabled)
                    .comment(languageHint())
                languageComment = language.comment
                syncLanguage()
            }
        }

        group(VoiceBundle["settings.performance"], indent = false) {
            row(VoiceBundle["processing.title"]) {
                comboBox(
                    ProcessingMode.values().toList(),
                    SimpleListCellRenderer.create<ProcessingMode>("") { processingLabel(it) },
                ).bindItem({ s.processingMode }, { s.processingMode = it ?: s.processingMode })
                    .widthGroup("voiceCombos")
                    .comment(VoiceBundle["processing.hint"])
            }

            row(VoiceBundle["threads.title"]) {
                val max = maxThreads.coerceAtLeast(1)
                val info = JBLabel(VoiceBundle.message("threads.status", s.threads.coerceIn(1, max), max))
                    .apply { foreground = JBColor.GRAY }
                slider(1, max, 0, 0)
                    .bindValue({ s.threads.coerceIn(1, max) }, { s.threads = it })
                    .applyToComponent {
                        addChangeListener { info.text = VoiceBundle.message("threads.status", value, max) }
                    }
                cell(info)
            }.rowComment(VoiceBundle["threads.hint"])
        }

        refreshModels()
    }

    // --- speech models (per-row Use / Download / Delete) ---

    private fun statusOf(option: ModelManager.ModelOption): ModelStatus {
        if (!ModelManager.isInstalled(option.id)) return ModelStatus.NOT_DOWNLOADED
        return if (option.id == s.modelName) ModelStatus.ACTIVE else ModelStatus.INSTALLED
    }

    private fun useModel(id: String) {
        if (!ModelManager.isInstalled(id)) return
        s.modelName = id
        refreshModels()
    }

    private fun refreshModels() {
        rebuildModelRows()
        syncLanguage()
    }

    private fun rebuildModelRows() {
        modelsPanel.removeAll()
        val options = ModelManager.AVAILABLE_MODELS
        options.forEachIndexed { index, option ->
            val constraints = GridBagConstraints().apply {
                gridx = 0
                gridy = index
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            }
            modelsPanel.add(
                ModelRow(
                    option,
                    statusOf(option),
                    isLast = index == options.lastIndex,
                    installedCount = { options.count { ModelManager.isInstalled(it.id) } },
                    onUse = { useModel(option.id) },
                    onDelete = { deleteModel(option.id) },
                    parentDisposable = rowsDisposable,
                ),
                constraints,
            )
        }
        modelsPanel.revalidate()
        modelsPanel.repaint()
    }

    /**
     * Locks the language picker to English while the active model is English-only (whisper `.en`),
     * and unlocks it when a multilingual model is active, when the spoken language can be chosen.
     */
    private fun syncLanguage() {
        val englishOnly = ModelManager.isEnglishOnly(s.modelName)
        languageEnabled.set(!englishOnly)
        languageComment?.text = languageHint()
        if (englishOnly && s.language != WhisperLanguage.ENGLISH.code) {
            s.language = WhisperLanguage.ENGLISH.code
        }
    }

    private fun languageHint(): String =
        if (ModelManager.isEnglishOnly(s.modelName)) VoiceBundle["language.englishOnly.hint"]
        else VoiceBundle["language.multilingual.hint"]

    private fun deleteModel(id: String) {
        val file = ModelManager.modelFile(id)
        if (!Files.isRegularFile(file)) return
        val confirmed = Messages.showYesNoDialog(
            modelsPanel,
            VoiceBundle.message("model.delete.confirm", id),
            VoiceBundle["model.delete.title"],
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return
        Files.deleteIfExists(file)
        if (s.modelName == id) {
            val fallback = ModelManager.AVAILABLE_MODELS
                .firstOrNull { ModelManager.isInstalled(it.id) }
                ?.id ?: ModelManager.DEFAULT_MODEL
            s.modelName = fallback
        }
        refreshModels()
    }

    /** Captures ~500 ms from the selected input and reports the peak level as a sanity check. */
    private fun testMic() {
        val deviceName = s.inputDevice
        val task = object : Task.Backgroundable(null, VoiceBundle["settings.mic.test"], true) {
            override fun run(indicator: ProgressIndicator) {
                val capture = AudioCapture()
                try {
                    capture.start(deviceName)
                    Thread.sleep(500)
                    val samples = capture.stop()
                    val peak = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                    val level = peak * 100 / Short.MAX_VALUE
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

    private fun processingLabel(mode: ProcessingMode): String = when (mode) {
        ProcessingMode.AUTOMATIC -> VoiceBundle["processing.automatic"]
        ProcessingMode.CPU -> VoiceBundle["processing.cpu"]
        ProcessingMode.GPU -> VoiceBundle["processing.gpu"]
    }

    /** "System default" first, then every capture (input) mixer. */
    private fun inputDeviceNames(): List<String> = buildList {
        add(SYSTEM_DEFAULT)
        for (info in AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).getTargetLineInfo().isNotEmpty()) {
                add(info.name)
            }
        }
    }
}
