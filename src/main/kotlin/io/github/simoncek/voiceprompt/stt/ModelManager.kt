package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.channels.FileChannel
import java.nio.channels.FileLock as NioFileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

object ModelManager {

    private val LOG = Logger.getInstance(ModelManager::class.java)

    /** The model that ships inside the plugin jar so dictation works without a network. */
    const val BUNDLED_MODEL = "tiny.en"

    /** Default model for fresh installs: the bundled one, so dictation works offline out of the box. */
    const val DEFAULT_MODEL = BUNDLED_MODEL

    /** A downloadable Whisper model, its approximate size, and a short quality note. */
    data class ModelOption(
        val id: String,
        val displayName: String,
        val sizeLabel: String,
        val note: String,
        val bundled: Boolean = false,
    )

    val AVAILABLE_MODELS: List<ModelOption> = listOf(
        ModelOption("tiny.en", "Tiny", "75 MB", "English · fastest, least accurate", bundled = true),
        ModelOption("base.en", "Base", "142 MB", "English · quick, fairly accurate"),
        ModelOption("small.en", "Small", "466 MB", "English · best balance, recommended"),
        ModelOption("small", "Small", "466 MB", "Multilingual · balanced accuracy"),
        ModelOption("large-v3-turbo", "Large v3 Turbo", "1.6 GB", "Multilingual · accurate, slower"),
    )

    /**
     * The exact commit of the `ggerganov/whisper.cpp` HuggingFace repo that the pinned URLs and
     * the `models/SHA256SUMS` entries were captured from (review N21: `resolve/main` tracks a
     * moving branch, so the bytes — and the checksums we verify them against — could change under
     * our feet). A commit is immutable, so bumping it is a deliberate release change: the new
     * commit and the refreshed manifest land together.
     */
    const val MODELS_REPO_COMMIT = "5359861c739e955e79d9a303bcbc70fb988958b1"

    fun downloadUrl(modelId: String): String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/$MODELS_REPO_COMMIT/ggml-$modelId.bin"

    /**
     * The SHA-256 checksum [modelId] must verify against, from the bundled `models/SHA256SUMS`
     * manifest (review N21). The manifest ships with the plugin, so it is always available and a
     * model without an entry cannot be downloaded at all (fail-closed). The old implementation
     * fetched a remote manifest the models repo does not publish (a 404), so every download was
     * silently verified by size only.
     */
    fun expectedSha256(modelId: String): String? {
        val sums = bundledSums() ?: return null
        return parseSha256Sums(sums, "ggml-$modelId.bin")
    }

    /** The bundled `models/SHA256SUMS` manifest, or null when the plugin build ships none. */
    private fun bundledSums(): String? =
        javaClass.classLoader.getResourceAsStream("models/SHA256SUMS")
            ?.use { input -> input.readBytes().decodeToString() }

