package io.github.simoncek.voiceprompt.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
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

/**
 * Review P5: nothing used to prevent several transcriptions running at once — a new TAP press
 * starts a fresh recording as soon as the capture line closes, even while the previous clip is
 * still transcribing — so results could arrive out of order, and an earlier clip's finish could
 * drop the shared phase to IDLE while a later one was still running.
 *
 * This test drives two clips through the real [DictationSession.transcribeBody] with the engine
 * faked: the first clip is held mid-"inference" (still holding the serialisation lock), and the
 * second must wait for it — not run underneath it — after which both deliver, in the order the
 * clips ended, and only the last one to finish may return the phase to IDLE.
 */
class DictationSessionConcurrencyTest : LightPlatformTestCase() {

    private fun session(): DictationSession =
        ApplicationManager.getApplication().getService(DictationSession::class.java)

    fun test_transcriptions_are_serialised_and_delivered_in_order() {
        val s = session()

        // The body resolves the real model; a dummy file at the expected path keeps that a fast
        // hit (no 75 MB bundled-model extraction) — the engine call is faked, so the content
        // is irrelevant.
        val modelFile = ModelManager.modelFile(CopilotVoiceSettings.instance.modelName).toFile()
        modelFile.parentFile?.mkdirs()
        modelFile.writeText("dummy")

        val delivered = Collections.synchronizedList(mutableListOf<String>())
        s.deliverTranscript = { _, text -> delivered.add(text) }

        val firstInference = CountDownLatch(1)  // the first clip is now in its (faked) inference
        val firstRelease = CountDownLatch(1)    // the test releases the first clip
        val secondInference = CountDownLatch(1) // would count down if the second clip ran early
        val secondRelease = CountDownLatch(1)   // the test releases the second clip

        s.engineTranscribe = { wavFile, _, _, _ ->
            if (wavFile.name == "first.wav") {
                firstInference.countDown()
                firstRelease.await(10, TimeUnit.SECONDS)
            } else {
                secondInference.countDown()
                secondRelease.await(10, TimeUnit.SECONDS)
            }
            "transcript:${wavFile.name}"
        }

        val dir = File(System.getProperty("java.io.tmpdir"), "vp-p5-${System.nanoTime()}").apply { mkdirs() }
        val firstWav = File(dir, "first.wav").apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) }
        val secondWav = File(dir, "second.wav").apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) }

        // What finishStop() does before queueing the task: the shared phase goes TRANSCRIBING.
        DictationStatus.instance.setPhase(DictationPhase.TRANSCRIBING)

        try {
            val t1 = Thread { s.transcribeBody(EmptyProgressIndicator(), null, firstWav) }
            t1.start()
            assertTrue("the first clip should start its inference", firstInference.await(10, TimeUnit.SECONDS))
            // t1 is now inside the faked engine — provably holding the serialisation lock.
            // Only now may the second clip begin, so it must queue behind the first.
            val t2 = Thread { s.transcribeBody(EmptyProgressIndicator(), null, secondWav) }
            t2.start()

            // Review P5's core: the second clip must NOT run while the first is still in
            // flight. Without the serialisation lock it would, and would deliver first.
            assertFalse(
                "the second transcription must wait for the first to finish (review P5)",
                secondInference.await(500, TimeUnit.MILLISECONDS),
            )

            // Release the first clip; it must deliver.
            firstRelease.countDown()
            assertTrue("the first clip should be delivered", awaitDelivery("transcript:first.wav", delivered))

            // ...and the shared phase must still be TRANSCRIBING: the second clip is queued or
            // running, not finished. (Without the in-flight counter the first clip's exit would
            // have dropped the phase to IDLE already.)
            PlatformTestUtil.waitWithEventsDispatching(
                "the phase must stay TRANSCRIBING until the last clip finishes (review P5)",
                { DictationStatus.instance.phase == DictationPhase.TRANSCRIBING },
                10,
            )

            // Let the second clip run to completion.
            secondRelease.countDown()
            assertTrue("the second clip should be delivered", awaitDelivery("transcript:second.wav", delivered))
            assertEquals(
                "clips must be delivered in the order they ended (review P5)",
                listOf("transcript:first.wav", "transcript:second.wav"),
                delivered,
            )
            PlatformTestUtil.waitWithEventsDispatching(
                "the phase must be IDLE once the last clip has finished",
                { DictationStatus.instance.phase == DictationPhase.IDLE },
                10,
            )
        } finally {
            s.engineTranscribe = null
            s.deliverTranscript = null
            dir.delete()
        }
    }

    /**
     * Polls [list] until [expected] is in it. The delivery seam runs on the clip's worker
     * thread, so a plain sleep-poll is safe here (nothing on that path needs the EDT).
     */
    private fun awaitDelivery(expected: String, list: List<String>): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (expected in list) return true
            Thread.sleep(25)
        }
        return expected in list
    }
}
