package io.github.simoncek.voiceprompt.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
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

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leShort(bytes: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short
}
