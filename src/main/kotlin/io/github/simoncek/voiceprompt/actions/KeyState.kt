package io.github.simoncek.voiceprompt.actions

import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * The physical key codes that make up a keyboard shortcut: the main key plus the modifier keys
 * named by the classic [InputEvent] mask (as reported by [java.awt.KeyboardStroke]).
 */
fun shortcutKeyCodes(keyCode: Int, modifiers: Int): Set<Int> {
    val keys = linkedSetOf(keyCode)
    if (modifiers and InputEvent.CTRL_MASK != 0) keys += KeyEvent.VK_CONTROL
    if (modifiers and InputEvent.SHIFT_MASK != 0) keys += KeyEvent.VK_SHIFT
    if (modifiers and InputEvent.ALT_MASK != 0) keys += KeyEvent.VK_ALT
    if (modifiers and InputEvent.META_MASK != 0) keys += KeyEvent.VK_META
    return keys
}

/**
 * Gates push-to-talk triggers against keyboard auto-repeat.
 *
 * A trigger is honoured only when the shortcut's main key has been released since the previous
 * honoured trigger. This kills two failure modes at once:
 *
 *  - In Tap mode, holding the key makes the IDE re-fire the action on every auto-repeat, which
 *    would toggle start/stop every few hundred milliseconds.
 *  - Past the 60 s duration cap, the auto-stop runs while the key is still held; the next
 *    auto-repeat would call start again and the recording would silently restart until the key
 *    is let go.
 *
 * The only transition back to "allowed" is an observed release of the main key, so the decision
 * is correct no matter whether our AWT listener sees the key event before or after the action
 * fires.
 */
class ShortcutTriggerGuard {
    @Volatile
    private var allowed = true

    /** The shortcut's main key was released: the next fresh press may be honoured again. */
    fun mainKeyReleased() {
        allowed = true
    }

    /**
     * Returns — and consumes — the permission to act on a shortcut trigger. Triggers without an
     * intervening [mainKeyReleased] return false.
     */
    fun consume(): Boolean {
        if (allowed) {
            allowed = false
            return true
        }
        return false
    }
}
