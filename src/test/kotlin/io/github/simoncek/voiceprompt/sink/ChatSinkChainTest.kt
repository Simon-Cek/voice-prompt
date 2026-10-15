package io.github.simoncek.voiceprompt.sink

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ChatSinkChainTest {

    /** A stub sink that records its calls and reports [result] (synchronously) via the callback. */
    private class StubSink(
        private val available: Boolean = true,
        private val result: Result<Unit> = Result.success(Unit),
    ) : ChatSink {
        var calls = 0
        var lastText: String? = null
        var lastAutoSend: Boolean? = null

        override fun isAvailable(project: Project?): Boolean = available

        override fun insert(project: Project?, text: String, autoSend: Boolean, onComplete: (Result<Unit>) -> Unit) {
            calls++
            lastText = text
            lastAutoSend = autoSend
            onComplete(result)
        }
    }

    /** A stub sink that throws on every insert, like a real sink failing inside setText. */
    private class ThrowingSink(private val message: String) : ChatSink {
        var calls = 0

        override fun isAvailable(project: Project?): Boolean = true

        override fun insert(project: Project?, text: String, autoSend: Boolean, onComplete: (Result<Unit>) -> Unit) {
            calls++
            throw RuntimeException(message)
        }
    }

    /** A stub sink that never reports a result — the sink the safety timer exists for. */
    private class HangingSink : ChatSink {
        var calls = 0

        override fun isAvailable(project: Project?): Boolean = true

        override fun insert(project: Project?, text: String, autoSend: Boolean, onComplete: (Result<Unit>) -> Unit) {
            calls++
            // Deliberately never calls onComplete.
        }
    }

    /** Runs [insert] and captures the single completion; the chain always reports exactly one. */
    private fun insert(chain: ChatSinkChain, autoSend: Boolean = false, text: String = "hi"): Result<Unit> {
        val result = AtomicReference<Result<Unit>?>(null)
        chain.insert(null, text, autoSend) { result.set(it) }
        // Every stub sink in this test completes synchronously (except HangingSink, whose tests
        // wait for the safety timer), so the result is available by now.
        return result.get() ?: throw AssertionError("the chain reported no result")
    }

    @Test
    fun `tries sinks in order and stops at the first success`() {
        val first = StubSink()
        val second = StubSink()
        val chain = ChatSinkChain(listOf(first, second))

        val result = insert(chain, text = "hello")

        assertTrue(result.isSuccess)
        assertEquals(1, first.calls)
        assertEquals(0, second.calls)
        assertEquals("hello", first.lastText)
        assertEquals(false, first.lastAutoSend)
    }

    @Test
    fun `skips sinks that report themselves unavailable`() {
        val first = StubSink(available = false)
        val second = StubSink()
        val chain = ChatSinkChain(listOf(first, second))

        val result = insert(chain, autoSend = true)

        assertTrue(result.isSuccess)
        assertEquals(0, first.calls)
        assertEquals(1, second.calls)
        assertEquals(true, second.lastAutoSend)
    }

    @Test
    fun `falls back to the next sink when one fails`() {
        val first = StubSink(result = Result.failure(Exception("boom")))
        val second = StubSink()
        val chain = ChatSinkChain(listOf(first, second))

        val result = insert(chain)

        assertTrue(result.isSuccess)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertEquals("hi", second.lastText)
    }

    @Test
    fun `falls back to the next sink when one throws`() {
        val first = ThrowingSink("setText blew up")
        val second = StubSink()
        val chain = ChatSinkChain(listOf(first, second))

        val result = insert(chain)

        assertTrue("a throwing sink must not abort the chain", result.isSuccess)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertEquals("hi", second.lastText)
    }

    @Test
    fun `returns a failure when every sink throws`() {
        val chain = ChatSinkChain(
            listOf(
                ThrowingSink("a"),
                ThrowingSink("b"),
            ),
        )

        val result = insert(chain)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is InputNotFoundException)
    }

    @Test
    fun `returns a failure when every sink fails`() {
        val chain = ChatSinkChain(
            listOf(
                StubSink(result = Result.failure(Exception("a"))),
                StubSink(result = Result.failure(Exception("b"))),
            ),
        )

        val result = insert(chain)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is InputNotFoundException)
    }

    @Test
    fun `returns a failure when no sink is available`() {
        val chain = ChatSinkChain(listOf(StubSink(available = false)))

        assertFalse(insert(chain).isSuccess)
    }

    @Test
    fun `an empty chain fails`() {
        assertFalse(insert(ChatSinkChain(emptyList())).isSuccess)
    }

    @Test
    fun `a sink that never reports is cut off by the safety timer`() {
        val hanging = HangingSink()
        val second = StubSink()
        val chain = ChatSinkChain(listOf(hanging, second), safetyTimeoutMs = 200)

        val result = AtomicReference<Result<Unit>?>(null)
        chain.insert(null, "hi", autoSend = false) { result.set(it) }

        // The safety timer (EDT) must cut the hanging sink off and report a failure — this is
        // what guarantees the balloon fallback still fires (review #7).
        val deadline = System.currentTimeMillis() + 3_000
        while (result.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val outcome = result.get() ?: throw AssertionError("the chain never reported a result")
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.exceptionOrNull() is InputNotFoundException)
        assertEquals(1, hanging.calls)
    }
}
