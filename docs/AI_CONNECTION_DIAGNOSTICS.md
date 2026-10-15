# AI connection diagnostics

## Observed issue

The owner installed the signed Main test APK 3.5.397 and supplied screenshots of contradictory answers to “Are you connected to Gemini?”, “Are you connected to AI?” and “Are you connected to internet?”. Generated replies claimed independent or fully local operation, while another request reported a Gemini connection failure. These screenshots establish the misleading replies and a failed request; they do not identify its transport or service cause.

## Host status and provider errors

`AiConnectionStatus` handles narrowly matched connection questions locally, before pending action prompts, learned aliases and AI fallback. `AiConnectionStatusReader` reads Android's current network capabilities and the same effective provider configuration used by existing chat. It never sends a probe, reads remote models, changes settings, or displays credential values. Network validation and configured credentials are explicitly distinguished from a successful provider request. Gemini keeps priority over the optional gateway when a Gemini key is configured. Gateway configuration does not establish which model its server uses.

Connection questions are reserved from command learning. They do not approve or run pending actions, and they preserve a pending device-action outcome check. Text and foreground voice use the same hybrid handler. Existing local commands, integrations, key storage, API URLs, models, timeouts, request authentication and provider selection remain in place. Direct Gemini prompts identify the actual component provider while preserving the Jarvis persona and action-confirmation rules.

`GeminiFailureMemory` retains only the existing sanitized HTTP code, status/reason tokens and timestamp, with an optional exception-category enum for new transport/response failures. Categories distinguish DNS lookup, timeout, TLS, transfer, malformed JSON and unknown failures. Raw exception messages, provider error messages, URLs, prompts and keys are never copied into these diagnostics. Existing code-zero records remain readable with an unknown cause. A successful request does not erase the historical last failure; “explain the last Gemini error” is explicitly historical, not a current health check.

The existing Intelligence dashboard's **Test primary AI connection** remains the explicit live test. It sends its existing short test prompt without chat history, memory, learned phrases or location. A local connection-status reply does not run that test.

## Validation and limits

JVM coverage checks screenshot phrases, exact matching, all network states, provider priority, reserved learning controls, pending outcome retention, safe typed failure messages and older failure records. Emulator regression uses disposable Main and Beta updater fixtures, intercepts HTTP(S), checks status routing online/offline, and verifies that provider/key preferences remain unchanged. Production API connectivity requires the owner's device and existing configuration; fixture credentials never establish successful Google or gateway access.

No theme, Arc Reactor, signing, package identity, data migration or distribution changes are part of this fix. The updater and signed APK 3.5.397 are reviewed separately in `UPDATE_CHANNELS.md`. This document describes the implementation; passing checks and phone acceptance must be reported separately.
