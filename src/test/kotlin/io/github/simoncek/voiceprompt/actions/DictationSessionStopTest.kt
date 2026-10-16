package io.github.simoncek.voiceprompt.actions

import com.intellij.testFramework.LightPlatformTestCase

/**
 * Review P4: a key-triggered stop arrives on the EDT (TAP press, status-bar click) or the AWT
 * event thread (Hold-mode release), and the stop tail — the capture join (up to 2 s), the WAV
 * write and the model resolve — must not run there. This test pins that contract with a
 * capturing dispatcher: the blocking work must be QUEUED for a background thread, not executed
 * on the calling one.
 *
 * Review N2: uses a fresh [DictationSession] per test (disposed in [tearDown]) so the new
 * per-session stop claim starts unclaimed, independent of other test classes.
 */
class DictationSessionStopTest : LightPlatformTestCase() {

    private lateinit var s: DictationSession

    override fun setUp() {
        super.setUp()
        s = DictationSession()
    }

    override fun tearDown() {
        try {
            s.dispose()
        } finally {
            super.tearDown()
        }
    }

    fun test_stop_queues_its_blocking_work_off_the_calling_thread() {
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
        // Run the captured work to a clean state. Nothing is recording here, so the review N2
        // guard drops it before the stop tone and the phase change. Safe on- and off-EDT alike.
        queued!!.invoke()
    }
}
