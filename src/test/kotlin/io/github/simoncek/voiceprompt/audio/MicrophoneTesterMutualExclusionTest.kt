package io.github.simoncek.voiceprompt.audio

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.simoncek.voiceprompt.actions.DictationSession
import io.github.simoncek.voiceprompt.state.DictationPhase
import io.github.simoncek.voiceprompt.state.DictationStatus
import io.github.simoncek.voiceprompt.state.DerivedPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/**
 * Review N14: the mic test and the dictation paths are mutually exclusive in both directions.
 * The test side already refuses while dictation is capturing ([MicrophoneTester.start]); this
 * pins that half, the new dictation half (a start stops every running mic test — the passive
 * 10 s diagnostic yields the microphone), and the registry cleanup a failed test owes.
 *
 * Deterministic: no real microphone — a "running" test is simulated with
 * [MicrophoneTester.registerActiveForTest], and dictation start is driven through
 * [DictationSession.startWorkDispatcher] (the start work is swallowed, so the capture line is
 * never opened). The failing-test case uses a device name that matches no mixer: resolve throws
 * before any line is opened, so the real start → register → fail → clean-up path runs with no
 * real audio.
 */
class MicrophoneTesterMutualExclusionTest : LightPlatformTestCase() {

    private lateinit var s: DictationSession
    private lateinit var parent: Disposable

    override fun setUp() {
        super.setUp()
        s = DictationSession()
        parent = Disposer.newDisposable()
    }

    override fun tearDown() {
        try {
            DictationStatus.instance.applyDerivedPhase { DerivedPhase(DictationPhase.IDLE) }
            MicrophoneTester.stopAll() // leave no registered tester behind
            s.dispose()
            Disposer.dispose(parent)
        } finally {
            super.tearDown()
        }
    }

    private fun tester() = MicrophoneTester(
        parent = parent,
        onLevel = { },
        onNoSound = { },
        onError = { },
        onStopped = { },
    )

    fun test_the_mic_test_refuses_to_start_while_dictation_is_capturing() {
        DictationStatus.instance.applyDerivedPhase { DerivedPhase(DictationPhase.RECORDING) }

        val t = tester()
        t.start(null)

        assertFalse(
            "a mic test must not open the mic while dictation is capturing (review N14)",
            t.isRunning,
        )
        assertEquals(0, MicrophoneTester.activeCountForTest)
    }

    fun test_dictation_start_stops_a_running_mic_test() {
        val t = tester()
        MicrophoneTester.registerActiveForTest(t) // simulate a running test (no real mic)
        s.startWorkDispatcher = { } // swallow the start work — never opens the capture line
        try {
            s.start(null)
            assertEquals(
                "dictation start must stop the running mic test (review N14)",
                0,
                MicrophoneTester.activeCountForTest,
            )
        } finally {
            s.startWorkDispatcher = null
            MicrophoneTester.stopAll()
        }
    }

    fun test_a_mic_test_that_fails_to_start_leaves_no_active_tester_behind() {
        val t = tester()
        t.start("no-such-microphone-device-review-n14")

        PlatformTestUtil.waitWithEventsDispatching(
            "a failed mic test should finish and clean up",
            { !t.isRunning },
            10,
        )
        assertEquals(
            "a failed mic test must leave no active tester behind (review N14)",
            0,
            MicrophoneTester.activeCountForTest,
        )
    }
}
