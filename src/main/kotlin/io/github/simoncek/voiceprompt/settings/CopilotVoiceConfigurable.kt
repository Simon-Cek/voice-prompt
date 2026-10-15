package io.github.simoncek.voiceprompt.settings

import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import io.github.simoncek.voiceprompt.state.isCapturing
import io.github.simoncek.voiceprompt.audio.MicrophoneTester
import io.github.simoncek.voiceprompt.stt.ModelManager
import io.github.simoncek.voiceprompt.stt.WhisperLanguage
import io.github.simoncek.voiceprompt.stt.WhisperRuntime
import com.intellij.openapi.Disposable
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.RightGap
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingConstants

class CopilotVoiceConfigurable : BoundSearchableConfigurable(
    VoiceBundle["settings.title"],
    "CopilotVoiceSettings",
    "CopilotVoiceSettings",
) {

    private val s = CopilotVoiceSettings.instance
    private val maxThreads = WhisperRuntime.availableThreads()
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
    private var languageCell: Cell<ComboBox<String>>? = null
    private var micComponent: JComponent? = null
    private var testButton: JButton? = null
    private var hintCell: Cell<JBLabel>? = null
    private val micTestEnabled = AtomicBooleanProperty(!DictationStatus.instance.isCapturing)

    // The IntelliJ framework reuses one CopilotVoiceConfigurable across settings sessions —
    // createPanel() runs again after disposeUIResources(). Registering a child on an already-
    // disposed parent throws IncorrectOperationException, so every UI-session resource is
    // (re)created per session and tied to a disposable that is live only for that session.
    private var session: Disposable? = null
    private var tester: MicrophoneTester? = null

    companion object {
        private val SYSTEM_DEFAULT = VoiceBundle["settings.input.default"]
    }

    override fun getPreferredFocusedComponent(): JComponent? = micComponent

    override fun disposeUIResources() {
        val current = session
        session = null
        tester = null
        if (current != null) Disposer.dispose(current)
        super.disposeUIResources()
    }

    /**
     * (Re)establishes the session-scoped resources for a settings page: a fresh disposable that
     * owns the mic tester, the model-row download listeners, and the dictation-state listener.
     * Safe to call again — it disposes any live session first — which is required because the
     * framework reuses this instance and calls createPanel() again after disposeUIResources().
     */
    private fun beginSession() {
        val current = session
        session = null
        tester = null
        if (current != null) Disposer.dispose(current)
        val rowsDisposable = Disposer.newDisposable()
        session = rowsDisposable
        // Keep the Test button in step with the dictation state; removed with the session.
        val dictationListener: (DictationPhase) -> Unit = { phase -> micTestEnabled.set(!phase.isCapturing) }
        DictationStatus.instance.addListener(dictationListener)
        Disposer.register(rowsDisposable) { DictationStatus.instance.removeListener(dictationListener) }
        tester = MicrophoneTester(
            parent = rowsDisposable,
            onLevel = { level ->
                levelMeter.level = level
                if (level > 0.05 && hintCell?.component?.isVisible == true) showHint(null)
            },
            onNoSound = { showHint("No sound detected. Check that the right microphone is selected and not muted.") },
            onError = { showHint(it) },
            onStopped = { testButton?.text = VoiceBundle["settings.mic.test"] },
        )
    }

    override fun createPanel(): DialogPanel {
        beginSession()
        return panel {
            // groupRowsRange, not group(): group() builds its content in a nested panel with an independent grid,
            // giving each section its own label column; groupRowsRange keeps the rows in the one shared grid.
            groupRowsRange(VoiceBundle["settings.dictation"], indent = false) {
                buttonsGroup {
                    row(VoiceBundle["recordingMode.title"]) {
                        radioButton(VoiceBundle["recordingMode.hold"], RecordingMode.HOLD)
                        radioButton(VoiceBundle["recordingMode.tap"], RecordingMode.TAP)
                    }
                }.bind({ s.mode }, { s.mode = it })

                row {
                    label(VoiceBundle["recordingMode.hint"]).applyToComponent { foreground = JBColor.GRAY }
                }

                row {
                    checkBox(VoiceBundle["sound.enabled"])
                        .bindSelected({ s.soundEnabled }, { s.soundEnabled = it })
                }

                row {
                    checkBox(VoiceBundle["settings.auto.send"])
                        .bindSelected({ s.autoSend }, { s.autoSend = it })
                        .comment(VoiceBundle["settings.auto.send.hint"])
                }

                row {
                    checkBox(VoiceBundle["settings.statusbar.idle"])
                        .bindSelected({ s.showIdleIndicator }, { s.showIdleIndicator = it })
                        .comment(VoiceBundle["settings.statusbar.idle.hint"])
                }

                row(VoiceBundle["settings.keepready.title"]) {
                    comboBox(
                        listOf(0, 10, 20, 30, 60),
                        TextCellRenderer<Int> { keepReadyLabel(it) },
                    ).bindItem({ s.keepMicReadySeconds }, { s.keepMicReadySeconds = it ?: 0 })
                        .widthGroup("voiceCombos")
                        .comment(VoiceBundle["settings.keepready.hint"])
                }

                row(VoiceBundle["settings.input.title"]) {
                    // The mic test can't run while a dictation recording is in progress.
                    micTestEnabled.set(!DictationStatus.instance.isCapturing)
                    val mic = comboBox(inputDeviceNames())
                    micComponent = mic.component
                    mic.bindItem(
                        { s.inputDevice ?: SYSTEM_DEFAULT },
                        { name -> s.inputDevice = if (name == SYSTEM_DEFAULT) null else name },
                    ).widthGroup("voiceCombos")
                    mic.component.addItemListener { tester?.stop() }

                    val testBtn = HoverButton(VoiceBundle["settings.mic.test"]).apply {
                        addActionListener { onMicTestClick() }
                    }
                    val testCell = cell(testBtn)
                    testButton = testBtn
                    testCell.enabledIf(micTestEnabled)

                    cell(levelMeter)
                }

                row {
                    val hint = cell(JBLabel().apply {
                        foreground = JBColor.GRAY
                        isVisible = false
                    })
                    hintCell = hint
                }
            }

            groupRowsRange(VoiceBundle["settings.model"], indent = false) {
                row {
                    cell(modelsPanel).align(Align.FILL)
                }

                row(VoiceBundle["language.title"]) {
                    val language = comboBox(
                        WhisperLanguage.values().map { it.code },
                        TextCellRenderer<String> { code ->
                            WhisperLanguage.fromCode(code).displayName
                        },
                    )
                    language.bindItem({ s.language }, { s.language = it ?: s.language })
                        .widthGroup("voiceCombos")
                        .enabledIf(languageEnabled)
                        .comment(languageHint())
                    languageComment = language.comment
                    languageCell = language
                    syncLanguage()
                }
            }

            groupRowsRange(VoiceBundle["settings.performance"], indent = false) {
                row(VoiceBundle["processing.title"]) {
                    comboBox(
                        ProcessingMode.values().toList(),
                        TextCellRenderer<ProcessingMode> { processingLabel(it) },
                    ).bindItem({ s.processingMode }, { s.processingMode = it ?: s.processingMode })
                        .widthGroup("voiceCombos")
                        .comment(VoiceBundle["processing.hint"])
                }

                row(VoiceBundle["threads.title"]) {
                    val max = maxThreads.coerceAtLeast(1)
                    val valueLabel = JBLabel(s.threads.coerceIn(1, max).toString()).apply {
                        horizontalAlignment = SwingConstants.RIGHT
                        val width = getFontMetrics(font).stringWidth(max.toString())
                        preferredSize = Dimension(width, preferredSize.height)
                        minimumSize = preferredSize
                    }
                    cell(AccentSlider(1, max, s.threads.coerceIn(1, max)))
                        .align(AlignY.CENTER)
                        .bindValue({ s.threads.coerceIn(1, max) }, { s.threads = it })
                        .applyToComponent {
                            addChangeListener { valueLabel.text = value.toString() }
                        }
                    // Small gap so the value and its suffix read as one phrase: "4 of 8 available".
                    cell(valueLabel).gap(RightGap.SMALL).align(AlignY.CENTER)
                    label(VoiceBundle.message("threads.of", max))
                        .align(AlignY.CENTER)
                        .applyToComponent { foreground = JBColor.GRAY }
                }.rowComment(VoiceBundle["threads.hint"])
            }

            refreshModels()
        }
    }

    // --- speech models (per-row Use / Download / Delete) ---

    private fun statusOf(option: ModelManager.ModelOption): ModelStatus {
        if (available(option)) {
            return if (option.id == s.modelName) ModelStatus.ACTIVE else ModelStatus.INSTALLED
        }
        return if (option.bundled && ModelManager.bundledModelAvailable()) {
            ModelStatus.BUNDLED
        } else {
            ModelStatus.NOT_DOWNLOADED
        }
    }

    /**
     * A model is available when its file is on disk, or — for the bundled model — when it ships
     * inside the plugin jar and the user hasn't explicitly deleted it. The bundled model is
     * extracted to disk lazily (on first transcription or Restore), so it counts as installed
     * even before that; an explicit deletion is what turns it into the restorable BUNDLED state.
     */
    private fun available(option: ModelManager.ModelOption): Boolean =
        ModelManager.isInstalled(option.id) ||
            (option.bundled && ModelManager.bundledModelAvailable() && !s.bundledModelDeleted)

    private fun useModel(option: ModelManager.ModelOption) {
        if (!available(option)) return
        s.modelName = option.id
        refreshModels()
    }

    private fun refreshModels() {
        rebuildModelRows()
        syncLanguage()
    }

    private fun rebuildModelRows() {
        val rowsDisposable = session ?: return
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
                    usableCount = { options.count { available(it) } },
                    onUse = { useModel(option) },
                    onDelete = { deleteModel(option) },
                    onRestore = { success ->
                        if (success) s.bundledModelDeleted = false
                        refreshModels()
                    },
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
        if (englishOnly) {
            // bindItem only commits the combo to s.language when the dialog is applied, so the combo
            // may be showing an uncommitted selection (e.g. "Portuguese") while s.language is still
            // "en". Treat the combo as the current value and reset both the combo and the state.
            val current = (languageCell?.component?.selectedItem as? String) ?: s.language
            if (current != WhisperLanguage.ENGLISH.code) {
                s.language = WhisperLanguage.ENGLISH.code
                languageCell?.component?.selectedItem = WhisperLanguage.ENGLISH.code
            }
        }
    }

    private fun languageHint(): String =
        if (ModelManager.isEnglishOnly(s.modelName)) VoiceBundle["language.englishOnly.hint"]
        else VoiceBundle["language.multilingual.hint"]

    private fun deleteModel(option: ModelManager.ModelOption) {
        val file = ModelManager.modelFile(option.id)
        // A bundled model can be deleted even before it was extracted to disk: the deletion then
        // just marks it as removed so the row offers to Restore it from the plugin jar.
        if (!Files.isRegularFile(file) && !option.bundled) return
        val message = if (option.bundled) {
            VoiceBundle.message("model.delete.confirm.bundled", option.displayName)
        } else {
            VoiceBundle.message("model.delete.confirm", option.displayName)
        }
        val confirmed = Messages.showYesNoDialog(
            modelsPanel,
            message,
            VoiceBundle["model.delete.title"],
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return
        Files.deleteIfExists(file)
        if (option.bundled) s.bundledModelDeleted = true
        if (s.modelName == option.id) {
            val fallback = ModelManager.AVAILABLE_MODELS
                .firstOrNull { it.id != option.id && available(it) }
                ?.id ?: ModelManager.DEFAULT_MODEL
            s.modelName = fallback
        }
        refreshModels()
    }

    private fun onMicTestClick() {
        val tester = tester ?: return
        if (tester.isRunning) {
            tester.stop()
        } else {
            showHint(null)
            testButton?.text = VoiceBundle["settings.mic.stop"]
            tester.start(s.inputDevice)
        }
    }

    private fun showHint(text: String?) {
        hintCell?.let {
            it.component.text = text ?: ""
            it.visible(text != null)
        }
    }

    private fun processingLabel(mode: ProcessingMode): String = when (mode) {
        ProcessingMode.AUTOMATIC -> VoiceBundle["processing.automatic"]
        ProcessingMode.CPU -> VoiceBundle["processing.cpu"]
        ProcessingMode.GPU -> VoiceBundle["processing.gpu"]
    }

    /** "Off" for 0, else "{n} s". */
    private fun keepReadyLabel(seconds: Int): String =
        if (seconds == 0) VoiceBundle["settings.keepready.off"]
        else VoiceBundle.message("settings.keepready.seconds", seconds)

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
