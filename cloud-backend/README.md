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
