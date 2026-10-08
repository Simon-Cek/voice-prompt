# Copilot Voice: Plan for an IntelliJ Plugin

Voice input for GitHub Copilot Chat in IntelliJ-based IDEs.

---

## 1. Goals

| Goal | MVP | Polished |
|------|-----|----------|
| Push-to-talk dictation into Copilot Chat | ✅ | ✅ |
| Local, offline speech-to-text | ✅ | ✅ |
| Cloud speech-to-text option | ❌ | ✅ |
| Hands-free wake word ("Hey Copilot") | ❌ | ✅ |
| Voice commands ("explain this file", "send", "cancel") | ❌ | ✅ |
| Context injection (selection, file, error) | ❌ | ✅ |
| Settings UI, status bar widget, onboarding | Minimal | ✅ |
| Published to the JetBrains Marketplace | ❌ | ✅ |

**Non-goals:** speaking Copilot's responses aloud (TTS) is out of scope for v1 and could be added later. Reimplementing Copilot's chat is also out of scope.

### 1.1 Lessons from existing plugins (why build this)

These come from trying existing plugins on a Windows work machine:

| Plugin | Problem on Windows |
|--------|--------------------|
| **Voice Input** (Federico Carpeggiani) | Ships only a `linux-x64` whisper.cpp runtime. Fails with `CreateProcess error=193, %1 is not a valid Win32 application`. Its default shortcut `Meta+V` (Win+V) is taken by Windows Clipboard History. |
| **Whisper Speech to Text** | Local mode compiles whisper.cpp from source and needs git, cmake, make and gcc or clang. Its error message only gives the Linux install command (`sudo apt install …`). |

Design requirements that follow:
- **Windows first.** Bundle a **prebuilt** `whisper-cli.exe` and its DLLs. Users never compile anything.
- **No Win-key shortcuts.** Windows reserves Win+V, Win+Space and Win+H.
- **Model downloaded separately** from **Settings → Tools → Copilot Voice**. Keep a manual "Browse…" option for networks where a proxy blocks downloads.
- **Local only by default.** No OpenAI or other cloud calls unless the user opts in.

---

## 2. Key risk: integrating with Copilot

The Copilot JetBrains plugin has **no public API** for sending chat messages. Any integration relies on its UI internals.

| Strategy | How | Stability | Use |
|----------|-----|-----------|-----|
| **A. Swing tree injection** | Find the Copilot Chat tool window, walk its component tree, find the input `JTextComponent`, set the text, and trigger send (Enter key event or the send button) | Medium: breaks when the UI is restructured | **Primary** |
| **B. Clipboard + paste** | Focus the Copilot tool window, put the text on the clipboard, dispatch Ctrl+V and Enter | Medium to high | **Fallback** |
| **C. AWT `Robot` typing** | Simulate keystrokes | Low: slow, depends on keyboard layout | Last resort |
| **D. Copilot CLI / SDK** | Send voice text to the `copilot` CLI in a terminal tool window | High, but a different chat surface | Optional "CLI mode" |

Design the plugin around an `ChatSink` interface so strategies can be swapped and tried in order at runtime:

```kotlin
interface ChatSink {
    fun isAvailable(project: Project): Boolean
    fun insert(project: Project, text: String, autoSend: Boolean): Result<Unit>
}
```

---

## 3. Architecture

```
┌────────────────────────────────────────────────────────────┐
│                     IntelliJ Plugin                        │
│                                                            │
│  ┌──────────────┐   ┌──────────────┐   ┌────────────────┐  │
│  │ Trigger      │──▶│ AudioCapture │──▶│ SttEngine      │  │
│  │ (PTT action, │   │ (javax.sound │   │ (Whisper/Vosk/ │  │
│  │  wake word)  │   │  16kHz mono) │   │  Azure/OpenAI) │  │
│  └──────────────┘   └──────────────┘   └───────┬────────┘  │
│                                                ▼           │
│                                       ┌────────────────┐   │
│                                       │ CommandRouter  │   │
│                                       │ (intent parse) │   │
│                                       └───┬────────┬───┘   │
│                         dictation text    │        │ IDE command
│                                           ▼        ▼       │
│                                 ┌───────────┐ ┌──────────┐ │
│                                 │ ChatSink  │ │ IDE      │ │
│                                 │ (A/B/C/D) │ │ Actions  │ │
│                                 └───────────┘ └──────────┘ │
│                                                            │
│  Settings (PersistentStateComponent) · Status bar widget   │
└────────────────────────────────────────────────────────────┘
```

