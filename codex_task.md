# Codex task — Initial Android SSML Player

## Status
Initial vertical slice and focused PR threading/audio corrections implemented. Current local checks: 33 tests pass, build/lint/signature/offline permissions pass. GitHub Actions passed for review implementation commit `388697b` (run 38641173918), including APK artifact delivery. Device listening/background verification remains pending. Implementation is on `feat/initial-android-player`; PR: https://github.com/metasemantix/ssml-player/pull/1.

## Objective
Create the first working Android app in this repository. A user can import or paste an SSML document, synthesize speech locally with an installed Android TTS engine, and listen hands-free. Deliver an installable debug APK via GitHub Actions.

## Proposed technical baseline
- Kotlin, Jetpack Compose, Material 3, Gradle Kotlin DSL.
- Minimum Android SDK 29 (Android 10).
- Application ID: `dev.metasemantix.ssmlplayer`.
- Android Storage Access Framework for importing `.ssml`/`.xml`.
- Native Android TextToSpeech backend initially, behind a replaceable synthesis interface.
- Local playback; choose suitable Android media APIs for background controls and lifecycle.
- No server, account, network requirement, or paid API.
Use current stable, mutually compatible toolchain versions; record them in the README.

## First milestone — functional vertical slice
1. Scaffold a compilable Android Studio project, including Gradle wrapper and basic tests.
2. Provide a simple UI: import SSML, paste/edit SSML, select installed engine/voice where available, render, play/pause/stop, and show status/errors.
3. Parse SSML securely into a testable intermediate representation. Support `<speak>`, `<p>`, `<s>`, `<break time="..."/>`, and nested `<prosody rate="..." pitch="...">` with documented approximations and bounds.
4. Render text segments through Android TextToSpeech into temporary local audio; implement exact timed silence independently of punctuation. Respect segment-specific rate/pitch when the selected engine supports them.
5. Implement local audio assembly/playback robustly: handle formats, cancellation, cleanup, large documents without loading everything into memory, and screen-off/background playback.
6. Expose errors for malformed input, unavailable voices, unsupported instructions, failed synthesis, and unsupported output formats.
7. Add a sample SSML lesson exercising paragraphs, pauses, and prosody.
8. Add GitHub Actions workflow to compile/test and upload a debug APK artifact.
9. Document installation, permissions, supported features, limitations, and manual test procedure.

If the entire audio pipeline cannot be delivered reliably in one pass, complete a smaller **working** end-to-end slice first, mark remaining criteria as pending, and avoid claiming full support.

## Explicitly deferred
- IPA `<phoneme>` synthesis and embedded eSpeak NG.
- Full W3C SSML compliance, advanced `<say-as>`, lexicons, and expressive voice models.
- Production release signing and automatic in-place APK upgrades.
- Cloud TTS, accounts, and network services.

Do not strip unsupported phoneme tags and pretend to have implemented phoneme control. Preserve their meaning in parsing/diagnostics for future backends.

## Acceptance criteria
- [x] Android project builds locally or in CI with committed Gradle wrapper.
- [x] Unit tests cover valid/invalid XML, external entity rejection, nested prosody, pause parsing/bounds, and unsupported tags.
- [ ] User can import a document and paste a document.
- [ ] User can choose an installed TTS engine/voice where supported.
- [ ] Basic SSML produces intelligible offline audio and deliberate silence at breaks.
- [ ] User can play/pause/stop; background/screen-off behavior is documented and tested where possible.
- [x] Errors are surfaced instead of silently dropping instructions.
- [x] CI uploads a debug APK as a workflow artifact.
- [x] README and docs/ssml-support.md reflect actual implementation.
- [x] Feature branch and pull request created, or limitation reported.

## Progress log
- 2026-10-14: Task specification created. No implementation or build verification yet.

- 2026-10-14: Created `feat/initial-android-player`; selected Kotlin 2.1.20, AGP 8.9.2, Gradle 8.11.1, SDK 35/minimum 29, Compose and Media3. Preparing local toolchain and parser tests. No device/audio verification yet.

- 2026-10-14: Implemented strict parser, bounded PCM silence/WAV validation, replaceable local TTS backend, Compose import/editor/voice UI, and Media3 background playback service. First core run: 15 tests executed, 8 parser failures isolated to kXML2 rejecting an optional feature setter; correcting feature negotiation while keeping DTD processing disabled. Android build/lint validation ongoing. GitHub API CONNECT currently denied; saved `api.github.com` network addition for review.

- 2026-10-14: Corrected kXML2 feature negotiation. `./gradlew :core:test`: **15 tests passed**, zero failures/errors/skips. `./gradlew :app:assembleDebug :app:lintDebug`: **BUILD SUCCESSFUL**; initial lint had two warnings (backup rules/icon), addressed with explicit extraction exclusions and a launcher icon. `apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk`: verified v2 signature, one signer. No physical device/emulator was available; audible offline synthesis and background controls are not claimed as device-verified.
- 2026-10-14: Packaged APK inspection found a transitive Media3 ACCESS_NETWORK_STATE permission; explicitly removed it and INTERNET from the merged manifest. Confirmed Media3's network observer safely handles missing permission. Added CI checks for signature and absence of those permissions. `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** (final core tests reused valid outputs; Android-specific unit task is NO-SOURCE, not additional tests). Resolved a remaining legacy backup-configuration warning with explicit pre-Android-12 XML exclusions. `./gradlew :app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL**, **zero lint issues**. Reverified the final APK signature and absence of network permissions. Repeated APK signature verification and packaged-permission checks passed: no INTERNET or ACCESS_NETWORK_STATE permission. Debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

