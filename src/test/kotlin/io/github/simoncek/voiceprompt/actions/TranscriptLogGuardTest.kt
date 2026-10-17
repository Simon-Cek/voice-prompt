package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ThrowableRunnable
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.stt.ModelManager
import io.github.simoncek.voiceprompt.stt.WhisperCliEngine
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Review (Test): a guard against transcribed text ever reaching idea.log (AGENTS.md rule 8 —
 * "never log transcribed text or audio"). Round-1 finding #2 fixed the direct case — the
 * session and the sinks log lengths only — and this test pins the boundary so a future
 * `log.info("transcript: $text")` fails the build instead of surfacing in a support ticket.
 *
 * It drives the real [DictationSession.transcribeBody] with a sentinel transcript and
 * collects everything the pipeline would put into the log:
 *  - info/warn/debug records via a JUL root handler (the platform's test logger publishes
 *    them to JUL — the same mechanism [io.github.simoncek.voiceprompt.sink.LocatorLogLevelTest]
 *    relies on),
 *  - error-level logs via a [LoggedErrorProcessor] swap: in the test environment the default
 *    processor RETHROWS every error log (TestLoggerAssertionError), so the tests install a
 *    [LeakCaptureProcessor] that records each (message, throwable) instead of throwing.
 *
 * Every collected message and throwable/cause chain is then scanned for the sentinel — it
 * must appear nowhere. The third test is the strict one: an engine exception whose MESSAGE
 * carries the transcript (the realistic worst case — an engine bug echoing
 * "last output was: ..."). The catch block must log the type and stack frames, never the
 * message; against a `log.warn("transcribe threw", t)`-style catch the scan fails.
 *
 * Coverage notes:
 *  - The success test also drives the real deliverToChat + sink chain
 *    (SwingInjectionSink / ClipboardPasteSink / ChatSinkChain) against a fixture tool
 *    window, so their log surface is captured too — audited: class names and lengths only,
 *    and their exceptions (NoCopilotWindowException, InputNotFoundException,
 *    SinkCancelledException) carry static messages.
 *  - The engine's own log lines are audited separately: they log lengths, exit codes,
 *    stderr tails and HTTP status + error-body tails — never the transcript (whisper's
 *    output is stdout, read only on success).
 */
class TranscriptLogGuardTest : LightPlatformTestCase() {

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

    /**
     * The marker is a stable PREFIX of the transcript, so the guard catches full leaks and
     * truncated ones alike — `log.info("first words: ${text.take(40)}")` still contains it.
     */
    private val transcript = "$MARKER-${System.nanoTime()} the quick brown fox jumps over the lazy dictation"

