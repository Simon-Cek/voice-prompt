package io.github.simoncek.voiceprompt.audio

import com.intellij.testFramework.LightPlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Mic timing log (review #11, start delay: "No timing log, so the 1 to 2 s start delay is
 * still unmeasured"): the start path must log per-phase durations — open, line start, and
 * the first-read gap (lookup + getLine are timed in [MicrophoneResolver.resolve], which
 * needs a real device and is therefore not driven here) — so the right lever (mixer cache
 * vs keep-ready) can be chosen from the log.
 *
 * The lines are per-dictation context: they must be debug (FINE under the test logger),
 * never info (the N20 discipline), and carry numbers only (AGENTS.md rule 8).
 *
 * Driven against [FakeTargetDataLine] through the [AudioCapture.lineFactoryForTest] seam —
 * no real audio device, no beeps; the only waits are [AudioCapture.awaitFirstSample]'s
 * bounded timeout and the reader-thread joins in stop. Log capture follows
 * [io.github.simoncek.voiceprompt.sink.LocatorLogLevelTest]: the platform test logger is
 * JUL-based and JUL loggers are a global registry, so a handler on the JUL root logger
 * catches the records no matter when the logger instances were created.
 */
class MicTimingLogTest : LightPlatformTestCase() {

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
    private val pkgLogger = Logger.getLogger("io.github.simoncek.voiceprompt")
    private val savedPkgLevel = pkgLogger.level

    private lateinit var capture: AudioCapture
    private lateinit var fake: FakeTargetDataLine

    override fun setUp() {
        super.setUp()
        // Let debug records through to the handler (the platform may gate them at the logger level).
        root.level = Level.ALL
        pkgLogger.level = Level.ALL
        root.addHandler(handler)
        capture = AudioCapture()
        fake = FakeTargetDataLine()
        capture.lineFactoryForTest = { _, _ -> fake }
    }

    override fun tearDown() {
        try {
            // End any live capture (joins the reader thread) and release the daemon executor
            // (AGENTS.md rule 2) so no reader thread outlives the test.
            capture.stop(0)
            capture.release()
            capture.lineFactoryForTest = null
        } finally {
            root.removeHandler(handler)
            root.level = savedRootLevel
            pkgLogger.level = savedPkgLevel
            super.tearDown()
        }
    }

    /**
     * The debug (FINE) line containing [marker], or an AssertionError — asserting the FINE
     * level here is what pins "timing context is debug, not info" (the N20 discipline).
     */
    private fun debugLine(marker: String): String {
        val line = records
            .filter { it.level == Level.FINE }
            .firstOrNull { it.message?.toString()?.contains(marker) == true }
            ?.message?.toString()
        assertNotNull(
            "no debug (FINE) log line containing '$marker' — the phase is not timed, or not logged at debug",
            line,
        )
        return line!!
    }

    fun test_fresh_start_times_the_open_start_and_first_read_phases() {
        capture.start(null)
        fake.feed(800)
        assertTrue("the first clip must deliver audio", capture.awaitFirstSample(2_000))
        capture.stop(0)

        val open = debugLine("capture line opened")
        val started = debugLine("capture line started")
        val firstRead = debugLine("first sample after")

        // Each timed phase carries a non-negative duration in ms.
        for (phase in listOf("open" to open, "line start" to started, "first read" to firstRead)) {
            val duration = Regex(" (\\d+) ms").find(phase.second)
            assertNotNull("the ${phase.first} phase must carry a duration in ms: '${phase.second}'", duration)
        }
    }

    fun test_keep_ready_reuse_times_start_without_an_open_phase() {
        // First clip, kept ready: the line stays open (just stopped).
        capture.start(null)
        fake.feed(800)
        assertTrue("the first clip must deliver audio", capture.awaitFirstSample(2_000))
        capture.stop(5)

        records.clear()
        // The next start reuses the open line: the log must SHOW the lever — no open phase.
        capture.start(null)
        fake.feed(800)
        assertTrue("the second clip must deliver audio", capture.awaitFirstSample(2_000))
        capture.stop(0)

        debugLine("capture line started")
        debugLine("first sample after")
        assertTrue(
            "a keep-ready reuse must log no open phase — that absence is the lever the timing log must show",
            records.none {
                it.message?.toString()?.contains("capture line opened") == true ||
                    it.message?.toString()?.contains("kept line re-opened") == true
            },
        )
    }
}
