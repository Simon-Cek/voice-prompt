package io.github.simoncek.voiceprompt.download

import io.github.simoncek.voiceprompt.VoiceBundle
import io.github.simoncek.voiceprompt.stt.ModelManager
import io.github.simoncek.voiceprompt.stt.WhisperRuntime
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    fun start(modelId: String, modelName: String, url: String, onDone: (Boolean) -> Unit) {
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
                    download(modelId, url, indicator)
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

    // Runs on a background thread.
    private fun download(modelId: String, url: String, indicator: ProgressIndicator) {
        val target = ModelManager.modelFile(modelId)
        Files.createDirectories(target.parent)
        // A unique per-process part file: two IDEs of the same version downloading the same
        // model must not truncate each other's in-progress download (a shared fixed name would).
        val part = Files.createTempFile(target.parent, target.fileName.toString(), ".part")
        try {
            var expectedSize = -1L
            HttpRequests.request(url).connect { request ->
                val total = request.connection.contentLengthLong
                expectedSize = total
                indicator.isIndeterminate = total <= 0
                request.inputStream.use { input ->
                    Files.newOutputStream(part).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            indicator.checkCanceled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                indicator.fraction = read.toDouble() / total
                                publish(modelId, (read * 100 / total).toInt())
                            }
                        }
                    }
                }
            }
            // Verify integrity before moving the file into place: a truncated or corrupted model
            // would otherwise fail later, during transcription, with no hint of why.
            verifyDownload(part, modelId, expectedSize)
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: ProcessCanceledException) {
            Files.deleteIfExists(part)
            throw t
        } catch (t: Throwable) {
            Files.deleteIfExists(part)
            LOG.warn("Model download failed for $modelId", t)
            throw t
        }
    }

    private fun verifyDownload(part: Path, modelId: String, expectedSize: Long) {
        val actualSize = Files.size(part)
        if (expectedSize > 0 && actualSize != expectedSize) {
            throw IOException("Model $modelId is $actualSize bytes, but the server reported $expectedSize")
        }
        val expected = ModelManager.expectedSha256(modelId)
        if (expected == null) {
            LOG.warn("Published checksum for model $modelId unavailable; verified size only")
            return
        }
        val actual = WhisperRuntime.sha256(part)
        if (actual != expected) {
            throw IOException("Checksum mismatch for model $modelId: expected $expected, got $actual")
        }
        LOG.info("Model $modelId verified: size=$actualSize sha256=$actual")
    }

    private companion object {
        private const val GROUP_ID = "CopilotVoice"
        private val LOG = Logger.getInstance(ModelDownloadService::class.java)
    }
}
