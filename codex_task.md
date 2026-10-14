# Codex task — Initial Android SSML Player

## Status
Not started.

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
- [ ] Android project builds locally or in CI with committed Gradle wrapper.
- [ ] Unit tests cover valid/invalid XML, external entity rejection, nested prosody, pause parsing/bounds, and unsupported tags.
- [ ] User can import a document and paste a document.
- [ ] User can choose an installed TTS engine/voice where supported.
- [ ] Basic SSML produces intelligible offline audio and deliberate silence at breaks.
- [ ] User can play/pause/stop; background/screen-off behavior is documented and tested where possible.
- [ ] Errors are surfaced instead of silently dropping instructions.
- [ ] CI uploads a debug APK as a workflow artifact.
- [ ] README and docs/ssml-support.md reflect actual implementation.
- [ ] Feature branch and pull request created, or limitation reported.

## Progress log
- 2026-10-14: Task specification created. No implementation or build verification yet.

## Open questions / implementation decisions
Record engine-specific limits, chosen audio format/assembly strategy, and Android background-playback approach here as implementation proceeds.
