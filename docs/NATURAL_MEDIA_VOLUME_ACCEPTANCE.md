# Natural media-volume acceptance

This Personal increment extends the existing offline media-volume route. Chat,
HUD, menus, settings, permission flows and AI provider/model controls are unchanged.

## Supported behavior

- Voice and typed input share local interpretation for explicit media levels.
  Examples include `set music volume to half`, `put the sound at a quarter`,
  `set music to three quarters`, `set media volume to thirty-five percent` and
  `boost the sound all the way up`. Polite requests and Jarvis wake prefixes work.
- Accept numeric and spoken integer percentages from zero through one hundred,
  including `percent` and `per cent`. Half and quarter fractions, full/minimum
  levels and all-the-way up/down requests resolve to an absolute media level.
- Android supplies the nearest supported media-volume index. Success requires
  readback of that index. Relative requests such as `make the music a little
  louder` remain one step. No cloud provider or new Android permission is needed.
- Negations, explanatory questions, other audio streams, relative percentages,
  compound actions, out-of-range numbers and implicit pronouns are not converted
  into an exact-volume command by this bounded local parser.
- Explicit levels take priority over older learned relative aliases. Existing
  entries remain available for review/removal rather than being rewritten.
  Teaching an exact-level phrase as a one-step command is rejected before any
  volume change. Conflicting model proposals and inherited pending relative
  approvals cannot change the volume or save a conflicting lesson.
- Normal exact-level commands do not add new aliases. Existing explicit consent,
  single-use/expiring approvals and verified-learning rules remain in effect.

## Verification gates

JVM cases cover all spoken integer levels, fractions, polite/wake variants,
strict exclusions, voice fallback routing, preserved legacy entries, review
messages and prevention of conflicting execution or confirmation proposals.

Android acceptance uses disposable-emulator media-volume readback and restores
its original level and mute state in `finally`. It exercises the actual assistant
with natural relative and absolute requests and a saved legacy alias. A separate
case checks that conflicting teaching and a still-unexpired proposal inherited
from an earlier build cause no volume change, dispatch or learned entry.

Local verification passes 695 JVM cases across 108 suites with no failures,
errors or skips, all three debug architectures, instrumentation Kotlin and Beta
manifest processing. Theme, 22 release/archive checks and script syntax pass.
CI, emulator and publication results will be recorded after their gates complete. The strict preflight and full native acceptance deadlines remain
120 and 360 seconds. No production screen deadline or privacy rule is relaxed.

Real microphone transcription, OEM volume policies, Bluetooth/remote audio routes,
installed-data upgrades and live Gemini connectivity require separate phone checks.
This increment does not claim to improve acoustic recognition or change alarm,
ringtone, notification or call volume.
