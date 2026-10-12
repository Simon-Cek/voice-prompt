import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

version = "1.0.0"

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)

        // Declared here so it is part of sandbox preparation (PrepareSandboxTask installs it on
        // every `runIde` start). A copy installed by the user inside the sandbox does not survive
        // relaunches: `runIde` re-prepares the sandbox and wipes the user plugins directory.
        // This keeps GitHub Copilot available for the chat-insertion checks across relaunches.
        plugin("com.github.copilot", "1.19.0-252")
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

tasks.verifyPlugin {
    // The verifier JVM is a plain fork: without a cap it takes ~1/4 of physical RAM (7.5 GB here),
    // which under this machine's memory pressure crashed G1 (SIGSEGV in G1ParScanThreadState).
    // Cap the heap and lower GC concurrency so it stays well inside available memory.
    maxHeapSize = "5g"
    jvmArgs("-XX:ParallelGCThreads=4")
}
