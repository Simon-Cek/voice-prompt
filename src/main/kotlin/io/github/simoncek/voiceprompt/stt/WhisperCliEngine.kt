package io.github.simoncek.voiceprompt.stt

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class WhisperCliEngine {

    private val log = Logger.getInstance(WhisperCliEngine::class.java)

    class TranscriptionException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Transcribes [wavFile] with a one-shot whisper-cli run.
     *
     * Review P3: with the GPU on (the "Automatic" default whenever a Vulkan backend is bundled)
     * a weak iGPU or a flaky driver can make the run fail where the same audio transcribes fine
     * on CPU. A non-zero exit with the GPU on therefore retries the utterance once with
     * `--no-gpu`; a timeout or a user cancellation does not retry (the second run would just
     * time out / cancel again).
     */
    fun transcribe(
        wavFile: File,
        model: Path,
        language: String = WhisperLanguage.AUTO.code,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        useGpu: Boolean = false,
        threads: Int = 0,
        /** The task's indicator: its Cancel button aborts the run and kills the process. Null for unattended calls. */
        indicator: ProgressIndicator? = null,
    ): String = runOnce(WhisperRuntime.cliPath(), wavFile, model, language, timeoutMs, useGpu, threads, indicator)

    /**
     * Test seam for [transcribe]: the same path against an explicit CLI binary, so the review-P3
     * retry tests can drive a fake CLI that fails on the GPU and answers on the CPU.
     */
    internal fun transcribeWithCli(
        cli: Path,
        wavFile: File,
        model: Path,
        language: String,
        timeoutMs: Long,
        useGpu: Boolean,
        threads: Int,
        indicator: ProgressIndicator?,
    ): String = runOnce(cli, wavFile, model, language, timeoutMs, useGpu, threads, indicator)

    /** One whisper-cli invocation; the review-P3 GPU→CPU retry lives at its non-zero-exit point. */
    private fun runOnce(
        cli: Path,
        wavFile: File,
        model: Path,
        language: String,
        timeoutMs: Long,
        useGpu: Boolean,
        threads: Int,
        indicator: ProgressIndicator?,
    ): String {
        val commandLine = GeneralCommandLine(buildArguments(cli, wavFile, model, language, useGpu, threads))
            .withWorkDirectory(WhisperRuntime.runtimeDir(cli))
        if (SystemInfo.isLinux || SystemInfo.isMac) {
            commandLine.withEnvironment("LD_LIBRARY_PATH", WhisperRuntime.runtimeDir(cli))
        }

        val handler = try {
            CapturingProcessHandler(commandLine)
        } catch (t: ExecutionException) {
            log.warn("failed to start whisper-cli at $cli", t)
            throw TranscriptionException("Could not start whisper-cli at $cli", t)
        }

        // Track the live handler so DictationSession.dispose() can kill a whisper-cli that is
        // still running when the IDE shuts down or the plugin is unloaded (review #6).
        activeHandlers += handler
        try {
            // CapturingProcessHandler reads stdout/stderr concurrently, so a process that hangs with
            // its streams open cannot block us past [timeoutMs]; on timeout or on cancellation via
            // [indicator] it destroys the process and flags the output accordingly.
            val output = if (indicator != null) {
                handler.runProcessWithProgressIndicator(indicator, timeoutMs.toInt())
            } else {
                handler.runProcess(timeoutMs.toInt())
            }

            if (output.isTimeout) {
                throw TranscriptionException("whisper-cli timed out after ${timeoutMs} ms")
            }
            if (output.isCancelled) {
                throw TranscriptionException("whisper-cli was cancelled")
            }
            val exitCode = output.exitCode
            val stderr = output.stderr.trim()
            log.info("whisper-cli exit=$exitCode stdoutLen=${output.stdout.length} stderr='${stderr.take(300)}'")
            if (exitCode != 0) {
                if (useGpu) {
                    // Review P3: the GPU run failed (weak iGPU / flaky driver); the CPU run of the
                    // same utterance may still work. One retry, then a combined error below.
                    log.warn("whisper-cli exited $exitCode on the GPU; retrying once with --no-gpu")
                    return try {
                        runOnce(cli, wavFile, model, language, timeoutMs, false, threads, indicator)
                    } catch (cpuFailure: TranscriptionException) {
                        throw TranscriptionException(
                            "whisper-cli failed on the GPU (exit $exitCode) and again on the CPU: ${cpuFailure.message}",
                            cpuFailure,
                        )
                    }
                }
                val detail = stderr.lineSequence().toList().takeLast(5).joinToString(" | ")
                throw TranscriptionException("whisper-cli exited with code $exitCode${if (detail.isNotEmpty()) ": $detail" else ""}")
            }
            return normalizeTranscript(output.stdout)
        } finally {
            activeHandlers -= handler
        }
    }

    /**
     * whisper.cpp (build b5454 and later) prints the special token `[BLANK_AUDIO]` for segments
     * with no speech, one per silent segment. Drop the token (and lines left empty by it) so a
     * silent clip returns empty text — the condition callers use to show the "no speech" balloon —
     * while a partly-silent clip keeps its transcribed lines.
     *
     * Whisper also hallucinates short phrases on silence and noise ("Thank you.", "you",
     * "(music)", …, issue #8). `--suppress-nst` handles most of that at inference time; this
     * blocklist is the backstop for anything that still slips through. Only a transcript made up
     * *entirely* of such phrases is dropped — "Thank you for the help" must survive.
     */
    internal fun normalizeTranscript(raw: String): String {
        val text = raw.lineSequence()
            .map { line -> line.replace(BLANK_AUDIO_TOKEN, " ").replace(MULTIPLE_SPACES, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return if (isHallucination(text)) "" else text
    }

    /** True when every line of [text] is a known non-speech hallucination (issue #8). */
    private fun isHallucination(text: String): Boolean {
        val lines = text.lineSequence()
            .map { it.trim().trimEnd('.', '!', '?', ' ').lowercase().trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return lines.isNotEmpty() && lines.all { it in HALLUCINATION_PHRASES }
    }

    fun buildArguments(cli: Path, wavFile: File, model: Path, language: String, useGpu: Boolean = false, threads: Int = 0): List<String> {
        val base = listOf(
            cli.toString(),
            "-m", model.toString(),
            "-f", wavFile.absolutePath,
            "-nt",
            "-l", language,
            // Suppress whisper's non-speech tokens like "(music)" on silence/noise (issue #8);
            // the HALLUCINATION_PHRASES blocklist in normalizeTranscript() is the backstop.
            // Verified present in the bundled whisper-cli (build b5454).
            "--suppress-nst",
        )
        // whisper.cpp defaults to just 4 threads; pass -t only when a concrete count is resolved.
        val withThreads = if (threads > 0) base + listOf("-t", threads.toString()) else base
        // whisper.cpp runs on the GPU by default; pass --no-gpu to force CPU-only inference.
        return if (useGpu) withThreads else withThreads + listOf("--no-gpu")
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        private val BLANK_AUDIO_TOKEN = Regex("\\[BLANK_AUDIO\\]", RegexOption.IGNORE_CASE)
        private val MULTIPLE_SPACES = Regex(" {2,}")

        /**
         * Non-speech output whisper emits on silence or noise (issue #8): its non-speech tags and
         * the short filler phrases the review names ("Thank you.", "you"). Compared
         * case-insensitively, with trailing punctuation stripped, against each whole line (see
         * [isHallucination]). Deliberately short: one-word replies like "ok" are legit dictation
         * and must survive (see the blank-audio tests).
         */
        private val HALLUCINATION_PHRASES = setOf(
            "(music)", "[music]",
            "(singing)", "[singing]",
            "(applause)", "[applause]",
            "(laughter)", "[laughter]",
            "(silence)", "[silence]",
            "(speech noise)", "[speech noise]",
            "thank you", "you",
        )

        /** whisper-cli handlers that are running right now (concurrent: more than one may transcribe). */
        private val activeHandlers: MutableSet<CapturingProcessHandler> =
            Collections.newSetFromMap(ConcurrentHashMap())

        /**
         * Kills every whisper-cli process still running. Called from [io.github.simoncek.voiceprompt.actions.DictationSession]
         * on dispose (IDE shutdown, dynamic plugin unload) so no inference process outlives the
         * plugin (review #6). Safe to call when nothing is running.
         */
        fun killActiveProcesses() {
            for (handler in activeHandlers) {
                runCatching { handler.destroyProcess() }
            }
            activeHandlers.clear()
        }
    }
}