    override fun setUp() {
        super.setUp()
        // Let every record through to the handler (the platform may gate at the logger level).
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

    fun test_success_path_logs_lengths_not_the_transcript() {
        val s = session()
        prepareModel()
        // The fixture window: the locator finds it via its candidate ids (the real Copilot
        // window registers under "Copilot Chat"), and the input the way the locator finds it
        // (first editable).
        val field = JTextField()
        val panel = JPanel().apply { add(field) }
        runInEdtAndGet {
            val w = ToolWindowManager.getInstance(project)
                .registerToolWindow("Copilot Chat", panel, ToolWindowAnchor.BOTTOM)
            // The registered component is not a Content yet — add it, so the sink has a root
            // component to scan (like the real Copilot window's content).
            w.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "input", false))
            w.setTitle("Copilot Chat")
            w.show()
        }
        try {
            val processor = LeakCaptureProcessor()
            s.engineTranscribe = { _, _, _, _ -> transcript }
            // deliverTranscript stays null → the REAL deliverToChat + sink chain runs.
            withErrorCapture(processor) { runClip(s, "guard.wav") }

            // deliverToChat runs on a later EDT round and a sink may complete asynchronously —
            // wait for the transcript to actually land in the fixture input.
            val waitMessage = "the transcript must land in the fixture input (error logs captured: " +
                processor.errors.map { it.first } + ")"
            PlatformTestUtil.waitWithEventsDispatching(
                waitMessage,
                BooleanSupplier { field.text.contains(MARKER) },
                15,
            )
            assertTrue("the transcript must land in the fixture input", field.text.contains(MARKER))

            // Positive side: the session did log, and it logged the LENGTH (the fixed shape).
            val done = records.firstOrNull { it.message?.contains("transcribe done") == true }
            assertNotNull("the transcribe-done line must be logged", done)
            assertEquals(
                "the done line must carry the length, not the text",
                "transcribe done: len=${transcript.length} blank=false",
                done?.message,
            )

            assertNoLeak(processor)
        } finally {
            s.engineTranscribe = null
            s.deliverTranscript = null
        }
    }

    fun test_engine_failure_logs_the_error_not_the_transcript() {
        val s = session()
        prepareModel()
        try {
            s.deliverTranscript = { _, _ -> }  // delivery is a no-op on failure
            s.engineTranscribe = { _, _, _, _ ->
                // A realistic engine failure: HTTP error, message with the error body —
                // never the transcript (the transcript is the 200 body, not the error body).
                throw WhisperCliEngine.TranscriptionException(
                    "whisper-server returned HTTP 500: {\"error\":\"model failed to load\"}",
                    httpStatus = 500,
                )
            }
            val processor = LeakCaptureProcessor()
            withErrorCapture(processor) { runClip(s, "guard.wav") }

            assertFailureLogged()
            // The realistic error details may be present — the transcript is what may not be.
            assertNoLeak(processor)
        } finally {
            s.engineTranscribe = null
            s.deliverTranscript = null
        }
    }

    fun test_engine_exception_carrying_the_transcript_is_redacted_from_the_log() {
        val s = session()
        prepareModel()
        try {
            s.deliverTranscript = { _, _ -> }
            s.engineTranscribe = { _, _, _, _ ->
                // The realistic worst case: an engine bug embeds the transcript in the
                // exception message. The session's catch must not forward it to the log —
                // the user sees the message in the failure notification instead.
                throw WhisperCliEngine.TranscriptionException("inference failed; last output was: $transcript")
            }
            val processor = LeakCaptureProcessor()
            withErrorCapture(processor) { runClip(s, "guard.wav") }

            assertFailureLogged()
            assertNoLeak(processor)
        } finally {
            s.engineTranscribe = null
            s.deliverTranscript = null
        }
    }

    private fun session(): DictationSession =
        ApplicationManager.getApplication().getService(DictationSession::class.java)

    /** A dummy model file at the expected path — resolveActive becomes a fast hit (no bundled-model extraction). */
    private fun prepareModel() {
        val modelFile = ModelManager.modelFile(CopilotVoiceSettings.instance.modelName).toFile()
        modelFile.parentFile?.mkdirs()
        modelFile.writeText("dummy")
    }

    /** What the task hook does: claim the slot and run the body on a plain thread. */
    private fun runClip(s: DictationSession, name: String) {
        val dir = File(System.getProperty("java.io.tmpdir"), "vp-guard-${System.nanoTime()}").apply { mkdirs() }
        val wav = File(dir, name).apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) }
        val clip = s.claimTranscriptionForTest()
        val failure = AtomicReference<Throwable?>()
        val t = Thread {
            try {
                s.transcribeBody(EmptyProgressIndicator(), project, wav, clip)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        t.start()
        t.join(30_000)
        assertFalse("the transcription thread must finish", t.isAlive)
        failure.get()?.let { throw it }
    }

    /**
     * Runs [body] with [processor] installed as the global [LoggedErrorProcessor] — so the
     * error logs the code under test attempts are captured instead of rethrown (the default
     * test processor throws TestLoggerAssertionError on every error log).
     */
    private fun withErrorCapture(processor: LeakCaptureProcessor, body: () -> Unit) {
        LoggedErrorProcessor.executeWith(processor, ThrowableRunnable<RuntimeException> { body() })
    }

    private fun assertNoLeak(processor: LeakCaptureProcessor) {
        val hits = leakHits(processor)
        assertTrue(
            "the transcript must not reach any log output:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /** The catch's failure log must be present — the failure was logged at all. Since the
     *  pre-1.1.0 finding 4 change it is at WARN, so it arrives via the JUL root handler (error
     *  capture is still scanned too, for leaks). */
    private fun assertFailureLogged() {
        val hasFailure = records.any { it.message?.contains("transcribe threw") == true }
        assertTrue(
            "the 'transcribe threw' failure must be logged; warn records seen: " +
                records.filter { it.level == Level.WARNING }.map { it.message },
            hasFailure,
        )
    }

    /** Every collected log output whose message or throwable/cause chain carries the marker. */
    private fun leakHits(processor: LeakCaptureProcessor): List<String> {
        val hits = mutableListOf<String>()
        // Error-level logs, captured by the processor (the default test processor would
        // rethrow them — that is exactly the content that would reach idea.log).
        for ((message, throwable) in processor.errors) {
            scan(message, "error log", hits)
            scanThrowable(throwable, hits)
        }
        // Everything else (info/warn/debug) via the JUL root handler.
        for (r in records) {
            scan(r.message, "level=${r.level} source=${r.sourceClassName}", hits)
            scanThrowable(r.thrown, hits)
        }
        return hits
    }

    private fun scanThrowable(throwable: Throwable?, hits: MutableList<String>) {
        var t = throwable
        while (t != null) {
            scan(t.message, "thrown ${t.javaClass.name}", hits)
            t = t.cause
        }
    }

    private fun scan(value: String?, where: String, hits: MutableList<String>) {
        if (value != null && value.contains(MARKER)) {
            hits.add("$where: \"$value\"")
        }
    }

    /**
     * Records each error log the code under test attempts, instead of letting the default
     * test processor rethrow it. Returning [Action].NONE suppresses logging, stderr and the
     * rethrow — the test scans the captured content itself.
     */
    private class LeakCaptureProcessor : LoggedErrorProcessor() {
        val errors = CopyOnWriteArrayList<Pair<String, Throwable?>>()

        override fun processError(
            loggerName: String,
            message: String,
            details: Array<out String>,
            throwable: Throwable?,
        ): Set<Action> {
            errors.add(message to throwable)
            return Action.NONE
        }
    }

    companion object {
        private const val MARKER = "VP_LEAK_GUARD"
    }
}
