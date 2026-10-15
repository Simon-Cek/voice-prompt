package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.util.SystemInfo
import io.github.simoncek.voiceprompt.audio.AudioCapture
import io.github.simoncek.voiceprompt.audio.WavFile
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat

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

/**
 * Review P2: the long-lived `whisper-server` engine. The launch arguments and the multipart
 * body are pure and tested directly; the live end-to-end path (real bundled server binary + real
 * bundled model + the engine's HTTP request) is a separate, platform-gated test class.
 */
class WhisperServerArgsTest {

    private val server = Paths.get("/runtime", "whisper-server")
    private val model = Paths.get("/models", "ggml-tiny.en.bin")

    @Test
    fun `cpu mode adds --no-gpu and the thread count`() {
        val args = WhisperServerEngine.buildServerArgs(server, model, useGpu = false, threads = 8, port = 1234)
        assertEquals(
            listOf(
                server.toString(), "-m", model.toString(),
                "--host", "127.0.0.1", "--port", "1234",
                "-t", "8", "--no-gpu",
            ),
            args,
        )
    }

    @Test
    fun `gpu mode omits --no-gpu and -t for the default thread count`() {
        val args = WhisperServerEngine.buildServerArgs(server, model, useGpu = true, threads = 0, port = 1234)
        assertEquals(
            listOf(server.toString(), "-m", model.toString(), "--host", "127.0.0.1", "--port", "1234"),
            args,
        )
        assertFalse("--no-gpu must be absent for GPU mode", args.contains("--no-gpu"))
        assertFalse("-t must be absent for the default thread count", args.contains("-t"))
    }
}

class WhisperServerMultipartTest {

    @Test
    fun `body carries the wav bytes, every field, and a well-formed boundary`() {
        val wav = File.createTempFile("vp-server-test", ".wav")
        wav.deleteOnExit()
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        wav.writeBytes(payload)

        val body = WhisperServerEngine.buildMultipart(wav, "en")
        val text = String(body.bytes, Charsets.UTF_8)
        val boundary = body.contentType.substringAfter("boundary=")

        assertTrue("multipart content type", body.contentType.startsWith("multipart/form-data; boundary="))
        assertTrue("body starts with the boundary", text.startsWith("--$boundary\r\n"))
        assertTrue("body ends with the closing boundary", text.endsWith("--$boundary--\r\n"))
        assertTrue("response_format field", text.contains("name=\"response_format\""))
        assertTrue("no_timestamps field", text.contains("name=\"no_timestamps\""))
        assertTrue("suppress_nst field", text.contains("name=\"suppress_nst\""))
        assertTrue("language field", text.contains("name=\"language\""))
        assertTrue("response_format is text (plain-text reply, no JSON to parse)", text.contains("\r\n\r\ntext\r\n"))
        assertTrue("language value", text.contains("\r\n\r\nen\r\n"))
        // The audio bytes themselves must survive the framing.
        assertTrue("the wav bytes are in the body", containsBytes(body.bytes, payload))
    }

    /**
     * Regression for the framing bug that broke every dictation on the live server: the file part
     * must present BOTH of its headers before the blank line that closes the header block. A blank
     * line after the `Content-Disposition` line turns the `Content-Type` line into body data, the
     * WAV magic is no longer at offset 0, and whisper-server answers 400 "failed to decode audio
     * data" (verified against the bundled v1.9.5 binary: correct framing -> 200, this shape -> 400).
     */
    @Test
    fun `file part headers are contiguous with a single blank line before the audio`() {
        val wav = File.createTempFile("vp-server-test", ".wav")
        wav.deleteOnExit()
        val audio = "RIFFfakewav".encodeToByteArray()
        wav.writeBytes(audio)

        val body = WhisperServerEngine.buildMultipart(wav, "en")
        val text = String(body.bytes, Charsets.UTF_8)

        val fileHeaders = "Content-Disposition: form-data; name=\"file\"; filename=\"${wav.name}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        assertTrue(
            "the file part's two headers must be contiguous, closed by one blank line",
            text.contains(fileHeaders),
        )
        assertFalse(
            "no blank line may separate the file part's headers (that makes Content-Type body data)",
            text.contains("filename=\"${wav.name}\"\r\n\r\nContent-Type"),
        )
        // And the audio must immediately follow the closing blank line of the file part.
        assertTrue(
            "the audio bytes follow the file part's header block directly",
            text.contains(fileHeaders) && text.substringAfter(fileHeaders).startsWith(String(audio)),
        )
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}

