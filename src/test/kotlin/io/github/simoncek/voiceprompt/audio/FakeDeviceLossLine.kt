package io.github.simoncek.voiceprompt.audio

import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.TargetDataLine

/**
 * A [TargetDataLine] the review-N6 device-loss tests drive instead of a real audio device.
 *
 * It keeps the N5 contract (a stopped line retains its buffered data, only flush discards it, and
 * read never blocks) and adds the failure modes N6 is about: [silent] makes reads return 0 (the
 * device stopped delivering), [throwNext] makes the next read throw (the OS took the line), and
 * [close] simulates the OS closing the line. [clock] advances by [tickMs] on every read, so the
 * "no data for N ms" loss window is crossed in fake time — no real waiting, no flakiness.
 */
internal class FakeDeviceLossLine : TargetDataLine {

    val calls = CopyOnWriteArrayList<String>()
    /** Buffer sizes (bytes) requested via open(format, size), in order — review N7. */
    val openedSizes = CopyOnWriteArrayList<Int>()
    private val buffered = ArrayDeque<ByteArray>()

    /** Fake time (ms) the read loop's loss window observes; advanced by [tickMs] on every read. */
    @Volatile var clock: Long = 0L
    /** Fake time (ms) that passes on every read() — the device's time. */
    @Volatile var tickMs: Long = 0L
    /** Reads that returned audio; tests wait on it to sequence "after the first sample". */
    @Volatile var positiveReads: Int = 0
    /** All reads; tests wait on it to sequence "after N reads". */
    @Volatile var readCount: Int = 0

    /** The device stopped delivering: reads return 0 until the test clears it. */
    @Volatile var silent: Boolean = false
    /** When set, the next read() throws this and clears it (the OS took the line mid-read). */
    @Volatile var throwNext: Throwable? = null

    @Volatile private var opened = false
    @Volatile private var running = false

    /** Simulate the microphone delivering [frames] of audio into the line's buffer. */
    fun feed(frames: Int, value: Short = 0x0101) {
        val bytes = ByteArray(frames * 2)
        var i = 0
        while (i < bytes.size) {
            bytes[i] = (value.toInt() and 0xFF).toByte()           // low byte first: little-endian
            bytes[i + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        synchronized(buffered) { buffered.addLast(bytes) }
    }

    override fun available(): Int = synchronized(buffered) { buffered.sumOf { it.size } }

    override fun open(format: AudioFormat?, bufferSize: Int) {
        calls.add("open")
        openedSizes.add(bufferSize)
        opened = true
    }

    override fun open(format: AudioFormat?) {
        open(format, 0)
    }

    override fun close() {
        calls.add("close")
        opened = false
        running = false
    }

    override fun start() {
        calls.add("start")
        running = true
    }

    override fun stop() {
        calls.add("stop")
        running = false
        // N5 contract: a stopped line retains its buffered data.
    }

    override fun flush() {
        calls.add("flush")
        synchronized(buffered) { buffered.clear() }
    }

    override fun read(buffer: ByteArray?, offset: Int, numBytes: Int): Int {
        readCount++
        clock += tickMs
        calls.add("read")
        val t = throwNext
        if (t != null) {
            throwNext = null
            throw t
        }
        if (silent) return 0
        val chunk: ByteArray
        val take: Int
        synchronized(buffered) {
            val first = buffered.firstOrNull() ?: return -1
            take = minOf(first.size, numBytes)
            chunk = first.copyOfRange(0, take)
            if (take < first.size) {
                buffered[0] = first.copyOfRange(take, first.size)
            } else {
                buffered.removeFirst()
            }
        }
        System.arraycopy(chunk, 0, buffer!!, offset, take)
        positiveReads++
        return take
    }

    override fun getFormat(): AudioFormat = AudioCapture.DEFAULT_FORMAT

    override fun isOpen(): Boolean = opened

    override fun isRunning(): Boolean = running

    override fun isControlSupported(controlType: Control.Type?): Boolean = false

    override fun getControl(controlType: Control.Type?): Control? = null

    // The rest of the Line/DataLine contract: the fake is never used for these.
    override fun open() {
        open(AudioCapture.DEFAULT_FORMAT)
    }

    override fun drain() {
    }

    override fun isActive(): Boolean = running

    override fun getBufferSize(): Int = 1600

    override fun getFramePosition(): Int = 0

    override fun getLongFramePosition(): Long = 0L

    override fun getMicrosecondPosition(): Long = 0L

    override fun getLevel(): Float = Float.NEGATIVE_INFINITY

    override fun getLineInfo(): Line.Info = Line.Info(TargetDataLine::class.java)

    override fun getControls(): Array<Control> = emptyArray()

    override fun addLineListener(listener: LineListener?) {
    }

    override fun removeLineListener(listener: LineListener?) {
    }
}
