package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightPlatformTestCase
import io.github.simoncek.voiceprompt.stt.WhisperCliEngine
import java.awt.Toolkit

/**
 * The dictation session must release its resources on dispose (AGENTS.md rule 2; review #6).
 * The AWT key watcher is the crux: while it is registered with the [Toolkit] the JVM cannot
 * unload the plugin's class loader, so a dynamic plugin unload or update would leak it. Disposing
 * the service — what the platform does in exactly that situation — must remove the listener,
 * close the capture line and kill any running whisper process.
 */
class DictationSessionLifecycleTest : LightPlatformTestCase() {

    private fun watcherCount(): Int = Toolkit.getDefaultToolkit().getAWTEventListeners().size

    private fun session(): DictationSession =
        ApplicationManager.getApplication().getService(DictationSession::class.java)

    fun test_key_watcher_is_installed_once_and_removed_on_dispose() {
        val s = session()
        val baseline = watcherCount()
        assertFalse("no key watcher before install", s.isKeyWatcherInstalled)

        s.installKeyWatcher()
        s.installKeyWatcher() // idempotent: must not register a second listener

        assertTrue(s.isKeyWatcherInstalled)
        assertEquals("installKeyWatcher() registers exactly one AWT listener", baseline + 1, watcherCount())

        s.dispose()

        assertFalse("dispose() must remove the key watcher", s.isKeyWatcherInstalled)
        assertEquals("dispose() must remove the AWT listener from the Toolkit", baseline, watcherCount())
    }

    fun test_dispose_is_idempotent() {
        val s = session()
        s.installKeyWatcher()
        s.dispose()
        s.dispose() // a double dispose must not throw
        assertFalse(s.isKeyWatcherInstalled)
    }

    fun test_dispose_is_safe_with_no_recording_and_no_transcription() {
        // Nothing is capturing and no whisper-cli is running: dispose() must not blow up.
        val s = session()
        s.dispose()
        WhisperCliEngine.killActiveProcesses()
    }
}
