package com.example.copilotvoice.stt

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class WhisperCliEngine {

    private val log = Logger.getInstance(WhisperCliEngine::class.java)

    class TranscriptionException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun transcribe(wavFile: File, model: Path, language: String = WhisperLanguage.AUTO.code, timeoutMs: Long = DEFAULT_TIMEOUT_MS, useGpu: Boolean = false, threads: Int = 0): String {
        val cli = WhisperRuntime.cliPath()
        val commandLine = GeneralCommandLine(buildArguments(cli, wavFile, model, language, useGpu, threads))
            .withWorkDirectory(WhisperRuntime.runtimeDir(cli))
        if (SystemInfo.isLinux || SystemInfo.isMac) {
            commandLine.withEnvironment("LD_LIBRARY_PATH", WhisperRuntime.runtimeDir(cli))
        }

        val process = try {
            commandLine.createProcess()
        } catch (t: IOException) {
            log.warn("failed to start whisper-cli at $cli", t)
            throw TranscriptionException("Could not start whisper-cli at $cli", t)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val errorThread = Thread({
            process.errorStream.bufferedReader().use { reader ->
                reader.forEachLine { line -> synchronized(stderr) { stderr.appendLine(line) } }
            }
        }, "whisper-stderr")
        errorThread.start()
        try {
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { stdout.appendLine(it) }
            }
        } finally {
            errorThread.join(ERROR_JOIN_TIMEOUT_MS)
        }

        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw TranscriptionException("whisper-cli timed out after ${timeoutMs} ms")
        }
        val exitCode = process.exitValue()
        log.info(
            "whisper-cli exit=$exitCode stdoutLen=${stdout.length} stderr='${stderr.toString().trim().take(300)}'",
        )
        if (exitCode != 0) {
            val detail = stderr.toString().trim().lineSequence().toList().takeLast(5).joinToString(" | ")
            throw TranscriptionException("whisper-cli exited with code $exitCode${if (detail.isNotEmpty()) ": $detail" else ""}")
        }
        return normalizeTranscript(stdout.toString())
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
        private const val ERROR_JOIN_TIMEOUT_MS = 5_000L
        private val BLANK_AUDIO_TOKEN = Regex("\\[BLANK_AUDIO\\]", RegexOption.IGNORE_CASE)
        private val MULTIPLE_SPACES = Regex(" {2,}")
    }
}
