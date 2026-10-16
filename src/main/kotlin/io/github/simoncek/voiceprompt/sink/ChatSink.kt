package io.github.simoncek.voiceprompt.sink

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.awt.event.ActionListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

/**
 * A destination for a dictated transcript. Strategies are tried in order at runtime; the first
 * one that is available and reports success wins.
 */
interface ChatSink {
    fun isAvailable(project: Project?): Boolean

    /**
     * Inserts [text] into the chat. A sink may complete synchronously or later — e.g. only after
     * the tool window has finished showing (review #7) — so the result is reported through
     * [onComplete], which the sink invokes exactly once (on the EDT), instead of being returned.
     * A sink that cannot insert must report a failure, not swallow the call.
     *
     * [isCancelled] is the chain's settlement token: it turns true the moment the chain reports —
     * a sink's success or the safety timeout. A sink that notices it before committing its
     * insertion must back out with [SinkCancelledException] instead of inserting: the transcript
     * has already reached the user via the balloon, and inserting now would deliver it twice
     * (review N19).
     */
    fun insert(
        project: Project?,
        text: String,
        autoSend: Boolean,
        isCancelled: () -> Boolean,
        onComplete: (Result<Unit>) -> Unit,
    )
}

class NoCopilotWindowException(message: String = "GitHub Copilot chat window not found") : Exception(message)
class InputNotFoundException(message: String = "Could not locate the Copilot chat input field") : Exception(message)

/**
 * Review N19: the sink discovered — right before committing its insertion — that the chain has
 * already settled (the safety timer fired and the transcript went to the user via the balloon).
 * Committing now would deliver the transcript twice, so the sink backed out. The chain has
 * already reported, so it discards this result; it never reaches the caller.
 */
class SinkCancelledException(message: String = "the chain settled before this sink committed its insertion") : Exception(message)

/**
 * Tries each [sink] in order, reporting the first successful insertion through [onComplete].
 * If none succeed (or none are available), reports a failure so the caller can fall back to a
 * notification balloon.
 *
 * [onComplete] is invoked exactly once, on the EDT, even if a sink fails, throws or — for
 * whatever reason — never reports at all: a safety timer cuts such a sink off after
 * [safetyTimeoutMs] so the transcript always reaches the user (via the balloon) instead of being
 * silently swallowed (review #7).
 */
class ChatSinkChain(
    private val sinks: List<ChatSink>,
    private val safetyTimeoutMs: Long = SAFETY_TIMEOUT_MS,
) {

    private val log = Logger.getInstance(ChatSinkChain::class.java)

    fun insert(project: Project?, text: String, autoSend: Boolean, onComplete: (Result<Unit>) -> Unit) {
        val completed = AtomicBoolean(false)
        val safety = Timer(safetyTimeoutMs.toInt(), ActionListener {
            if (completed.compareAndSet(false, true)) {
                log.warn("no sink reported a result within ${safetyTimeoutMs} ms; failing over")
                onComplete(Result.failure(InputNotFoundException()))
            }
        }).apply {
            isRepeats = false
            start()
        }

        fun complete(result: Result<Unit>) {
            if (completed.compareAndSet(false, true)) {
                safety.stop()
                onComplete(result)
            }
        }

        fun tryNext(index: Int) {
            if (index >= sinks.size) {
                complete(Result.failure(InputNotFoundException()))
                return
            }
            val sink = sinks[index]
            if (!sink.isAvailable(project)) {
                tryNext(index + 1)
                return
            }
            // A sink may throw (e.g. from setText or an editor write) as well as report a
            // failure; both must fall through to the next sink, not abort the chain.
            try {
                // The settlement token: true the moment this chain reports (a sink's success or
                // the safety timeout). A late sink that checks it before committing its insertion
                // backs out instead of double-delivering (review N19).
                sink.insert(project, text, autoSend, { completed.get() }) { result ->
                    if (result.isSuccess) {
                        complete(result)
                    } else {
                        log.warn("sink ${sink.javaClass.simpleName} failed; trying next", result.exceptionOrNull())
                        tryNext(index + 1)
                    }
                }
            } catch (t: Throwable) {
                log.warn("sink ${sink.javaClass.simpleName} threw; trying next", t)
                tryNext(index + 1)
            }
        }

        tryNext(0)
    }

    companion object {
        /** How long to wait for a sink to report a result before failing over (see [insert]). */
        const val SAFETY_TIMEOUT_MS = 5_000L

        fun default(): ChatSinkChain = ChatSinkChain(
            listOf(SwingInjectionSink(), ClipboardPasteSink()),
        )
    }
}
