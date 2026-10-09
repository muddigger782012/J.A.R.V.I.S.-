# Jarvis AI cloud build and AI gateway

## Android cloud build (no Cursor or Android Studio)
1. Upload this project **contents** to a GitHub repository, preserving `.github/workflows/android-apk.yml`.
2. In the repository, open Actions > Build Jarvis Android APK > Run workflow.
3. When the run succeeds, download `Jarvis-AI-debug-apk` under Artifacts (GitHub may deliver it as a ZIP). Extract the APK and install it.
4. To publish a release, create and push a `v*` tag; the workflow builds and attaches a development APK to a GitHub Release. Release publishing requires workflow contents write permission.

This build has **not** been run or verified. Existing Android code may require further fixes.

## Cloud AI gateway
1. Deploy `cloud-backend` to a Python cloud host (Render, Railway, Fly.io, etc.). Set start command `uvicorn main:app --host 0.0.0.0 --port $PORT` with working directory `cloud-backend`.
2. Set private environment variables `OPENAI_API_KEY` (separately billed API credential), `JARVIS_GATEWAY_TOKEN` (random long secret), optionally `OPENAI_MODEL`.
3. In Jarvis chat settings, set endpoint `https://YOUR-HOST/chat` and gateway token.
4. The backend accepts `POST /chat` with `session_id`, `messages` and `Authorization: Bearer TOKEN` and responds `{ "reply": "..." }`.

**Important:** This does NOT sign into a ChatGPT Plus account or provide access to ChatGPT menus, saved memory or chat history. API usage is separately billed. The shared gateway token is a development approach, not suitable for public multiuser deployment. A production release needs proper user login, secure credential storage, rate limits, and signed APKs. Android chat history is currently stored locally in app preferences.
