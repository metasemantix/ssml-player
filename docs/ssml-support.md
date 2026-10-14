# SSML support matrix

This document tracks **implemented behavior**, not merely intended features. Update it alongside code.

| SSML feature | Target for first milestone | Current status |
| --- | --- | --- |
| `<speak>` | Root document | Not implemented |
| `<p>`, `<s>` | Segment boundaries; document any inferred pause | Not implemented |
| `<break time="..."/>` | Explicit bounded silence | Not implemented |
| `<prosody rate="..." pitch="...">` | Segment-level TTS adjustments; engine-dependent accuracy | Not implemented |
| `<phoneme alphabet="ipa">` | Explicit unsupported diagnostic; future backend | Deferred |
| `<say-as>` | Explicit unsupported diagnostic | Deferred |
| Other SSML elements | Explicit unsupported diagnostic | Deferred |

## Compatibility principles
Android's TextToSpeech API does not guarantee SSML or IPA interpretation. The application interprets supported markup and passes speech segments to the selected engine. Exact pronunciation, voices, rate, pitch, and offline availability depend on the installed engine.

Unsupported elements or attributes must be reported clearly rather than silently discarded. XML external entities and DTDs must be rejected.

## Manual test checklist
- Import a valid SSML document and paste equivalent text.
- Verify an explicit 1500 ms break is audibly distinct.
- Verify nested rate/pitch handling and document engine limitations.
- Check missing voices, malformed XML, unsupported tags, and cancellation.
- Verify playback with screen locked and app in background.
- Test longer documents without excessive memory use.
