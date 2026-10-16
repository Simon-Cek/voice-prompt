package io.github.simoncek.voiceprompt.download

import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.stt.ModelManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.APP)
class ModelDownloadService {

    fun interface Listener {
        fun changed(modelId: String)
    }

    private val percents = ConcurrentHashMap<String, Int>()
    private val indicators = ConcurrentHashMap<String, ProgressIndicator>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val names = ConcurrentHashMap<String, String>()

    /** Current percent (0..100) for [modelId], `-1` for unknown size, or `null` when not downloading. */
    fun percent(modelId: String): Int? = percents[modelId]

    fun addListener(parent: Disposable, listener: Listener) {
        listeners += listener
        Disposer.register(parent, Disposable { listeners -= listener })
    }

    fun cancel(modelId: String) {
        indicators[modelId]?.cancel()
    }

    fun start(modelId: String, modelName: String, onDone: (Boolean) -> Unit) {
        if (percents.containsKey(modelId)) return
        percents[modelId] = -1
        names[modelId] = modelName
        notifyChanged(modelId)

        // Tie the task to the active project so it shows in the "Background Tasks" tool window.
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Downloading $modelName model", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicators[modelId] = indicator
                    download(modelId, indicator)
                }

                override fun onSuccess() = finish(modelId, true, null, onDone)
                override fun onCancel() = finish(modelId, false, null, onDone)
                override fun onThrowable(error: Throwable) = finish(modelId, false, error.message ?: error.javaClass.simpleName, onDone)
            },
        )
    }

    private fun finish(modelId: String, success: Boolean, errorMessage: String?, onDone: (Boolean) -> Unit) {
        percents.remove(modelId)
        indicators.remove(modelId)
        onDone(success)
        notifyChanged(modelId)
        finishNotification(modelId, success, errorMessage)
    }

    private fun publish(modelId: String, percent: Int) {
        if (percents.put(modelId, percent) != percent) notifyChanged(modelId)
    }

    private fun notifyChanged(modelId: String) {
        ApplicationManager.getApplication().invokeLater { listeners.forEach { it.changed(modelId) } }
    }

    private fun finishNotification(modelId: String, success: Boolean, errorMessage: String?) {
        val name = names.remove(modelId)
        val content: String
        val type: NotificationType
        when {
            success -> {
                content = VoiceBundle["model.downloaded", name.orEmpty()]
                type = NotificationType.INFORMATION
            }
            errorMessage != null -> {
                content = VoiceBundle["model.download.failed", errorMessage]
                type = NotificationType.ERROR
            }
            else -> return
        }
        val target = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(content, type)
        if (java.awt.EventQueue.isDispatchThread()) {
            notification.notify(target)
        } else {
            ApplicationManager.getApplication().invokeLater { notification.notify(target) }
        }
    }

    // Runs on a background thread. The hardened core (resume, locking, fail-closed verification)
    // lives in ModelManager; the service adds the platform task and the settings-page percent.
    private fun download(modelId: String, indicator: ProgressIndicator) {
        ModelManager.download(modelId, indicator) { percent -> publish(modelId, percent) }
    }

    private companion object {
        private const val GROUP_ID = "CopilotVoice"
    }
}
