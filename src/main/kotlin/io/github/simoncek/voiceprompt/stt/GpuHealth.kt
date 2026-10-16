package io.github.simoncek.voiceprompt.stt

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import io.github.simoncek.voiceprompt.VoiceBundle
import java.nio.file.Path

/**
 * Review N9: a CONFIRMED GPU failure — a GPU run failed and the CPU run of the same utterance
 * succeeded (or the GPU server died at startup and the CPU one came up) — is remembered per model
 * so the next transcription goes straight to the CPU instead of failing on the GPU and
 * restarting on the CPU every single time.
 *
 * Scope (review N9 #4): the blacklist is per MODEL (not per thread count), and it is scoped to
 * the Processing settings in effect when the failure was confirmed. [sync] is called with the
 * current processing key at the start of every transcription; a change to the key — the user
 * moved the Processing dropdown or the thread count — CLEARS the blacklist, because that is the
 * user's deliberate "try the GPU again" (and what the one-time notification tells them).
 *
 * Thread safety (review N9 #3): the engines call from worker threads; all state lives behind one
 * lock, so access is safe from any thread.
 */
object GpuHealth {

    /** A confirmed failure: the model plus the processing key under which it happened. */
    private data class Entry(val model: Path, val processingKey: String)

    private val LOCK = Any()

    @Volatile
    private var entry: Entry? = null

    /** The processing key last seen by [sync]; a change to it clears [entry] (review N9 #4). */
    @Volatile
    private var lastKey: String? = null

    /**
     * The processing-settings key that scopes the blacklist: the Processing mode (the user's
     * dropdown choice, not the resolved backend) plus the CPU thread count. Any change to either
     * is a different key — and therefore clears a stored entry.
     */
    fun processingKey(processingMode: String, threads: Int): String =
        "mode=$processingMode;threads=$threads"

    /**
     * Review N9 #4: a change to the Processing settings clears the blacklist. Called at the
     * start of every transcription (DictationSession) with the current settings.
     */
    fun sync(processingKey: String) {
        synchronized(LOCK) {
            if (processingKey != lastKey) {
                entry = null
                lastKey = processingKey
            }
        }
    }

    /** True when a GPU failure was confirmed for [model] under the current processing settings. */
    fun isBlacklisted(model: Path, processingKey: String): Boolean {
        val e = entry ?: return false
        return e.processingKey == processingKey && e.model == model
    }

    /**
     * Records a confirmed GPU failure. Returns true when the blacklist just became active (the
     * entry changed) — the caller shows the one-time notification then, and only then
     * (review N9 #5: a user who chose the GPU must not be silently overridden, but the balloon
     * must not repeat on every utterance either).
     */
    fun noteFailure(model: Path, processingKey: String): Boolean {
        synchronized(LOCK) {
            val e = Entry(model, processingKey)
            if (entry == e) return false
            entry = e
            return true
        }
    }

    /** Clears the blacklist (the entry stays scoped to its key regardless; this is explicit). */
    fun clear() {
        synchronized(LOCK) { entry = null }
    }

    /** Test seam (review N9 #3): the state is process-wide and outlives individual tests. */
    fun clearForTest() {
        synchronized(LOCK) {
            entry = null
            lastKey = null
        }
    }
}

/**
 * Review N9 #5: one INFORMATION balloon per confirmed GPU failure (plus a log line), so a user
 * who chose the GPU sees that Voice Prompt is overriding the choice — and how to undo it.
 *
 * No-op outside an IDE (unit tests) or when no project is open; a notification failure must
 * never break a transcription that already succeeded.
 */
internal fun notifyGpuBlacklisted(model: Path) {
    val logger = Logger.getInstance(GpuHealth::class.java)
    logger.info(
        "GPU inference failed for ${model.fileName}; using the CPU until the model or the " +
            "Processing setting changes",
    )
    runCatching {
        val target = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(VoiceBundle["notification.gpu.blacklisted"], NotificationType.INFORMATION)
        if (java.awt.EventQueue.isDispatchThread()) {
            notification.notify(target)
        } else {
            ApplicationManager.getApplication().invokeLater { notification.notify(target) }
        }
    }.onFailure { logger.warn("failed to show the GPU-blacklist notification", it) }
}

/** The plugin's notification group (META-INF/plugin.xml `<notificationGroup id="CopilotVoice">`). */
private const val NOTIFICATION_GROUP_ID = "CopilotVoice"
