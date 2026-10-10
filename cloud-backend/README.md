# Dedicated J.A.R.V.I.S. gateway

Deploy this directory as a Docker service on an HTTPS hosting platform. The service supports the app's existing POST /chat contract and GET /health. No public endpoint is created by committing this code.

Required server environment:
- JARVIS_GATEWAY_TOKEN: generate a strong random token (`python -c 'import secrets; print(secrets.token_urlsafe(32))'`). Keep it secret and enter it on your phone.
- OPENAI_API_KEY: optional for live US weather; required for general AI. Keep this key on the server only.
- OPENAI_MODEL: optional, defaults to gpt-4.1-mini.
- PORT: provided by hosting, defaults to 8080.

Use a personal, single-user deployment. Do not embed keys or tokens in the APK or repository. Health means the process is online, not that provider credentials are configured.

On the phone: Voice Settings → AI gateway and weather settings. Enter `https://YOUR-HOST/chat`, the gateway token, and weather coordinates. Chesapeake example: latitude 36.7682, longitude -76.2875. Coordinates are explicitly user-selected, not automatically tracked. The current weather endpoint answers for the saved coordinates; named cities and travel locations are not resolved yet.

Weather questions fetch National Weather Service point metadata and its forecast URL. Rain answers name the next unexpired forecast period mentioning rain/showers/storms, with forecast wording and precipitation chance; they are not a guarantee of rainfall. Weather responses are never persisted as learned answers. US locations only. Network/provider failures return 502 instead of invented weather.

Hosting is required before this gateway can answer from the app. Configure provider credentials and HTTPS at the host; GitHub Actions builds the APK but does not host the gateway.

## Optional assistant providers

The APK's **Voice Settings → Assistant connections** page keeps Home Assistant and gateway tokens encrypted with Android Keystore. Commands must explicitly name the provider; local device commands and Gemini do not automatically get rerouted to a home server. These integrations do not provision a server or include a third party assistant's source code in the APK.

### Home Assistant Assist (direct from the phone)

1. Install Home Assistant on your own computer, Raspberry Pi, or Home Assistant hardware. Follow https://www.home-assistant.io/installation/.
2. Configure Assist and expose only the devices/entities you want it to control: https://www.home-assistant.io/voice_control/.
3. In your Home Assistant profile's Security section, create a long-lived access token. Keep it private; do not add it to this repository. See https://developers.home-assistant.io/docs/auth_api/#long-lived-access-token.
4. Provide an HTTPS base URL reachable from your phone (e.g. your Nabu Casa remote URL). Set the base URL and token in Assistant connections; optionally specify a conversation agent ID. The app appends `/api/conversation/process`.
5. Say `Home Assistant turn on the kitchen lights` or `Ask Home Assistant what is the living room temperature`. The server's response is spoken through J.A.R.V.I.S. A server timeout does not establish whether a device action occurred, so check before retrying.

This connector uses Home Assistant's Conversation API. It requires a real Home Assistant installation, exposed devices, and network access. A general web-service gateway is not a Home Assistant server.

### Google Assistant SDK excluded from this build

Google limits the legacy Google Assistant SDK to experimental, non-commercial use (https://developers.google.com/assistant/sdk/overview). It is intentionally excluded from this standalone build because the app may eventually be offered to the public. Gemini's direct API supplies optional general answers; Android App Actions supplies the supported app integration. Google Assistant proprietary source is not included.

### Mycroft / compatible OpenVoiceOS message bus

Run an existing Mycroft or compatible OpenVoiceOS installation on your own server and install the skills you want. This APK does not run the Python assistant on Android. Message-bus reference: https://mycroft-ai.gitbook.io/docs/mycroft-technologies/mycroft-core/message-bus.

Build with `docker build --build-arg INSTALL_ASSISTANT_ADAPTERS=true -t jarvis-gateway cloud-backend`, or install `requirements-assistants.txt` into your gateway environment. Set `MYCROFT_BUS_URL` to your bus WebSocket address (for example `ws://127.0.0.1:8181/core` when both run on the same host) and optionally `MYCROFT_LANGUAGE` (default `en-us`). Keep the unauthenticated bus private; only expose the authenticated HTTPS gateway. Configure the same gateway endpoint/token in Assistant connections and say `Mycroft what time is it?`.

The adapter sends `recognizer_loop:utterance` and relays only correlated `speak` events. Skills must preserve request context or reply destination; unrelated bus speech is deliberately ignored. If your skills drop that context, the request times out rather than exposing another user's conversation. A timeout may follow an executed skill action; do not automatically retry. Full skill-response compatibility needs a running server test.

### Android App Actions and launcher shortcuts

Long-press the installed J.A.R.V.I.S. launcher icon for Voice chat, Weather settings, and Assistant connections. `shortcuts.xml` also declares `actions.intent.OPEN_APP_FEATURE`. Only those fixed feature identifiers are accepted by the exported activity; external intents cannot inject arbitrary commands.

Google Assistant voice invocation requires a developer preview with Android Studio App Actions tools or Play Console submission and Google's App Actions review. Sideloading an APK does not automatically activate App Actions. See https://developer.android.com/develop/devices/assistant/get-started. Old Conversational Actions were retired in 2023 and are not used.

### New gateway hosting with Render

`render.yaml` at the repository root is a deployment blueprint for the basic gateway. Connect your Render account and authorize access to this repository, then create a Blueprint service from it. Render generates `JARVIS_GATEWAY_TOKEN` in the service environment. Copy that token privately to the phone; use the service's real `https://...onrender.com/chat` URL. Set `OPENAI_API_KEY` only if you want the gateway's optional general-AI route; the APK's direct Gemini and weather features do not require it.

This creates only a gateway, not Home Assistant or Mycroft. The optional Mycroft adapter additionally needs the WebSocket dependency and an existing message-bus server. Service plan availability, cold starts, and resource limits should be checked in Render before deployment. No address, provider credentials, or active server is created by committing the blueprint.
