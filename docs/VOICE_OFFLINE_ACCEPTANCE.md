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
Branch and merged-source Android gates passed all 11 preflight and all 57 full
optimized acceptance cases. The UI emulator, gateway and theme checks passed too.

Personal [3.6.516 is published](https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/tag/jarvis-3.6.516)
through the existing in-app updater. All three architecture APKs, the ARM64 ZIP
and checksums are attached to the release. Automatic signing/publication checks
passed, and an independent download of the exact default updater matched the
manifest, Git blob, Personal identity and prior production signing certificate.
Native/wake assets are present and acceptance fixtures are absent. The production
APK also contains the live capture engine reporting paths.

The first merged-source preflight failed one uncached Connection Manager screen
read and publication stopped. The unchanged source then passed both required
suites on a fresh emulator; no test assertions, capture deadlines or action rules
were relaxed. The initial log shows heavy emulator startup load, but the exact
capture failure is not diagnosed. Cold-window/OEM read availability and more
specific privacy-safe failure diagnostics remain follow-up work. A failed read
provides no screen controls and never retries an action automatically.

- [Implementation and review](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/207)
- [Branch Android, unit, gateway and UI verification](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39943019672)
- [Merged-source Android, unit, gateway and UI verification](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39944824951)
- [Production build and signing](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39944824865)
- [Automatic publication and retained receipt](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39946029875)
- [Download the updater APK](https://raw.githubusercontent.com/muddigger782012/J.A.R.V.I.S.-/main/artifacts/app-debug.apk)

Phone/OEM wake-word and deep-voice microphone quality, installed-data upgrade and
live Gemini/provider availability remain separate from controlled unit/emulator
results. No measured recognition improvement is inferred from compilation or
synthetic callbacks.
