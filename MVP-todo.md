# MVP TODO

Remaining work to close the MVP against `copilot-voice-plan.md` §4.
Baseline verified 2026-10-11: `./gradlew test buildPlugin` green (18/18 tests), plugin zip builds (91 MB, bundled whisper + tiny model).

## 1. Browse… / custom model path — the one real feature gap

Plan §4.1 requires a **Browse…** button for a manually-downloaded `.bin` (corporate-proxy fallback); Day 4 lists a "custom model path" setting. Plumbing is half-built and never wired:

- [ ] Add `customPath: String?` field to `CopilotVoiceSettings.State` (settings/ `CopilotVoiceSettings.kt`)
- [ ] Add Browse… control to the Speech model section in `CopilotVoiceConfigurable` (file chooser filtered to `*.bin`)
- [ ] Pass `s.customPath` into `ModelManager.resolveActive(...)` in `PushToTalkAction.transcribeInBackground` — currently hard-coded `null` (line 153); `resolveActive` already supports the parameter
- [ ] Handle stale path gracefully (file deleted / moved → clear the field, fall back to downloaded model)
- [ ] Show the active custom path somewhere visible in Settings (e.g. label under the model rows)

## 2. Auto-send + live Copilot verification (highest-risk unverified items)

Nothing in code can prove these; needs the sandbox IDE with the Copilot plugin installed (`bin/ide-start.sh`):

- [ ] Hold PTT, speak, release → transcript lands in the **live** Copilot Chat input (Strategy A path, `SwingInjectionSink`)
- [ ] Auto-send ON → message actually sent. Current impl dispatches a synthetic Enter `KeyEvent` to the input component only (`SwingInjectionSink.dispatchEnter`); if that doesn't send, add the plan's alternative: find the send button in the tool window tree and `doClick()` it (§4 Day 3 step 4)
- [ ] Copilot not installed / UI changed → clear balloon with Copilot version + transcript text (graceful failure)
- [ ] Fallback: Strategy B (`ClipboardPasteSink`) still inserts when A fails

## 3. Small code gaps

- [x] **"Open Settings" action link** on the no-model balloon (plan Day 2 step 6): `NotificationAction` on the `notification.no.model` notification in `PushToTalkAction.notify` (public `ShowSettingsUtil` API; `SettingsDialog.getInstance().show()` is `@ApiStatus.Internal` in 2025.3)
- [x] **`ChatSinkChain` unit tests** (plan Day 5 step 2): `ChatSinkChainTest` covers order, skip-unavailable, fallback, all-fail → `InputNotFoundException`, none-available, empty chain
- [x] **Downloaded-model integrity** — `ModelDownloadService.verifyDownload` checks size vs `Content-Length` then SHA-256 against whisper.cpp's public `SHA256SUMS` manifest (best-effort: falls back to size-only if the manifest fetch fails)
- [x] **Mic Test vs PTT concurrency** — shared `DictationState` flag: Test button disabled while recording, and `MicrophoneTester.start()` refuses to start if dictation is active

## 4. Release polish

- [x] **Plugin icon**: `src/main/resources/META-INF/pluginIcon.svg` (45×45 microphone glyph, single JetBrains blue, reads in Light and Dark). No `<icon>` tag: verified in the 2025.3.6.1 bytecode that the plugin logo is resolved by convention from `META-INF/pluginIcon.svg` (or `pluginIcon_dark.svg`) in the plugin jar (`PluginLogoKt`), and the 2025.3 descriptor parser rejects an `<icon>` element ("Unknown element" SEVERE) — the conventional path is the documented default and is picked up from 242 onwards
- [ ] **CHANGELOG**: `[Unreleased]` only mentions scaffold + bundled model — add PTT (hold/tap), settings page, model download with progress, mic test, sink chain, bundled-binary runtime
- [ ] **README**: flip "MVP in progress" to released status once criteria are met
- [ ] `verifyPlugin` locally (CI already runs it)

## Acceptance criteria (plan §4) — final sign-off

- [ ] Holding the shortcut records audio and shows "🎙 Listening" (MVP level = notification balloon; status-bar widget is polished-version §5.6)
- [ ] Releasing puts transcribed text in the Copilot Chat input within ~2 s for a 5-second clip
- [ ] Auto-send works when enabled
- [ ] Fails gracefully with a clear message if Copilot isn't installed or its UI has changed