- 2026-10-14: Pushed `feat/initial-android-player` and opened PR #1: https://github.com/metasemantix/ssml-player/pull/1. GitHub API access became usable after the earlier network denial. Local APK/build/test checks passed; GitHub Actions result remains a separate check.

- 2026-10-14: GitHub Actions run 38636325547 failed at `android-actions/setup-android@v3`, before compilation/tests. Detailed log storage was denied by cloud egress policy. Replaced that SDK action with the same checksum-verified command-line tools 19.0 (13114758) and SDK 35 installation proven locally, with explicit license-command status handling. Updated checkout/setup-java to Node 24-compatible v5. CI rerun pending; no verification was bypassed.

- 2026-10-14: Review identified an engine-initiated stop that could leave rendering marked busy. Treat engine interruption as a visible synthesis error; user cancellation still cancels the render job. `./gradlew :app:lintDebug :app:assembleDebug` passed after this correction; zero lint issues. The replacement CI SDK bootstrap succeeded, and its build/test step is running.

- 2026-10-14: **GitHub Actions passed** for final implementation commit `f81816e`: https://github.com/metasemantix/ssml-player/actions/runs/38636775928 (build job 3m34s). Tests/lint/build, APK signature/offline-permission checks, and both artifact uploads succeeded. `ssml-player-debug` contains the debug APK; `verification-reports` contains test/lint reports. This final task-log update changes documentation only. No device listening/background validation was performed.

- 2026-10-14 (PR review): Confirmed main-dispatcher Binder/file operations in TTS initialization, catalog/voice selection, synthesis submission, stop and shutdown; cancellation did not join prior work before cache deletion/reuse. Also identified unnecessary speech/silence playlist transitions and sample-rate changes. Implementing worker-dispatched engine lifecycle, ordered cancellation/cleanup, isolated render directories and streaming single-WAV assembly, with fake-engine and PCM regression tests. PR remains open and unmerged.

- 2026-10-14 (PR review validation): `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passed. **32 core tests passed**, zero failures/errors/skips (17 new regressions); Android unit task is NO-SOURCE. Lint: **zero issues**. APK signature and absence of INTERNET/ACCESS_NETWORK_STATE reverified. README/support matrix now describe single-WAV assembly, same-format requirements, engine padding, worker/cancellation semantics, transient disk bounds, and device profiling/waveform checks. No physical-device behavior claimed as verified.

- 2026-10-14 (stop failure review): The native adapter also ignored the `TextToSpeech.stop()` error return. It now checks that result; failed stop retires/shuts down the session, clears pending callbacks, and requires engine reload rather than reusing unknown state. Added a cancellation/failed-stop regression. `./gradlew :core:test :app:lintDebug :app:assembleDebug` passed: **33 tests**, zero failures/errors/skips; zero lint issues. Physical Binder/engine behavior remains unverified.

- 2026-10-14 (PR review CI): **GitHub Actions passed** for implementation commit `388697b`: https://github.com/metasemantix/ssml-player/actions/runs/38641173918 (4m14s). Full test/lint/debug build, APK signature/offline-permission checks, debug APK and report uploads succeeded. Current suite has **33 tests** (18 added regression tests). This follow-up updates documentation only. PR #1 remains open and unmerged; native Binder responsiveness, engine padding/audible gaps and physical-device lifecycle checks remain unverified and documented.

## Open questions / implementation decisions
- Android TTS is interchangeable via the `SpeechEngine` adapter and worker-dispatched `SpeechSession`; offer installed voices reporting offline use and present initialization/synthesis errors. Android may fall back to the default engine if a requested engine cannot initialize; offline behavior and prosody must be tested with the installed voice.
- Speech payloads are streamed into one app-private lesson WAV (PCM16 mono/stereo, 8–48 kHz) with break frames at the same rate/channels. Media3 receives one item, removing speech/silence playlist transitions. Mid-lesson format changes are rejected; engine-provided padding is preserved. No whole-lesson RAM buffer, resampling, or re-encoding. Audible timing still requires a device; export is deferred.
- Bounds: 128 Ki input characters, 32 nesting levels, 512 segments, 2,000 characters/chunk, 10 seconds/break, 120 seconds total explicit breaks, 32 MiB/engine file, 128 MiB/completed lesson. Split long documents when limits are reached.
- `SpeechSession` invokes engine construction, metadata/voice queries, submission, stop and shutdown on IO workers. Replacements cancel and join prior work before touching its files. Each render/service has isolated cache ownership; destruction cleanup is asynchronous. Coroutine deadlines cannot forcibly interrupt a stuck Binder call, so cancellation can remain pending until it returns. Platform-internal TTS locks/callbacks need physical-device profiling.
- Rendering requires keeping the app open and is not a rendering foreground service. Playback uses MediaSessionService/ExoPlayer with foreground media controls, audio focus, noisy-device handling, and local wake mode. Process-death/reboot playback restoration is deferred.
- Import/paste, engine/voice choice, deliberate breaks, playback controls and notification lifecycle are implemented; their device acceptance boxes remain unchecked until the documented manual checklist is executed. CI workflow and debug APK artifact upload are verified by run 38641173918 for review implementation commit 388697b; device checks remain pending.
- Remaining verification: install APK on Android 10 and a current Android device; run airplane-mode TTS, import, pause/resume/stop/cancel, background/screen-off/headset/focus/task-removal, longer-document and cleanup checks from README. No cloud/IPA/say-as/production-signing support is claimed.