    /** The lowercase SHA-256 for [fileName] in SHA256SUMS content, or null if it's not listed. */
    internal fun parseSha256Sums(content: String, fileName: String): String? =
        content.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].equals(fileName, ignoreCase = true) }
            ?.first()
            ?.lowercase()

    fun isInstalled(modelName: String): Boolean = Files.isRegularFile(modelFile(modelName))

    /** Whisper `*.en` models are trained English-only; every other id is multilingual. */
    fun isEnglishOnly(modelId: String): Boolean = modelId.endsWith(".en")

    /**
     * Shared across IDE products and versions (review P6) via [VoicePromptPaths] — the legacy
     * per-IDE location is migrated to it on first use.
     */
    fun modelsDir(): Path = VoicePromptPaths.modelsDir()

    fun modelFile(modelName: String): Path = modelsDir().resolve("ggml-$modelName.bin")

    fun resolveActive(customPath: String?, modelName: String): Path? {
        customPath?.takeIf { it.isNotBlank() }?.let { candidate ->
            val path = Paths.get(candidate)
            if (Files.isRegularFile(path)) return path
        }
        val default = modelFile(modelName)
        return if (Files.isRegularFile(default)) default else null
    }

    fun installedModels(): List<String> {
        val dir = modelsDir()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.startsWith("ggml-") && it.endsWith(".bin") }
                .map { it.removePrefix("ggml-").removeSuffix(".bin") }
                .sorted()
                .toList()
        }
    }

    // --- Test seams (house pattern, cf. Beeps.stopExecutorForTest): point the download at a
    // local server, a temp dir, and a synthetic manifest so the resume/verification paths run
    // offline. Null in production — the real URLs, dir, and bundled manifest are used.
    internal @Volatile var urlForTest: ((modelId: String) -> String)? = null
    internal @Volatile var sha256ForTest: ((modelId: String) -> String?)? = null
    internal @Volatile var modelsDirForTest: (() -> Path)? = null

    private fun urlFor(modelId: String): String = urlForTest?.invoke(modelId) ?: downloadUrl(modelId)

    private fun sha256For(modelId: String): String? = sha256ForTest?.invoke(modelId) ?: expectedSha256(modelId)

    private fun modelsDirFor(): Path = modelsDirForTest?.invoke() ?: modelsDir()

    /**
     * Downloads [modelId] into the models directory and returns the installed file (review N21):
     * - fail-closed: without a checksum in the bundled manifest the download is refused before a
     *   single byte is transferred, instead of verifying size only;
     * - resumable: a deterministic `.part` name plus an HTTP `Range` request, so a failed 1.6 GB
     *   download continues where it stopped instead of restarting from zero — a cancelled or
     *   interrupted download keeps its partial `.part` (deleted only on a checksum mismatch or
     *   when the server rejects the range);
     * - concurrency-safe: a cross-process lock guards the shared part file, so two IDEs of the
     *   same version downloading the same model serialize instead of corrupting each other
     *   (the lock is an OS file lock — a crashed IDE releases it, so it can never wedge);
     * - self-cleaning: `.part` files left by a dead download are removed (see
     *   [cleanStaleParts]).
     *
     * Runs on a background thread; [indicator] is polled for cancellation.
     */
    fun download(modelId: String, indicator: ProgressIndicator, onPercent: ((Int) -> Unit)? = null): Path {
        val expected = sha256For(modelId)
            ?: throw IOException(
                "No SHA-256 checksum is listed for model '$modelId' — " +
                    "refusing to download a model file we cannot verify.",
            )
        val dir = modelsDirFor()
        val target = dir.resolve("ggml-$modelId.bin")
        Files.createDirectories(dir)
        cleanStaleParts(dir, exceptModelId = modelId)
        val lockFile = dir.resolve(target.fileName.toString() + ".download.lock")
        val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            waitWhileLocked(channel, indicator)
            // Another IDE may have finished the download while we waited — the shared home
            // (review P6) means the target can already be here.
            if (Files.isRegularFile(target)) return target
            val part = dir.resolve(target.fileName.toString() + ".part")
            downloadInto(part, urlFor(modelId), indicator, onPercent)
            // Verify integrity before moving the file into place: a truncated or corrupted
            // model would otherwise fail later, during transcription, with no hint of why.
            val actual = WhisperRuntime.sha256(part)
            if (actual != expected) {
                // Pre-1.1.0 finding 2: the whole file is here and it is the wrong bytes —
                // resuming from it would just re-fail, so delete it. (A server that rejects
                // our Range already deleted and retried on its own, inside downloadInto.)
                // A Cancel or a network failure throws before we reach this check, and the
                // partial .part is still a valid prefix of the file (we write bytes
                // sequentially) — keep it, so the next attempt resumes where this one stopped
                // instead of restarting a 1.6 GB download from zero.
                Files.deleteIfExists(part)
                throw IOException("Checksum mismatch for model $modelId: expected $expected, got $actual")
            }
            LOG.info("Model $modelId verified: size=${Files.size(part)} sha256=$actual")
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            channel.close()
        }
    }

    /**
     * Waits until [channel]'s lock is free, staying responsive to [indicator] the whole time
     * (the holder is another IDE downloading the same model — or a thread of this one; the OS
     * releases the lock when that process exits, so a crashed IDE can never wedge the wait).
     */
    private fun waitWhileLocked(channel: FileChannel, indicator: ProgressIndicator) {
        var announced = false
        while (true) {
            indicator.checkCanceled()
            val acquired = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                // Held by another thread of this JVM (the service already serializes per model).
                null
            }
            if (acquired != null) return
            if (!announced) {
                indicator.text = "Waiting for another Voice Prompt download of this model to finish…"
                announced = true
            }
            Thread.sleep(500)
        }
    }

    /**
     * Streams [url] into [part], resuming from whatever [part] already holds. With a non-empty
     * part we send `Range: bytes=N-`; the server's answer decides how to write (review N21):
     * - `206 Partial Content` — append after the existing bytes;
     * - `200 OK` — the server ignored the range: truncate and take the full body;
     * - `416 Range Not Satisfiable` — the part is larger than the file (upstream shrank it):
     *   discard the part and download fresh (one retry).
     *
     * `HttpURLConnection.getInputStream()` throws on a non-2xx answer, so the processor reads the
     * status code first and throws [HttpRequests.HttpStatusException] for a 416; the retry is a
     * catch around that, not a branch inside the stream.
     */
    private fun downloadInto(part: Path, url: String, indicator: ProgressIndicator, onPercent: ((Int) -> Unit)?) {
        var resumeFrom = if (Files.isRegularFile(part)) Files.size(part) else 0L
        for (attempt in 1..2) {
            val ranged = resumeFrom > 0
            // requestWithRange prepends "bytes=" to the range (platform ConnectionTuner).
            val builder = (if (ranged) HttpRequests.requestWithRange(url, "$resumeFrom-")
            else HttpRequests.request(url))
                .connectTimeout(20_000)
                .readTimeout(60_000)
            try {
                builder.connect { request ->
                    // getInputStream() throws on a non-2xx answer, so read the status first and
                    // decide before streaming; 416 is surfaced as a recognizable exception below.
                    // responseCode and getHeaderField live on HttpURLConnection, not URLConnection.
                    val code = (request.connection as HttpURLConnection).responseCode
                    when (code) {
                        200, 206 -> streamBody(part, request, resumeFrom, code == 206, indicator, onPercent)
                        416 -> throw HttpRequests.HttpStatusException("Range not satisfiable (416)", 416, url)
                        else -> throw IOException("Model download failed with HTTP $code")
                    }
                }
                return
            } catch (e: HttpRequests.HttpStatusException) {
                if (e.getStatusCode() == 416 && ranged) {
                    LOG.warn("the server rejected the resume range (HTTP 416) on attempt $attempt — " +
                        "discarding the part and re-downloading $url from scratch")
                    Files.deleteIfExists(part)
                    resumeFrom = 0L
                    continue
                }
                throw IOException("Model download failed with HTTP ${e.getStatusCode()}", e)
            }
        }
        // Unreachable: the second attempt is un-ranged, and 416 only comes in answer to a range.
        throw IOException("Model download failed: the server rejected every attempt")
    }

    private fun streamBody(
        part: Path,
        request: HttpRequests.Request,
        resumeFrom: Long,
        resuming: Boolean,
        indicator: ProgressIndicator,
        onPercent: ((Int) -> Unit)?,
    ) {
        // getHeaderField lives on HttpURLConnection; connection is the concrete type here.
        val connection = request.connection as HttpURLConnection
        val total = if (resuming) {
            // "Content-Range: bytes 100-999/1000" — the whole file is 1000 bytes.
            connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: (resumeFrom + connection.contentLengthLong)
        } else {
            connection.contentLengthLong
        }
        indicator.isIndeterminate = total <= 0
        val options = if (resuming) {
            arrayOf(StandardOpenOption.APPEND)
        } else {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }
        request.inputStream.use { input ->
            Files.newOutputStream(part, *options).use { out ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    indicator.checkCanceled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    read += n
                    if (total > 0) {
                        val done = (resumeFrom + read).coerceAtMost(total)
                        indicator.fraction = done.toDouble() / total
                        onPercent?.invoke((done * 100 / total).toInt())
                    }
                }
            }
        }
    }

    /**
     * Removes `.part` files left behind by a download that died without finishing (a crash, a
     * kill, a power loss) — review N21. A part is only ever written while its downloader holds
     * the model's download lock, so a part whose lock we can take is stale by definition; a part
     * whose lock is held belongs to a live download and is left alone.
     */
    internal fun cleanStaleParts(modelsDir: Path, exceptModelId: String? = null) {
        if (!Files.isDirectory(modelsDir)) return
        val removed = mutableListOf<Path>()
        Files.list(modelsDir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.startsWith("ggml-") && it.endsWith(".bin.part") }
                .forEach { name ->
                    val modelId = name.removePrefix("ggml-").removeSuffix(".bin.part")
                    if (modelId == exceptModelId) return@forEach
                    val part = modelsDir.resolve(name)
                    if (deleteIfUnlocked(part, modelsDir.resolve("ggml-$modelId.bin.download.lock"))) {
                        removed.add(part)
                        LOG.info("removed a stale download part: $name")
                    }
                }
        }
    }

    /**
     * Tries to take [lockFile] for up to two seconds (polling [FileChannel.tryLock]) and deletes
     * [part] when the lock is free. A held lock means a live download owns the part.
     */
    private fun deleteIfUnlocked(part: Path, lockFile: Path): Boolean {
        val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (true) {
                val acquired = try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    null
                }
                if (acquired != null) return Files.deleteIfExists(part)
                if (System.nanoTime() >= deadline) return false
                Thread.sleep(50)
            }
        } finally {
            runCatching { channel.close() }
        }
    }

    // --- bundled model (ships inside the plugin jar, no network needed) ---

    /** Whether the bundled model resource is present on the classpath. */
    fun bundledModelAvailable(): Boolean =
        javaClass.classLoader.getResource("models/ggml-$BUNDLED_MODEL.bin") != null

    /** Expected SHA-256 of the bundled model, from `models/SHA256SUMS`. */
    fun bundledSha256(): String? = expectedSha256(BUNDLED_MODEL)

    /**
     * Makes the bundled model available in the models directory and returns the installed file.
     * An already-installed copy is kept as-is; otherwise the resource is extracted to a temp file,
     * checksum-verified, and moved into place — so a failure never leaves a half-installed model.
     * Returns null when the plugin build ships no bundled model.
     */
    fun installBundledModel(): Path? {
        val target = modelFile(BUNDLED_MODEL)
        if (Files.isRegularFile(target)) return target
        if (!bundledModelAvailable()) return null
        val expected = bundledSha256() ?: return null
        val resource = javaClass.classLoader.getResourceAsStream("models/ggml-$BUNDLED_MODEL.bin")
            ?: return null
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "model", ".part")
        try {
            resource.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            if (WhisperRuntime.sha256(temp) != expected) {
                throw IOException("Checksum mismatch for bundled model $BUNDLED_MODEL")
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
