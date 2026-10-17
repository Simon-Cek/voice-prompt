package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.simoncek.voiceprompt.settings.CopilotVoiceSettings
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import io.github.simoncek.voiceprompt.stt.ModelManager
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Review §3: [DictationSession.cancelTranscription] reaches every live clip — running and
 * queued — through its per-clip [TranscriptionHandle]: a running clip's [ProgressIndicator]
 * is cancelled, and a clip whose task has not started yet (no indicator to cancel) has its
 * cancelRequested flag set and bails the moment its task does start — the engine never runs
 * for a cancelled clip.
 *
 * The tests drive [DictationSession.transcribeBody] directly on raw threads with the engine
 * faked, exactly as the review P5 concurrency test does. The threads use
 * [EmptyProgressIndicator], whose cancel() is a no-op and whose isCanceled is always false —
 * so in these tests the handle's cancelRequested flag (not the indicator) is the cancel
 * signal, which is precisely the path a clip whose task has not started yet relies on. The
 * faked engine emulates a real one's unwind: it blocks, and once the test says the cancel
 * reached it, it throws [ProcessCanceledException], as the real engine's checkCanceled would.
 */
class DictationSessionCancelTest : LightPlatformTestCase() {

    private lateinit var s: DictationSession

    override fun setUp() {
        super.setUp()
        s = DictationSession()
    }

    override fun tearDown() {
        try {
            s.dispose()
        } finally {
            drainEdt()
            super.tearDown()
        }
    }

    /** The phase lands on the EDT, so drain it until the expected phase holds. */
    private fun awaitPhase(expected: DictationPhase) {
        drainEdt()
        assertEquals(
            "the phase should be ${expected.name} (was ${DictationStatus.instance.phase.name})",
            expected,
            DictationStatus.instance.phase,
        )
    }

    /** Forces the EDT to process everything queued (see the phase-derivation test). */
    private fun drainEdt() {
        val drained = java.util.concurrent.atomic.AtomicBoolean(false)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { drained.set(true) }
        PlatformTestUtil.waitWithEventsDispatching(
            "the EDT should drain its queue",
            { drained.get() },
            10,
        )
    }

    /** Keeps the real model resolution in [DictationSession.transcribeBody] a fast hit. */
    private fun installDummyModel() {
        val modelFile = ModelManager.modelFile(CopilotVoiceSettings.instance.modelName).toFile()
        modelFile.parentFile?.mkdirs()
        modelFile.writeText("dummy")
    }

