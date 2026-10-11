package com.example.copilotvoice.settings

import com.intellij.testFramework.LightPlatformTestCase

/**
 * The IntelliJ framework reuses one [CopilotVoiceConfigurable] across settings sessions — it calls
 * [CopilotVoiceConfigurable.createPanel] again after [CopilotVoiceConfigurable.disposeUIResources].
 *
 * Historically the page registered its UI listeners against a single disposable that was disposed
 * in disposeUIResources() and then reused by the next createPanel(), so the second open threw
 * [com.intellij.openapi.util.IncorrectOperationException] (registering a child on a disposed parent).
 * This test drives that exact sequence and fails if the page can't survive it.
 */
class CopilotVoiceConfigurableLifecycleTest : LightPlatformTestCase() {

    fun test_panel_survives_repeated_dispose_and_rebuild() {
        val configurable = CopilotVoiceConfigurable()
        // Open → close → open → close, like a user navigating to the page in two separate sessions.
        repeat(3) {
            val panel = configurable.createPanel()
            assertNotNull("createPanel() must return a panel", panel)
            configurable.disposeUIResources()
        }
    }

    fun test_dispose_is_idempotent() {
        val configurable = CopilotVoiceConfigurable()
        configurable.createPanel()
        configurable.disposeUIResources()
        // The framework (or a double close) must not be able to crash us by disposing twice.
        configurable.disposeUIResources()
    }
}
