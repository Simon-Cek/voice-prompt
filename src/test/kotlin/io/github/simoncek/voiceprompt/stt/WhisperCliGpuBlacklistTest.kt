package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Review N9, CLI path (the only path on Windows): a CONFIRMED GPU failure — a GPU run failed
 * and the CPU run of the same utterance succeeded — is remembered per model, so the NEXT call
 * starts with `--no-gpu` on its very first attempt. The fail-and-retry dance happens once, not
 * on every utterance. A failure that survives the CPU retry is not the GPU's fault: no
 * blacklist, the error goes up, and the next call still tries the GPU.
 *
 * Driven with a fake CLI (a POSIX shell script standing in for whisper-cli) that records each
 * invocation's `--no-gpu` flag in `calllog`; the tests assert exactly which runs happened.
 */
class WhisperCliGpuBlacklistTest {

    private var dir: Path? = null
    private var wav: File? = null

    /** Distinct models so one test's blacklist entry can never shadow another test's. */
    private val modelA = Paths.get("/models/ggml-medium.en.bin")
    private val modelB = Paths.get("/models/ggml-small.en.bin")

    /** The Processing settings for most tests, plus a "changed" variant (a different thread count). */
    private val key = GpuHealth.processingKey("AUTOMATIC", 0)
    private val keyChanged = GpuHealth.processingKey("AUTOMATIC", 4)

    @Before
    fun setUp() {
        Assume.assumeFalse("the fake CLI needs a POSIX shell", SystemInfo.isWindows)
        val d = Files.createTempDirectory("vp-cli-blacklist-test")
        d.toFile().deleteOnExit()
        dir = d
        val w = File(d.toFile(), "recording.wav")
        w.writeBytes(byteArrayOf(1, 2, 3, 4))
        w.deleteOnExit()
        wav = w
        GpuHealth.clearForTest()
    }

    @After
    fun tearDown() {
        GpuHealth.clearForTest()
        dir?.toFile()?.deleteRecursively()
    }

    /**
     * A whisper-cli stand-in. `gpuFailsFor` names the model whose GPU run fails (`""` = every
     * model's GPU run fails): it exits 3 on that GPU run and answers on the CPU. Every
     * invocation appends its `--no-gpu` flag to `calllog` (the engine's working directory is
     * the script's parent). Shell variables are spliced via [D] so the template stays plain.
     */
    private fun fakeCli(gpuFailsFor: String): Path {
        val D = '$'
        val body = if (gpuFailsFor.isEmpty()) """
            if [ "${D}nogpu" = "0" ]; then
              echo "ggml_vulkan: no device found" >&2
              exit 3
            fi
            echo "cpu says hi"
        """.trimIndent() else """
            if [ "${D}nogpu" = "0" ]; then
              case "${D}model" in
                *$gpuFailsFor*) echo "ggml_vulkan: no device found" >&2; exit 3;;
              esac
              echo "gpu says hi"
            else
              echo "cpu says hi"
            fi
        """.trimIndent()
        val script = dir!!.resolve("whisper-cli")
        Files.writeString(
            script,
            """
                #!/bin/sh
                nogpu=0
                model=""
                prev=""
                for a in "${D}@"; do
                  [ "${D}a" = "--no-gpu" ] && nogpu=1
                  [ "${D}prev" = "-m" ] && model="${D}a"
                  prev="${D}a"
                done
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

    private fun transcribe(cli: Path, model: Path, useGpu: Boolean, processingKey: String): String =
        WhisperCliEngine().transcribeWithCli(cli, wav!!, model, "en", 10_000, useGpu, 0, null, processingKey)

    @Test
    fun `a confirmed gpu failure blacklists the model so the next call starts on the cpu`() {
        val cli = fakeCli(gpuFailsFor = "")
        assertEquals("the CPU retry's answer must be returned", "cpu says hi", transcribe(cli, modelA, true, key))
        assertTrue("a confirmed GPU failure must blacklist the model", GpuHealth.isBlacklisted(modelA, key))
        assertEquals(
            "first call: the failed GPU run, then the CPU retry",
            listOf("nogpu=0", "nogpu=1"),
            calllogLines(),
        )

        assertEquals("cpu says hi", transcribe(cli, modelA, true, key))
        assertEquals(
            "the next call must pass --no-gpu on its FIRST attempt — no GPU run at all",
            listOf("nogpu=0", "nogpu=1", "nogpu=1"),
            calllogLines(),
        )
    }

    @Test
    fun `another model is not blacklisted`() {
        val cli = fakeCli(gpuFailsFor = "ggml-medium")
        assertEquals("the CPU retry answers for modelA", "cpu says hi", transcribe(cli, modelA, true, key))
        assertTrue("modelA has a confirmed failure", GpuHealth.isBlacklisted(modelA, key))

        // modelB has no confirmed failure: its first attempt is still the GPU one (which works).
        assertEquals("the GPU answers for modelB", "gpu says hi", transcribe(cli, modelB, true, key))
        assertFalse("modelB has no confirmed failure", GpuHealth.isBlacklisted(modelB, key))
    }

    @Test
    fun `a failure that survives the cpu retry blacklists nothing and retries the gpu again`() {
        val cli = dir!!.resolve("whisper-cli")
        val D = '$'
        Files.writeString(
            cli,
            """
                #!/bin/sh
                nogpu=0
                for a in "${D}@"; do [ "${D}a" = "--no-gpu" ] && nogpu=1; done
                echo "nogpu=${D}nogpu" >> calllog
                echo "boom on both" >&2
                exit 7
            """.trimIndent(),
        )
        val perms = Files.getPosixFilePermissions(cli)
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(cli, perms)

        try {
            transcribe(cli, modelA, true, key)
            error("expected the GPU+CPU failures to throw")
        } catch (t: WhisperCliEngine.TranscriptionException) {
            assertTrue("the error must name the GPU failure: ${t.message}", t.message!!.contains("GPU (exit 7)"))
        }
        assertFalse("a failure on both backends is not a GPU problem", GpuHealth.isBlacklisted(modelA, key))

        // The next call must still try the GPU first (no blacklist to skip it).
        try {
            transcribe(cli, modelA, true, key)
            error("expected the GPU+CPU failures to throw again")
        } catch (t: WhisperCliEngine.TranscriptionException) {
            assertTrue(t.message!!.contains("GPU (exit 7)"))
        }
        assertEquals(
            "two calls, each GPU-then-CPU — nothing remembered, nothing skipped",
            listOf("nogpu=0", "nogpu=1", "nogpu=0", "nogpu=1"),
            calllogLines(),
        )
    }

    @Test
    fun `a changed processing setting clears the blacklist`() {
        val cli = fakeCli(gpuFailsFor = "")
        assertEquals("the CPU retry answers", "cpu says hi", transcribe(cli, modelA, true, key))
        assertTrue("blacklisted under the original settings", GpuHealth.isBlacklisted(modelA, key))

        // The user changes the thread count: the GPU must be tried again for the same model…
        assertEquals("…and the CPU answers again", "cpu says hi", transcribe(cli, modelA, true, keyChanged))
        assertTrue("the failure is confirmed again under the NEW settings", GpuHealth.isBlacklisted(modelA, keyChanged))
        assertFalse("the old settings key is no longer active", GpuHealth.isBlacklisted(modelA, key))
        assertEquals(
            "both calls tried the GPU first",
            listOf("nogpu=0", "nogpu=1", "nogpu=0", "nogpu=1"),
            calllogLines(),
        )
    }
}
