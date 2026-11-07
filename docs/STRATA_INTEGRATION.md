# Strata integration for J.A.R.V.I.S.

This feature branch adds Strata as an **optional inference provider**, not a replacement for Personal Jarvis, on-device LFM3, Gemini, the existing gateway, or Jarvis Developer's OpenAI/Gemini providers.

Upstream: https://github.com/Niko1221/Strata (MIT-licensed source; model weights and other dependencies may use different licenses).

## Windows / Linux / macOS host

1. Install Strata from its upstream releases or CLI and choose a model that fits the host's available RAM/VRAM/storage. The model engine runs on the computer, **not inside the Android APK**.
2. Start Strata serving. Its default server is http://127.0.0.1:8080; this default is reachable only from the same machine.
3. In Strata's sharing/access settings, explicitly share the inference server to your Android device and enable the API key. Configure the network firewall and use the computer's private LAN IP or a secure VPN/Tailscale address. Never publish an unauthenticated HTTP service on the public Internet.
4. Use the served model identifier reported by the Strata server. The conventional alias is \`strata\`; another model identifier may be valid if your running Strata version reports it.

## Personal J.A.R.V.I.S. (Android)

Go to *Weather and AI* settings. Set **Strata server URL**, **Strata served model ID**, and optional **Strata sharing API key**. Examples:

- Private LAN: \`http://192.168.1.25:8080/v1\`
- TLS gateway: \`https://your-secure-strata-host.example/v1\`

Tap **Test Strata connection** to perform a brief real inference probe; then Save and select **Strata local AI** as the conversation provider. Enable or disable automatic provider fallback as preferred. On-device commands, voice recognition, ambient speech handling, and Android permission checks remain separate from Strata inference. The *Personal* model-selection and LFM3 features are preserved.

## J.A.R.V.I.S. Developer (Android)

Under *Settings / Connect your AI*, choose **Strata** and enter the encrypted provider credential as \`https://HOST/v1#SHARING_KEY\` or \`http://192.168.1.25:8080/v1#SHARING_KEY\`, where \`#SHARING_KEY\` is omitted if the server requires no token. Choose \`strata\` or a custom server model, test the connection, and save. Coding suggestions, repository-context prompts, and the bounded CodingAgent prompt can now use Strata. The existing app still requires its independent source review, code approval, build evidence, and installation/upgrade approval.

## Intentional engineering boundaries

- No arbitrary product subscription quotas or per-message caps are added by this client. The running model/server still imposes memory, context, maximum response size, and hardware limits.
- HTTPS is allowed; cleartext HTTP is accepted by the shared client only for loopback, numeric private IPv4 and Tailscale CGNAT ranges. Never send credentials over untrusted networks.
- The client rejects unsolicited tool calls. Model-generated text cannot directly execute shell/device actions, overwrite GitHub source, sign an APK or install updates.
- Model data is sent to the selected Strata server computer and any server configuration it enables; it does not automatically remain on the Android phone.
- Android microphone and device actions have no dependency on Strata and still require device permissions and their existing runtime checks.

## Release and verification gates

This is source integration pending Android unit tests, lint, APK builds, device connectivity verification on a real Strata host, and signed in-app release testing. No live Strata host credentials or hardware were available for an end-to-end inference test. Do not describe an unverified source branch as an already-installed release.

The commercial Beta variant is not automatically switched to Strata: its existing Gemini/assistant mode is deliberately unchanged pending a separately tested commercial routing decision. Other standalone Jarvis apps require their own source integrations.
