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

    fun transcribe(wavFile: File, model: Path, language: String = "en", timeoutMs: Long = DEFAULT_TIMEOUT_MS, useGpu: Boolean = false): String {
        val cli = WhisperRuntime.cliPath()
        val commandLine = GeneralCommandLine(buildArguments(cli, wavFile, model, language, useGpu))
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
        return stdout.toString().trim()
    }

    fun buildArguments(cli: Path, wavFile: File, model: Path, language: String, useGpu: Boolean = false): List<String> {
        val base = listOf(
            cli.toString(),
            "-m", model.toString(),
            "-f", wavFile.absolutePath,
            "-nt",
            "-l", language,
        )
        // whisper.cpp runs on the GPU by default; pass --no-gpu to force CPU-only inference.
        return if (useGpu) base else base + listOf("--no-gpu")
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        private const val ERROR_JOIN_TIMEOUT_MS = 5_000L
    }
}
