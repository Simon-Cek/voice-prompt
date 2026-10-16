package io.github.simoncek.voiceprompt.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Review N10: the win-x64 bundle shipped whisper-cli.exe but no whisper-server.exe, so on
 * Windows every dictation spawned a fresh CLI (model reload and Vulkan init per utterance) and
 * the warm-server path (P2) was silently unavailable. The .exe is now bundled from the same
 * upstream b5454 release the rest of the bundle came from (every shared file is hash-identical)
 * and is listed in SHA256SUMS, so the checksum-verified extraction covers it like everything
 * else.
 *
 * A static check on the classpath bundle: no binary is executed, no network, no timing.
 */
class WhisperServerBundleTest {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Parses SHA256SUMS for win-x64 the same way [WhisperRuntime.readSums] does. */
    private fun winSums(): Map<String, String> {
        val sums = LinkedHashMap<String, String>()
        javaClass.classLoader.getResourceAsStream("whisper/SHA256SUMS")?.use { input ->
            input.bufferedReader().forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) return@forEachLine
                val path = parts[1]
                if (path.startsWith("win-x64/")) sums[path.removePrefix("win-x64/")] = parts[0].lowercase()
            }
        }
        return sums
    }

    @Test
    fun `the win-x64 bundle ships the whisper server binary`() {
        val resource = javaClass.classLoader.getResource("whisper/win-x64/whisper-server.exe")
        assertNotNull("whisper/win-x64/whisper-server.exe is missing from the bundle", resource)
    }

    @Test
    fun `the bundled whisper server binary is covered by SHA256SUMS with a matching hash`() {
        val sums = winSums()
        assertTrue(
            "SHA256SUMS has no win-x64/whisper-server.exe entry, so the runtime would not verify it",
            sums.containsKey("whisper-server.exe"),
        )
        val bytes = javaClass.classLoader.getResource("whisper/win-x64/whisper-server.exe")!!
            .openStream().use { it.readBytes() }
        assertEquals(
            "the SHA256SUMS entry does not match the bundled whisper-server.exe",
            sums.getValue("whisper-server.exe"),
            sha256Hex(bytes),
        )
    }
}
