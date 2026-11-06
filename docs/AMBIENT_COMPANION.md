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
- Saying **Jarvis** directs a turn to the assistant. When context is enabled,
  natural question-like or short conversational follow-ups within 45 seconds
  receive strictly text-only responses, never device commands. Companion
  initiative 55%+ may answer explicit advisory questions; higher initiative
  can answer ordinary questions. Active high initiative can offer occasional
  help for recognized problems. Quiet only responds when addressed. Unaddressed
  responses are user-opted-in and may be transmitted to AI providers; UNKNOWN
  voices are not identified as the owner. Recognized OTHER voices still require
  third-party permission. Unselected observations stay out of the AI router.
- Personal Live Chat can now stay active across long quiet periods when the new
  toggle is enabled (default on for explicitly started Personal Live Chat only).
  The existing timeout remains available when the toggle is off, and the Beta
  package retains its previous timeout behavior. The new **Quiet microphone**
  setting (enabled by default) disables the app-owned wake acknowledgment beep
  and pauses the ordinary recognizer's automatic retries after two empty turns,
  avoiding a repeating system chime. Live Chat remains available, but during
  that pause **the provider is not listening** until the user taps the mic.
  The experimental continuous-PCM mode instead keeps the AudioRecord open and
  starts provider recognition only after local speech onset. This does not
  eliminate a vendor's recognition start tone or guarantee vendor support.
- Existing explicit `stop live chat` commands still work, even in Quiet mode.
  Mic button behavior, wake-word engine, TTS, signer and updater are unchanged.
- Ordinary passive room speech classified as OBSERVE is **not** submitted to Jarvis's AI/action router or added to saved Live Chat history. High-initiative advisor prompts classified as OFFER_HELP can be submitted to the configured AI provider for a response. Speech recognition itself may also use network processing. A bounded 90-second in-memory window now permits explicitly requested local recall; quoting an observation in the answer can appear in visible chat history. Speech-to-text may still use Android's network recognizer.


## Short-lived conversation continuity

- Answered ambient utterances are retained only in RAM, capped at three
  exchanges and 90 seconds. Clear on Live Chat start/stop, context disable,
  or a delayed AI callback after revocation. Never persist unanswered speech.
- The microphone/chime repair merged as PR #302 remains intact. In default
  system recognition, after repeated silence Android recognition pauses to
  avoid recurring tones. The **session stays available but is not actively
  listening** until the microphone is tapped; experimental PCM is opt-in.
  Continuous vendor-independent ASR and Samsung acceptance are not complete.
- Text-only responses cannot approve installs, operate the device, or use
  privilege-bearing tool actions. Ordinary addressed commands retain the
  existing privileged approval checks.

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
- **Semantic reasoning from room speech:** local recall of recent observations remains available; only *answered* ambient exchanges contribute temporary context to later text-only AI replies. Unanswered passive observations do not become semantic background, device commands or speaker identity.
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
