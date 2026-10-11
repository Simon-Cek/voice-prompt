package com.example.copilotvoice.audio

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
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
        if (isDictationRecording()) return
        if (!running.compareAndSet(false, true)) return
        stopping = false
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

    // TODO: return true while real dictation is capturing (PushToTalkAction.PttState.isRecording), so the test can't run in parallel.
    private fun isDictationRecording(): Boolean = false

    private fun capture(deviceName: String?) {
        val format = AudioFormat(16000f, 16, 1, true, false)
        var smoothed = 0.0
        var peak = 0.0
        var heardSound = false
        var warned = false
        try {
            val l = openLine(deviceName, format)
            line = l
            l.open(format)
            l.start()
            val buffer = ByteArray(1280)
            val startTime = System.currentTimeMillis()
            while (running.get() && System.currentTimeMillis() - startTime < 10_000) {
                val n = l.read(buffer, 0, buffer.size)
                if (n <= 0) continue
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
                    is LineUnavailableException -> ui { onError("The microphone is in use by another app.") }
                    is IllegalArgumentException -> ui { onError("No microphone found.") }
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
            log.info("Mic test finished: device=${deviceName ?: "system default"} peakLevel=$peak")
            ui {
                onLevel(0.0)
                onStopped()
            }
        }
    }

    // null/blank means the system default microphone (settings store null for the default).
    private fun openLine(deviceName: String?, format: AudioFormat): TargetDataLine {
        if (deviceName.isNullOrEmpty()) return AudioSystem.getTargetDataLine(format)
        val info = AudioSystem.getMixerInfo().firstOrNull { it.name == deviceName }
            ?: throw IllegalArgumentException("device not found")
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
        return AudioSystem.getMixer(info).getLine(lineInfo) as TargetDataLine
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
}
