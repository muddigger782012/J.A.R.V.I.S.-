# Voice response controls and live dialogue

Settings → Assistant voice has three immediate, independent switches:

- Speak responses outside live chat (initially on).
- Speak live chat responses (initially on).
- Show live chat dialogue popup (initially off).

Every VoiceAssistantService speech call is classified as regular or live chat.
Muted calls complete their response callback so live chat advances to listening
and normal wake capture rearms. Changing a switch also stops an already playing
response in that channel. Voice auditions remain explicitly playable.

Response options use atomic files rather than cached SharedPreferences because
the microphone service runs in `:wake`. Same-app, non-exported change broadcasts
update active playback and popups immediately; each new response reads disk.

The popup shows the latest assistant response. Inside MainActivity it uses the
shared themed dialog without overlay permission; outside the app it uses a
non-focusable overlay when Android's display-over-other-apps permission is
granted. No activity is forcibly launched from the background. Overlay text
scrolls within a bounded panel, follows current theme/font snapshots, and has
Close reply and End live chat controls. Closing suppresses that reply until a
new reply arrives. Disabling popups, ending live chat, or destroying the service
removes the overlay. It is hidden while the device is locked.

Menu → Live chat history, also reachable from Assistant voice, lists retained
sessions and opens timestamped dialogue. New sessions have distinct IDs even
when using the same conversation. Older entries remain readable without invented
timestamps. The existing 2,000-entry retention limit still applies.

Copy all history for debug and Copy this session for debug copy a fresh snapshot
to Android's clipboard. Records include dialogue, timestamps, session/conversation
IDs, app/device versions and current response options (explicitly identified as
current, not historical). They do not access provider credentials or send data.
Records larger than 400 KB are refused with guidance to copy individual sessions,
avoiding Android's clipboard IPC limit and silent truncation.

Validation: theme/manifest/ID checks, response option round trips and all switch
combinations, silent live turn completion, session separation, legacy logs and
debug copy content tests. Android compilation and unit tests run in CI. Device
review remains required for audio interruption, overlay permission and touch,
all theme variants, large fonts, narrow screens, clipboard and silent turn timing.
