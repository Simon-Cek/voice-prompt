# Voice Prompt 1.0.0 — Windows build & test runbook

**Purpose:** verify that the Voice Prompt IntelliJ plugin **builds, loads, and dictation-works on
Windows 10/11 x64**. Linux is already verified; Windows is currently an *untested claim* in the
release listing, so this run must pass before we publish.

**Audience:** an agent (or a human) working on a **Windows 10/11 x64** machine. All commands assume
a standard Windows shell (PowerShell or cmd). The `bin/*.sh` scripts in the repo are
**Linux-only — do not use them on Windows**.

---

## 0. Prerequisites (check first, stop if missing)

| Requirement | Check |
| --- | --- |
| Windows 10 or 11, **64-bit x64** | `systeminfo \| findstr /B /C:"OS Name" /C:"OS Architecture"` |
| JDK 17+ on `PATH` | `java -version` (the Gradle toolchain can also download its own JDK, but a local one is faster) |
| Git | `git --version` |
| ~8 GB free disk | `wmic logicaldisk get size,freespace` (sandbox IDE ≈ 3 GB, build caches, models) |
| Internet | access to `download.jetbrains.com`, `repo1.maven.org`, `plugins.jetbrains.com` (and `huggingface.co` only for the optional download test) |
| Microphone connected | appears in Windows Settings → System → Sound → Input |
| GitHub Copilot subscription + sign-in | needed for the Copilot Chat UI in the sandbox IDE |

## 1. Get the code (and verify the identity!)

```bat
git clone https://github.com/Simon-Cek/voice-prompt.git
cd voice-prompt
git checkout main
git pull
```

**Identity check (important — the plugin was renamed right before this run):**

```bat
git log --oneline -5
dir /s /b src\main\kotlin
findstr /s /m "io.github.simoncek.voiceprompt" src\main\kotlin\*.kt
findstr /m "io.github.simon-cek.voiceprompt" src\main\resources\META-INF\plugin.xml
```

- ✅ **Correct:** source tree is under `src\main\kotlin\io\github\simoncek\voiceprompt\` and
  `plugin.xml` contains `<id>io.github.simon-cek.voiceprompt</id>`.
- ❌ **Wrong:** if you see `com.example.copilotvoice` anywhere, the identity rename has **not been
  pushed yet**. **Stop and report this** — do not run the tests on the old identity.

## 2. Build + unit tests

```bat
gradlew.bat test
```

Expect: **all tests green** (8 suites / 33 tests: WavFile, ConfigurableLifecycle, ChatSinkChain,
ModelManager, WhisperCliEngine, WhisperLanguage, WhisperRuntime, WhisperRuntimeThreads).

```bat
gradlew.bat buildPlugin
```

Expect: `BUILD SUCCESSFUL` and a distributable at
`build\distributions\copilot-voice-1.0.0.zip` (≈ 130 MB — it bundles the whisper runtime + Tiny model).

**If the build fails:** capture the full output, check `java -version` (needs 17+), and check that
`gradle-wrapper` downloads succeed (Gradle 9.8.0). Report verbatim.

## 3. Launch the sandbox IDE

```bat
gradlew.bat runIde
```

Notes:

- This is a **long-lived foreground process** — keep its terminal open, don't pipe it, don't run it
  in the background with `|` etc.
- **First run downloads IntelliJ IDEA IU 2025.3.6.1** (≈ 1–2 GB) plus the Kotlin/Gradle toolchain.
  Budget 5–15 minutes before the IDE window appears.
- If it hangs or fails: `tail`-equivalent = scroll the terminal output, or look at
  `build\reports\problems\problems-report.html`. Kill any leftover `java.exe` Gradle daemons
  (`taskkill /F /IM java.exe` only if you are sure no IDE is needed) and retry once.
- Stop the IDE by closing its window (or Ctrl+C in its terminal).

Sandbox layout (all relative to the repo root):

| What | Path |
| --- | --- |
| IDE log | `.intellijPlatform\sandbox\copilot-voice\IU-2025.3.6.1\log_runIde\idea.log` |
| Plugin data dir | `.intellijPlatform\sandbox\copilot-voice\IU-2025.3.6.1\system_runIde\copilot-voice\` |
| Extracted runtime | `...\system_runIde\copilot-voice\runtime\b5454\win-x64\` (13 files: `whisper-cli.exe` + 12 `*.dll`) |
| Bundled model | `...\system_runIde\copilot-voice\models\ggml-tiny.en.bin` (≈ 75 MB) |

## 4. Verify the plugin loaded

In `idea.log` (use PowerShell):

```powershell
Select-String -Path ".intellijPlatform\sandbox\copilot-voice\IU-2025.3.6.1\log_runIde\idea.log" -Pattern "Loaded custom plugins|Voice Prompt|simoncek" | Select-Object -Last 20
```

Expect at least:

```
INFO - #c.i.i.p.PluginManager - Loaded custom plugins: Voice Prompt (1.0.0)
```

and the IDE launched with `-Didea.required.plugins.id=io.github.simon-cek.voiceprompt`.

If the plugin did **not** load, `idea.log` will contain a `PluginManager` ERROR with a stack trace
— capture it and stop.

## 5. Functional test checklist

Work through in order. Mark ✅ / ❌ for each. For ❌, capture the relevant `idea.log` lines.

### 5.1 Settings page

- [ ] **5.1.1** `Settings | Tools | Voice Prompt` opens and renders correctly (try both light and
      dark theme: `Ctrl+Alt+Shift+S` → look for **Voice Prompt** under **Tools**).
- [ ] **5.1.2** The microphone dropdown lists the Windows input device(s).
- [ ] **5.1.3** **Test** button: click it, speak a few words, the level meter moves.
      Log anchor: `#io.github.simoncek.voiceprompt.audio.MicrophoneTester - Mic test finished: device=... peakLevel=...`
      with `peakLevel > 0`.

