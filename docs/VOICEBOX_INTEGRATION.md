# Voicebox integration

Source reference: https://github.com/jamiepine/voicebox at `8af7efe62fab8d33e2a5dfbedabaa45f68a5184f`.

The shared JVM REST client uses `GET /profiles` and `POST /v1/audio/speech`
with `model: tts-1`, the saved profile name or ID, and `response_format: wav`.
The model alias uses the voice profile's configured engine. Responses play on
Android, not the Voicebox host. This deliberately avoids `/speak`, which is for
host-side playback. The upstream engine and voice profiles are managed in
Voicebox; its source, binaries and model weights are not bundled in our APKs.

## Use

1. Install Voicebox on your computer, install a speech model, create a voice
   profile and confirm generation in Voicebox itself.
2. For a separate Android device, make the Voicebox REST service reachable
   through your own trusted HTTPS endpoint. Android must trust its certificate.
   This change does not deploy that endpoint. Keep the unauthenticated upstream
   service private; the client does not yet implement authenticated gateways.
   `http://127.0.0.1:17493` is for a server on the *same device*, or a separately
   configured USB reverse tunnel. It does not point at a Windows PC from a phone.
3. Assistant: Settings → Assistant voice → Voicebox. Developer: Settings →
   Voicebox. Enter the endpoint, list profiles, enter the chosen name/ID, test,
   enable spoken replies and save. Each app has independent saved preferences.
4. Assistant routes regular/live replies through Voicebox only when the existing
   speech channel toggle permits it. Connection/audio failure reports a status
   and tries Android speech for that reply. Disabling Voicebox restores the
   existing voice-selection path.
5. Developer speaks new successful coding replies while foregrounded when
   enabled. It omits text after the first code fence and caps narration at
   10,000 characters. Playback failure leaves the text visible. Source review,
   commit, build and release approvals remain separate.

Playback supports cancellation, audio focus, lifecycle cleanup, bounded downloads,
WAV signature checks, timeout and stale-response rejection. Host settings cannot
contain credentials, paths, query strings or fragments. Remote plaintext and HTTP
redirects are rejected. No text/audio/server error body is written to logs.

## Scope and evidence

Eight JVM tests pass locally using actual loopback HTTP: profile listing, speech
request/response, HTTP failure, malformed audio, cancellation, input limits,
endpoint validation and redirect rejection. These use fixture WAV bytes and do
not prove model inference or audible output. The shared Android adapter is also
compiled separately against the installed Android SDK. Full app CI and live
Voicebox/device playback remain distinct validation steps.

This milestone adds speech output in Personal Assistant and Developer. It does
not replace Android microphone capture, wake-word detection or ambient response
policy, and does not yet integrate Voicebox transcription, profile creation,
MCP tools, Keyboard or Connection Manager. The pure JVM client can be reused by
a future Windows host; it is not a completed Windows application.

The feature defaults off. App identities, signing and updater feeds are retained.
No release promotion or device installation is performed by this source change.
