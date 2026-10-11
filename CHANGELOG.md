<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Voice Prompt Changelog

## [Unreleased]

## [1.0.0] - 2026-10-11

First release.

Voice input for GitHub Copilot Chat in IntelliJ IDEA, with fully local transcription.

### Added

- Dictate into Copilot Chat. Push-to-talk (hold to speak) or tap-to-start/stop. Assign your shortcut in Settings | Keymap.
- Works right after install. The Whisper engine and the Tiny model are bundled, so it runs offline with no download and no account, including on restricted corporate laptops.
- Windows and Linux support.
- Five speech models. Tiny (bundled), Base, Small, Small multilingual and Large v3 Turbo. Download the ones you want from the settings page, with background progress and a cancel button.
- Language selection for multilingual models.
- CPU or GPU processing. Automatic, CPU only, or GPU (Vulkan), plus a CPU threads setting.
- Microphone test with a live level meter.
- Optional auto-send after dictation (off by default).
- Restore bundled model. Delete the Tiny model and bring it back in one click, with no download needed.

### Known limitations

- Transcription speed depends on your hardware and the model you choose. Larger models are slower on CPU.
- English-only models can't transcribe other languages. Pick a multilingual model for those.
