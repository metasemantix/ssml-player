# SSML Player

An Android 10+ app for importing or pasting a bounded SSML lesson, rendering it with an installed **offline** Android TextToSpeech voice, and listening with play/pause/stop and a media notification. No accounts, analytics, server, or Internet permission. Version 0.1.0 is an initial vertical slice, not a complete SSML implementation.

## Build and install

Use JDK **21**, Android SDK platform **35**, and build-tools **35.0.0**. Set `ANDROID_HOME` to your SDK, or use Android Studio's ignored `local.properties`. The committed Gradle wrapper downloads and verifies Gradle **8.11.1**. The compatible baseline is AGP **8.9.2**, Kotlin/Compose compiler **2.1.20**, Compose BOM **2025.04.01**, and Media3 **1.6.1**. JVM bytecode targets Java 17; minimum Android SDK is 29 and target SDK is 35.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `Android debug APK` GitHub Actions workflow runs those checks for pull requests and main, and can be started manually. Download `ssml-player-debug` from a successful run's artifacts, unzip, and install `app-debug.apk` on Android 10 or later. Android may require allowing installation from your file manager. This is a development-signed APK, not a production release. CI artifact availability depends on an actual successful workflow run.

## Listen

1. Install an Android TTS engine and its offline voice data through Android settings. Voice downloads may require a connection in the engine/settings app; SSML Player itself has no network permission.
2. Open SSML Player, choose an engine and an offline voice. Use **TTS settings**, then **Reload engines** after installing voice data. Android may fall back to its default engine if a requested engine cannot initialize; check the reported voices.
3. Use the built-in sample, paste/edit SSML, or **Import SSML** with the system document picker. Imported files must be UTF-8. The picker supports arbitrary file extensions; parsing determines whether the contents are supported SSML. A cloud document provider may download a file outside this app; use local storage for a fully offline workflow.
4. Tap **Render locally**, keeping the screen/app open until rendering completes. Then tap **Play**. Pause/resume and stop are available in the app; Android media controls support background/lock-screen playback. Stop returns to the beginning; Cancel aborts rendering. Editing the document or changing voice invalidates the old audio.

## Behavior and limits

- Supported tags: `speak`, `p`, `s`, `break time`, nested `prosody rate/pitch`. See [the exact support matrix](docs/ssml-support.md). Unsupported tags, attributes, namespaces, and malformed XML cause a visible error. IPA/phoneme and say-as are explicitly unsupported.
- DTDs, external entities, and processing instructions are rejected. Input is limited to 128 Ki UTF-16 characters, 32 levels, and 512 audio segments. Speech chunks contain at most 2,000 characters without splitting surrogate pairs.
- Each speech chunk is synthesized to an app-private temporary file through a replaceable backend. No direct TTS `speak()` queue is used. Accepted engine output is RIFF/WAVE PCM16, mono/stereo, 8–48 kHz. Unsupported/truncated output produces an error. A synthesis call times out after 60 seconds; engine initialization after 15 seconds.
- Breaks are independent PCM16 silence files at 22,050 Hz, accurate to the nearest sample. Each break is 0–10 seconds; total explicit silence is at most 120 seconds. Media3 plays the speech/silence playlist sequentially, decoding/resampling per file. There can be extra transition latency between files; this version does **not** promise sample-accurate gapless concatenation.
- Prosody is an approximation: nested multipliers are applied through Android rate/pitch controls. Engines may ignore or approximate them even when the API reports success. Only voices that report no network requirement and no missing-data flag are offered. Incorrect engine metadata/behavior cannot be independently verified by the app; test your voice in airplane mode.
- Audio is streamed from disk, not concatenated in RAM. Each engine output file is capped at 32 MiB after synthesis and the completed lesson at 128 MiB; split longer lessons. The app checks limits between segments, so one in-progress segment may temporarily exceed the lesson cap. Temporary files are removed on new render, cancellation, voice/document change, service destruction, or next service startup. Stop retains a completed render for replay. Android may evict cache files.
- Rendering is cancellable but **not promised to survive backgrounding/process death**; keep the app open until ready. Playback uses a media foreground service, handles audio focus and unplugged headphones, and takes a wake lock during playback. Playback does not survive process death, a force-stop, or a reboot. Completed audio is not exported/persisted as a saved lesson. Removing the task while playing is intended to continue playback; removing it while paused stops the service.
- The foreground-service/media-playback permissions enable the media notification and background playback. `WAKE_LOCK` supports screen-off playback. There is no Internet, microphone, or broad storage permission; document access uses Android's picker. Android may apply manufacturer-specific battery restrictions.

## Architecture and verification

`:core` is a plain JVM module with the strict SSML parser, segment representation, streamed silence writer, WAV validator, and tests. Android uses the platform XML pull parser; JVM tests use kXML2. `SynthesisBackend` separates rendering from Android TTS. `PlaybackService` owns rendering, cache, ExoPlayer and MediaSession; the Compose activity binds locally for UI controls. No SSML is sent to media controllers.

Automated checks cover valid/malformed XML, DTD/external/internal entities, unsupported markup, nested prosody scoping, resource limits, Unicode chunking, PCM silence bytes, and rejection of corrupt/unsupported WAVs. A build and lint verify packaging and Android API usage. They cannot establish audible TTS correctness or device lifecycle behavior.

### Manual device checklist

- Install APK; install an offline voice; enable airplane mode. Load [samples/lesson.ssml](samples/lesson.ssml), render, and listen to the entire lesson.
- Compare the 1,500 ms break with the 1,000 ms break. Check nested rate/pitch changes, supported engine behavior, and any transition gaps.
- Import a local `.ssml` file, then paste equivalent SSML. Try invalid UTF-8, malformed XML, a DTD, `<phoneme>`, `<say-as>`, unsupported attributes, oversized input, and excessive pauses. Confirm clear errors without playback of flattened text.
- Change engine/voice; try missing voice data and an engine with only online voices. Simulate a synthesis error, cancel mid-render, and render again. Confirm no stale lesson plays after editing.
- During playback, pause/resume/stop, unplug headphones, receive another audio source/call, lock the screen, background the app, use notification/headset controls, rotate the activity, and remove the task. Check on Android 10 and a current Android version. Repeat with a longer lesson and inspect cache cleanup/storage bounds.
- Force-stop/relaunch; confirm the previous render is discarded and the app can render again. Do not expect process-death playback restoration.

Device listening/background checks have not been run in the cloud environment. See `codex_task.md` for actual build/test results and pending acceptance criteria.
