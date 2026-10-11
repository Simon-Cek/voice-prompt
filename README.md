# Voice Prompt

Voice input for GitHub Copilot Chat in IntelliJ-based IDEs. Hold a shortcut, speak, release — your words land in the Copilot chat input (even if the editor has focus), transcribed locally with whisper.cpp. No cloud calls by default.

See [copilot-voice-plan.md](copilot-voice-plan.md) for the full plan (goals, architecture, MVP scope, risks).

## Building

```
./gradlew buildPlugin   # plugin zip in build/distributions/
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew test          # unit tests
```

Requires JDK 17+ on `PATH` (the Gradle toolchain resolves the rest).

## Status

v1.0.0 — released. All MVP acceptance criteria (plan §4) verified in the sandbox IDE against Copilot 1.19.0-252 on IntelliJ IDEA 2025.3.6.1. See [CHANGELOG.md](CHANGELOG.md) for the release notes.
