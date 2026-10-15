package io.github.simoncek.voiceprompt.state

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationPhaseTrackerTest {

    @Test
    fun `initial phase is idle`() {
        assertEquals(DictationPhase.IDLE, DictationPhaseTracker().phase)
    }

    @Test
    fun `isCapturing is true only while the capture line is open`() {
        assertFalse(DictationPhase.IDLE.isCapturing)
        assertTrue(DictationPhase.OPENING.isCapturing)
        assertTrue(DictationPhase.RECORDING.isCapturing)
        assertFalse(DictationPhase.TRANSCRIBING.isCapturing)
    }

    @Test
    fun `setPhase notifies listeners only when the phase actually changes`() {
        val tracker = DictationPhaseTracker()
        val seen = mutableListOf<DictationPhase>()
        tracker.addListener { seen += it }

        tracker.setPhase(DictationPhase.OPENING)
        tracker.setPhase(DictationPhase.OPENING) // unchanged: no notification
        tracker.setPhase(DictationPhase.RECORDING)

        assertEquals(listOf(DictationPhase.OPENING, DictationPhase.RECORDING), seen)
    }

    @Test
    fun `a throwing listener does not stop the other listeners`() {
        val tracker = DictationPhaseTracker()
        val second = mutableListOf<DictationPhase>()
        tracker.addListener { throw IllegalStateException("broken view") }
        tracker.addListener { second += it }

        tracker.setPhase(DictationPhase.RECORDING)

        assertEquals(listOf(DictationPhase.RECORDING), second)
    }

    @Test
    fun `a listener is only removed once and re-adding it does not duplicate notifications`() {
        val tracker = DictationPhaseTracker()
        val seen = mutableListOf<DictationPhase>()
        val listener: (DictationPhase) -> Unit = { seen += it }

        tracker.addListener(listener)
        tracker.removeListener(listener)
        tracker.addListener(listener)
        tracker.addListener(listener) // idempotent

        tracker.setPhase(DictationPhase.RECORDING)

        assertEquals(listOf(DictationPhase.RECORDING), seen)
    }

    @Test
    fun `phase is visible across threads`() {
        val tracker = DictationPhaseTracker()
        val latch = CountDownLatch(1)
        val thread = Thread {
            tracker.setPhase(DictationPhase.TRANSCRIBING)
            latch.countDown()
        }
        thread.start()
        assertTrue("worker thread timed out", latch.await(5, TimeUnit.SECONDS))
        assertEquals(DictationPhase.TRANSCRIBING, tracker.phase)
    }

    @Test
    fun `clear drops all listeners`() {
        val tracker = DictationPhaseTracker()
        val seen = mutableListOf<DictationPhase>()
        tracker.addListener { seen += it }

        tracker.clear()
        tracker.setPhase(DictationPhase.RECORDING)

        assertEquals(DictationPhase.RECORDING, tracker.phase)
        assertTrue(seen.isEmpty())
    }
}
