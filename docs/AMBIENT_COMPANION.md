# Personal J.A.R.V.I.S. — Ambient Companion

## What is implemented in this milestone

- **Settings → Voice → Ambient companion** is a real Android editor, with the
  initiative slider (default 55%), Quiet / Companion / Active participation,
  conversation-context toggle, interruption preference, third-party
  response preference, and **Keep Live Chat available until I stop it**. Save persists an atomic, per-installation file and is
  re-read from the microphone service in the separate `:wake` process.
- Live Chat now **observes ordinary room speech silently**. It does not send
  uninterpreted ambient utterances to the AI/action router, does not speak,
  and immediately returns to listening.
- Saying **Jarvis** directs a turn to the assistant. The Companion profile
  accepts bounded, question-like follow-ups within 45 seconds when context
  is enabled. Quiet only responds when addressed. Active may offer limited
  advice when the initiative threshold is high and speaker permission is
  present. Unknown speakers are *not* assumed to be the owner for proactive
  participation.
- Personal Live Chat can now stay active across long quiet periods when the new
  toggle is enabled (default on for explicitly started Personal Live Chat only).
  The existing timeout remains available when the toggle is off, and the Beta
  package retains its previous timeout behavior. System recognizer no-match retries continue at bounded increasing delays
  (maximum 45 seconds) rather than silently pausing after three quiet turns.
  During those gaps the provider is **not** actively listening. This does
  not remove Android microphone/battery/provider restrictions or turn-based ASR.
- Existing explicit `stop live chat` commands still work, even in Quiet mode.
  Mic button behavior, wake-word engine, TTS, signer and updater are unchanged.
- Passive room speech is **not** submitted to Jarvis's AI/action router or added to saved Live Chat history. A bounded 90-second in-memory window now permits explicitly requested local recall; quoting an observation in the answer can appear in visible chat history. Speech-to-text may still use Android's network recognizer.

## Important limits (not yet implemented)

- **Speaker recognition / diarization:** Android's recognizer supplies text,
  not a reliable person identity. The third-party setting is persisted and
  supported by the participation policy, but the service passes UNKNOWN until
  a separate voice-identity/diarization component exists. Direct address
  from an unknown voice can still be answered, even when third-party replies
  are off. Do not advertise this switch as an existing security or voice-ID gate.
- **Full duplex / spoken barge-in:** the manual microphone interruption path
  is preserved, but speech and recognition are still sequenced. The stored
  interruption setting is for later automatic barge-in implementation.
- **Semantic reasoning from room speech:** direct local recall of recent observed phrases is implemented. These are not yet provided to cloud AI as semantic background, never authorize actions, and do not identify the speaker.
- **Per-account sync:** settings are per app installation, not synchronized
  across separate user logins/devices.
- **Device acceptance:** on-device tests must check no accidental responses,
  end-of-speech capture, speed, accessibility, Bluetooth and privacy notices.

## Next engineering milestones

1. True persistent VAD/streaming ASR audio session with noise reduction and
   turn segmentation; keep the foreground microphone indicator and mute/stop
   control. Avoid a silent background surveillance mode.
2. Explicit opt-in owner voice verification and optional ephemeral speaker
   diarization, with UNKNOWN remaining conservative. Consent and visibility
   are required when other people are present.
3. Integrate the implemented bounded in-memory context with explicitly approved semantic references; never route room speech into privileged commands or cloud processing without deliberate opt-in.
4. Context-aware participation scoring combining direct address, topic,
   speaker evidence, timing and interruption cost, with measured false
   interruption and missed-address rates. Add real-time full-duplex barge-in.
5. Samsung hardware acceptance before publishing release claims.