/**
 * Review P2, end-to-end: the real bundled `whisper-server` binary, the real bundled model, and
 * the engine's actual HTTP path transcribing a silent clip. Skipped on platforms whose bundle
 * has no server binary (Windows for now) or without the bundled model on the classpath.
 */
class WhisperServerIntegrationTest {

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
    }

    private var workDir: Path? = null
    private var serverBinary: Path? = null
    private var model: Path? = null

    @Before
    fun setUp() {
        Assume.assumeTrue("supported platform", platformDir != "unsupported")
        Assume.assumeTrue(
            "bundled model on the classpath",
            javaClass.classLoader.getResource("models/ggml-tiny.en.bin") != null,
        )
        val dir = Files.createTempDirectory("whisper-server-test")
        dir.toFile().deleteOnExit()
        workDir = dir
        val runtime = WhisperRuntime.ensureRuntime(dir, version, platformDir)
        val candidate = runtime.cli.parent.resolve(
            if (SystemInfo.isWindows) "whisper-server.exe" else "whisper-server",
        )
        Assume.assumeTrue("whisper-server is bundled for this platform", Files.isRegularFile(candidate))
        serverBinary = candidate
        // The server loads the model from a file path: extract the classpath copy into the work dir.
        val modelFile = Files.createTempFile(dir, "ggml-tiny.en", ".bin")
        javaClass.classLoader.getResourceAsStream("models/ggml-tiny.en.bin")!!.use { input ->
            Files.copy(input, modelFile, StandardCopyOption.REPLACE_EXISTING)
        }
        model = modelFile
    }

    @After
    fun tearDown() {
        WhisperServerEngine.shutdown()
        workDir?.toFile()?.deleteRecursively()
    }

    /** 1 s of a 440 Hz sine — deterministic, and not on the hallucination blocklist. */
    private fun toneWav(): File {
        val sr = AudioCapture.SAMPLE_RATE
        val samples = ShortArray(sr) { i ->
            (12_000 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / sr)).toInt().toShort()
        }
        val wav = File(workDir!!.toFile(), "tone.wav")
        WavFile.write(wav, samples, AudioCapture.DEFAULT_FORMAT)
        return wav
    }

    private fun serverTranscribe(wav: File): String = WhisperServerEngine.transcribeInternal(
        wavFile = wav,
        model = model!!,
        language = WhisperLanguage.ENGLISH.code,
        timeoutMs = 90_000,
        useGpu = false,
        threads = 4,
        indicator = null,
        serverBinary = serverBinary!!,
        logFile = File(workDir!!.toFile(), "server.log"),
        // No CLI fallback: the server path alone must produce the answer, or the test fails.
        // This is what catches a malformed request body — the fallback would otherwise mask it.
        allowFallback = false,
    )

    @Test
    fun `a tone transcribes to non-blank text over the live server`() {
        val text = serverTranscribe(toneWav())
        assertFalse(
            "a real audio tone must decode and yield text on the server path, got: '$text'",
            text.isBlank(),
        )
    }

    @Test
    fun `a silent clip transcribes to blank text over the live server`() {
        val wav = File(workDir!!.toFile(), "silence.wav")
        WavFile.write(wav, ShortArray(AudioCapture.SAMPLE_RATE), AudioCapture.DEFAULT_FORMAT)
        val text = serverTranscribe(wav)
        assertTrue(
            "silence must come back blank (hallucination blocklist), got: '$text'",
            text.isBlank(),
        )
    }
}

/**
 * Review P3: "Automatic" runs the GPU build whenever a Vulkan backend is bundled, without
 * knowing a usable device exists — on a weak iGPU or a flaky driver the GPU run fails where
 * the CPU is fine. whisper-cli must therefore retry a non-zero exit once with `--no-gpu`, and
 * an explicit CPU failure must not retry at all.
 *
 * Driven with a fake CLI (a POSIX shell script standing in for whisper-cli) that honours
 * `--no-gpu` the way the real one does. The engine runs it with the runtime dir as its working
 * directory, so the script records each invocation in a `calllog` file right next to itself —
 * the tests assert exactly which runs happened.
 */
class WhisperCliGpuRetryTest {

    private var dir: Path? = null
    private var wav: File? = null

    @Before
    fun setUp() {
        Assume.assumeFalse("the fake CLI needs a POSIX shell", SystemInfo.isWindows)
        val d = Files.createTempDirectory("vp-cli-retry-test")
        d.toFile().deleteOnExit()
        dir = d
        val w = File(d.toFile(), "recording.wav")
        w.writeBytes(byteArrayOf(1, 2, 3, 4))
        w.deleteOnExit()
        wav = w
    }

    @After
    fun tearDown() {
        dir?.toFile()?.deleteRecursively()
    }

