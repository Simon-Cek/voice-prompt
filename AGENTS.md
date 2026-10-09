# AGENTS.md

Copilot Voice is an **IntelliJ Platform plugin** (not a standalone app): voice-to-text for
GitHub Copilot Chat, transcribed locally with whisper.cpp. You "run" it by booting a sandbox
IDE with the plugin installed via Gradle's `runIde` task.

## Running the app (use these scripts, not raw `runIde`)

The project ships wrapper scripts in `bin/`. Prefer them over calling `./gradlew runIde`
directly — `runIde` is a long-lived foreground process that hangs a plain shell, and a wedged
Gradle/Kotlin daemon will hang the next start. The scripts handle both.

```bash
bash bin/ide-start.sh    # launch the sandbox IDE with our plugin (detached, survives the shell)
bash bin/ide-status.sh   # is the IDE up? is our plugin loaded? recent log lines
bash bin/ide-stop.sh     # stop the IDE + its build daemons (idempotent, safe to re-run)
```

### What each does
- **`bin/ide-start.sh`** — first runs `ide-stop.sh` to clear any prior instance and daemons,
  then launches `./gradlew runIde` fully detached in a `screen` session named `copilot-ide`.
  Output goes to `/tmp/opencode/runide.out`. Give it **~40s** to compile + open, then check
  with `ide-status.sh`.
- **`bin/ide-status.sh`** — reports the IDE process, greps the IDE log for our plugin
  (`copilot-voice` / `CopilotVoice` / `PushToTalk`), and tails the IDE log.
- **`bin/ide-stop.sh`** — `pkill`s the sandbox IDE (`runIde`) plus the Kotlin compiler daemon
  and Gradle daemon so a wedged daemon can't hang the next start. Idempotent; prints how many
  build processes remain.

### Key paths
| What | Path |
| --- | --- |
| IDE (sandbox) log | `.intellijPlatform/sandbox/copilot-voice/IU-2025.3.6.1/log_runIde/idea.log` |
| `runIde` stdout/stderr | `/tmp/opencode/runide.out` |
| Plugin zip (distributable) | `build/distributions/copilot-voice-0.1.0.zip` |
| Gradle config cache / build state | `.gradle/`, `build/` |

If `ide-start.sh` hangs or the IDE never opens, run `bash bin/ide-stop.sh`, then tail
`/tmp/opencode/runide.out` for the real error before retrying.

## Build & test

```bash
./gradlew buildPlugin   # compile + package the installable zip (build/distributions/)
./gradlew test          # unit tests (STT + WAV)
```

Requires JDK 17+ on `PATH` (the Gradle toolchain resolves the exact version).

## Project layout (where things live)

- `src/main/kotlin/com/example/copilotvoice/settings/` — `CopilotVoiceSettings`, `CopilotVoiceConfigurable`, `RecordingMode`
- `src/main/kotlin/com/example/copilotvoice/actions/PushToTalkAction.kt` — PTT + TAP mode, start/stop, beeps
- `src/main/kotlin/com/example/copilotvoice/audio/` — `AudioCapture`, `Beeps`, `WavFile`
- `src/main/kotlin/com/example/copilotvoice/stt/` — `ModelManager`, `WhisperCliEngine`
- `src/main/resources/META-INF/plugin.xml` — plugin descriptor (actions, configurable, resource bundle)
- `src/main/resources/messages/VoiceBundle.properties` — UI strings (add new labels here)
- `bin/` — the start/stop/status scripts above
- `.run/` — IntelliJ IDE run configs (Run Plugin / Run Tests / Run Verifications)
