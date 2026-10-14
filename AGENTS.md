# Agent instructions — SSML Player

## Mission
Build a trustworthy, free, offline-first Android app for listening to SSML documents, including long, hands-free listening sessions. Favor working, testable vertical slices over broad but incomplete implementations.

## Project invariants
- Keep all document content and speech synthesis local to the device. Do not introduce cloud APIs, accounts, ads, analytics, telemetry, or network permissions without explicit approval.
- Keep SSML parsing, synthesis backends, audio assembly, and playback separately testable.
- Treat Android TextToSpeech as the first interchangeable synthesis backend. Do not assume any engine supports SSML, IPA, a language, or offline voices.
- Never silently ignore unsupported SSML instructions. Distinguish supported, approximated, and unsupported features in user-facing diagnostics and docs.
- Parse untrusted XML securely: disable DTDs, external entities, and external resource loading; bound input sizes and pause durations.
- Avoid unnecessary permissions. Use Android's document picker for file import.
- Prefer stable, compatible Kotlin/Android dependencies and simple, maintainable architecture.
- Write tests for parsing, nested prosody, pauses, invalid input, and rendering edge cases.
- Do not commit generated build outputs, secrets, signing keys, or machine-local configuration.
- Preserve the Gradle wrapper (including wrapper JAR) in version control. Keep CI builds reproducible.
- Keep README and docs/ssml-support.md accurate; never claim capabilities before they work.

## Workflow
1. Read this file, codex_task.md, and relevant project docs before changing code.
2. Inspect the repository and make a brief implementation plan.
3. Work on a feature branch and open a pull request when the environment supports it.
4. Implement in small, reviewable steps, running relevant tests/builds.
5. Update codex_task.md with progress, decisions, blockers, and verification results. Preserve its requirements and acceptance criteria.
6. Document any feature deferred or dependent on installed Android TTS engines. Report exact commands and results; do not claim a build passed without running it.
7. Ask for guidance only when a product decision materially blocks progress; otherwise make a documented, reversible choice.

## UX priorities
Simple file import, clear play/pause/stop, voice selection, reliable pauses, screen-off/background listening, and meaningful errors. The user may have no free hands while listening.
