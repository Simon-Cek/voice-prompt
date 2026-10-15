package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightPlatformTestCase

/**
 * Review P4: a key-triggered stop arrives on the EDT (TAP press, status-bar click) or the AWT
 * event thread (Hold-mode release), and the stop tail — the capture join (up to 2 s), the WAV
 * write and the model resolve — must not run there. This test pins that contract with a
 * capturing dispatcher: the blocking work must be QUEUED for a background thread, not executed
 * on the calling one.
 */
class DictationSessionStopTest : LightPlatformTestCase() {

    private fun session(): DictationSession =
        ApplicationManager.getApplication().getService(DictationSession::class.java)

    fun test_stop_queues_its_blocking_work_off_the_calling_thread() {
        val s = session()
        var queued: (() -> Unit)? = null
        s.stopWorkDispatcher = { queued = it }
        try {
            s.stop()
            assertNotNull(
                "the blocking stop tail (capture join, WAV write, model resolve) must be queued " +
                    "for a background thread, not run on the calling one (review P4)",
                queued,
            )
        } finally {
            s.stopWorkDispatcher = null
        }
        // Run the captured work to a clean state (nothing is recording: empty samples -> IDLE).
        // Safe off-EDT and on-EDT alike — the stop tail marshals its own UI updates.
        queued!!.invoke()
    }
}
