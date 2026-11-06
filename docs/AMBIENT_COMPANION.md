# Personal J.A.R.V.I.S. — Ambient Companion

## What is implemented in this milestone

- **Settings → Voice → Ambient companion** is a real Android editor, with the
  initiative slider (default 55%), Quiet / Companion / Active participation,
  conversation-context toggle, interruption preference, and third-party
  response preference. Save persists an atomic, per-installation file and is
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
- Existing explicit `stop live chat` commands still work, even in Quiet mode.
  Mic button behavior, wake-word engine, TTS, signer and updater are unchanged.
- Passive room speech is **not** uploaded to the AI and **not** added to the
  saved Live Chat transcript in this first implementation.

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
- **Semantic memory of unaddressed room speech:** passive turns remain local
  and are not used as semantic context by the AI yet. The context toggle in
  this milestone controls the immediate addressed-turn follow-up window, not
  a transcript of other people.
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
3. Bounded, ephemeral context buffer for passive speech and optional
   permissioned semantic references, never routed into privileged commands.
4. Context-aware participation scoring combining direct address, topic,
   speaker evidence, timing and interruption cost, with measured false
   interruption and missed-address rates. Add real-time full-duplex barge-in.
5. Samsung hardware acceptance before publishing release claims.
