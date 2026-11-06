# Continuous PCM capture prototype (Personal J.A.R.V.I.S.)

This is a real experimental AudioRecord-to-SpeechRecognizer bridge, NOT a claim
that the existing Android recognizer supports every streaming feature.

- In **Ambient Companion Settings**, explicitly enable "Experimental continuous
  microphone pipeline (Android 13+)" (off by default).
- In an explicitly started Live Chat, the foreground service owns one
  16 kHz mono PCM AudioRecord for the session. Android 13+ provides a
  ParcelFileDescriptor pipe through RecognizerIntent.EXTRA_AUDIO_SOURCE.
- Each completed turn returns through the existing J.A.R.V.I.S. participation
  policy, AI/router and TTS. A bounded energy activity gate signals EOF after
  around 3 seconds of silence. AudioRecord stays open across turns.
- The microphone reader is isolated from provider backpressure with a bounded
  byte queue; voice samples are not written to disk. No passive audio is sent to
  Jarvis AI/action routing without a response-worthy addressed transcript.
- A vendor may ignore the injected PCM source and open another mic. On hard
  provider errors, after missing locally detected speech, **or after three
  consecutive empty recognition turns**, the experimental source is released
  before normal capture resumes. Any successful transcription resets this
  fallback counter. This avoids silently retrying a nonfunctional mic path.
- Manual stop, foreground microphone visibility and rollback to the default
  turn-based capture remain mandatory.

Limitations:
- This is **not** neural voice activity detection, speaker verification,
  true full-duplex speech or cross-provider tested continuous ASR.
- System speech recognizers may use network transcription; the PCM path does
  not guarantee local-only transcription.
- Some provider implementations do not implement EXTRA_AUDIO_SOURCE or segmented
  results; Samsung hardware testing is needed before enabling by default.
- Energy gating can misclassify loud workshop machinery as speech. Replace with
  a supported neural VAD once its model and performance are validated.

This architecture is an incremental transition from repeated microphone
ownership to one explicitly started, continuously owned audio source. It is
not intended for a silent background recording mode.
