package com.example.copilotvoice.stt

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

object ModelManager {

    const val DEFAULT_MODEL = "base.en"

    /** A downloadable Whisper model, its approximate size, and a short quality note. */
    data class ModelOption(val id: String, val displayName: String, val sizeLabel: String, val note: String)

    val AVAILABLE_MODELS: List<ModelOption> = listOf(
        ModelOption("tiny.en", "Tiny", "~75 MB", "fastest, least accurate"),
        ModelOption("base.en", "Base", "~142 MB", "balanced"),
        ModelOption("small.en", "Small", "~466 MB", "good balance"),
        ModelOption("medium.en", "Medium", "~1.5 GB", "most accurate, slowest"),
    )

    fun downloadUrl(modelId: String): String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$modelId.bin"

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
}
