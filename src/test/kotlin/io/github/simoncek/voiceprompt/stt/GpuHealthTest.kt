package io.github.simoncek.voiceprompt.stt

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * Review N9: the confirmed-GPU-failure blacklist. Per MODEL (not per thread count), scoped to
 * the Processing settings (mode + thread count), thread-safe, and cleared when the user
 * changes the Processing settings — that change is the user's deliberate "try the GPU again"
 * (and what the one-time notification tells them).
 */
class GpuHealthTest {

    private val modelA = Paths.get("/models/ggml-small.en.bin")
    private val modelB = Paths.get("/models/ggml-tiny.en.bin")
    private val keyAuto8 = GpuHealth.processingKey("AUTOMATIC", 8)
    private val keyAuto4 = GpuHealth.processingKey("AUTOMATIC", 4)
    private val keyCpu8 = GpuHealth.processingKey("CPU", 8)

    @Before
    fun setUp() {
        GpuHealth.clearForTest()
    }

    @After
    fun tearDown() {
        GpuHealth.clearForTest()
    }

    @Test
    fun `a confirmed failure blacklists that model under those settings only`() {
        GpuHealth.sync(keyAuto8)
        assertTrue("the first confirmation must activate the blacklist", GpuHealth.noteFailure(modelA, keyAuto8))
        assertFalse("confirming the same failure again is not 'new'", GpuHealth.noteFailure(modelA, keyAuto8))
        assertTrue("the model must now be blacklisted under those settings", GpuHealth.isBlacklisted(modelA, keyAuto8))
        assertFalse("other models must not be blacklisted", GpuHealth.isBlacklisted(modelB, keyAuto8))
        assertFalse("other settings must not be blacklisted", GpuHealth.isBlacklisted(modelA, keyAuto4))
    }

    @Test
    fun `changing the processing settings clears the blacklist`() {
        GpuHealth.sync(keyAuto8)
        GpuHealth.noteFailure(modelA, keyAuto8)
        assertTrue(GpuHealth.isBlacklisted(modelA, keyAuto8))

        // The user switches the Processing mode…
        GpuHealth.sync(keyCpu8)
        // …and back to AUTOMATIC: the change in between must have cleared the entry, so the
        // GPU gets a fresh try now.
        GpuHealth.sync(keyAuto8)
        assertFalse(
            "a round trip through changed Processing settings must clear the blacklist",
            GpuHealth.isBlacklisted(modelA, keyAuto8),
        )
    }

    @Test
    fun `repeating the same settings keeps the entry`() {
        GpuHealth.sync(keyAuto8)
        GpuHealth.noteFailure(modelA, keyAuto8)
        GpuHealth.sync(keyAuto8)
        assertTrue("syncing with the same settings must not clear the entry", GpuHealth.isBlacklisted(modelA, keyAuto8))
    }

    @Test
    fun `a failure under new settings replaces the entry`() {
        GpuHealth.sync(keyAuto8)
        GpuHealth.noteFailure(modelA, keyAuto8)
        // The settings change and the GPU fails again under the new settings…
        GpuHealth.sync(keyAuto4)
        assertTrue("a fresh confirmation under the new settings is 'new'", GpuHealth.noteFailure(modelA, keyAuto4))
        assertTrue(GpuHealth.isBlacklisted(modelA, keyAuto4))
        assertFalse("…and the old settings key is no longer active", GpuHealth.isBlacklisted(modelA, keyAuto8))
    }

    @Test
    fun `concurrent access from worker threads is safe`() {
        // The engines call from worker threads: hammer all three accessors from eight threads.
        val key = GpuHealth.processingKey("AUTOMATIC", 4)
        GpuHealth.sync(key)
        val threads = (1..8).map {
            thread(start = false) {
                repeat(2000) { i ->
                    val model = if (i % 4 == 0) modelA else modelB
                    if (i % 2 == 0) GpuHealth.isBlacklisted(model, key) else GpuHealth.noteFailure(model, key)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertFalse("all worker threads must finish", threads.any { it.isAlive })
        // Whatever the interleaving, the state must now be consistent and clearable.
        GpuHealth.clearForTest()
        assertFalse(GpuHealth.isBlacklisted(modelA, key))
        assertFalse(GpuHealth.isBlacklisted(modelB, key))
    }
}
