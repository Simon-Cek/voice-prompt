# Voice Prompt

Voice input for GitHub Copilot Chat in IntelliJ-based IDEs. Hold a shortcut, speak, release — your words land in the Copilot chat, transcribed locally with whisper.cpp. No cloud calls by default.

See [copilot-voice-plan.md](copilot-voice-plan.md) for the full plan (goals, architecture, MVP scope, risks).

## Building

```
./gradlew buildPlugin   # plugin zip in build/distributions/
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew test          # unit tests
```

Requires JDK 17+ on `PATH` (the Gradle toolchain resolves the rest).

## Status

MVP in progress — see the plan's §4 for scope and acceptance criteria.
