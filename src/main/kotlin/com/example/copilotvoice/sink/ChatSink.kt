package com.example.copilotvoice.sink

import com.intellij.openapi.project.Project

/**
 * A destination for a dictated transcript. Strategies are tried in order at runtime; the first
 * one that is available and whose [insert] succeeds wins.
 */
interface ChatSink {
    fun isAvailable(project: Project?): Boolean
    fun insert(project: Project?, text: String, autoSend: Boolean): Result<Unit>
}

class NoCopilotWindowException(message: String = "GitHub Copilot chat window not found") : Exception(message)
class InputNotFoundException(message: String = "Could not locate the Copilot chat input field") : Exception(message)

/**
 * Tries each [sink] in order, returning the first successful insertion. If none succeed (or none
 * are available), returns a failure so the caller can fall back to a notification balloon.
 */
class ChatSinkChain(private val sinks: List<ChatSink>) {

    fun insert(project: Project?, text: String, autoSend: Boolean): Result<Unit> {
        for (sink in sinks) {
            if (!sink.isAvailable(project)) continue
            val result = sink.insert(project, text, autoSend)
            if (result.isSuccess) return result
        }
        return Result.failure(InputNotFoundException())
    }

    companion object {
        fun default(): ChatSinkChain = ChatSinkChain(
            listOf(SwingInjectionSink(), ClipboardPasteSink()),
        )
    }
}