### 5.2 Push-to-talk (PTT) — the core path

Prep: open **Copilot Chat** (tool window or `Ctrl+Shift+L` depending on your Copilot config).
Default recording mode is **Push-to-talk**, default shortcut **Alt+Shift+Z**.

- [ ] **5.2.1** Put focus in a **code editor** (not the chat). Hold **Alt+Shift+Z**, say clearly
      **"One, two, three, four."**, release. Wait a few seconds.
      **Expected:** the text appears in the Copilot chat input; nothing is sent.
      Log anchors (in this order):
      ```
      #io.github.simoncek.voiceprompt.stt.WhisperCliEngine - whisper-cli exit=0 stdoutLen=...
      findInput: SELECTED
      appendToSwing: before='...' after='One, two, three, four.'
      deliverToChat: sink insert succeeded
      ```
- [ ] **5.2.2** Repeat **5.2.1** but with focus **inside the Copilot chat input** (click into it
      first). Same expectation.
- [ ] **5.2.3** Silent PTT: hold the shortcut, say nothing (~2 s), release.
      **Expected:** a "no speech" balloon; nothing inserted; log shows `whisper-cli exit=0` with
      empty/`[BLANK_AUDIO]` output normalized away.
- [ ] **5.2.4** No console window flashes on screen during transcription (the CLI runs hidden).

### 5.3 Tap mode

- [ ] **5.3.1** In settings switch **Recording mode → Tap to start/stop**.
- [ ] **5.3.2** Press **Alt+Shift+Z** once (recording starts — balloon/beep), speak, press again.
      **Expected:** text inserted into the chat input as in 5.2.1.

### 5.4 Nice-to-have (do if quick, otherwise mark SKIPPED)

- [ ] **5.4.1** Model download: download **Base** (142 MB) from the settings page — progress bar
      visible, cancel button works; then select Base and dictate once.
      (Needs huggingface.co reachable.)
- [ ] **5.4.2** GPU: if this machine has a Vulkan-capable GPU, set **Processing → GPU (Vulkan)** and
      dictate once. Otherwise confirm **Automatic/CPU** works and mark SKIPPED.
- [ ] **5.4.3** **Auto-send**: enable it, dictate, confirm the message is submitted to the chat.
      (Then turn it back off.)
- [ ] **5.4.4** Restore bundled model: delete the Tiny model from the settings page, click
      **Restore**, confirm it is back without a download.

## 6. Windows-specific *expected* behaviors (not bugs)

Report these as PASS notes, **do not** file them as failures:

1. **Thread count:** `WhisperRuntime.threadsPerCore()` detects physical cores on Linux/mac only; on
   Windows it falls back to 1, so the max CPU-thread setting equals the **logical** core count.
   Expected — do not "fix" it in this run.
2. **Extraction path:** runtime DLLs are extracted next to `whisper-cli.exe` under the sandbox
   `system_runIde` dir (see table in §3); the working directory is set to that folder, so the DLLs
   are found via the normal Windows loader search. No `PATH` or `LD_LIBRARY_PATH` involved.
3. **Audio API:** capture uses the Java Sound API over the Windows audio stack — device names in
   logs will be Windows device names, not PipeWire ones.
4. **Copilot version:** record the exact Copilot plugin version
   (`Settings | Plugins | Installed` → GitHub Copilot). The listing claims compatibility with
   1.19.0-252; if you run a different version, note whether insertion still works.

## 7. If something fails

Capture and include in your report:

1. The failing checklist item number.
2. The last ~50 lines of `idea.log` around the failure, especially any line containing
   `io.github.simoncek.voiceprompt` at WARN/ERROR level:
   ```powershell
   Select-String -Path ".intellijPlatform\sandbox\copilot-voice\IU-2025.3.6.1\log_runIde\idea.log" -Pattern "simoncek|whisper-cli|CopilotChat|appendToSwing|sink" | Select-Object -Last 60
   ```
3. For transcription failures: the `whisper-cli exit=...` line (it contains the first 300 chars of
   stderr) — e.g. missing DLLs show up as `whisper-cli exited with code ...` or a failed start.
4. For "chat input not found": the `findInput:` lines — the locator may not see the Copilot chat
   panel; note whether Copilot Chat is open, its version, and whether the panel is JCEF or Swing.
5. OS build (`winver`), CPU model, GPU, Java version, IntelliJ build (Help → About), Copilot plugin
   version.

**Do not** change source code to make tests pass. This run is verification only; report instead.

## 8. Report template

```
Voice Prompt 1.0.0 — Windows verification
Machine:  Windows <10/11> build <xxxx>, x64, CPU <...>, GPU <...>
JDK:      <java -version first line>
IDE:      IntelliJ IDEA <build, e.g. IU-2025.3.6.1>
Copilot:  <plugin version, e.g. 1.19.0-252>
Commit:   <git rev-parse HEAD>

Build:    test: <PASS/FAIL (n tests)> | buildPlugin: <PASS/FAIL> | zip: <size>
Load:     <PASS/FAIL> + the "Loaded custom plugins" log line
5.1.x:    ...
5.2.x:    ...
5.3.x:    ...
5.4.x:    ... (SKIPPED where applicable)
Unexpected issues: <none / list with log excerpts>
Verdict:  WINDOWS OK / WINDOWS BLOCKED (why)
```

## 9. Definition of done

`test` + `buildPlugin` green, plugin loaded, and **5.1 – 5.3 all ✅** on Windows x64.
5.4 items are bonus evidence for the listing claims (GPU, downloads) but do not block.
