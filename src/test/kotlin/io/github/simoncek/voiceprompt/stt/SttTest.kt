package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors

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
                "--suppress-nst",
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

    @Test
    fun `strips the blank-audio token so a silent clip is empty`() {
        assertEquals("", WhisperCliEngine().normalizeTranscript("[BLANK_AUDIO]"))
        assertEquals("", WhisperCliEngine().normalizeTranscript("[BLANK_AUDIO]\n[BLANK_AUDIO]"))
        assertEquals("", WhisperCliEngine().normalizeTranscript(""))
    }

    @Test
    fun `keeps the transcribed lines of a partly-silent clip`() {
        assertEquals("hello world", WhisperCliEngine().normalizeTranscript("[BLANK_AUDIO]\nhello world"))
        assertEquals("hello\nworld", WhisperCliEngine().normalizeTranscript("hello\n[BLANK_AUDIO]\nworld"))
    }

    @Test
    fun `blank-audio token matching is case-insensitive`() {
        assertEquals("ok", WhisperCliEngine().normalizeTranscript("[blank_audio] ok"))
    }

    @Test
    fun `treats a whole hallucination as no speech`() {
        val engine = WhisperCliEngine()
        assertEquals("", engine.normalizeTranscript("Thank you."))
        assertEquals("", engine.normalizeTranscript("you"))
        assertEquals("", engine.normalizeTranscript("(music)"))
        assertEquals("", engine.normalizeTranscript("[Music]"))
        assertEquals("", engine.normalizeTranscript("(singing)"))
        assertEquals("", engine.normalizeTranscript("(speech noise)"))
        assertEquals("", engine.normalizeTranscript("(applause)\n(laughter)"))
        assertEquals("", engine.normalizeTranscript("you\nyou"))
    }

    @Test
    fun `keeps hallucinated phrases inside a real transcript`() {
        val engine = WhisperCliEngine()
        assertEquals("Thank you for the help", engine.normalizeTranscript("Thank you for the help"))
        assertEquals("you are welcome", engine.normalizeTranscript("you are welcome"))
        assertEquals("OK let's start", engine.normalizeTranscript("OK let's start"))
        assertEquals("hello", engine.normalizeTranscript("hello"))
    }
}

class WhisperRuntimeThreadsTest {

    @Test
    fun `default threads stay within the logical range`() {
        val logical = WhisperRuntime.availableThreads()
        val def = WhisperRuntime.defaultThreads()
        assertTrue("default >= 1", def >= 1)
        assertTrue("default <= logical ($def <= $logical)", def <= logical)
    }
}

/**
 * Review P1: [WhisperRuntime.cliPath] used to SHA-256 every bundled library (~50–100 MB on
 * Linux) on every dictation. The runtime must now be validated once (a version-keyed marker in
 * the runtime dir), cached for the session, and extraction must be guarded by a lock so two
 * concurrent transcriptions can't race. These tests drive the real [WhisperRuntime.ensureRuntime]
 * against the real bundled resources into a temp dir; whichever test resolves first pays the
 * one-time extraction, the rest exercise the cheap marker fast path.
 */
class WhisperRuntimeResolveTest {

    companion object {
        private val platformDir: String = when {
            SystemInfo.isWindows -> "win-x64"
            SystemInfo.isLinux -> "linux-x64"
            else -> "unsupported"
        }

        private val version: String =
            javaClass.classLoader.getResource("whisper/WHISPER_VERSION.txt")
                ?.readText()?.trim()
                ?: error("whisper/WHISPER_VERSION.txt is missing from the test classpath")

        /** Shared by most tests: extracted once per test class, then mutated per scenario. */
        private val sharedTarget: Path by lazy {
            val dir = Files.createTempDirectory("whisper-runtime-test")
            dir.toFile().deleteOnExit()
            dir
        }
    }

    private fun resolved(): Path =
        WhisperRuntime.ensureRuntime(sharedTarget, version, platformDir).cli

