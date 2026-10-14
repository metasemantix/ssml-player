# SSML support matrix — version 0.1.0

This tracks implemented behavior. Audible results still depend on the installed Android TTS engine and offline voice.

| Feature | Implemented behavior |
| --- | --- |
| `<speak>` | Exactly one root; optional `version="1.0"`; empty namespace or W3C SSML namespace |
| `<p>`, `<s>` | Speech segment boundaries; whitespace normalized; no inferred timed silence |
| `<break time="..."/>` | Required nonnegative decimal `ms` or `s`; rounded to milliseconds; 0–10 s each, 120 s total; independent PCM silence, nearest-sample accuracy |
| Nested `<prosody rate="..." pitch="...">` | Scoped multipliers; product must stay 0.25–4; engine-dependent approximation |
| Rate values | `x-slow`=0.5, `slow`=0.75, `medium`/`default`=1, `fast`=1.5, `x-fast`=2; unsigned percentages are multipliers; signed percentages adjust relative to the parent |
| Pitch values | `x-low`=0.5, `low`=0.75, `medium`/`default`=1, `high`=1.5, `x-high`=2; percentages as above; semitones/Hz unsupported |
| XML predefined entities / CDATA / comments | Predefined entities and CDATA become literal text; comments ignored |
| DTD / custom entities / external resources / processing instructions | Rejected before rendering; no external resource loading |
| `<phoneme>`, `<say-as>`, lexicons | Explicit error; meaning is not silently stripped; deferred |
| Other elements, foreign namespaces, unknown attributes (including `xml:lang`, `volume`, `strength`) | Explicit error; choose language via offline voice selection |

Input must contain speech; silence-only lessons are rejected. Maximum input: 128 Ki UTF-16 characters, nesting: 32, segments: 512; speech chunks: at most 2,000 characters. The parser rejects `<break>` children/non-whitespace content. A break without `time` is unsupported. Empty paragraphs are allowed.

The renderer requests per-segment rate/pitch through Android TextToSpeech and validates cached output as RIFF/WAVE PCM16 mono/stereo at 8–48 kHz. Media3 plays separate local files. Explicit silence has the specified number of samples, but file transitions can introduce additional gaps. Exact pronunciation, rate, pitch, voice availability and fidelity remain engine-dependent; Android TTS does not promise SSML or IPA support.

The device checklist, permissions, storage/duration bounds and background-rendering limitations are documented in [README.md](../README.md). Automated core tests cover XML security/limits, prosody, pause parsing, Unicode chunking, silence headers/data, and WAV validation. Listening, offline engine compliance and background behavior require manual device tests and are not verified by the JVM suite.
