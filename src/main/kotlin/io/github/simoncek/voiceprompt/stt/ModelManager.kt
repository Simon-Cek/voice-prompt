package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

object ModelManager {

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

    fun downloadUrl(modelId: String): String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$modelId.bin"

    /** The checksum manifest the whisper.cpp models repo publishes alongside the models. */
    fun sha256SumsUrl(): String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/SHA256SUMS"

    /**
     * The published SHA-256 checksum for the [modelId] model file, or null when the manifest
     * can't be fetched. Does network I/O — call from a background thread.
     */
    fun expectedSha256(modelId: String): String? {
        val fileName = "ggml-$modelId.bin"
        return try {
            val content = HttpRequests.request(sha256SumsUrl())
                .connectTimeout(10_000)
                .readTimeout(10_000)
                .readString()
            parseSha256Sums(content, fileName)
        } catch (t: Exception) {
            null
        }
    }

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

    fun modelsDir(): Path = Paths.get(PathManager.getSystemPath(), "copilot-voice", "models")

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

    /**
     * Downloads [modelName] into the models directory using the IDE's HTTP stack (which honours
     * proxy settings), streaming to a `.part` file and renaming it to the final name only on
     * success, so a cancelled or failed download never leaves a half-installed model behind.
     */
    fun download(modelName: String, indicator: ProgressIndicator): Path {
        val target = modelFile(modelName)
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        Files.createDirectories(target.parent)
        try {
            HttpRequests.request(downloadUrl(modelName))
                .connectTimeout(20_000)
                .readTimeout(60_000)
                .saveToFile(part, indicator)
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            Files.deleteIfExists(part)
        }
    }

    // --- bundled model (ships inside the plugin jar, no network needed) ---

    /** Whether the bundled model resource is present on the classpath. */
    fun bundledModelAvailable(): Boolean =
        javaClass.classLoader.getResource("models/ggml-$BUNDLED_MODEL.bin") != null

    /** Expected SHA-256 of the bundled model, from `models/SHA256SUMS`. */
    fun bundledSha256(): String? {
        var sum: String? = null
        javaClass.classLoader.getResourceAsStream("models/SHA256SUMS")?.use { input ->
            input.bufferedReader().forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[1] == "ggml-$BUNDLED_MODEL.bin") sum = parts[0].lowercase()
            }
        }
        return sum
    }

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
