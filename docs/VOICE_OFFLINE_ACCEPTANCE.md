# Voice and offline usability acceptance

This Personal increment builds on the verified menu release. Chat layout, utility
navigation, saved voice/profile settings, manual AI provider/model controls and
production signing identity remain unchanged.

## Supported behavior

- Personal command capture and live chat prefer Android's on-device recognizer
  when requested and available, including when no default system provider is
  configured. Otherwise they use the available system provider. The system
  provider's offline availability depends on its installed models.
- Live chat reports the selected capture engine. If an on-device language/model
  cannot serve the request, a non-strict capture can try the system provider once;
  its status states that it may use a network connection. Other errors do not
  automatically restart capture.
- Explicit on-device practice never falls back to system/network capture. Explicit
  default-provider practice keeps that provider and the existing offline-preference
  hint. Its existing online-provider consent remains required. Practice scores
  retain the actual selected mode; no acoustic model training is claimed.
- Each capture attempt owns its callbacks. Cancellation, replacement and fallback
  reject late old results/errors; cancellation during engine selection prevents
  starting that microphone session. A callback that cancels during hypothesis
  review also prevents final transcript delivery.
- Blank transcripts keep their original confidence indices when removed. Invalid
  or unavailable scores remain unknown; alternatives never promote a different
  command or merge a duplicate's higher score into the first choice.
- Explicit media requests such as "Could you please make the music a little
  louder?" and "Could you turn the music down a little please?" execute one local
  Android media-volume step. Success requires Android readback verification.
  Negations, command explanations, other audio streams, bare "turn it up" and
  compound requests are not interpreted as these single-step commands.

Android microphone permissions, screen-review binding, protected-screen rules and
consequential-action approvals remain in force. Beta keeps its existing automatic
recognizer routing; explicit practice selection remains available as before.

## Verification gates

JVM tests exercise provider availability/manual choice, score indexing/invalid
scores, alternative ordering, natural volume requests and rejection boundaries.
Android capture acceptance drives controlled recognizer callbacks through the
actual capture adapter with real Android Intent/Bundle objects, without recording
audio or contacting a speech provider. It verifies one fallback, strict practice,
immediate cancellation and rejection of late callbacks.

An isolated assistant fixture on the disposable Android emulator also executes
the natural volume requests against the real AudioManager, verifies readback and
restores the original media level/mute state. No other device adapter or activity
dispatch is permitted in that fixture; its preferences/files/database are isolated.

The required real Accessibility/conversation/capture preflight contains 11 cases;
the full optimized Android suite contains 57. Existing menu, Appearance, Chat draft,
theme, small-screen keyboard, voice playback, gateway, production packaging and
public-updater verification gates remain required before publication.

Local verification passes 679 JVM cases across 107 suites without failures, errors
or skips, all debug architectures, instrumentation Kotlin and Beta manifest
processing. Theme checks, 22 release/archive regressions and script syntax pass.
Actual runtime/publication results must be recorded after the candidate passes.
Phone/OEM wake-word and deep-voice microphone quality, installed-data upgrade and
live Gemini/provider availability remain separate from controlled unit/emulator
results. No measured recognition improvement is inferred from compilation or
synthetic callbacks.
