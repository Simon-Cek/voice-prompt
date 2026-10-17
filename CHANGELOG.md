<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Voice Prompt Changelog

## [Unreleased]

## [1.1.0] - 2026-10-17

Faster dictation, one shared data folder, and fail-closed model downloads.

### Added

- **Keep the model warm.** The Whisper model now runs in one long-lived `whisper-server` process instead of a fresh CLI (and a full model reload) for every utterance, so each clip starts faster — especially with a GPU.
- **Status bar indicator.** The dictation phase (idle, recording, transcribing) is shown in the status bar with an icon and text, in Light, Dark and High Contrast.
- **Opt-in keep-ready microphone.** Keep the capture line open between clips (Settings | Tools | Voice Prompt) so the next clip starts without re-opening the microphone.
- **Shared data folder.** The runtime and the models (bundled and downloaded) now live in one folder shared by all your IDEs — `%LOCALAPPDATA%\VoicePrompt` on Windows, `~/.local/share/voice-prompt` on Linux — instead of a per-IDE copy. Existing installations migrate automatically, and the location can be overridden with the `VOICE_PROMPT_HOME` environment variable.

### Changed

- **GPU fallback.** A GPU failure is remembered, so the next utterance falls back to the CPU instead of crashing and restarting.
- **Fail-closed model downloads.** Downloads are pinned to a specific commit of the whisper.cpp model repository and verified against SHA-256 checksums bundled with the plugin — a download without a known checksum is refused before a byte is fetched. An interrupted download keeps its progress and resumes when you retry.
- **Linux requirements, documented.** The bundled Linux binaries need glibc 2.34 or newer (Ubuntu 22.04+, Debian 12+, Fedora 36+) and a GCC 12-era libstdc++ (verified against the shipped binaries with objdump).

### Fixed

- Cancel and timeout now abort the in-flight inference on the server instead of leaving it queued behind the next clip.
- A whisper-server that never becomes healthy (blocked loopback, etc.) no longer keeps re-spawning: after two consecutive startup failures it is disabled for the session, and the loopback client bypasses system proxies.
- Expected transcription failures (a whisper error, a timeout) no longer light up the red plugin-error indicator; they are logged at warn and the notification is unchanged.
- Rapid clips can no longer collide on the temp WAV name; the file is created with a unique, owner-only name.
- A cancelled start can no longer leave the widget stuck on Recording.
- Stale `whisper-server-<pid>.log` files are cleaned up from the shared data folder at IDE startup.

## [1.0.0] - 2026-10-11

First release.

Voice input for GitHub Copilot Chat in IntelliJ IDEA, with fully local transcription.

### Added

- **Dictate into Copilot Chat.** Push-to-talk (hold to speak) or tap-to-start/stop. The default shortcut is `Alt+Shift+Z`, and you can change it in *Settings | Keymap*.
- **Works right after install.** The Whisper engine and the Tiny model are bundled, so it runs offline with no download and no account, including on restricted corporate laptops.
- **Windows 10/11 and Linux support** (64-bit x64).
- **Five speech models.** Tiny (bundled), Base, Small, Small multilingual and Large v3 Turbo. Download the ones you want from the settings page, with background progress and a cancel button.
- **Language selection** for multilingual models.
- **CPU or GPU processing.** Automatic, CPU only, or GPU (Vulkan), plus a CPU threads setting.
- **Microphone test** with a live level meter.
- **Optional auto-send after dictation** (off by default).
- **Restore bundled model.** Delete the Tiny model and bring it back in one click, with no download needed.

### Known limitations

- Transcription speed depends on your hardware and the model you choose. Larger models are slower on CPU.
- English-only models can't transcribe other languages. Pick a multilingual model for those.
- 64-bit x64 only. macOS, ARM and 32-bit systems are not supported.
- Inserting text into Copilot Chat was tested with GitHub Copilot plugin 1.19.0-252. Other Copilot versions may behave differently.
