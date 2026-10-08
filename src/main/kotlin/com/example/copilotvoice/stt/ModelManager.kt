package com.example.copilotvoice.stt

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ModelManager {

    const val DEFAULT_MODEL = "base.en"

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
}
