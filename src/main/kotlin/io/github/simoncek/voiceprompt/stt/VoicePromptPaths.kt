package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The on-disk home for everything this plugin persists (review P6): the whisper runtime, the
 * models (bundled and downloaded) and the server log.
 *
 * These used to live under [PathManager.getSystemPath], which is per IDE product *and* version —
 * so every IDE the user installed re-extracted the ~180 MB of runtime and model, and every
 * downloaded model (up to 1.6 GB) was duplicated per IDE. A single shared home means the second
 * IDE finds the files already there.
 *
 * The default home is per platform (review N11): `%LOCALAPPDATA%\VoicePrompt` on Windows
 * (`%USERPROFILE%` can be roaming, redirected or synced there, and running DLLs/EXEs from it
 * gets slowed by antivirus or blocked by AppLocker), `$XDG_DATA_HOME/voice-prompt`
 * (or `~/.local/share/voice-prompt`) on Linux, and the historical `~/.voice-prompt` elsewhere.
 * The `VOICE_PROMPT_HOME` environment variable overrides the default for a user or a corporate
 * policy. The one-time migrations move what earlier versions already hold (the per-IDE system
 * path, and the old `~/.voice-prompt` default) into the resolved home on first use, so nothing
 * has to be re-downloaded.
 */
object VoicePromptPaths {

    private val LOG = Logger.getInstance(VoicePromptPaths::class.java)

    /** System property (review P6 test seam): full override of the home directory. */
    const val HOME_PROPERTY = "voiceprompt.home"

    /** Environment variable (review N11): a user or corporate-policy override of the home. */
    const val HOME_ENV = "VOICE_PROMPT_HOME"

    private val migrationStarted = AtomicBoolean(false)

    /**
     * Test seam (review P6): redirects [baseDir] for a single test without a system property.
     * Null (the default) = production resolution.
     */
    internal var override: Path? = null

    /** Test seam: a test JVM cannot set its own environment, so [baseDir] looks env vars up here. */
    internal var getenv: (String) -> String? = { System.getenv(it) }

    /**
     * The shared home: [override] > the [HOME_PROPERTY] system property (the test suite points
     * it at a build-local directory so tests never touch the real home) > the [HOME_ENV]
     * environment variable (review N11) > the platform default ([defaultHome]).
     */
    fun baseDir(): Path {
        override?.let { return it }
        System.getProperty(HOME_PROPERTY)?.let { return Paths.get(it) }
        getenv(HOME_ENV)?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        val home = defaultHome(
            isWindows = SystemInfo.isWindows,
            isLinux = SystemInfo.isLinux,
            userHome = System.getProperty("user.home"),
            localAppData = getenv("LOCALAPPDATA"),
            xdgDataHome = getenv("XDG_DATA_HOME"),
        )
        maybeStartMigration(home)
        return home
    }

    /**
     * The platform default home (review N11), as a pure function of the environment so the
     * per-OS rules are testable on any runner:
     * - Windows: `%LOCALAPPDATA%\VoicePrompt` — `%USERPROFILE%` can be roaming, redirected or
     *   synced on corporate machines, and running DLLs/EXEs from it gets slowed by antivirus or
     *   blocked by AppLocker;
     * - Linux: `$XDG_DATA_HOME/voice-prompt`, or `~/.local/share/voice-prompt` when the XDG
     *   variable is absent;
     * - elsewhere (macOS): the historical `~/.voice-prompt` — no roaming concern there.
     * Each branch falls back to `~/.voice-prompt` when its directory variable is missing.
     */
    internal fun defaultHome(
        isWindows: Boolean,
        isLinux: Boolean,
        userHome: String?,
        localAppData: String?,
        xdgDataHome: String?,
    ): Path {
        // The historical location; also the fallback whenever the platform variable is missing.
        fun dotDir(): Path = Paths.get(
            userHome ?: throw IOException("user.home is not set; cannot resolve the plugin home"),
            ".voice-prompt",
        )
        fun localShare(): Path = Paths.get(
            userHome ?: throw IOException("user.home is not set; cannot resolve the plugin home"),
            ".local", "share", "voice-prompt",
        )
        if (isWindows) {
            return localAppData?.takeIf { it.isNotBlank() }?.let { Paths.get(it, "VoicePrompt") }
                ?: dotDir()
        }
        if (isLinux) {
            return xdgDataHome?.takeIf { it.isNotBlank() }?.let { Paths.get(it, "voice-prompt") }
                ?: localShare()
        }
        return dotDir()
    }

    fun modelsDir(): Path = baseDir().resolve("models")

    fun runtimeDir(version: String, platformDir: String): Path =
        baseDir().resolve("runtime").resolve(version).resolve(platformDir)

    // --- one-time migrations from the legacy locations (review P6 per-IDE path, N11 shared home) ---

