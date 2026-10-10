package com.example.copilotvoice.download

import com.example.copilotvoice.stt.ModelManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.Disposer
import com.intellij.util.io.HttpRequests
import java.nio.file.Files
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
        notifyChanged(modelId)

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(null, "Downloading $modelName model", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicators[modelId] = indicator
                    download(modelId, url, indicator)
                }

                override fun onSuccess() = finish(modelId, true, onDone)
                override fun onCancel() = finish(modelId, false, onDone)
                override fun onThrowable(error: Throwable) = finish(modelId, false, onDone)
            },
        )
    }

    private fun finish(modelId: String, success: Boolean, onDone: (Boolean) -> Unit) {
        percents.remove(modelId)
        indicators.remove(modelId)
        onDone(success)
        notifyChanged(modelId)
    }

    private fun publish(modelId: String, percent: Int) {
        if (percents.put(modelId, percent) != percent) notifyChanged(modelId)
    }

    private fun notifyChanged(modelId: String) {
        ApplicationManager.getApplication().invokeLater { listeners.forEach { it.changed(modelId) } }
    }

    // Runs on a background thread.
    private fun download(modelId: String, url: String, indicator: ProgressIndicator) {
        val target = ModelManager.modelFile(modelId)
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        Files.createDirectories(target.parent)
        try {
            HttpRequests.request(url).connect { request ->
                val total = request.connection.contentLengthLong
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
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            // TODO: size/checksum verification of the downloaded file.
        } catch (t: ProcessCanceledException) {
            Files.deleteIfExists(part)
            throw t
        } catch (t: Throwable) {
            Files.deleteIfExists(part)
            LOG.warn("Model download failed for $modelId", t)
            // TODO: failure notification to the user (currently only logged).
            throw t
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(ModelDownloadService::class.java)
    }
}
