# Assistant voice profiles

Settings → Assistant voice offers an audition before saving. JARVIS — Original is
the initial profile; Default (Female) retains the previous locale-default voice
and pitch/rate when that voice is installed. The profiles use the device's TTS
engine, not a cloned actor voice or a newly trained speech model. Original uses
British English preference, pitch 0.94 and rate 0.93. Gender cannot be guaranteed
from Android Voice metadata; users can audition and pin an installed voice.

Voice choices are saved in an atomically replaced file, separately from wake-word
settings, and observed via same-app broadcasts by each
VoiceAssistantService instance, including foreground and live chat. Changes are
applied before the next response rather than changing a speaking utterance.
Preview selections are temporary until Use this voice is tapped. Leaving the
screen stops the audition; destroying it shuts down its TTS client.

Original excludes the retained female voice's base identity, including its
local/network aliases. Opaque engine voice names therefore cannot silently make
both automatic profiles identical. The settings save the actual auditioned voice
ID. If no distinct installed voice exists, Original preview/save is unavailable
with guidance to install another voice; ordinary assistant speech retains a
clearly reported Default (Female) fallback. Engine activation is checked with
getVoice after setVoice. Different IDs still require an audible device audition.

Legacy SharedPreferences are imported on the first atomic-file update. Fresh
reads in the separate :wake process avoid cached preferences. A file lock and
within-process lock serialize initial female capture and user voice saves so
capturing the old voice does not overwrite a newly saved profile.

Only installed offline English voices are offered. Missing saved voices fall
back to another eligible voice with a visible notice. No eligible voice disables
preview/save and offers Android speech settings. No API keys, new services,
voice recordings, paid providers or downloads are introduced by this change.

Android setLanguage resets the engine voice, so explicit selections use setVoice
without a subsequent setLanguage call. The migration reproduces the old locale
choice once to capture the actual previously heard voice before applying Original.

## Validation

AssistantVoicePolicyTest covers initial/saved profiles, distinct gender hints,
pinning, offline availability, removed voices, opaque names, alias exclusion,
single-voice devices and deterministic selection. AssistantVoiceSelectionStoreTest
covers fresh-reader visibility, legacy migration and concurrent capture/saves.
Run testDebugUnitTest, assembleDebug and scripts/check_ui_theme.py. Audition on
the user's device remains required: compilation cannot establish sound, gender
or accent. Review all theme variants and large-font/narrow-screen scrolling.

A bespoke expressive voice model remains a separate follow-up. These controls
provide a playable device audition and voice persistence, not cinematic synthesis.