    /**
     * A whisper-cli stand-in. `gpuFails=true`: fails on the GPU, answers on the CPU.
     * `gpuFails=false`: fails regardless of the backend. Every invocation appends its
     * `--no-gpu` flag to `calllog` (the engine's working directory is the script's parent).
     * Shell variables are spliced via [D] so the template stays plain Kotlin interpolation.
     */
    private fun fakeCli(gpuFails: Boolean): Path {
        val D = '$'
        val body = if (gpuFails) """
            if [ "${D}nogpu" = "0" ]; then
              echo "ggml_vulkan: no device found" >&2
              exit 3
            fi
            echo "cpu says hi"
        """.trimIndent() else """
            echo "boom on both" >&2
            exit 7
        """.trimIndent()
        val script = dir!!.resolve("whisper-cli")
        Files.writeString(
            script,
            """
                #!/bin/sh
                nogpu=0
                for a in "${D}@"; do [ "${D}a" = "--no-gpu" ] && nogpu=1; done
                echo "nogpu=${D}nogpu" >> calllog
                $body
            """.trimIndent(),
        )
        val perms = Files.getPosixFilePermissions(script)
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(script, perms)
        return script
    }

    private fun calllogLines(): List<String> = Files.readAllLines(dir!!.resolve("calllog"))

    private fun transcribe(cli: Path, useGpu: Boolean): String =
        WhisperCliEngine().transcribeWithCli(cli, wav!!, Paths.get("/models/ggml-tiny.en.bin"), "en", 10_000, useGpu, 0, null)

    @Test
    fun `a gpu failure retries once with --no-gpu and returns the cpu result`() {
        val text = transcribe(fakeCli(gpuFails = true), useGpu = true)
        assertEquals("the cpu retry's answer must be returned", "cpu says hi", text)
        assertEquals(
            "exactly two runs: the failed GPU one, then the CPU retry",
            listOf("nogpu=0", "nogpu=1"),
            calllogLines(),
        )
    }

    @Test
    fun `a failure that survives the cpu retry reports both failures and does not loop`() {
        val failure = try {
            transcribe(fakeCli(gpuFails = false), useGpu = true)
            error("expected the GPU+CPU failures to throw")
        } catch (t: WhisperCliEngine.TranscriptionException) {
            t
        }
        assertTrue("the error must name the GPU failure: ${failure.message}", failure.message!!.contains("GPU (exit 7)"))
        assertTrue("the error must name the CPU failure: ${failure.message}", failure.message!!.contains("CPU"))
        assertEquals("the retry happens exactly once", listOf("nogpu=0", "nogpu=1"), calllogLines())
    }

    @Test
    fun `an explicit cpu failure is not retried`() {
        val failure = try {
            transcribe(fakeCli(gpuFails = false), useGpu = false)
            error("expected the CPU failure to throw")
        } catch (t: WhisperCliEngine.TranscriptionException) {
            t
        }
        assertTrue("the error must carry the exit code: ${failure.message}", failure.message!!.contains("exited with code 7"))
        assertEquals("an explicit CPU failure must not retry", listOf("nogpu=1"), calllogLines())
    }
}

/**
 * Review P3, server path: the GPU-backed whisper-server can fail a request on a weak iGPU or
 * flaky driver. The engine must restart the server on CPU (`--no-gpu`) and retry the utterance
 * once — not drop straight to the CLI (which would re-load the model and lose the warm
 * process).
 *
 * Driven with a fake server (a python3 HTTP stand-in for whisper-server) that honours
 * `--no-gpu` the way the real one does. The engine starts and queries the fake with its real
 * launch + request path, so this exercises the real retry orchestration; `allowFallback=false`
 * keeps the CLI from masking anything (the lesson of the P2 framing bug).
 */
class WhisperServerGpuRetryTest {

    private var dir: Path? = null
    private var wav: File? = null

    @Before
    fun setUp() {
        Assume.assumeFalse("the fake server needs a POSIX shell", SystemInfo.isWindows)
        Assume.assumeTrue(
            "python3 is available for the fake server",
            runCatching { ProcessBuilder("python3", "--version").start().destroy() }.isSuccess,
        )
        val d = Files.createTempDirectory("vp-server-retry-test")
        d.toFile().deleteOnExit()
        dir = d
        val w = File(d.toFile(), "recording.wav")
        w.writeBytes(byteArrayOf(1, 2, 3, 4))
        w.deleteOnExit()
        wav = w
    }

    @After
    fun tearDown() {
        WhisperServerEngine.shutdown()
        dir?.toFile()?.deleteRecursively()
    }

