package io.github.simoncek.voiceprompt.stt

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.CountDownLatch

/**
 * Pre-1.1.0 finding 7: the shared home (review N11) accumulates one `whisper-server-<pid>.log`
 * per IDE session, because the log is named per process precisely because the home is shared.
 * At IDE startup this removes the stale ones (a dead PID, or older than 7 days —
 * [WhisperServerEngine.cleanStaleServerLogs]) on a background thread: file I/O must not run on
 * the EDT (AGENTS.md rule 1). Best effort — a failure here never affects startup, and the next
 * startup retries.
 *
 * [AppLifecycleListener] is `@ApiStatus.Internal`, and there is no supported alternative for
 * application-startup code in the 2025.3 platform (verified against IU-2025.3.6.1):
 * `PostStartupActivity` no longer exists, `StartupActivity` is `@ApiStatus.Obsolete` and
 * project-scoped, and the `applicationActivity` EP rejects extensions from non-core plugins
 * (ApplicationLoader's asyncAppListenerAllowListForNonCorePlugin). The platform itself
 * registers its application-startup hooks on this same topic (PlatformExtensions.xml, e.g.
 * UpdateCheckerService, WindowButtonsAppLifecycleListener), and the current plugin-SDK
 * guidance for application startup is to subscribe to it.
 */
class StaleServerLogCleanup : AppLifecycleListener {

    private val log = Logger.getInstance(StaleServerLogCleanup::class.java)

    /**
     * Test seam (finding 7): a latch a test can await after [runCleanup] to prove the work ran
     * to completion. Null in production.
     */
    internal var doneForTest: CountDownLatch? = null

    override fun appStarted() {
        runCleanup(doneForTest)
    }

    /** The work itself: clean the shared home's stale server logs, on a daemon background thread. */
    internal fun runCleanup(done: CountDownLatch?) {
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
            done?.countDown()
        }, "voice-prompt-log-cleanup").apply {
            isDaemon = true
            start()
        }
    }
}
