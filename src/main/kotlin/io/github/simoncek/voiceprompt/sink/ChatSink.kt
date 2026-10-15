package io.github.simoncek.voiceprompt.sink

import com.intellij.openapi.diagnostic.Logger
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

    private val log = Logger.getInstance(ChatSinkChain::class.java)

    fun insert(project: Project?, text: String, autoSend: Boolean): Result<Unit> {
        for (sink in sinks) {
            if (!sink.isAvailable(project)) continue
            // A sink may throw (e.g. from setText or an editor write) as well as return a
            // failure; both must fall through to the next sink, not abort the chain.
            val result = try {
                sink.insert(project, text, autoSend)
            } catch (t: Throwable) {
                log.warn("sink ${sink.javaClass.simpleName} threw; trying next", t)
                Result.failure(t)
            }
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
