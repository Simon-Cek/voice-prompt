package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.project.DumbAware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review N12: while the IDE indexes, the platform disables every action that lacks the
 * [DumbAware] marker — the dictation shortcut must keep working on large repos, so the action
 * carries the marker and updates on BGT. [DictationSession] needs no smart project (it
 * dispatches to pooled threads and reads only the event's input/project), so staying enabled
 * during dumb mode is safe.
 */
class PushToTalkActionDumbAwareTest {

    @Test
    fun `the action stays enabled while the IDE is indexing`() {
        val action = PushToTalkAction()

        assertTrue(
            "a non-dumb-aware action is disabled by the platform during indexing (review N12)",
            action is DumbAware,
        )
    }

    @Test
    fun `the action updates on BGT, off the EDT`() {
        assertEquals(ActionUpdateThread.BGT, PushToTalkAction().getActionUpdateThread())
    }
}
