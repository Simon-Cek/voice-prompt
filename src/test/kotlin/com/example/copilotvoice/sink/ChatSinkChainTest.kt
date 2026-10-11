package com.example.copilotvoice.sink

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSinkChainTest {

    /** A stub sink that records its calls and behaves per [available]/[result]. */
    private class StubSink(
        private val available: Boolean = true,
        private val result: Result<Unit> = Result.success(Unit),
    ) : ChatSink {
        var calls = 0
        var lastText: String? = null
        var lastAutoSend: Boolean? = null

        override fun isAvailable(project: Project?): Boolean = available

        override fun insert(project: Project?, text: String, autoSend: Boolean): Result<Unit> {
            calls++
            lastText = text
            lastAutoSend = autoSend
            return result
        }
    }

    @Test
    fun `tries sinks in order and stops at the first success`() {
        val first = StubSink()
        val second = StubSink()
        val chain = ChatSinkChain(listOf(first, second))

        val result = chain.insert(null, "hello", autoSend = false)

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

        assertTrue(chain.insert(null, "hi", autoSend = true).isSuccess)
        assertEquals(0, first.calls)
        assertEquals(1, second.calls)
        assertEquals(true, second.lastAutoSend)
    }

    @Test
    fun `falls back to the next sink when one fails`() {
        val first = StubSink(result = Result.failure(Exception("boom")))
        val second = StubSink()
        val chain = ChatSinkChain(listOf(first, second))

        val result = chain.insert(null, "hi", autoSend = false)

        assertTrue(result.isSuccess)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertEquals("hi", second.lastText)
    }

    @Test
    fun `returns a failure when every sink fails`() {
        val chain = ChatSinkChain(
            listOf(
                StubSink(result = Result.failure(Exception("a"))),
                StubSink(result = Result.failure(Exception("b"))),
            ),
        )

        val result = chain.insert(null, "hi", autoSend = false)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is InputNotFoundException)
    }

    @Test
    fun `returns a failure when no sink is available`() {
        val chain = ChatSinkChain(listOf(StubSink(available = false)))

        assertFalse(chain.insert(null, "hi", autoSend = false).isSuccess)
    }

    @Test
    fun `an empty chain fails`() {
        assertFalse(ChatSinkChain(emptyList()).insert(null, "hi", autoSend = false).isSuccess)
    }
}
