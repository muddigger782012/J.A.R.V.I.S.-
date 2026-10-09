# Jarvis conversation layer — initial implementation

This adds a separate chat screen, local per-session history, new-chat/history controls, and an HTTPS JSON backend adapter without changing existing device-control code. No APK has been compiled or tested yet.

## Backend contract
POST to the configured HTTPS endpoint with JSON `{ "session_id": "uuid", "messages": [{"role":"user","content":"hello"}] }`.
Return JSON `{ "reply": "Hello" }` with HTTP 200. The backend must hold OpenAI API credentials; never embed an API key in Android source or an APK. Add user authentication to the backend before exposing it publicly. HTTPS alone does not authenticate the caller.

**Important:** OpenAI API usage is billed separately from ChatGPT subscriptions; this integration does not sign into, control, or mirror the official ChatGPT app/account, menus, or existing chat history. ChatGPT account authorization cannot be assumed available to third-party apps. The local history uses Android app preferences and is not encrypted; avoid sending sensitive data until secure storage and authentication are implemented.

No background actions, overlay, voice AI, or Windows client are implemented in this increment.
