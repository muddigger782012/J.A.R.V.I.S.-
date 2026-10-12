# Assistant voice profiles

Settings → Assistant voice offers an audition before saving. JARVIS — Original is
the initial profile; Default (Female) retains the previous locale-default voice
and pitch/rate when that voice is installed. The profiles use the device's TTS
engine, not a cloned actor voice or a newly trained speech model. Original uses
British English preference, pitch 0.94 and rate 0.93. Gender cannot be guaranteed
from Android Voice metadata; users can audition and pin an installed voice.

Voice choices are saved separately from wake-word settings and observed by each
VoiceAssistantService instance, including foreground and live chat. Changes are
applied before the next response rather than changing a speaking utterance.
Preview selections are temporary until Use this voice is tapped. Leaving the
screen stops the audition; destroying it shuts down its TTS client.

Only installed offline English voices are offered. Missing saved voices fall
back to another eligible voice with a visible notice. No eligible voice disables
preview/save and offers Android speech settings. No API keys, new services,
voice recordings, paid providers or downloads are introduced by this change.

Android setLanguage resets the engine voice, so explicit selections use setVoice
without a subsequent setLanguage call. The migration reproduces the old locale
choice once to capture the actual previously heard voice before applying Original.

## Validation

AssistantVoicePolicyTest covers initial/saved profiles, distinct gender hints,
pinning, offline availability, removed voices and deterministic selection.
Run testDebugUnitTest, assembleDebug and scripts/check_ui_theme.py. Audition on
the user's device remains required: compilation cannot establish sound, gender
or accent. Review all theme variants and large-font/narrow-screen scrolling.

A bespoke expressive voice model remains a separate follow-up. These controls
provide a playable device audition and voice persistence, not cinematic synthesis.
