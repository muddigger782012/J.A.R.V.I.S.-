# P0 voice regression acceptance checklist — Issue #215

Target: reproduce and fix on an installed Personal Jarvis build, not merely by passing unit tests.

## Reproduction matrix
- Foreground and background: wake phrase after idle, after response, after cancelling, after screen lock/unlock
- Microphone button: tap-to-capture latency, speech-start latency, transcript finalization, reply generation and TTS-start latency
- Long continuous speech and natural pauses of 1, 2 and 3 seconds; confirm no premature final transcript
- Repeated live chat cycles and interrupted TTS; ensure session stays active until explicit stop or documented timeout
- Audio focus: Bluetooth headset connect/disconnect, phone call interruption, app background/foreground
- Provider availability: offline local commands and failed Gemini requests must not block re-arming microphone

## Evidence and telemetry
Measure monotonic elapsed milliseconds per stage (wake detected, mic acquired, speech start, partial/final recognized, processing start/end, TTS start/end, wake re-armed). Record transitions, capture generation, recognizer errors and cancellation reasons. Do not log raw audio, user transcripts, tokens or credentials. Include the installed version, device, Android version and recognition engine.

## Release gate
- Unit and emulator tests for stale callbacks, double finalization, endpointing and session transitions
- Repeated physical-device tests for wake re-arming, long utterances and live chat
- Accurate locally sourced installed version/capability answers
- Gemini connectivity probe distinguishes configured credentials from successful response
- Explicitly list any unverified physical-device cases in release notes

The presence of a 'Say hey jarvis' label is not evidence the wake detector is actually listening.
