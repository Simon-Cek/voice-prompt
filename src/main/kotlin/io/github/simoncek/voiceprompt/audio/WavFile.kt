package io.github.simoncek.voiceprompt.audio

import javax.sound.sampled.AudioFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

object WavFile {

    /**
     * Writes the clip to [file], which must not exist yet.
     *
     * The WAV holds the user's raw voice, and it lives in a shared temp dir between creation
     * and the session's post-inference delete, so on POSIX it is created owner-only (0600)
     * instead of world-readable: the permission is set atomically at creation (no window of
     * default perms), and 0600 is unaffected by any umask. On Windows the per-user %TEMP%
     * default ACLs already keep the file to the owner, so no attribute is passed there.
     * CREATE without TRUNCATE_EXISTING: a name collision fails instead of clobbering an
     * existing file.
     */
    fun write(file: File, samples: ShortArray, format: AudioFormat) {
        require(format.encoding == AudioFormat.Encoding.PCM_SIGNED) { "Expected PCM_SIGNED, got ${format.encoding}" }
        val payload = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) payload.putShort(sample)
        val path = file.toPath()
        val attrs = if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            arrayOf<FileAttribute<*>>(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            emptyArray()
        }
        Files.newByteChannel(path, EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), *attrs).use { out ->
            out.write(ByteBuffer.wrap(header(format, payload.position())))
            out.write(ByteBuffer.wrap(payload.array()))
        }
    }

    private fun header(format: AudioFormat, dataSize: Int): ByteArray {
        val bytesPerSample = format.sampleSizeInBits / 8
        val byteRate = format.sampleRate * format.channels * bytesPerSample
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(format.channels.toShort())
            putInt(format.sampleRate.toInt())
            putInt(byteRate.toInt())
            putShort((format.channels * bytesPerSample).toShort())
            putShort(format.sampleSizeInBits.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
    }
}
