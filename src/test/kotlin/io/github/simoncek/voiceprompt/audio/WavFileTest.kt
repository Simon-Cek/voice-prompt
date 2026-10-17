package io.github.simoncek.voiceprompt.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.CountDownLatch
import javax.sound.sampled.AudioFormat

class WavFileTest {

    private val format = AudioFormat(16_000f, 16, 1, true, false)

    @Test
    fun `writes valid RIFF header and pcm payload`() {
        val samples = ShortArray(100) { (it * 7).toShort() }
        // write() creates the file (owner-only on POSIX) — the target must not exist yet.
        val dir = Files.createTempDirectory("copilot-voice-test").toFile()
        val file = File(dir, "clip.wav")
        try {
            WavFile.write(file, samples, format)
            val bytes = file.readBytes()

            assertEquals(44 + samples.size * 2, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals(36 + samples.size * 2, leInt(bytes, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals("fmt ", String(bytes, 12, 4))
            assertEquals(16, leInt(bytes, 16))
            assertEquals(1, leShort(bytes, 20).toInt())
            assertEquals(1, leShort(bytes, 22).toInt())
            assertEquals(16_000, leInt(bytes, 24))
            assertEquals(32_000, leInt(bytes, 28))
            assertEquals(2, leShort(bytes, 32).toInt())
            assertEquals(16, leShort(bytes, 34).toInt())
            assertEquals("data", String(bytes, 36, 4))
            assertEquals(samples.size * 2, leInt(bytes, 40))

            val payload = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
            val decoded = ShortArray(payload.remaining() / 2)
            payload.asShortBuffer().get(decoded)
            assertArrayEquals(samples, decoded)
        } finally {
            file.delete()
            dir.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-pcm formats`() {
        val dir = Files.createTempDirectory("copilot-voice-test").toFile()
        try {
            WavFile.write(
                File(dir, "clip.wav"),
                ShortArray(10),
                AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 16_000f, 16, 1, 1, 2f, false),
            )
        } finally {
            dir.delete()
        }
    }

    @Test
    fun `creates the file owner-only on posix file systems`() {
        assumeTrue(
            "POSIX permissions only exist on POSIX file systems (on Windows the per-user %TEMP% ACLs keep the file to the owner)",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val dir = Files.createTempDirectory("copilot-voice-test").toFile()
        val file = File(dir, "clip.wav")
        try {
            WavFile.write(file, ShortArray(4), format)
            assertEquals(
                "the WAV carries the user's voice and must not be group/world readable",
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file.toPath()),
            )
        } finally {
            file.delete()
            dir.delete()
        }
    }

    @Test
    fun `writes a valid wav to a fresh temp file and returns it`() {
        // Pre-1.1.0 finding 5: the production clip path picks its own name (createTempFile)
        // and gets the file back — no caller-chosen path to collide on.
        val samples = ShortArray(64) { (it * 3).toShort() }
        val file = WavFile.write(samples, format)
        try {
            assertTrue("the temp file must exist", file.isFile)
            val bytes = file.readBytes()
            assertEquals(44 + samples.size * 2, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            val payload = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
            val decoded = ShortArray(payload.remaining() / 2)
            payload.asShortBuffer().get(decoded)
            assertArrayEquals(samples, decoded)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the temp file is created owner-only on posix file systems`() {
        assumeTrue(
            "POSIX permissions only exist on POSIX file systems (on Windows the per-user %TEMP% ACLs keep the file to the owner)",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val file = WavFile.write(ShortArray(4), format)
        try {
            assertEquals(
                "the WAV carries the user's voice and must not be group/world readable",
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file.toPath()),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `back-to-back writes get unique names and never collide`() {
        // Pre-1.1.0 finding 5: two clips in the same millisecond (or a leftover file from a
        // crashed run) shared the old `copilot-voice-<millis>.wav` name — the CREATE open
        // then threw and the clip was lost. createTempFile makes the name unique at creation.
        val a = WavFile.write(shortArrayOf(1, 2, 3), format)
        val b = WavFile.write(shortArrayOf(4, 5, 6, 7, 8, 9), format)
        try {
            assertNotEquals("two rapid writes must land on distinct files", a, b)
            assertTrue(a.isFile)
            assertTrue(b.isFile)
            assertEquals(44 + 3 * 2, a.length())
            assertEquals(44 + 6 * 2, b.length())
        } finally {
            a.delete()
            b.delete()
        }
    }

    @Test
    fun `concurrent writes never collide`() {
        // Same guarantee under contention: every call gets its own file and a complete payload.
        val threads = 8
        val perThread = 3
        val written = Collections.synchronizedList(mutableListOf<Pair<File, ShortArray>>())
        val start = CountDownLatch(1)
        val workers = (1..threads).map { i ->
            Thread {
                start.await()
                repeat(perThread) { j ->
                    val samples = ShortArray(50) { k -> (i * 1000 + j * 50 + k).toShort() }
                    written.add(WavFile.write(samples, format) to samples)
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(30_000) }
        val files = written.map { it.first }
        try {
            assertEquals(threads * perThread, files.size)
            assertEquals(
                "every concurrent write must get its own file",
                threads * perThread,
                files.toSet().size,
            )
            for ((f, samples) in written) {
                assertTrue("$f must exist", f.isFile)
                assertEquals(44L + samples.size * 2L, f.length())
                val payload = ByteBuffer.wrap(f.readBytes(), 44, f.length().toInt() - 44).order(ByteOrder.LITTLE_ENDIAN)
                val decoded = ShortArray(payload.remaining() / 2)
                payload.asShortBuffer().get(decoded)
                assertArrayEquals("each file must hold exactly its own payload", samples, decoded)
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leShort(bytes: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short
}
