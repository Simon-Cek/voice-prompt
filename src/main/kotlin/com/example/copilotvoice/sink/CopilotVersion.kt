package com.example.copilotvoice.sink

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Detects the installed GitHub Copilot plugin. Knowing the version lets us pick a known-good chat
 * locator for it (see [CopilotChatLocator]) and report which Copilot a failure happened against.
 */
object CopilotVersion {

    private const val COPILOT_PLUGIN_ID = "com.github.copilot"

    /** The installed Copilot plugin's version string, or null if the plugin isn't installed. */
    fun installed(): String? =
        PluginManagerCore.getPlugin(PluginId.getId(COPILOT_PLUGIN_ID))?.version

    /** A short human-readable description, for logs and failure balloons. */
    fun describe(): String = installed()?.let { "Copilot $it" } ?: "Copilot plugin not detected"
}
