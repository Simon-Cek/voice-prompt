package io.github.simoncek.voiceprompt.actions

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutTriggerGuardTest {

    @Test
    fun `the first press is honoured`() {
        val guard = ShortcutTriggerGuard()
        assertTrue(guard.consume())
    }

    @Test
    fun `auto-repeat presses while the key is held are ignored`() {
        val guard = ShortcutTriggerGuard()
        assertTrue(guard.consume()) // fresh press -> start
        repeat(20) {
            assertFalse("repeat #$it must be ignored", guard.consume())
        }
    }

    @Test
    fun `a press after the main key has been released is honoured again`() {
        val guard = ShortcutTriggerGuard()
        guard.consume() // press
        guard.mainKeyReleased()
        assertTrue(guard.consume()) // fresh press -> honoured
    }

    @Test
    fun `repeats after an auto-stop are ignored until the key is released`() {
        // 60 s cap: the recording stops while the user still holds the key.
        val guard = ShortcutTriggerGuard()
        guard.consume() // start
        // (no release — the cap stopped the recording, the key is still held)
        assertFalse("must not restart while the key is held", guard.consume())
        guard.mainKeyReleased()
        assertTrue("the next fresh press starts again", guard.consume())
    }

    @Test
    fun `release re-arms the guard for an indefinite number of taps`() {
        val guard = ShortcutTriggerGuard()
        repeat(5) {
            assertTrue(guard.consume()) // tap
            guard.mainKeyReleased()
        }
        assertTrue("still armed after repeated tap cycles", guard.consume())
    }

    @Test
    fun `a cross-app focus boundary re-arms the guard for an indefinite number of taps`() {
        // Review N3: the key-up of a still-held key went to another application and will
        // never be seen here, so the boundary itself accounts for it.
        val guard = ShortcutTriggerGuard()
        repeat(5) {
            assertTrue(guard.consume()) // press
            guard.rearm() // focus crossed the application boundary
        }
        assertTrue("still armed after repeated focus-boundary cycles", guard.consume())
    }

    @Test
    fun `a cross-app focus boundary re-arms without any observed release`() {
        val guard = ShortcutTriggerGuard()
        guard.consume() // press — the guard is disarmed and no release will come
        guard.rearm()
        assertTrue("the next fresh press must be honoured (review N3)", guard.consume())
    }
}

class ShortcutKeyCodesTest {

    @Test
    fun `main key plus the modifier keys in the mask`() {
        val codes = shortcutKeyCodes(KeyEvent.VK_Z, InputEvent.ALT_MASK or InputEvent.SHIFT_MASK)
        assertEquals(setOf(KeyEvent.VK_Z, KeyEvent.VK_ALT, KeyEvent.VK_SHIFT), codes)
    }

    @Test
    fun `a bare main key has no modifiers`() {
        assertEquals(setOf(KeyEvent.VK_Z), shortcutKeyCodes(KeyEvent.VK_Z, 0))
    }

    @Test
    fun `all four modifiers are included`() {
        val mask = InputEvent.CTRL_MASK or InputEvent.SHIFT_MASK or
            InputEvent.ALT_MASK or InputEvent.META_MASK
        assertEquals(
            setOf(
                KeyEvent.VK_Z,
                KeyEvent.VK_CONTROL,
                KeyEvent.VK_SHIFT,
                KeyEvent.VK_ALT,
                KeyEvent.VK_META,
            ),
            shortcutKeyCodes(KeyEvent.VK_Z, mask),
        )
    }

    @Test
    fun `releasing any combo key ends a hold recording`() {
        val combo = shortcutKeyCodes(KeyEvent.VK_Z, InputEvent.ALT_MASK or InputEvent.SHIFT_MASK)
        assertTrue("main key released -> stop", KeyEvent.VK_Z in combo)
        assertTrue("modifier released before the main key -> stop", KeyEvent.VK_SHIFT in combo)
        assertTrue(KeyEvent.VK_ALT in combo)
        assertFalse("an unrelated key release does not stop", KeyEvent.VK_X in combo)
    }
}
