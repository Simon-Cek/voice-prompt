package com.example.copilotvoice.stt

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

object WhisperRuntime {

    private val LOG = Logger.getInstance(WhisperRuntime::class.java)

    class UnsupportedPlatformException(message: String) : IOException(message)
    class RuntimeExtractionException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun cliPath(): Path {
        val version = readVersion()
        val platformDir = when {
            SystemInfo.isWindows -> "win-x64"
            SystemInfo.isLinux -> "linux-x64"
            else -> throw UnsupportedPlatformException(
                "Unsupported platform: ${SystemInfo.OS_NAME} (${SystemInfo.OS_ARCH}). " +
                    "Copilot Voice currently bundles whisper-cli for Windows x64 and Linux x64."
            )
        }
        return extractIfNeeded(version, platformDir)
    }

    private fun readVersion(): String =
        javaClass.classLoader.getResource("whisper/WHISPER_VERSION.txt")?.let { url ->
            url.openStream().use { it.readBytes() }.decodeToString().trim()
        } ?: throw RuntimeExtractionException("Bundled whisper version file is missing")

    private fun extractIfNeeded(version: String, platformDir: String): Path {
        val target = Paths.get(PathManager.getSystemPath(), "copilot-voice", "runtime", version, platformDir)
        val sums = readSums(platformDir)
        if (sums.isEmpty()) throw RuntimeExtractionException("No bundled whisper files found for $platformDir")
        if (!allPresentAndValid(target, sums)) {
            Files.createDirectories(target)
            for ((name, expected) in sums) {
                val resource = javaClass.classLoader.getResourceAsStream("whisper/$platformDir/$name")
                    ?: throw RuntimeExtractionException("Bundled resource is missing: $platformDir/$name")
                val destination = target.resolve(name)
                Files.createDirectories(destination.parent)
                val temp = Files.createTempFile(target, "extract", ".part")
                try {
                    resource.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
                    if (sha256(temp) != expected) {
                        throw RuntimeExtractionException("Checksum mismatch for $platformDir/$name")
                    }
                    Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
            if (SystemInfo.isLinux || SystemInfo.isMac) fixUnixPermissions(target)
        }
        val cli = target.resolve(if (SystemInfo.isWindows) "whisper-cli.exe" else "whisper-cli")
        if (!Files.exists(cli)) throw RuntimeExtractionException("whisper-cli is missing after extraction: $cli")
        return cli
    }

    private fun readSums(platformDir: String): Map<String, String> {
        val sums = LinkedHashMap<String, String>()
        javaClass.classLoader.getResourceAsStream("whisper/SHA256SUMS")?.use { input ->
            input.bufferedReader().forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) return@forEachLine
                val path = parts[1]
                val prefix = "$platformDir/"
                if (path.startsWith(prefix)) sums[path.removePrefix(prefix)] = parts[0].lowercase()
            }
        } ?: throw RuntimeExtractionException("Bundled SHA256SUMS file is missing")
        return sums
    }

    private fun allPresentAndValid(target: Path, sums: Map<String, String>): Boolean {
        for ((name, expected) in sums) {
            val file = target.resolve(name)
            if (!Files.isRegularFile(file)) return false
            if (sha256(file) != expected) return false
        }
        return true
    }

    private fun fixUnixPermissions(target: Path) {
        val realNames = Files.list(target).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
        for (name in realNames) {
            val file = target.resolve(name)
            // A desugar_jdk_libs stub on the compile classpath shadows this JDK class, so use
            // only the constants it carries.
            val permissions = Files.getPosixFilePermissions(file)
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
            permissions.add(PosixFilePermission.GROUP_EXECUTE)
            permissions.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(file, permissions)
        }
        for ((linkName, realName) in sonameSymlinks(realNames)) {
            val link = target.resolve(linkName)
            if (!Files.exists(link)) Files.createSymbolicLink(link, Paths.get(realName))
        }
    }

    fun sonameSymlinks(realFileNames: Collection<String>): Map<String, String> {
        val symlinks = LinkedHashMap<String, String>()
        for (name in realFileNames) {
            val match = Regex("^lib(.+)\\.so\\.(\\d+)(?:\\..+)?$").find(name) ?: continue
            val soname = "lib${match.groupValues[1]}.so.${match.groupValues[2]}"
            if (soname != name) symlinks[soname] = name
        }
        return symlinks
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun runtimeDir(cli: Path): String = cli.parent.toString()

    /** Whether the bundled whisper build for the current platform ships a Vulkan (GPU) backend. */
    fun gpuBackendAvailable(): Boolean {
        val platformDir = when {
            SystemInfo.isWindows -> "win-x64"
            SystemInfo.isLinux -> "linux-x64"
            else -> return false
        }
        return try {
            readSums(platformDir).keys.any { it.contains("vulkan", ignoreCase = true) }
        } catch (t: Exception) {
            false
        }
    }
}
