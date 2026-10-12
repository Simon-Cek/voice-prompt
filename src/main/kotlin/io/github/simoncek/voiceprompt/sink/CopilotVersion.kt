package io.github.simoncek.voiceprompt.sink

import com.intellij.openapi.diagnostic.Logger

/**
 * Detects the installed GitHub Copilot plugin. The version is diagnostics only: it tells us
 * which Copilot a failure happened against (see [CopilotChatLocator]) so users can report it.
 * It must never drive behavior — detection can fail on any platform version.
 */
object CopilotVersion {

    private val log = Logger.getInstance(CopilotVersion::class.java)

    private const val COPILOT_PLUGIN_ID = "com.github.copilot"

    /**
     * The installed Copilot plugin's version string, or null if it cannot be detected (plugin not
     * installed, or the platform lookup is unavailable). Callers must treat null as "unknown".
     */
    fun installed(): String? = lookup()

    /** A short human-readable description, for logs and failure balloons. */
    fun describe(): String = installed()?.let { "Copilot $it" } ?: "Copilot (version unknown)"

    /**
     * Intentionally reflective — do not "clean this up" into a static call.
     *
     * The platform's "look up a plugin by id" API is mid-migration, and no single static call is
     * valid on every supported build (verified with the Plugin Verifier across 242-263):
     * - 2024.2-2025.1 (242-251): `com.intellij.openapi.extensions.PluginId` is a Java class and
     *   `PluginId.getId` is a plain static method.
     * - 2025.2+ (252+): `PluginId` became a Kotlin class with a *private* constructor, and `getId`
     *   is only reachable through `PluginId$Companion` — a class that does not exist on 242-251.
     *   A compiled `PluginId.getId(...)` emits the companion call and throws
     *   `NoClassDefFoundError` on those builds (this is what broke the plugin there).
     * - 2026.2+ (262+): the static facades (`PluginManager.getPlugin`,
     *   `PluginManagerCore.getPlugin`) are marked `@ApiStatus.Internal`, which fails the
     *   Plugin Verifier build.
     *
     * Reflecting the long-stable public facade (unchanged since 2017) keeps the code
     * binary-compatible across 242 -> 263 and the verifier clean.
     *
     * Diagnostics only: the result is never used to choose behavior; every caller handles null.
     */
    private fun lookup(): String? = try {
        val pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId")
        val pluginId = pluginIdClass
            .getMethod("getId", String::class.java)
            .invoke(null, COPILOT_PLUGIN_ID)
        val descriptor = Class.forName("com.intellij.ide.plugins.PluginManager")
            .getMethod("getPlugin", pluginIdClass)
            .invoke(null, pluginId)
        if (descriptor == null) {
            null
        } else {
            descriptor.javaClass.getMethod("getVersion").invoke(descriptor) as? String
        }
    } catch (t: Throwable) {
        // Never let a lookup failure break dictation: report "unknown" and move on.
        log.warn("Could not detect the GitHub Copilot plugin version", t)
        null
    }
}
