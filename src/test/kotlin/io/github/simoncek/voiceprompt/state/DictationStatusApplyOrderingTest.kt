package io.github.simoncek.voiceprompt.state

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Review N4: [DictationStatus.applyDerivedPhase] takes a *reader* of the facts and evaluates
 * it on the EDT at apply time — never a value the caller computed on its own thread. Otherwise
 * an application queued behind a newer one would land a stale phase on top of the newer one:
 * the worker thread computes "IDLE" (its last clip just ended), the EDT applies a newer
 * "RECORDING", and the queued stale apply then overwrites it back to IDLE.
 *
 * In the light test the test thread is the EDT: an application requested from a worker thread
 * lands only when the EDT is pumped, and one requested from the test thread applies
 * synchronously. The second test is the discriminating one — a stale application is queued by
 * a worker (where a precomputed implementation would capture its value), then the EDT sets the
 * new facts and applies synchronously, then the pump lets the stale one land last: the phase
 * must still reflect the new facts.
 */
class DictationStatusApplyOrderingTest : LightPlatformTestCase() {

    /**
     * Forces the EDT to process everything currently queued (the sentinel runs last in the
     * queue), so after it returns no worker application is left pending and the phase is final.
     */
    private fun drainEdt() {
        val drained = java.util.concurrent.atomic.AtomicBoolean(false)
        ApplicationManager.getApplication().invokeLater { drained.set(true) }
        PlatformTestUtil.waitWithEventsDispatching(
            "the EDT should drain its queue",
            { drained.get() },
            10,
        )
    }

    fun test_an_application_from_a_worker_thread_lands_on_the_edt() {
        val status = DictationStatus.instance
        val worker = Thread {
            status.applyDerivedPhase { DerivedPhase(DictationPhase.TRANSCRIBING) }
        }
        worker.start()
        worker.join(10_000)
        assertTrue("the worker should finish", !worker.isAlive)

        // It was requested from a worker thread, so it lands on the EDT — drain the EDT.
        drainEdt()
        assertEquals(
            "the worker-thread application must land on the EDT (review N4)",
            DictationPhase.TRANSCRIBING,
            status.phase,
        )

        // Back to IDLE for the next test (synchronous: the test thread is the EDT).
        status.applyDerivedPhase { DerivedPhase(DictationPhase.IDLE) }
    }

    fun test_a_queued_application_cannot_overwrite_a_newer_synchronous_one() {
        val status = DictationStatus.instance
        // The live fact, as the session keeps it: readable from any thread, mutable.
        val fact = java.util.concurrent.atomic.AtomicBoolean(false)

        // A worker thread requests an application while the fact is still false. A precomputed
        // (pre-N4 style) implementation captures the value now — IDLE — and the queued
        // application lands it on the EDT after the synchronous application below.
        val worker = Thread {
            status.applyDerivedPhase { DerivedPhase(if (fact.get()) DictationPhase.RECORDING else DictationPhase.IDLE) }
        }
        worker.start()
        worker.join(10_000)
        assertTrue("the worker should finish", !worker.isAlive)
        // The request is queued now (join establishes the happens-before), but not applied yet.

        // The EDT (this test thread) sets the new fact and applies it synchronously — exactly
        // as the session does when it runs on the EDT. This is the newer, correct value.
        fact.set(true)
        status.applyDerivedPhase { DerivedPhase(if (fact.get()) DictationPhase.RECORDING else DictationPhase.IDLE) }
        assertEquals(
            "a synchronous EDT application must land immediately",
            DictationPhase.RECORDING,
            status.phase,
        )

        // Now let the queued stale application land. It must evaluate the facts as they are now
        // (RECORDING) — and the newest facts must win even though the stale request lands last.
        // A precomputed-value implementation lands its stale IDLE here and fails this.
        drainEdt()
        assertEquals(
            "the newest facts must win, even when the stale request lands last (review N4)",
            DictationPhase.RECORDING,
            status.phase,
        )
    }
}
