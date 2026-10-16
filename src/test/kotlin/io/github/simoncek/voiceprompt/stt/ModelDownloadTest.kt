package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.testFramework.LightPlatformTestCase
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Review N21, end-to-end against a local [com.sun.net.httpserver] server (Range-capable and
 * request-recording): the download core must verify the SHA-256 from the bundled manifest
 * (fail-closed when a model is not listed), resume an interrupted `.part` with an HTTP Range
 * request, discard a part that is larger than the file, and clean up parts left by dead
 * downloads — all offline and deterministic.
 */
class ModelDownloadTest : LightPlatformTestCase() {

    private var dir: Path? = null
    private var server: RangeServer? = null

    override fun setUp() {
        super.setUp()
        val d = Files.createTempDirectory("vp-download-test")
        d.toFile().deleteOnExit()
        dir = d
        ModelManager.modelsDirForTest = { d }
    }

    override fun tearDown() {
        try {
            ModelManager.modelsDirForTest = null
            ModelManager.urlForTest = null
            ModelManager.sha256ForTest = null
            server?.close()
            server = null
        } finally {
            dir?.toFile()?.deleteRecursively()
            dir = null
            super.tearDown()
        }
    }

    /** Starts the local server and points the download core at it. */
    private fun startServer(payload: ByteArray): RangeServer {
        val s = RangeServer(payload)
        server = s
        ModelManager.urlForTest = { "http://127.0.0.1:${s.port}/ggml-base.en.bin" }
        return s
    }

    private fun sha256Of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** [ModelManager.download] on its own thread (as in production: never the EDT). */
    private fun downloadInThread(modelId: String, indicator: ProgressIndicator): Path {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = java.util.concurrent.Callable<Path> { ModelManager.download(modelId, indicator) }
            return try {
                executor.submit(task).get(30, TimeUnit.SECONDS)
            } catch (t: ExecutionException) {
                throw t.cause ?: t
            }
        } finally {
            executor.shutdownNow()
        }
    }

    fun test_a_fresh_download_streams_verifies_and_lands_in_place() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val srv = startServer(payload)
        ModelManager.sha256ForTest = { sha256Of(payload) }
        val indicator = FakeIndicator()

        val target = downloadInThread("base.en", indicator)

        assertEquals(dir!!.resolve("ggml-base.en.bin"), target)
        assertArrayEquals("the installed model must be exactly what the server sent", payload, Files.readAllBytes(target))
        assertFalse("the .part file must be gone after a successful install", Files.exists(dir!!.resolve("ggml-base.en.bin.part")))
        assertEquals("progress must reach 1.0", 1.0, indicator.getFraction())
        assertEquals(1, srv.requests.size)
        assertEquals("a fresh download must not send a Range header", null, srv.requests[0].rangeHeader)
    }

    fun test_an_interrupted_part_resumes_with_a_range_request() {
        val payload = ByteArray(128 * 1024) { (it * 7 % 256).toByte() }
        val srv = startServer(payload)
        ModelManager.sha256ForTest = { sha256Of(payload) }
        // A download that died after 10 KB — the deterministic part it left behind.
        Files.write(dir!!.resolve("ggml-base.en.bin.part"), payload.copyOfRange(0, 10_000))

        val target = downloadInThread("base.en", FakeIndicator())

        assertArrayEquals(payload, Files.readAllBytes(target))
        assertEquals("the resume must fetch only the missing bytes", 1, srv.requests.size)
        assertEquals("bytes=10000-", srv.requests[0].rangeHeader)
    }

    fun test_a_server_that_ignores_range_restarts_from_zero() {
        val payload = ByteArray(64 * 1024) { (it % 13).toByte() }
        val srv = startServer(payload)
        srv.ignoreRange = true
        ModelManager.sha256ForTest = { sha256Of(payload) }
        Files.write(dir!!.resolve("ggml-base.en.bin.part"), payload.copyOfRange(0, 4_000))

        val target = downloadInThread("base.en", FakeIndicator())

        assertArrayEquals("a 200 in answer to a ranged request must be taken as the full file", payload, Files.readAllBytes(target))
        assertEquals(1, srv.requests.size)
        assertEquals("bytes=4000-", srv.requests[0].rangeHeader)
    }

    fun test_a_part_larger_than_the_file_is_discarded_and_redownloaded() {
        val payload = ByteArray(32 * 1024) { (it % 31).toByte() }
        val srv = startServer(payload)
        ModelManager.sha256ForTest = { sha256Of(payload) }
        // A part from an older, larger build of the model — the server answers 416.
        Files.write(dir!!.resolve("ggml-base.en.bin.part"), ByteArray(payload.size + 5) { (it % 17).toByte() })

        val target = downloadInThread("base.en", FakeIndicator())

        assertArrayEquals(payload, Files.readAllBytes(target))
        assertEquals("the 416 must be followed by a fresh full download", 2, srv.requests.size)
        assertEquals("bytes=${payload.size + 5}-", srv.requests[0].rangeHeader)
        assertEquals("the retry must be an un-ranged full download", null, srv.requests[1].rangeHeader)
    }

    fun test_a_download_without_a_listed_checksum_fails_closed_before_any_request() {
        val payload = ByteArray(8 * 1024) { (it % 7).toByte() }
        val srv = startServer(payload)
        // No sha256ForTest: the real (bundled) manifest is consulted, and "giant" is not in it.
        val error = runCatching { downloadInThread("giant", FakeIndicator()) }.exceptionOrNull()

        assertTrue("a missing checksum must fail closed, got: $error", error is IOException)
        assertTrue("the error must say why", error!!.message!!.contains("checksum", ignoreCase = true))
        assertEquals("fail-closed means the download never starts", 0, srv.requests.size)
        assertFalse(Files.exists(dir!!.resolve("ggml-giant.bin")))
    }

    fun test_a_checksum_mismatch_is_removed_and_reported() {
        val payload = ByteArray(16 * 1024) { (it % 41).toByte() }
        startServer(payload)
        // The manifest "says" something the bytes are not.
        ModelManager.sha256ForTest = { "0".repeat(64) }

        val error = runCatching { downloadInThread("base.en", FakeIndicator()) }.exceptionOrNull()

        assertTrue("a mismatch must be rejected, got: $error", error is IOException)
        assertTrue("the error must name the mismatch", error!!.message!!.contains("mismatch", ignoreCase = true))
        assertFalse("a failing download must not leave the target behind", Files.exists(dir!!.resolve("ggml-base.en.bin")))
        assertFalse("a failing download must not leave the part behind", Files.exists(dir!!.resolve("ggml-base.en.bin.part")))
    }

    fun test_stale_parts_of_other_models_are_cleaned_up() {
        val stale = dir!!.resolve("ggml-tiny.en.bin.part")
        Files.write(stale, byteArrayOf(1, 2, 3))

        ModelManager.cleanStaleParts(dir!!)

        assertFalse("an unlocked leftover part must be removed", Files.exists(stale))
    }

    fun test_cleanup_skips_a_part_whose_lock_is_held() {
        val stale = dir!!.resolve("ggml-tiny.en.bin.part")
        Files.write(stale, byteArrayOf(1, 2, 3))
        val lockFile = dir!!.resolve("ggml-tiny.en.bin.download.lock")

        FileLock(lockFile, 30).use {
            ModelManager.cleanStaleParts(dir!!)
            assertTrue("a part whose lock is held belongs to a live download and must stay", Files.exists(stale))
        }
        ModelManager.cleanStaleParts(dir!!)
        assertFalse("once the lock is free the part is stale and removable", Files.exists(stale))
    }

    fun test_cancellation_aborts_and_installs_nothing() {
        val payload = ByteArray(32 * 1024) { (it % 19).toByte() }
        val srv = startServer(payload)
        ModelManager.sha256ForTest = { sha256Of(payload) }
        val indicator = FakeIndicator()
        indicator.canceled = true // the first checkCanceled() throws

        val error = runCatching { downloadInThread("base.en", indicator) }.exceptionOrNull()

        assertTrue("a cancelled download must surface the cancellation, got: $error", error is ProcessCanceledException)
        assertFalse(Files.exists(dir!!.resolve("ggml-base.en.bin")))
        assertFalse(Files.exists(dir!!.resolve("ggml-base.en.bin.part")))
        assertEquals("cancellation happens before any network I/O", 0, srv.requests.size)
    }
}

