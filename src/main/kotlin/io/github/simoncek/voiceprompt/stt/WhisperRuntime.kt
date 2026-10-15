package io.github.simoncek.voiceprompt.stt

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

    /**
     * Serializes the extract-or-validate decision: two concurrent transcriptions must not both
     * extract the same runtime at once (review P1).
     */
    private val LOCK = Any()

    /**
     * The resolved runtime for this session. [WhisperRuntime] is a per-classloader singleton, so
     * this lives exactly as long as the plugin does — "validate once per session" (review P1).
     */
    @Volatile
    private var cached: ResolvedRuntime? = null

    /** Marker written into the runtime dir after a full checksum pass; makes it survive IDE restarts. */
    private const val VALIDATION_MARKER = ".validated"

    /** The resolved CLI plus everything needed to verify it cheaply on later calls. */
    internal class ResolvedRuntime(val cli: Path, val target: Path, val names: Set<String>)

    fun cliPath(): Path {
        // Fast path: already resolved this session and every file is still present (a handful of
        // stats — no hashing). This is the review P1 fix: the SHA-256 pass over the bundled
        // libraries runs once per install, not on every dictation.
        cached?.let { resolved ->
            if (isStillValid(resolved)) return resolved.cli
        }
        val version = readVersion()
        val platformDir = platformDirName()
        val resolved = ensureRuntime(runtimeTarget(version, platformDir), version, platformDir)
        cached = resolved
        return resolved.cli
    }

    private fun isStillValid(resolved: ResolvedRuntime): Boolean =
        resolved.names.all { Files.isRegularFile(resolved.target.resolve(it)) }

    private fun platformDirName(): String = when {
        SystemInfo.isWindows -> "win-x64"
        SystemInfo.isLinux -> "linux-x64"
        else -> throw UnsupportedPlatformException(
            "Unsupported platform: ${SystemInfo.OS_NAME} (${SystemInfo.OS_ARCH}). " +
                "Voice Prompt currently bundles whisper-cli for Windows x64 and Linux x64."
        )
    }

    private fun runtimeTarget(version: String, platformDir: String): Path =
        // Shared home (review P6): one runtime for every IDE product and version, not one per IDE.
        VoicePromptPaths.runtimeDir(version, platformDir)

    /**
     * Ensures [target] holds the extracted, checksum-verified runtime for [version] and returns
     * the resolved CLI. The full SHA-256 pass runs only when the version marker is missing
     * (fresh install, wiped dir, or a bumped whisper version) or a file has disappeared;
     * otherwise a cheap existence check suffices (review P1: don't re-hash the bundled libraries
     * on every dictation). Thread-safe: concurrent callers are serialized by [LOCK], so the
     * runtime is never extracted twice at once (review P1).
     */
    internal fun ensureRuntime(target: Path, version: String, platformDir: String): ResolvedRuntime =
        synchronized(LOCK) {
            val sums = readSums(platformDir)
            if (sums.isEmpty()) throw RuntimeExtractionException("No bundled whisper files found for $platformDir")
            val marker = target.resolve(VALIDATION_MARKER)
            if (!markerIsValid(marker, version) || !allPresent(target, sums)) {
                if (!allPresentAndValid(target, sums)) {
                    extractUnderLock(target, sums, platformDir)
                }
                writeMarker(marker, version)
            }
            val cli = target.resolve(if (SystemInfo.isWindows) "whisper-cli.exe" else "whisper-cli")
            if (!Files.isRegularFile(cli)) throw RuntimeExtractionException("whisper-cli is missing after extraction: $cli")
            ResolvedRuntime(cli, target, sums.keys.toSet())
        }

    /** True when a previous run already checksummed every file for this exact [version]. */
    private fun markerIsValid(marker: Path, version: String): Boolean =
        runCatching { Files.isRegularFile(marker) && Files.readString(marker).trim() == version }.getOrDefault(false)

    private fun writeMarker(marker: Path, version: String) {
        runCatching {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, version)
        }.onFailure { LOG.warn("could not write the runtime validation marker: $marker", it) }
    }

    /** Cheap existence check (no checksums): catches a partially deleted runtime dir. */
    private fun allPresent(target: Path, sums: Map<String, String>): Boolean =
        sums.keys.all { Files.isRegularFile(target.resolve(it)) }

    private fun readVersion(): String =
        javaClass.classLoader.getResource("whisper/WHISPER_VERSION.txt")?.let { url ->
            url.openStream().use { it.readBytes() }.decodeToString().trim()
        } ?: throw RuntimeExtractionException("Bundled whisper version file is missing")

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

    /** The lock name inside the runtime dir, held while a runtime is being extracted. */
    private const val EXTRACT_LOCK_NAME = ".extract.lock"

    /** How long to wait for another IDE's extraction before giving up on this pass. */
    private const val EXTRACT_LOCK_TIMEOUT_SECONDS = 120L

    /**
     * Extracts under a cross-process lock (review P6): the runtime dir is shared across IDEs,
     * and [LOCK] only serializes within this JVM. The validity pass is repeated under the lock —
     * if a concurrent IDE finished extracting while we waited, the work is skipped.
     */
    private fun extractUnderLock(target: Path, sums: Map<String, String>, platformDir: String) {
        Files.createDirectories(target)
        FileLock(target.resolve(EXTRACT_LOCK_NAME), EXTRACT_LOCK_TIMEOUT_SECONDS).use {
            if (!allPresentAndValid(target, sums)) {
                extract(target, sums, platformDir)
            }
        }
    }

    /** Extracts every bundled file into [target], verifying each one's checksum as it goes. */
    private fun extract(target: Path, sums: Map<String, String>, platformDir: String) {
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

    internal fun sha256(file: Path): String {
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

    /**
     * The bundled whisper-server (review P2) for this platform, or null when the platform bundle
     * ships no server binary (Windows for now — no cross-built .exe in the bundle). The binary is
     * part of the checksum-verified runtime, so resolving it also guarantees the libraries it
     * links are extracted next to it.
     */
    fun serverPath(): Path? {
        val name = if (SystemInfo.isWindows) "whisper-server.exe" else "whisper-server"
        return cliPath().parent.resolve(name).takeIf { Files.isRegularFile(it) }
    }

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

    /** Logical processor count (threads) available to the JVM. */
    fun availableThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * Recommended CPU threads for whisper: half the logical processor count. On SMT machines that is
     * exactly the physical core count — hyperthreads share execution units, so they don't help the
     * compute-bound matmuls past the core count; on machines without SMT it is a conservative value
     * (whisper.cpp itself defaults to just 4 threads). Half-the-logical needs no OS-specific SMT
     * detection and yields the same number on every platform.
     */
    fun defaultThreads(): Int = (availableThreads() / 2).coerceIn(1, availableThreads())
}
