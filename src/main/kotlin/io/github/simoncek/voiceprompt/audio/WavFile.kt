package io.github.simoncek.voiceprompt.audio

import javax.sound.sampled.AudioFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFile {

    fun write(file: File, samples: ShortArray, format: AudioFormat) {
        require(format.encoding == AudioFormat.Encoding.PCM_SIGNED) { "Expected PCM_SIGNED, got ${format.encoding}" }
        val payload = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) payload.putShort(sample)
        file.outputStream().use { out ->
            out.write(header(format, payload.position()))
            out.write(payload.array())
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