    private fun wavIn(dir: File, name: String) =
        File(dir, name).apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) }

    /**
     * Two clips: one running (holding the serialisation lock, mid-"inference"), one queued
     * (waiting for the lock). A cancel must end BOTH — no transcript may be delivered, both
     * clips must be released, and the phase must return to IDLE.
     */
    fun test_cancel_ends_both_a_running_and_a_queued_clip() {
        installDummyModel()
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        s.deliverTranscript = { _, text -> delivered.add(text) }

        val inInference = CountDownLatch(1) // clip 1 is now inside the (faked) inference
        val cancelReachedEngine = CountDownLatch(1) // the test tells the fake the cancel landed
        s.engineTranscribe = { wavFile, _, _, _ ->
            inInference.countDown()
            // A real engine observes the indicator's cancellation and unwinds; the fake
            // emulates that — the test releases it exactly when the cancel happens.
            cancelReachedEngine.await(10, TimeUnit.SECONDS)
            throw ProcessCanceledException()
        }

        val dir = File(System.getProperty("java.io.tmpdir"), "vp-s3-${System.nanoTime()}").apply { mkdirs() }
        val runningWav = wavIn(dir, "running.wav")
        val queuedWav = wavIn(dir, "queued.wav")

        val running = s.claimTranscriptionForTest()
        val queued = s.claimTranscriptionForTest()
        awaitPhase(DictationPhase.TRANSCRIBING)

        try {
            val runningError = AtomicReference<Throwable?>()
            val t1 = Thread {
                try {
                    s.transcribeBody(EmptyProgressIndicator(), null, runningWav, running)
                } catch (e: Throwable) {
                    runningError.set(e)
                }
            }
            t1.start()
            assertTrue("clip 1 should start its inference", inInference.await(10, TimeUnit.SECONDS))

            // Clip 2 is queued behind clip 1's lock — waiting in transcribeBody's poll loop.
            val queuedError = AtomicReference<Throwable?>()
            val t2 = Thread {
                try {
                    s.transcribeBody(EmptyProgressIndicator(), null, queuedWav, queued)
                } catch (e: Throwable) {
                    queuedError.set(e)
                }
            }
            t2.start()

            // Review §3: cancel everything — both the running and the queued clip.
            s.cancelTranscription()
            // Tell the faked engine the cancel reached it (a real engine would notice via
            // the indicator and unwind on its own); it then throws, as the real path would.
            cancelReachedEngine.countDown()

            t1.join(10_000)
            t2.join(10_000)
            assertFalse("the running clip must end on cancel", t1.isAlive)
            assertFalse("the queued clip must end on cancel", t2.isAlive)

            assertTrue("the running clip's handle must be marked cancelled", running.cancelRequested)
            assertTrue("the queued clip's handle must be marked cancelled", queued.cancelRequested)
            assertEquals(
                "a cancelled clip must not deliver its transcript",
                emptyList<String>(),
                delivered,
            )
            // Pre-1.1.0 finding 4: the cancellation must surface as a ProcessCanceledException —
            // the platform settles the task as a cancel — not be swallowed into a "transcribe
            // failed" ERROR notification. (The queued clip threw one at its cancel check; the
            // running clip's must not be eaten by the catch block.)
            assertTrue(
                "a cancelled clip must surface as ProcessCanceledException, was: ${runningError.get()}",
                runningError.get() is ProcessCanceledException,
            )
            assertTrue(
                "a cancelled clip must surface as ProcessCanceledException, was: ${queuedError.get()}",
                queuedError.get() is ProcessCanceledException,
            )
            // Both clips released — the phase returns to IDLE (not stuck on TRANSCRIBING).
            awaitPhase(DictationPhase.IDLE)
        } finally {
            s.engineTranscribe = null
            s.deliverTranscript = null
            dir.delete()
        }
    }

    /**
     * A cancel that arrives before the clip's task has started: its handle has no indicator
     * to cancel, so the cancelRequested flag is the whole signal — and [DictationSession
     * .transcribeBody] must check it first, so the engine never runs for that clip.
     */
    fun test_cancel_before_the_task_starts_never_runs_the_engine() {
        installDummyModel()
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        var engineCalls = 0
        s.engineTranscribe = { _, _, _, _ ->
            engineCalls++
            "transcript"
        }
        s.deliverTranscript = { _, text -> delivered.add(text) }

        val dir = File(System.getProperty("java.io.tmpdir"), "vp-s3-${System.nanoTime()}").apply { mkdirs() }
        val wav = wavIn(dir, "clip.wav")

        val clip = s.claimTranscriptionForTest()
        awaitPhase(DictationPhase.TRANSCRIBING)
        try {
            // The task has not started yet — the handle has no indicator, only the flag.
            s.cancelTranscription()
            assertTrue("the clip's handle must be marked cancelled", clip.cancelRequested)
            assertFalse("a queued clip has no indicator yet", clip.indicator != null)

            // Now the "task" starts (the queue hands it to a thread): transcribeBody must
            // bail at its very start, before model resolution and the engine call.
            val t = Thread { s.transcribeBody(EmptyProgressIndicator(), null, wav, clip) }
            t.start()
            t.join(10_000)
            assertFalse("a cancelled clip must end immediately", t.isAlive)

            assertEquals("the engine must never run for a cancelled clip", 0, engineCalls)
            assertEquals("a cancelled clip must not deliver", emptyList<String>(), delivered)
            awaitPhase(DictationPhase.IDLE)
        } finally {
            s.engineTranscribe = null
            s.deliverTranscript = null
            dir.delete()
        }
    }

    /**
     * A cancel with nothing live is a no-op — and a subsequent, uncancelled clip must still
     * run and deliver (the cancel flag is per-clip, not a shared "cancelled" latched state).
     */
    fun test_cancel_with_nothing_live_is_a_noop_and_leaves_later_clips_able_to_run() {
        installDummyModel()
        s.cancelTranscription() // must not throw

        val delivered = Collections.synchronizedList(mutableListOf<String>())
        s.engineTranscribe = { _, _, _, _ -> "hello" }
        s.deliverTranscript = { _, text -> delivered.add(text) }

        val dir = File(System.getProperty("java.io.tmpdir"), "vp-s3-${System.nanoTime()}").apply { mkdirs() }
        val wav = wavIn(dir, "clip.wav")

        val clip = s.claimTranscriptionForTest()
        awaitPhase(DictationPhase.TRANSCRIBING)
        try {
            val t = Thread { s.transcribeBody(EmptyProgressIndicator(), null, wav, clip) }
            t.start()
            t.join(10_000)
            assertFalse("the uncancelled clip should finish", t.isAlive)

            assertFalse("a no-op cancel must not mark a later clip", clip.cancelRequested)
            assertEquals(listOf("hello"), delivered)
            awaitPhase(DictationPhase.IDLE)
        } finally {
            s.engineTranscribe = null
            s.deliverTranscript = null
            dir.delete()
        }
    }
}