/**
 * A local HTTP server that stands in for HuggingFace: it honours `Range: bytes=N-` (206 / 416),
 * records every request it sees, and can be told to ignore ranges (always 200, full body) to
 * exercise the restart path.
 */
private class RangeServer(private val payload: ByteArray) : AutoCloseable {

    class Recorded(val rangeHeader: String?)

    /** Every request this server saw, in order. */
    val requests = Collections.synchronizedList(mutableListOf<Recorded>())

    /** When true the server ignores Range and always serves the full body (200). */
    @Volatile
    var ignoreRange = false

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int = server.address.port

    init {
        server.createContext("/") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requests += Recorded(range)
            val send = { code: Int, from: Int, to: Int ->
                val body = payload.copyOfRange(from, to + 1)
                exchange.responseHeaders.add("Content-Length", body.size.toString())
                if (code == 206) exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${payload.size}")
                exchange.sendResponseHeaders(code, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            when {
                ignoreRange || range == null -> send(200, 0, payload.size - 1)
                start >= payload.size -> exchange.sendResponseHeaders(416, -1)
                else -> send(206, start, payload.size - 1)
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}

/** A minimal cancellable [ProgressIndicator] for driving [ModelManager.download] in tests. */
private class FakeIndicator : ProgressIndicator {

    var canceled = false
    private var fractionValue: Double = 0.0
    private var textValue: String? = null

    override fun start() {}
    override fun stop() {}
    override fun isRunning() = true
    override fun cancel() {
        canceled = true
    }

    override fun isCanceled() = canceled
    override fun setText(text: String?) {
        textValue = text
    }

    override fun getText(): String? = textValue
    override fun setText2(text: String?) {}
    override fun getText2(): String? = null
    override fun getFraction() = fractionValue
    override fun setFraction(fraction: Double) {
        fractionValue = fraction
    }

    override fun pushState() {}
    override fun popState() {}
    override fun isModal() = false
    override fun getModalityState() = ModalityState.NON_MODAL
    override fun setModalityProgress(indicator: ProgressIndicator?) {}
    override fun isIndeterminate(): Boolean = false
    override fun setIndeterminate(indeterminate: Boolean) {}
    override fun checkCanceled() {
        if (canceled) throw ProcessCanceledException()
    }

    override fun isPopupWasShown() = false
    override fun isShowing() = false
}
