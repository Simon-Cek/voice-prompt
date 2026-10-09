package com.example.copilotvoice.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

class WhisperRuntimeTest {

    @Test
    fun `derives soname symlinks from versioned library names`() {
        val realFiles = listOf(
            "whisper-cli",
            "libwhisper.so.1.9.5",
            "libggml.so.0.26.0",
            "libggml-base.so.0.26.0",
            "libggml-cpu-sse42.so",
        )
        assertEquals(
            mapOf(
                "libwhisper.so.1" to "libwhisper.so.1.9.5",
                "libggml.so.0" to "libggml.so.0.26.0",
                "libggml-base.so.0" to "libggml-base.so.0.26.0",
            ),
            WhisperRuntime.sonameSymlinks(realFiles),
        )
    }

    @Test
    fun `ignores executables and already-sonamed files`() {
        assertEquals(emptyMap<String, String>(), WhisperRuntime.sonameSymlinks(listOf("whisper-cli", "libfoo.so")))
    }
}

class WhisperCliEngineTest {

    private val cli: Path = Paths.get("/opt/copilot-voice/runtime/b5454/linux-x64/whisper-cli")
    private val model: Path = Paths.get("/home/user/.cache/models/ggml-base.en.bin")

    @Test
    fun `builds whisper-cli arguments`() {
        assertEquals(
            listOf(
                "/opt/copilot-voice/runtime/b5454/linux-x64/whisper-cli",
                "-m", "/home/user/.cache/models/ggml-base.en.bin",
                "-f", "/tmp/recording.wav",
                "-nt",
                "-l", "en",
                "--no-gpu",
            ),
            WhisperCliEngine().buildArguments(cli, java.io.File("/tmp/recording.wav"), model, "en"),
        )
    }

    @Test
    fun `uses the requested language`() {
        val args = WhisperCliEngine().buildArguments(cli, java.io.File("/tmp/a.wav"), model, "de")
        assertEquals("de", args[args.indexOf("-l") + 1])
    }

    @Test
    fun `forces cpu with --no-gpu when gpu is disabled`() {
        val args = WhisperCliEngine().buildArguments(cli, java.io.File("/tmp/recording.wav"), model, "en", useGpu = false)
        assertTrue(args.contains("--no-gpu"))
    }

    @Test
    fun `omits --no-gpu when gpu is requested`() {
        val args = WhisperCliEngine().buildArguments(cli, java.io.File("/tmp/recording.wav"), model, "en", useGpu = true)
        assertFalse(args.contains("--no-gpu"))
    }

    @Test
    fun `passes the resolved thread count with -t`() {
        val args = WhisperCliEngine().buildArguments(cli, java.io.File("/tmp/recording.wav"), model, "en", useGpu = true, threads = 8)
        assertEquals("8", args[args.indexOf("-t") + 1])
    }

    @Test
    fun `omits -t when no thread count is set`() {
        val args = WhisperCliEngine().buildArguments(cli, java.io.File("/tmp/recording.wav"), model, "en")
        assertFalse(args.contains("-t"))
    }
}

class WhisperRuntimeThreadsTest {

    @Test
    fun `physical threads stay within the logical range`() {
        val logical = WhisperRuntime.availableThreads()
        val physical = WhisperRuntime.physicalThreads()
        assertTrue("physical >= 1", physical >= 1)
        assertTrue("physical <= logical ($physical <= $logical)", physical <= logical)
    }
}

class WhisperLanguageTest {

    private val cli: Path = Paths.get("/opt/copilot-voice/runtime/b5454/linux-x64/whisper-cli")
    private val model: Path = Paths.get("/home/user/.cache/models/ggml-base.en.bin")

    @Test
    fun `auto is the default detection mode`() {
        assertEquals("auto", WhisperLanguage.AUTO.code)
        assertEquals(WhisperLanguage.AUTO, WhisperLanguage.fromCode("auto"))
    }

    @Test
    fun `fromCode falls back to auto for unknown codes`() {
        assertEquals(WhisperLanguage.AUTO, WhisperLanguage.fromCode("xx"))
    }

    @Test
    fun `passes the chosen language to the cli`() {
        val args = WhisperCliEngine().buildArguments(cli, java.io.File("/tmp/a.wav"), model, WhisperLanguage.SPANISH.code)
        assertEquals("es", args[args.indexOf("-l") + 1])
    }
}

class ModelManagerTest {

    @Test
    fun `english-only models are flagged by the en suffix`() {
        assertTrue(ModelManager.isEnglishOnly("base.en"))
        assertTrue(ModelManager.isEnglishOnly("tiny.en"))
    }

    @Test
    fun `multilingual models are not english-only`() {
        assertFalse(ModelManager.isEnglishOnly("base"))
        assertFalse(ModelManager.isEnglishOnly("small"))
    }
}
