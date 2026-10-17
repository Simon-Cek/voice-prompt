package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.sink.ChatSink
import io.github.simoncek.voiceprompt.sink.ChatSinkChain
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * Pre-1.1.0 finding 4: an expected transcription failure (a broken model, a timeout, a
 * missing sink) is a normal operating outcome, not a plugin bug — it must be logged at WARN,
 * because on a release IDE an ERROR log lights the red plugin-error indicator. The user is
 * already told by the notification/balloon, which the code under test keeps.
 *
 * The red/green discriminator is the test environment itself: its default error processor
 * RE-THROWS every error log (TestLoggerAssertionError), and error logs never reach JUL. So a
 * reverted `log.error("transcribe threw", ...)` either kills the transcription thread (the
 * rethrow is caught below) or simply never lands in the JUL records the assertion reads. A
 * `log.warn` does neither — the record arrives at WARNING and the thread ends cleanly.
 *
 * The second test pins the `deliverToChat` catch the same way, through a sink chain whose
 * sink throws from `isAvailable` (the chain's try/catch covers `insert` only, so the throw
 * escapes to `deliverToChat`'s catch — the line under test).
 */
class DictationSessionExpectedFailureTest : LightPlatformTestCase() {

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

    fun test_an_expected_engine_failure_is_logged_at_warn_not_error() {
        val s = session()
        prepareModel()
        try {
            s.deliverTranscript = { _, _ -> } // delivery is a no-op on failure
            s.engineTranscribe = { _, _, _, _ ->
                // A realistic expected failure: the server answers, but with an error body.
                throw WhisperCliEngine.TranscriptionException(
                    "whisper-server returned HTTP 500: {\"error\":\"model failed to load\"}",
                    httpStatus = 500,
                )
            }
            val wav = wavIn("expected-failure.wav")
            val clip = s.claimTranscriptionForTest()
            val threadError = AtomicReference<Throwable?>()
            val t = Thread {
                try {
                    s.transcribeBody(EmptyProgressIndicator(), project, wav, clip)
                } catch (e: Throwable) {
                    threadError.set(e)
                }
            }
            t.start()
            t.join(30_000)
            assertFalse("the transcription thread must finish", t.isAlive)
            // Red if the code logs an ERROR: the test environment's default error processor
            // rethrows it (caught here) — and the warn record below would be absent anyway.
            assertNull(
                "an expected failure must not be an ERROR log (it would light the IDE's plugin-error indicator); the thread got: " +
                    threadError.get(),
                threadError.get(),
            )
            val record = records.firstOrNull { it.message?.contains("transcribe threw") == true }
            assertNotNull("the 'transcribe threw' failure must still be logged", record)
            assertEquals("the expected failure must be logged at WARN, not ERROR", Level.WARNING, record?.level)
        } finally {
            s.engineTranscribe = null
            s.deliverTranscript = null
        }
    }

    fun test_a_throwing_sink_is_logged_at_warn_not_error() {
        val s = session()
        prepareModel()
        try {
            s.engineTranscribe = { _, _, _, _ -> "transcript" }
            // deliverTranscript stays null → the REAL deliverToChat runs. A sink whose
            // isAvailable throws: the chain's try/catch covers insert only — so the throw
            // escapes to deliverToChat's catch (the line under test).
            s.chatSink = ChatSinkChain(listOf(object : ChatSink {
                override fun isAvailable(project: Project?): Boolean =
                    throw IllegalStateException("sink boom (test)")
                override fun insert(
                    project: Project?,
                    text: String,
                    autoSend: Boolean,
                    isCancelled: () -> Boolean,
                    onComplete: (Result<Unit>) -> Unit,
                ) = throw IllegalStateException("unreachable")
            }))
            val wav = wavIn("sink-failure.wav")
            val clip = s.claimTranscriptionForTest()
            val threadError = AtomicReference<Throwable?>()
            val t = Thread {
                try {
                    s.transcribeBody(EmptyProgressIndicator(), project, wav, clip)
                } catch (e: Throwable) {
                    threadError.set(e)
                }
            }
            t.start()
            t.join(30_000)
            assertFalse("the transcription thread must finish", t.isAlive)
            assertNull(
                "an expected sink failure must not be an ERROR log; the thread got: " + threadError.get(),
                threadError.get(),
            )
            // deliverToChat runs on a later EDT round — wait for its log record.
            PlatformTestUtil.waitWithEventsDispatching(
                "the 'deliverToChat threw' line must be logged",
                BooleanSupplier { records.any { it.message?.contains("deliverToChat threw") == true } },
                10,
            )
            val record = records.firstOrNull { it.message?.contains("deliverToChat threw") == true }
            assertNotNull("the 'deliverToChat threw' failure must still be logged", record)
            assertEquals("an expected sink failure must be logged at WARN, not ERROR", Level.WARNING, record?.level)
        } finally {
            s.engineTranscribe = null
            s.chatSink = ChatSinkChain.default()
        }
    }

    private fun session(): DictationSession =
        ApplicationManager.getApplication().getService(DictationSession::class.java)

    /** A dummy model file at the expected path — resolveActive becomes a fast hit. */
    private fun prepareModel() {
        val modelFile = ModelManager.modelFile(CopilotVoiceSettings.instance.modelName).toFile()
        modelFile.parentFile?.mkdirs()
        modelFile.writeText("dummy")
    }

    private fun wavIn(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "vp-f4-${System.nanoTime()}").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) }
    }
}