    /**
     * A whisper-server stand-in: `/health` answers 200 once up (like the real server after
     * model load); `/inference` honours the `--no-gpu` launch flag, failing or answering
     * exactly as the test configures. Every start appends a `FAKE-SERVER` line to `starts.log`
     * in its working directory (the engine runs the server with the binary's parent dir as
     * the working directory) — the tests read the start sequence from that file, because the
     * engine's own log file is truncated on each launch (JDK 25 `RedirectOutput` semantics).
     */
    private fun fakeServer(gpuOk: Boolean, cpuOk: Boolean): Path {
        val python = """
            #!/usr/bin/env python3
            import sys
            from http.server import BaseHTTPRequestHandler, HTTPServer

            args = sys.argv[1:]
            no_gpu = "--no-gpu" in args
            port = int(args[args.index("--port") + 1])
            GPU_OK = {gpuOk}
            CPU_OK = {cpuOk}

            with open("starts.log", "a") as f:
                f.write("FAKE-SERVER gpu=" + ("false" if no_gpu else "true") + "\n")

            class Handler(BaseHTTPRequestHandler):
                def _send(self, code, body):
                    data = body.encode()
                    self.send_response(code)
                    self.send_header("Content-Type", "text/plain")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)

                def do_GET(self):
                    if self.path == "/health":
                        self._send(200, '{"status": "ok"}')
                    else:
                        self._send(404, "not found")

                def do_POST(self):
                    self.rfile.read(int(self.headers.get("Content-Length") or 0))
                    if self.path != "/inference":
                        self._send(404, "not found")
                    elif no_gpu:
                        self._send(200 if CPU_OK else 500, "cpu says hi" if CPU_OK else "cpu broken")
                    else:
                        self._send(200 if GPU_OK else 500, "gpu says hi" if GPU_OK else "gpu broken")

                def log_message(self, *args):
                    pass

            HTTPServer(("127.0.0.1", port), Handler).serve_forever()
        """.trimIndent()
            .replace("{gpuOk}", if (gpuOk) "True" else "False")
            .replace("{cpuOk}", if (cpuOk) "True" else "False")
        val script = dir!!.resolve("whisper-server")
        Files.writeString(script, python)
        val perms = Files.getPosixFilePermissions(script)
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(script, perms)
        return script
    }

    private fun transcribe(binary: Path, useGpu: Boolean): String = WhisperServerEngine.transcribeInternal(
        wavFile = wav!!,
        model = Paths.get("/models/ggml-tiny.en.bin"),
        language = WhisperLanguage.ENGLISH.code,
        timeoutMs = 30_000,
        useGpu = useGpu,
        threads = 0,
        indicator = null,
        serverBinary = binary,
        logFile = File(dir!!.toFile(), "server.log"),
        // No CLI fallback: the server-side retry alone must produce the answer or throw,
        // so the CLI cannot mask what the server path actually did (the lesson of P2).
        allowFallback = false,
    )

    private fun startSequence(): List<String> =
        Files.readAllLines(File(dir!!.toFile(), "starts.log").toPath())
            .filter { it.startsWith("FAKE-SERVER") }

    @Test
    fun `a gpu request failure restarts the server on cpu and returns the cpu result`() {
        val text = transcribe(fakeServer(gpuOk = false, cpuOk = true), useGpu = true)
        assertEquals("the cpu retry's answer must be returned", "cpu says hi", text)
        assertEquals(
            "the GPU server runs, fails, and a CPU server takes over",
            listOf("FAKE-SERVER gpu=true", "FAKE-SERVER gpu=false"),
            startSequence(),
        )
    }

    @Test
    fun `a failure that survives the cpu restart is reported and does not loop`() {
        val failure = try {
            transcribe(fakeServer(gpuOk = false, cpuOk = false), useGpu = true)
            error("expected the GPU+CPU failures to throw")
        } catch (t: WhisperCliEngine.TranscriptionException) {
            t
        }
        assertTrue("the error must carry the server's HTTP failure: ${failure.message}", failure.message!!.contains("HTTP 500"))
        assertEquals(
            "exactly one CPU restart, no retry loop",
            listOf("FAKE-SERVER gpu=true", "FAKE-SERVER gpu=false"),
            startSequence(),
        )
    }

    @Test
    fun `an explicit cpu run does not restart`() {
        val text = transcribe(fakeServer(gpuOk = false, cpuOk = true), useGpu = false)
        assertEquals("the cpu server's answer must be returned", "cpu says hi", text)
        assertEquals("a CPU run starts exactly one CPU server", listOf("FAKE-SERVER gpu=false"), startSequence())
    }
}
