package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pre-1.1.0 finding 7: the shared home (review N11) accumulates one `whisper-server-<pid>.log`
 * per IDE session, because the log is named per process precisely because the home is shared.
 * This removes the stale ones (a dead PID, or older than 7 days —
 * [WhisperServerEngine.cleanStaleServerLogs]) on a background thread: file I/O must not run on
 * the EDT (AGENTS.md rule 1). Best effort — a failure here never affects the dictation, and
 * the next session retries.
 *
 * It runs lazily — [WhisperServerEngine] calls [ensureOnce] on the first dictation of a
 * session — instead of at app startup. The stale logs only matter before the next server log
 * is written, so cleaning up right before that is as good as cleaning up at startup, and it
 * keeps the plugin free of internal-API usage and of an extension-point registration (the
 * `AppLifecycleListener` hook used before 1.1.0 was `@ApiStatus.Internal`).
 */
class StaleServerLogCleanup {

    private val log = Logger.getInstance(StaleServerLogCleanup::class.java)

    /** The cleanup runs at most once per instance; the engine keeps one instance for the JVM. */
    private val started = AtomicBoolean(false)

    /**
     * Test seam (finding 7): a latch a test can await after [ensureOnce] to prove the work ran
     * to completion. Null in production.
     */
    internal var doneForTest: CountDownLatch? = null

    /**
     * Runs the cleanup once, on a daemon background thread; a second call is a no-op.
     */
    fun ensureOnce() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            runCatching {
                val removed = WhisperServerEngine.cleanStaleServerLogs(VoicePromptPaths.baseDir())
                if (removed.isNotEmpty()) {
                    log.info(
                        "removed ${removed.size} stale whisper-server log(s): " +
                            removed.joinToString { it.fileName.toString() },
                    )
                }
            }.onFailure { log.warn("stale server-log cleanup failed", it) }
            doneForTest?.countDown()
        }, "voice-prompt-log-cleanup").apply {
            isDaemon = true
            start()
        }
    }
}
