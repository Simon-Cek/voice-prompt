package io.github.simoncek.voiceprompt.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import io.github.simoncek.voiceprompt.VoiceBundle

/**
 * Registers [RecordingStatusWidget] on the status bar. The platform instantiates the factory
 * per project window, so every open project gets its own widget instance — all of them views of
 * the same application-level [io.github.simoncek.voiceprompt.state.DictationStatus], which is
 * what keeps every window in step with the recorder.
 *
 * Enabled by default and toggleable from the status bar widget settings, like the platform's
 * own widgets (`isEnabledByDefault` and `isConfigurable` are true by default on the interface).
 */
class RecordingStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = RecordingStatusWidget.WIDGET_ID

    override fun getDisplayName(): String = VoiceBundle["statusbar.displayName"]

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = RecordingStatusWidget(project)
}
