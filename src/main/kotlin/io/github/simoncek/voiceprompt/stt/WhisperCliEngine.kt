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

    fun transcribe(
        wavFile: File,
        model: Path,
        language: String = WhisperLanguage.AUTO.code,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        useGpu: Boolean = false,
        threads: Int = 0,
        /** The task's indicator: its Cancel button aborts the run and kills the process. Null for unattended calls. */
        indicator: ProgressIndicator? = null,
    ): String {
        val cli = WhisperRuntime.cliPath()
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
     */
    internal fun normalizeTranscript(raw: String): String =
        raw.lineSequence()
            .map { line -> line.replace(BLANK_AUDIO_TOKEN, " ").replace(MULTIPLE_SPACES, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    fun buildArguments(cli: Path, wavFile: File, model: Path, language: String, useGpu: Boolean = false, threads: Int = 0): List<String> {
        val base = listOf(
            cli.toString(),
            "-m", model.toString(),
            "-f", wavFile.absolutePath,
            "-nt",
            "-l", language,
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
