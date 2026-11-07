# Personal J.A.R.V.I.S. → third-party Android Jarvis: localhost AI bridge

**Status:** feature branch implementation. Not a released or installed APK.
Personal app only (com.assistant.core). No change to Beta or Developer provider routing.

## Purpose

The third-party AbuZar-Ansarii/Jarvis Flutter app's Custom provider sends:
- GET /v1/models (optional)
- POST /v1/chat/completions, OpenAI-compatible messages, optional stream=true

Personal Jarvis previously acted only as a client to Gemini, HTTPS gateway, and local LFM3. This bridge adds an **explicitly started local server**, forwarding text to the Personal app's configured provider via IntelligenceRouting. It does not route external messages through the action/approval executor, wake-word microphone, or learning engine.

## On the Samsung, after a properly signed Personal build containing this change

1. In **Personal J.A.R.V.I.S. → Settings → Personal AI bridge**, choose **Start local AI bridge**.
2. Choose **Check connection** and verify it passes.
3. Choose **Copy access token**. This is a generated 256-bit random secret stored under the Personal app's private *no-backup* directory. Treat it like a password.
4. In downloaded AbuZar Jarvis, open **Settings → Model Provider Preset → Custom (OpenAI-compatible)**, then fill:
   - Base URL: \`http://127.0.0.1:8765/v1\`
   - Model: \`personal-jarvis\`
   - API key: paste the access token from the Personal bridge screen.
   - Save changes.
5. Ask a short text question in downloaded Jarvis. It should receive Personal's chosen AI provider response.

The process runs as an Android foreground service and displays a persistent notification with **Stop bridge**. It is opt-in, does not auto-start after reboot or app death, and never binds to 0.0.0.0 or the phone's Wi-Fi/mobile network interface. If Chrome reports a failed local URL when the server is stopped, that is expected.

## Choose a live Personal provider first

Inside Personal → AI and weather choose Gemini, your configured HTTPS gateway, or Local LFM3, and set fallback intentionally.

- Local LFM3 requires working GGUF inference (a working on-phone llama-server on 127.0.0.1:8080 in compatibility mode, or the separate experimental native-enabled build).
- The bridge's port **8765** is not the local model runtime port **8080**; the bridge forwards text to whichever provider Personal selects.
- If the selected provider is Gemini or the gateway, the app sends conversation text to that cloud service.
- If a local provider fails and fallback is enabled, the request can be forwarded to an alternate configured cloud provider.
- The bridge **does not install Ollama**, grant Android permissions to the other app, or perform screen/device actions on Personal's behalf.

## Live connection diagnostics (next milestone)

The bridge Settings screen now shows the selected Personal AI provider, whether it is configured, and whether fallback is enabled. These labels are configuration observations, not proof of network or inference health.

- **Check connection** sends an authenticated GET to the local /v1/models endpoint, testing the listener and stored bearer token without invoking an AI model.
- **Test AI response** sends one short authenticated OpenAI-compatible chat request to /v1/chat/completions, consumes an actual Personal AI reply, and reports whether the model path worked. Cloud AI usage may be billed. HTTP 503 points to the selected provider or runtime; HTTP 401 indicates a token mismatch; connection refusal means the bridge is not listening.
- **Regenerate access token** requires an explicit confirmation. Personal replaces the stored token atomically; newly issued HTTP requests using the old token are immediately rejected, but in-flight responses may finish. Copy the new token into downloaded Jarvis under API key. Regenerating the token does **not** erase conversations or change model choice.
- The bridge remains **manual start**, loopback-only, and text-only. In-app AI testing does not grant actions to another application.

This increment has Android token-revocation and bridge-screen navigation acceptance tests. A successful emulator test does not replace a physical Samsung cross-app session test.

## Acceptance checks

Before declaring this production-ready, verify on a real Android device:
- Fresh signed Personal build installs over prior Personal build without a signature mismatch.
- Start bridge from a visible settings screen; the foreground notification appears, and Check connection passes.
- GET /v1/models with the token returns only personal-jarvis; missing/wrong token returns HTTP 401.
- POST chat completion works with stream=false and stream=true (one final SSE delta, not incremental token streaming).
- Incorrect model, invalid payload, oversized HTTP body fail cleanly.
- Downloaded Jarvis Custom provider works with the exact URL, model, token.
- Stop bridge: requests fail; restart requires explicit owner action.
- Verify only 127.0.0.1 is bound; confirm LAN clients cannot reach it.
- Confirm Beta does not show or start bridge; no voice wake, microphone or Developer regression.
- Confirm no external prompt triggers privileged Personal app actions, permission approvals or learned commands.
- Check Android battery/FGS behavior and policy constraints for long sessions.

## Troubleshooting

- **\"Is Ollama running in Termux?\"**: downloaded Jarvis is still set to Ollama or uses port 11434; change to **Custom** and copy the bridge Base URL above.
- **Connection refused**: start Personal AI bridge and press Check connection. Confirm no port conflict at 8765.
- **HTTP 401**: re-copy token; do not use a cloud/Gemini API key as the bridge key.
- **HTTP 503**: bridge is reachable but Personal's configured AI provider is unavailable. Fix Personal AI settings or start the LFM3 runtime.
- **HTTP 400**: wrong model or malformed request. Use model personal-jarvis.
- **HTTP 404**: Base URL is wrong; it must end in /v1 and the calling app appends /chat/completions.

## Security limits

The loopback listener is bound only to 127.0.0.1 with a random bearer token. Requests are capped at 64 KiB; no tool invocation is supported. Token storage is app-private and excluded from backups. Users must explicitly enable the server; stop it when not needed. The token is copied to Android's clipboard when requested, so treat clipboard exposure accordingly. Local apps can reach the loopback port but only authorized apps with the token should receive answers. Protocol-level tests do not replace signed Android/device acceptance.