    /** The first bundled file other than the CLI, with its manifest checksum. */
    private fun sacrificialFile(): Pair<Path, String> {
        val entries = javaClass.classLoader.getResourceAsStream("whisper/SHA256SUMS")!!
            .bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.trim().split(Regex("\\s+"), limit = 2) }
                    .filter { it.size == 2 && it[1].startsWith("$platformDir/") }
                    .map { it[1].removePrefix("$platformDir/") to it[0].lowercase() }
                    .toList()
            }
        val (name, sha) = entries.first {
            !it.first.equals("whisper-cli", ignoreCase = true) && !it.first.endsWith(".exe")
        }
        return sharedTarget.resolve(name) to sha
    }

    @Test
    fun `resolves the runtime and writes the version marker`() {
        Assume.assumeTrue("supported platform", platformDir != "unsupported")
        val cli = resolved()
        assertTrue("whisper-cli must exist after resolution", Files.isRegularFile(cli))
        if (!SystemInfo.isWindows) {
            val perms = Files.getPosixFilePermissions(cli)
            assertTrue("whisper-cli must be executable", perms.contains(PosixFilePermission.OWNER_EXECUTE))
        }
        val marker = sharedTarget.resolve(".validated")
        assertTrue("the version marker must exist after resolution", Files.isRegularFile(marker))
        assertEquals("the marker must carry the whisper version", version, Files.readString(marker).trim())
    }

    @Test
    fun `does not re-hash when the marker and all files are present`() {
        Assume.assumeTrue("supported platform", platformDir != "unsupported")
        resolved() // extract (or reuse) the shared runtime
        val (file, expected) = sacrificialFile()
        // Corrupt the file in place: name and presence survive, only the checksum is wrong.
        Files.write(file, byteArrayOf(1, 2), StandardOpenOption.APPEND)
        assertTrue("precondition: the file is now corrupt", WhisperRuntime.sha256(file) != expected)
        resolved()
        // The marker fast path must NOT detect the corruption — that would mean it re-hashed.
        assertTrue(
            "a corrupt-but-present file must not be re-downloaded (no re-hash on the fast path)",
            WhisperRuntime.sha256(file) != expected,
        )
    }

    @Test
    fun `restores a deleted file on the next resolution`() {
        Assume.assumeTrue("supported platform", platformDir != "unsupported")
        resolved()
        val (file, expected) = sacrificialFile()
        Files.deleteIfExists(file)
        resolved()
        assertTrue("a deleted file must be restored", Files.isRegularFile(file))
        assertEquals("the restored file must match the manifest", expected, WhisperRuntime.sha256(file))
    }

    @Test
    fun `a stale marker forces a full revalidation`() {
        Assume.assumeTrue("supported platform", platformDir != "unsupported")
        resolved()
        val marker = sharedTarget.resolve(".validated")
        Files.writeString(marker, "bogus-version")
        resolved()
        assertEquals("a stale marker must be rewritten with the real version", version, Files.readString(marker).trim())
    }

    @Test
    fun `concurrent resolutions are serialized and agree`() {
        Assume.assumeTrue("supported platform", platformDir != "unsupported")
        val dir = Files.createTempDirectory("whisper-runtime-concurrency")
        dir.toFile().deleteOnExit()
        val results = Collections.synchronizedList(mutableListOf<Path>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                executor.submit {
                    results.add(WhisperRuntime.ensureRuntime(dir, version, platformDir).cli)
                }
            }
            futures.forEach { future ->
                try {
                    future.get()
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        } finally {
            executor.shutdownNow()
        }
        assertTrue("no thread may fail: $errors", errors.isEmpty())
        assertEquals("every thread must resolve the same CLI", 1, results.toSet().size)
        assertTrue("the marker must be valid afterwards", Files.isRegularFile(dir.resolve(".validated")))
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

    @Test
    fun `the tiny model ships inside the plugin`() {
        assertEquals("tiny.en", ModelManager.BUNDLED_MODEL)
        assertTrue("bundled model resource missing from the classpath", ModelManager.bundledModelAvailable())
    }

    @Test
    fun `the bundled model checksum matches the shipped file`() {
        val expected = ModelManager.bundledSha256()
        assertNotNull("models/SHA256SUMS must list the bundled model", expected)
        val resource = javaClass.classLoader.getResourceAsStream("models/ggml-${ModelManager.BUNDLED_MODEL}.bin")
        assertNotNull("bundled model resource missing", resource)
        val digest = MessageDigest.getInstance("SHA-256")
        resource!!.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `parseSha256Sums finds the checksum for the requested file`() {
        val sums = """
            abc123  win-x64/whisper-cli.exe
            DEF456  ggml-base.en.bin
            000000  ggml-small.en.bin
        """.trimIndent()
        assertEquals("def456", ModelManager.parseSha256Sums(sums, "ggml-base.en.bin"))
    }

    @Test
    fun `parseSha256Sums is case-insensitive on the file name`() {
        val sums = "abc123  ggml-base.EN.bin"
        assertEquals("abc123", ModelManager.parseSha256Sums(sums, "ggml-base.en.bin"))
    }

    @Test
    fun `parseSha256Sums returns null when the file is not listed`() {
        val sums = "abc123  ggml-base.en.bin"
        assertEquals(null, ModelManager.parseSha256Sums(sums, "ggml-huge.bin"))
    }

    @Test
    fun `parseSha256Sums ignores malformed lines`() {
        val sums = """
            not-a-checksum
            abc123
            abc123  ggml-base.en.bin
        """.trimIndent()
        assertEquals("abc123", ModelManager.parseSha256Sums(sums, "ggml-base.en.bin"))
    }
}
