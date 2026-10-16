package io.github.simoncek.voiceprompt.audio

import com.intellij.openapi.Disposable
import io.github.simoncek.voiceprompt.state.DictationStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class MicrophoneTester(
    parent: Disposable,
    private val onLevel: (Double) -> Unit, // called on the EDT
    private val onNoSound: () -> Unit, // called on the EDT
    private val onError: (String) -> Unit, // called on the EDT
    private val onStopped: () -> Unit // called on the EDT
) : Disposable {

    private val running = AtomicBoolean(false)
    private val log = Logger.getInstance(MicrophoneTester::class.java)
    @Volatile
    private var stopping = false
    @Volatile
    private var line: TargetDataLine? = null

    init {
        Disposer.register(parent, this)
    }

    val isRunning: Boolean get() = running.get()

    fun start(deviceName: String?) {
        if (DictationStatus.instance.isCapturing) {
            // Review N14 (test side of the guard): never open a second capture line while
            // dictation holds the microphone. The other direction — dictation start stopping a
            // running test — lives in MicrophoneTester.stopAll(), called from DictationSession.
            log.info("Mic test not started: dictation is in progress")
            return
        }
        if (!running.compareAndSet(false, true)) return
        stopping = false
        active += this
        ApplicationManager.getApplication().executeOnPooledThread { capture(deviceName) }
    }

    fun stop() {
        stopping = true
        running.set(false)
        // Closing the line unblocks a possibly-blocked read() on the capture thread.
        line?.let { l ->
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    l.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun dispose() {
        stop()
    }

    private fun capture(deviceName: String?) {
        val format = AudioFormat(16000f, 16, 1, true, false)
        var smoothed = 0.0
        var peak = 0.0
        var heardSound = false
        var warned = false
        try {
            val l = MicrophoneResolver.resolve(deviceName, format)
            line = l
            l.open(format)
            l.start()
            val buffer = ByteArray(1280)
            val startTime = System.currentTimeMillis()
            while (running.get() && System.currentTimeMillis() - startTime < 10_000) {
                val n = l.read(buffer, 0, buffer.size)
                if (!l.isOpen) break // the device went away mid-test (review N6)
                if (n <= 0) {
                    // Review N6: sleep instead of a bare continue (which spun the CPU); the 10 s
                    // wall clock above already bounds the wait.
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    continue
                }
                val v = (rms(buffer, n) * 6).coerceIn(0.0, 1.0)
                smoothed = if (v > smoothed) v else smoothed * 0.85 + v * 0.15
                if (v > peak) peak = v
                if (v > 0.05) heardSound = true
                val elapsed = System.currentTimeMillis() - startTime
                if (!heardSound && !warned && elapsed > 3000) {
                    warned = true
                    ui { onNoSound() }
                }
                // sqrt = perceptual expansion, so a quiet-but-real signal is clearly visible
                // on the thin bar (linear RMS under-represents low levels).
                val shown = Math.sqrt(smoothed)
                ui { onLevel(shown) }
            }
        } catch (e: Exception) {
            if (!stopping) {
                when (e) {
                    // MicrophoneNotFoundException is a LineUnavailableException; match it first.
                    is MicrophoneNotFoundException -> ui { onError("No microphone found.") }
                    is LineUnavailableException -> ui { onError("The microphone is in use by another app.") }
                    else -> ui { onError("Couldn't start the microphone test.") }
                }
            }
        } finally {
            try {
                line?.stop()
            } catch (_: Exception) {
            }
            try {
                line?.close()
            } catch (_: Exception) {
            }
            line = null
            running.set(false)
            active -= this // review N14: never leave a finished test in the registry
            log.info("Mic test finished: device=${deviceName ?: "system default"} peakLevel=$peak")
            ui {
                onLevel(0.0)
                onStopped()
            }
        }
    }

    private fun rms(buf: ByteArray, len: Int): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            count++
            i += 2
        }
        return if (count == 0) 0.0 else Math.sqrt(sum / count) / 32768.0
    }

    // ModalityState.any(): a plain invokeLater from a pooled thread posts with NON_MODAL state
    // (verified in ModalityState/ModalityKt bytecode), which a modal dialog or progress indicator
    // on the EDT would delay. These UI updates must run regardless of modality.
    private fun ui(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(Runnable { block() }, ModalityState.any())
    }

    companion object {
        private val log = Logger.getInstance(MicrophoneTester::class.java)

        /** Every mic test currently running (or starting). Review N14: the dictation side of the
         *  mutual-exclusion guard needs to find them, but the tester instances are owned by the
         *  settings session — so they register here for the lifetime of the capture. */
        private val active = CopyOnWriteArrayList<MicrophoneTester>()

        /**
         * Review N14: stops every running mic test and clears them from the registry. Called
         * when dictation starts — dictation takes the microphone, and the test (a passive
         * 10 s diagnostic) yields. [start] already refuses while dictation captures, so after
         * this the two capture paths are mutually exclusive in both directions.
         *
         * Safe to call from the EDT ([stop] only sets flags and schedules the line close on a
         * pooled thread), and a no-op when no test is running.
         */
        internal fun stopAll() {
            for (tester in active) {
                active -= tester
                runCatching { tester.stop() }
                    .onFailure { log.warn("failed to stop the mic test for dictation start", it) }
            }
        }

        /** Test seam (review N14): registers [tester] as running, so a test can drive [stopAll]
         *  without a real microphone. */
        internal fun registerActiveForTest(tester: MicrophoneTester) {
            active += tester
        }

        /** Test seam (review N14): how many tests are registered as running. */
        internal val activeCountForTest: Int
            get() = active.size
    }
}
