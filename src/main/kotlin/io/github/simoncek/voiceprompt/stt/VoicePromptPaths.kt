package io.github.simoncek.voiceprompt.stt

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
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
 * downloaded model (up to 1.6 GB) was duplicated per IDE. A single shared home
 * (`~/.voice-prompt`) means the second IDE finds the files already there; the one-time
 * migration moves what the current IDE already holds on first use, so nothing has to be
 * re-downloaded.
 */
object VoicePromptPaths {

    private val LOG = Logger.getInstance(VoicePromptPaths::class.java)

    /** System property (review P6 test seam): full override of the home directory. */
    const val HOME_PROPERTY = "voiceprompt.home"

    private val migrationStarted = AtomicBoolean(false)

    /**
     * Test seam (review P6): redirects [baseDir] for a single test without a system property.
     * Null (the default) = production resolution.
     */
    internal var override: Path? = null

    /**
     * The shared home: [override] > the [HOME_PROPERTY] system property (the test suite points
     * it at a build-local directory so tests never touch the real home) > `~/.voice-prompt`.
     */
    fun baseDir(): Path {
        override?.let { return it }
        System.getProperty(HOME_PROPERTY)?.let { return Paths.get(it) }
        maybeStartMigration()
        val home = System.getProperty("user.home")
            ?: throw IOException("user.home is not set; cannot resolve the plugin home")
        return Paths.get(home, ".voice-prompt")
    }

    fun modelsDir(): Path = baseDir().resolve("models")

    fun runtimeDir(version: String, platformDir: String): Path =
        baseDir().resolve("runtime").resolve(version).resolve(platformDir)

    // --- one-time migration from the legacy per-IDE location (review P6) ---

    /**
     * Kicks off the one-time migration on a daemon thread: [baseDir] itself stays pure (path
     * computation only) so it is safe on the EDT, and the file moves never run there. The
     * migration and the normal install paths are mutually safe — both land complete files with
     * an atomic move — so a dictation that starts before the migration finishes simply
     * re-creates the file it needs (checksum-verified) and the migration keeps going.
     */
    private fun maybeStartMigration() {
        if (migrationStarted.get()) return
        // Unavailable in a platform-less JVM (plain unit tests): no legacy location to migrate.
        // Checked before consuming the one-shot flag so a too-early call doesn't lose the chance.
        val legacy = runCatching { Paths.get(PathManager.getSystemPath(), "copilot-voice") }.getOrNull()
            ?: return
        if (!migrationStarted.compareAndSet(false, true)) return
        Thread({
            runCatching { migrateFrom(legacy) }
                .onFailure { LOG.warn("migration from the per-IDE system path failed; missing files will be recreated", it) }
        }, "voice-prompt-migration").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Moves the models and the runtime from the legacy per-IDE base [legacy]
     * (`<IDE system path>/copilot-voice`) into the shared home. Best effort: each failure is
     * logged, and the normal extract/download path recreates anything that did not move.
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
