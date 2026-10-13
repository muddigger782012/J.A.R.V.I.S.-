# Local explanations of Gemini failures

Future Gemini HTTP errors and connection/response-reading failures retain only the most recent numeric code, bounded symbolic status/reason tokens and timestamp in private local preferences. No user prompt, API key, URL or raw provider error message is saved. Success does not erase the historical failure; the response clearly identifies it as the last recorded failure rather than a live outage check.

`why did Gemini reject the request`, `why did Gemini fail`, `explain the last Gemini error` and `show last Gemini error` answer locally, including when offline or Gemini is unavailable. The default `Hey Jarvis` prefix is accepted. Codes 503, 429, 401/403, 404, 400 and other server errors receive bounded explanations and next steps; ambiguous causes remain explicitly unknown. A 503 is worded as a service failure, not a content refusal. The explanation does not establish live-chat state. Unknown/no retained metadata is reported honestly, and failures before this update cannot be reconstructed automatically.

Inspection preserves a pending timer/app outcome and cannot approve or verify it. Diagnostic questions are reserved against aliases. Phone check after a future Gemini failure: ask why, then repeat offline. The same local explanation should appear. Without a recorded failure, the app should say so. This feature awaits phone verification.

`how do I fix the Gemini error` and `how do I fix the error` return the same locally grounded cause and repair steps. Available guidance includes retrying transient failures, checking API quota, checking the saved key and access restrictions, or correcting the configured model/request. It does not claim to repair provider outages or infer a detailed cause absent from the recorded metadata.
