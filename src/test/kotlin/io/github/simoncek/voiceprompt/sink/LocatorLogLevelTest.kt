package io.github.simoncek.voiceprompt.sink

import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.ui.content.ContentFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Review N20: findInput runs on every dictation, so its per-call context (the Copilot version,
 * the window scanned, every candidate component) must be debug, not info. The outcome lines
 * (SELECTED / NO input found) stay info — that is the one line a user wants when asking where
 * a dictation went.
 *
 * Drives the real [CopilotChatLocator.findInput] against a real tool window (light fixture) and
 * captures the log records it emits. The platform's test logger is JUL-based (its levels show
 * as FINE/INFO), and JUL loggers are a global registry — so a handler on the root logger
 * catches the records no matter when the platform's logger instances were created.
 */
class LocatorLogLevelTest : LightPlatformTestCase() {

    private val records = CopyOnWriteArrayList<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }

        override fun flush() = Unit

        override fun close() {}
    }
    private val root = Logger.getLogger("")
    private val savedRootLevel = root.level

    override fun setUp() {
        super.setUp()
        // Let debug records through to the handler (the platform may gate them at the logger level).
        root.level = Level.ALL
        Logger.getLogger("io.github.simoncek.voiceprompt").level = Level.ALL
        root.addHandler(handler)
    }

    override fun tearDown() {
        try {
            root.removeHandler(handler)
            Logger.getLogger("io.github.simoncek.voiceprompt").level = savedRootLevel
            root.level = savedRootLevel
        } finally {
            super.tearDown()
        }
    }

    fun test_per_dictation_locator_context_is_debug_and_the_outcome_stays_info() {
        val panel = JPanel().apply { add(JTextField()) }
        val window = runInEdtAndGet {
            val w = ToolWindowManager.getInstance(project)
                .registerToolWindow("N20 Copilot Chat", panel, ToolWindowAnchor.BOTTOM)
            // The registered component is not a Content yet — add it, so findInput has a root
            // component to scan (like the real Copilot window, whose content holds the composer).
            w.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "input", false))
            w
        }

        val input = CopilotChatLocator.findInput(window)
        assertNotNull("the fixture window must yield an input, or the test does not exercise findInput", input)

        val ours = records.filter { it.message?.contains("findInput:") == true || it.message?.contains("candidate ") == true }

        fun levelOf(marker: String): Level = ours
            .firstOrNull { it.message?.contains(marker) == true }
            ?.level
            ?: throw AssertionError("no locator log record found for '$marker' — findInput did not run as expected")

        // Review N20: per-dictation context (the version line, the window scan, every candidate)
        // is debug — and must not spam the info log on every dictation.
        assertEquals("the Copilot version line must be debug", Level.FINE, levelOf("findInput: copilot="))
        assertEquals("the window-scan line must be debug", Level.FINE, levelOf("scanning tool window"))
        assertEquals("the candidate lines must be debug", Level.FINE, levelOf("candidate javax.swing.JTextField"))
        // The outcome stays info — the line a user wants when asking where a dictation went.
        assertEquals("the SELECTED outcome must stay info", Level.INFO, levelOf("findInput: SELECTED"))
    }
}
