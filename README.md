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

v1.1.0 — released. Faster dictation (a long-lived whisper-server keeps the model warm), a status bar indicator, opt-in keep-ready microphone, one shared data folder (`VOICE_PROMPT_HOME`), and fail-closed model downloads with resume. See [CHANGELOG.md](CHANGELOG.md) for the release notes.
