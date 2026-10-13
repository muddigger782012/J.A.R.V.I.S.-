# My voice profile

Settings → My voice profile calibrates Android speech recognition with 15 editable practice phrases. Record, review the original transcript alongside intended wording, then approve. Practice callbacks never reach the assistant, command parser, learning session, or action engine.

Approved text is saved locally in a bounded, atomic file shared with the :wake process. No recordings are saved by Jarvis. The default Android recognizer may process audio and supplied vocabulary remotely; each default-engine practice attempt requires acknowledging this. On-device practice uses the explicit Android on-device recognizer when available, with no silent online fallback. Android language packs may still need installation.

On Android 13+, up to 50 distinct approved phrases in the current locale are supplied as optional biasing strings to ordinary command and live-chat recognition. Engines may ignore hints. Hints influence recognition, but never replace a transcript or approve a command. This is vocabulary personalization, not acoustic fine-tuning. Wrong wording can be removed from Review approved phrases or cleared with Reset profile; learned commands remain separate.

Scores show case/punctuation-insensitive exact matches for approved attempts only, grouped by locale, engine and whether hints were supplied. The most recent 200 approved attempts are retained. Discarded attempts and recognition errors are not counted; this is not a population accuracy measurement. Restart practice preserves earlier scores for retesting.

Microphone ownership: the main screen suspends its local wake listener while voice settings are open. Each capture requests a token acknowledgement from the foreground wake service, pauses an existing wake pipeline, refuses active live chat, and restores only a previously running wake pipeline after completion, cancellation, or leaving the screen. Capture times out at 20 seconds; the service has a 30-second recovery timer. No microphone capture starts without the acknowledgement.

Validation: storage bounds/corruption/locale isolation/score integrity/reset are unit tested. Optimized Android instrumentation opens the profile in all 12 themes, reviews/removes phrases, and checks that hints disappear after reset. Actual recognition quality and appearance at large text sizes still require phone verification.