### Tech stack
- **Language:** Kotlin
- **Build:** Gradle with the IntelliJ Platform Gradle Plugin (2.x)
- **Target:** IntelliJ Platform 2024.2+ (all IntelliJ-based IDEs)
- **Audio:** `javax.sound.sampled.TargetDataLine` (16 kHz, 16-bit, mono PCM)
- **STT (local, MVP):** a **sidecar `whisper-cli.exe`** from the prebuilt Windows release of [whisper.cpp](https://github.com/ggml-org/whisper.cpp/releases) (`whisper-bin-x64.zip`), bundled in the plugin and run as a separate process. See §4.1.
- **STT (local, alternatives):** [whisper-jni](https://github.com/GiviMAD/whisper-jni) (in-process JNI), or Vosk (`com.alphacephei:vosk`)
- **STT (cloud):** Azure AI Speech SDK or the OpenAI `/v1/audio/transcriptions` endpoint
- **Wake word (polished):** Porcupine (Picovoice) or openWakeWord through ONNX Runtime
- **VAD (polished):** Silero VAD through ONNX Runtime

---

## 4. MVP (about 2–5 days)

### Scope
- Hold a shortcut to record and release it to transcribe. The default is `Ctrl+Alt+Shift+D`; check it for conflicts in Keymap. Don't use the Win key, and don't use `Ctrl+Alt+V`, which is IntelliJ's Extract Variable.
- **Windows x64 only for the MVP.** A bundled prebuilt `whisper-cli.exe` transcribes the audio with the `base.en` or `small.en` model.
- The user downloads the model from **Settings → Tools → Copilot Voice**, or picks a file manually.
- The text is inserted into the Copilot Chat input with Strategy A, falling back to B.
- Optional auto-send.
- A simple settings page for the model path, auto-send, and shortcut.
- A notification balloon shows errors and status.

### Steps

#### Day 1: Scaffold and audio
1. Generate the project from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
2. Add `plugin.xml` dependencies: `com.intellij.modules.platform`, plus an optional dependency on `com.github.copilot`.
3. Implement `AudioCapture`:
   - `start()` opens a `TargetDataLine` and buffers to a `ByteArrayOutputStream` on a background thread.
   - `stop(): ShortArray/FloatArray` returns the PCM samples.
4. Register a `PushToTalkAction` (an `AnAction`). Use the action's key press and release, or a custom `IdeEventQueue` dispatcher, to detect hold and release.

#### Day 2: Speech-to-text (bundled Windows Whisper)
1. Download `whisper-bin-x64.zip` from the whisper.cpp releases page and put `whisper-cli.exe` and its `.dll` files in `src/main/resources/whisper/win-x64/`. Pin the version in `WHISPER_VERSION.txt` and record SHA-256 checksums.
2. On first use, `WhisperRuntime` extracts the exe and DLLs to `PathManager.getSystemPath()/copilot-voice/runtime/<version>/`. Skip extraction if they're already there and the checksums match.
3. `AudioCapture` writes a 16 kHz, mono, 16-bit WAV file to a temp file.
4. Implement `WhisperCliEngine.transcribe(wav): String`:
   - Run `whisper-cli.exe -m <model> -f <wav> -nt -l en` with `GeneralCommandLine` and `CapturingProcessHandler`, with a timeout.
   - Use stdout as the transcript.
   - Delete the WAV file afterwards.
5. Run transcription in `Task.Backgroundable` with the progress text "Transcribing…".
6. If no model is installed, show a notification with an **Open Settings** link instead of failing. See §4.1.

#### Day 3: Copilot Chat injection
1. Find the tool window: `ToolWindowManager.getInstance(project).getToolWindow("GitHub Copilot Chat")`. **Verify the exact ID** in the installed Copilot version with UI Inspector (`Ctrl+Alt+Shift+F1` in internal mode).
2. Activate the window, then walk its content components with `UIUtil.findComponentsOfType(root, JTextComponent::class.java)`. Choose the editable, visible input, which may be an `EditorTextField` and needs `editor.document` handling.
3. Set the text with `WriteCommandAction` for `EditorTextField`, or `setText` for a plain `JTextComponent`.
4. If auto-send is on, dispatch an Enter `KeyEvent` to the component or find and `doClick()` the send button.
5. Implement `ClipboardPasteSink` as the fallback.

#### Day 4: Settings and polish
1. Create `VoiceSettings` (a `PersistentStateComponent`) with fields for the model name, a custom model path, auto-send, language, microphone, and selected sink.
2. Add a `Configurable` page under **Tools → Copilot Voice**, with the model manager from §4.1.
3. Show error notifications for no microphone, a missing model, or a missing Copilot chat window.

### 4.1 Model download from Settings

Register the page in `plugin.xml`:

```xml
<applicationConfigurable parentId="tools"
    instance="com.example.copilotvoice.settings.VoiceConfigurable"
    id="com.example.copilotvoice.settings"
    displayName="Copilot Voice"/>
```

The page contains:
- **Model dropdown** with these options:

  | Model | Size |
  |-------|------|
  | Tiny (`tiny.en`) | about 75 MB |
  | Base (`base.en`) | about 142 MB (default) |
  | Small (`small.en`) | about 466 MB |
  | Medium (`medium.en`) | about 1.5 GB |

  Each entry shows whether it's installed.
- **Download** button:
  - Downloads with `HttpRequests` (which respects the IDE's proxy settings) inside `ProgressManager` / `Task.Backgroundable`, with progress and a Cancel option.
  - The source URL is `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-<model>.bin`.
  - Downloads to a `.part` file first, then renames it once complete.
- **Delete** button for installed models.
- **Browse…** button to use a `.bin` file downloaded manually. This is the fallback when a corporate proxy blocks Hugging Face.
- **Microphone** dropdown, listing `AudioSystem.getMixerInfo()` entries that support `TargetDataLine`.
- **Language** setting, `en` by default.

Save models to `PathManager.getSystemPath()/copilot-voice/models/`. `ModelManager` resolves the active model: the custom path if it's set, otherwise the downloaded model.

#### Day 5: Testing and packaging
1. Test manually in IntelliJ IDEA and one other IDE, such as PyCharm or WebStorm.
2. Add unit tests for the audio buffer and the sink selection logic.
3. Run `./gradlew buildPlugin`, then install from disk to dogfood.

### MVP acceptance criteria
- [ ] Holding the shortcut records audio, and the status bar shows "🎙 Listening".
- [ ] Releasing the shortcut puts the transcribed text in the Copilot Chat input within about 2 seconds for a 5-second clip on a modern CPU.
- [ ] Auto-send works when enabled.
- [ ] Fails gracefully with a clear message if Copilot isn't installed or its UI has changed.

---

## 5. Polished version (about 3–6 weeks after the MVP)

### 5.1 Hands-free mode
- **Wake word:** "Hey Copilot" via Porcupine (custom keyword) or openWakeWord.
- **VAD:** Silero VAD detects the end of speech automatically, so no button is needed.
- **Continuous listening** can be toggled from the status bar widget, with a visible indicator.
- **Privacy:** the plugin never records unless listening is enabled, and audio is never stored or sent anywhere while local STT is selected.

### 5.2 Voice command grammar
`CommandRouter` checks the transcript against command patterns before treating it as dictation:

| Utterance | Action |
|-----------|--------|
| "send" / "submit" | Press send in Copilot Chat |
| "cancel" / "clear" | Clear the chat input |
| "explain this" / "explain this file" | Insert `/explain` with the current selection or file as context |
| "fix this" | `/fix` with the selection |
| "write tests for this" | `/tests` with the selection |
| "new chat" | Start a new Copilot conversation |
| "open file X" | `GotoFile` with the query X |
| "go to line N" | Move the caret |
| "stop listening" | Turn hands-free mode off |
| anything else | Treat as dictation and send to `ChatSink` |

Implementation path:
1. **v1:** regex or keyword matching, configurable in settings.
2. **v2 (optional):** a small local intent classifier, or a structured LLM call for fuzzy matching.

### 5.3 Context injection
- Before sending, optionally prefix with `#selection`, `#file`, or the current error under the caret, using Copilot's chat variables where supported.
- Voice phrases such as "with this file" or "about this error" trigger context attachment.

### 5.4 STT engines
- Pluggable `SttEngine` interface: `WhisperLocal`, `VoskLocal`, `AzureSpeech`, `OpenAIWhisper`.
- **Streaming partial results** (Azure or Vosk) show live text in a popup while you speak.
- **Custom vocabulary or prompt biasing:** feed Whisper's `initial_prompt` with project symbols (class names, frameworks) to improve accuracy on code terms.
- **GPU acceleration:** whisper.cpp with CUDA, Metal or Vulkan builds when available.
- Store API keys with `PasswordSafe`, never in plain text.

### 5.5 Copilot integration hardening
- **Version-aware sinks:** detect the installed Copilot plugin version (`PluginManagerCore.getPlugin(PluginId.getId("com.github.copilot"))`) and choose a known-good locator.
- **Self-test action:** a "Copilot Voice: Diagnose" action reports which sink works.
- **Automatic fallback chain:** A → B → C, with a notification when it degrades.
- **CI canary:** a nightly job installs the latest Copilot plugin into a headless IDE (`runIdeForUiTests` + [Remote-Robot](https://github.com/JetBrains/intellij-ui-test-robot)) and checks that injection still works.
- **Optional CLI mode (Strategy D):** send voice text to the `copilot` CLI in the Terminal tool window for users who prefer it.

### 5.6 UX
- **Status bar widget:** idle, listening, transcribing and error states, with a click to toggle.
- **Floating transcript popup** (`JBPopup`) with live partial text and buttons to edit or confirm before sending.
- **Audio feedback:** optional start and stop chimes.
- **Microphone picker** and input level meter in settings.
- **Languages:** expose Whisper's language choice; English models are the default.
- **Accessibility:** works with screen readers and supports remapping all shortcuts.

### 5.7 Quality and release
- **Tests:** unit tests for `CommandRouter` and the settings, integration tests for the sinks with Remote-Robot, and a WAV-fixture regression suite for STT accuracy (WER).
- **Telemetry (opt-in only):** sink success rate and transcription latency, with no audio or text.
- **Plugin verifier:** `./gradlew verifyPlugin` against the supported IDE versions.
- **Marketplace:** signing, a changelog, screenshots or GIFs, and a privacy policy that explains microphone use.
- **Licensing:** check the model and library licenses (Whisper MIT, Vosk Apache-2.0, Porcupine commercial terms).

### Polished timeline

| Week | Deliverable |
|------|-------------|
| 1 | Pluggable STT engines, cloud engines, PasswordSafe |
| 2 | VAD, wake word, hands-free mode, status bar widget |
| 3 | `CommandRouter` and the voice command set, context injection |
| 4 | Transcript popup, streaming partials, mic picker, vocabulary biasing |
| 5 | Sink hardening, diagnose action, Remote-Robot CI canary |
| 6 | Tests, plugin verifier, docs, Marketplace release |

---

## 6. Project structure

```
copilot-voice/
├── build.gradle.kts
├── gradle.properties
├── src/main/kotlin/com/example/copilotvoice/
│   ├── actions/
│   │   ├── PushToTalkAction.kt
│   │   ├── ToggleHandsFreeAction.kt
│   │   └── DiagnoseAction.kt
│   ├── audio/
│   │   ├── AudioCapture.kt
│   │   └── Vad.kt                    # polished
│   ├── stt/
│   │   ├── SttEngine.kt
│   │   ├── WhisperRuntime.kt         # extracts the bundled whisper-cli.exe
│   │   ├── WhisperCliEngine.kt
│   │   ├── VoskEngine.kt             # polished
│   │   ├── AzureSpeechEngine.kt      # polished
│   │   └── ModelManager.kt
│   ├── wake/WakeWordDetector.kt      # polished
│   ├── commands/CommandRouter.kt     # polished
│   ├── sink/
│   │   ├── ChatSink.kt
│   │   ├── SwingInjectionSink.kt
│   │   ├── ClipboardPasteSink.kt
│   │   ├── RobotTypingSink.kt        # polished
│   │   └── CopilotCliSink.kt         # polished
│   ├── settings/
│   │   ├── VoiceSettings.kt
│   │   └── VoiceConfigurable.kt
│   └── ui/
│       ├── VoiceStatusBarWidget.kt
│       └── TranscriptPopup.kt        # polished
├── src/main/resources/
│   ├── META-INF/plugin.xml
│   └── whisper/win-x64/              # whisper-cli.exe, *.dll, WHISPER_VERSION.txt, SHA256SUMS
└── src/test/kotlin/...
```

---

## 7. Risks and mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Copilot plugin UI changes break injection | High | Sink abstraction, fallback chain, CI canary, quick patch releases |
| Native whisper libraries per OS and architecture | Medium | MVP bundles the prebuilt Windows x64 `whisper-cli.exe`. Add `linux-x64` and `macos-arm64` folders later, and pick the folder from `SystemInfo` at runtime. |
| Users forced to compile whisper.cpp, as with Whisper Speech to Text | High | Never require a build toolchain; ship prebuilt binaries only |
| Wrong-OS binary shipped, as with Voice Input's `error=193` | High | Check the OS and architecture before running, and show a clear "unsupported platform" message |
| Antivirus or endpoint security blocks the extracted `.exe` | Medium | Extract to the IDE system folder, verify checksums, and document an allow-list path for IT |
| Corporate proxy blocks the model download | Medium | Download through `HttpRequests` (uses the IDE proxy settings), with a **Browse…** fallback for a manually downloaded file |
| Shortcut swallowed by Windows (Win+V, Win+Space, Win+H) | Medium | Default to Ctrl/Alt/Shift combinations only, and document the Keymap override |
| Model size (75 MB–500 MB) | Medium | Download on demand and let the user choose the model size |
| CPU latency on low-end machines | Medium | Default to `base.en`, offer cloud engines, use the GPU when available |
| Misrecognising code terms | Medium | Prompt biasing with project symbols, editable transcript before send |
| Privacy concerns about an always-on mic | Medium | Hands-free mode is opt-in, local processing by default, clear indicator |
| Marketplace review or ToS concerns with Copilot UI automation | Low–Medium | Automate only user-initiated input, read no Copilot data, document the behaviour |

---

## 8. Immediate next steps

1. Install the Copilot plugin in a sandbox IDE (`./gradlew runIde`) and use UI Inspector to record the chat tool window ID and the input and send component classes.
2. Prototype `SwingInjectionSink` with a hard-coded string, which proves the riskiest part first.
3. On a **personal Windows machine** (the work device is too restricted), download `whisper-bin-x64.zip` and `ggml-base.en.bin`. Check from a terminal that `whisper-cli.exe -m ggml-base.en.bin -f sample.wav -nt` works.
4. Build the **Settings → Tools → Copilot Voice** page with model download and Browse (§4.1).
5. Add audio capture and the bundled `whisper-cli.exe` engine, then wire up push-to-talk. The MVP is then complete.