    /**
     * Kicks off the one-time migrations on a daemon thread: [baseDir] itself stays pure (path
     * computation only) so it is safe on the EDT, and the file moves never run there. Two
     * legacy locations feed the shared home (review N11): the per-IDE system path (P6) and the
     * old cross-IDE `~/.voice-prompt` default that moved to the platform location. The
     * migrations and the normal install paths are mutually safe — all land complete files with
     * an atomic move — so a dictation that starts before they finish simply re-creates the file
     * it needs (checksum-verified) and the migrations keep going.
     */
    private fun maybeStartMigration(target: Path) {
        if (migrationStarted.get()) return
        // Unavailable in a platform-less JVM (plain unit tests): no per-IDE location to migrate.
        // Checked before consuming the one-shot flag so a too-early call doesn't lose the chance.
        val legacyPerIde = runCatching { Paths.get(PathManager.getSystemPath(), "copilot-voice") }.getOrNull()
        val legacyShared = System.getProperty("user.home")?.let { Paths.get(it, ".voice-prompt") }
        val candidates = migrationCandidates(target, legacyPerIde, legacyShared)
        if (candidates.isEmpty()) return
        if (!migrationStarted.compareAndSet(false, true)) return
        Thread({
            candidates.forEach { legacy ->
                runCatching { migrateFrom(legacy) }
                    .onFailure { LOG.warn("migration from $legacy failed; missing files will be recreated", it) }
            }
        }, "voice-prompt-migration").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Pure (review N11): the legacy locations to migrate from, in order. The target itself is
     * never a source — on macOS the resolved home IS the historical `~/.voice-prompt`, and
     * migrating a directory into itself would delete it.
     */
    internal fun migrationCandidates(target: Path, legacyPerIde: Path?, legacyShared: Path?): List<Path> {
        val candidates = mutableListOf<Path>()
        if (legacyPerIde != null && legacyPerIde != target) candidates.add(legacyPerIde)
        if (legacyShared != null && legacyShared != target && legacyShared != legacyPerIde) {
            candidates.add(legacyShared)
        }
        return candidates
    }

    /**
     * Moves the models and the runtime from a legacy base [legacy] — the per-IDE
     * `<IDE system path>/copilot-voice` (P6) or the old cross-IDE `~/.voice-prompt` default
     * (N11) — into the shared home. Best effort: each failure is logged, and the normal
     * extract/download path recreates anything that did not move.
     * Exposed (internal) so the migration is testable without a platform.
     */
    internal fun migrateFrom(legacy: Path) {
        try {
            migrateModels(legacy)
        } catch (t: Exception) {
            LOG.warn("could not migrate the models to the shared home", t)
        }
        try {
            migrateRuntime(legacy)
        } catch (t: Exception) {
            LOG.warn("could not migrate the runtime to the shared home", t)
        }
    }

    /** Moves every `ggml-*.bin` into the shared models dir; a copy already there wins. */
    private fun migrateModels(legacy: Path) {
        val from = legacy.resolve("models")
        if (!Files.isDirectory(from)) return
        val to = modelsDir()
        Files.list(from).use { stream ->
            stream.filter { path ->
                val name = path.fileName.toString()
                Files.isRegularFile(path) && name.startsWith("ggml-") && name.endsWith(".bin")
            }.forEach { src ->
                val dst = to.resolve(src.fileName)
                try {
                    if (Files.exists(dst)) {
                        // The shared home already has this model; the per-IDE copy is redundant.
                        Files.delete(src)
                        LOG.info("removed redundant per-IDE model: $src")
                    } else {
                        // Create the dir only when something actually moves — an IDE with no
                        // finished models should not gain an empty models/ dir.
                        Files.createDirectories(to)
                        Files.move(src, dst)
                        LOG.info("moved model to the shared home: $src -> $dst")
                    }
                } catch (t: Exception) {
                    LOG.warn("could not migrate model $src; it will be recreated on demand", t)
                }
            }
        }
    }

    /**
     * Moves the whole `runtime/<version>/<platform>` tree (including its `.validated` marker,
     * so the checksum pass is not repeated); a runtime already present in the shared home wins.
     */
    private fun migrateRuntime(legacy: Path) {
        val from = legacy.resolve("runtime")
        if (!Files.isDirectory(from)) return
        val to = baseDir().resolve("runtime")
        if (Files.exists(to)) {
            // The shared home already has a runtime; the per-IDE copy is redundant.
            // (kotlin.io deleteRecursively — the compile-classpath JDK stub for Files lacks the
            // java.nio one.)
            val deleted = from.toFile().deleteRecursively()
            if (deleted) LOG.info("removed redundant per-IDE runtime: $from")
            else LOG.warn("could not remove the redundant per-IDE runtime: $from")
        } else {
            Files.createDirectories(to.parent)
            Files.move(from, to)
            LOG.info("moved runtime to the shared home: $from -> $to")
        }
    }
}
