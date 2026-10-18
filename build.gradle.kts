import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

version = "1.1.0"

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // The IDE line is parameterized so CI can verify the whole `sinceBuild`..latest range
        // (`untilBuild` is null, i.e. "all future builds"): .github/workflows/build.yml passes
        // `-PplatformVersion=<line>` per matrix leg. The default stays the dev baseline.
        intellijIdea((findProperty("platformVersion") as String?) ?: "2025.3.6.1")
        testFramework(TestFrameworkType.Platform)

        // Declared here so it is part of sandbox preparation (PrepareSandboxTask installs it on
        // every `runIde` start). A copy installed by the user inside the sandbox does not survive
        // relaunches: `runIde` re-prepares the sandbox and wipes the user plugins directory.
        // This keeps GitHub Copilot available for the chat-insertion checks across relaunches.
        //
        // It is a local-dev convenience, not a plugin requirement — no test needs the real
        // plugin (the chat tests use a fixture window). Its builds are line-specific
        // (1.19.0-252 declares since-build 252, until-build 253.*), so CI legs on unsupported
        // lines pass an empty `-PcopilotPluginVersion=` to skip it entirely.
        val copilotVersion = (findProperty("copilotPluginVersion") as String?) ?: "1.19.0-252"
        if (copilotVersion.isNotBlank()) {
            plugin("com.github.copilot", copilotVersion)
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    // Review P6: the plugin's shared home (~/.voice-prompt) is redirected into the build
    // directory for the whole test suite, so no test ever reads or writes the developer's
    // real home.
    systemProperty("voiceprompt.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // The 2025.3+ platform dispatches Logger records asynchronously (verified in
    // com.intellij.openapi.diagnostic.AsyncLogKt, util-8.jar of IU-2025.3.6.1: every
    // JulLogger call is enqueued and published by a background coroutine). Tests that assert on
    // captured log records (LocatorLogLevelTest, MicTimingLogTest, TranscriptLogGuardTest) then
    // race the consumer — LocatorLogLevelTest flaked on the CI 253 leg ("no locator log record
    // found"). This is the platform's own switch: with it set, AsyncLogKt falls back to
    // synchronous Logger.log(...) dispatch, so the record reaches the test's JUL handler before
    // the call returns. A no-op on platform lines without async dispatch (e.g. 242).
    jvmArgs("-Dintellij.platform.log.sync=true")
}

tasks.verifyPlugin {
    // The verifier JVM is a plain fork: without a cap it takes ~1/4 of physical RAM (7.5 GB here),
    // which under this machine's memory pressure crashed G1 (SIGSEGV in G1ParScanThreadState).
    // Cap the heap and lower GC concurrency so it stays well inside available memory.
    maxHeapSize = "5g"
    jvmArgs("-XX:ParallelGCThreads=4")
}
