# Issue #178 — Provider diagnostics implementation contract

## Confirmed code inspection
- `HybridAssistantService.kt` routes phrases including `test search connection` to a `https://www.google.com/generate_204` probe.
- A successful 204 is only evidence that this endpoint is reachable; it is not a search-provider or Gemini-model health check.
- Existing `GeminiModelDiscovery.kt`, `GeminiFailureMemory.kt`, and Voice Settings must be reused.

## Implementation order
1. Extract the existing 204 probe into a network diagnostic returning a typed result: endpoint, HTTP status, latency, timestamp, error category, and confidence scope.
2. Separate explicit intents for `test internet`, `test search`, `test Gemini`, and `test AI`. Do not silently alias provider tests to the internet probe.
3. For search: use the configured search provider's safe authenticated test request. For Gemini: use the configured API key and a low-cost provider-supported endpoint/model listing, distinguishing authentication, quota, model configuration, transport, and server errors. Avoid generating paid content solely for a status test where possible.
4. Return `not configured`, `not tested`, `reachable`, `unavailable`, or `inconclusive` with evidence. Do not claim full conversation capability based only on endpoint reachability.
5. Redact API keys, user prompts, auth headers, raw URLs with tokens, and provider response bodies in logs. Use bounded timeouts; no tests without user initiation or explicit consent.
6. Wire results to the existing Connection Manager design without changing the chat HUD, app identity, or personal developer model controls.
7. Add JVM tests for exact voice/text phrases, offline/DNS/TLS/401/403/404/429/5xx/timeouts, missing key, and successful probes. Include device acceptance checks.
8. Follow up with contextual weather question resolution, installed release notes, persistent honorific preferences, and voice controls as tracked in #178.

## Acceptance prompts
- “Test internet connections” — report basic probe only.
- “Test search connections” — actually exercise configured search provider, or say it is unconfigured.
- “Test Gemini connection” — exercise Gemini provider independently, or say why not.
- “Test AI is functional” — distinguish provider reachability from successful inference.
- “What's new since your update?” — report actual installed version and bundled release notes.
- “What's the next day it's supposed to rain?” — search forecast beyond prior answer or state forecast horizon.
- “Quit saying sir so much” — persist preference across app restart.

## Release policy
Personal Jarvis remains the development configuration. Commercial Beta stays separate. Require CI, signed personal APK, existing in-app updater compatibility, and physical-device verification before claiming completion. No automatic publishing.
