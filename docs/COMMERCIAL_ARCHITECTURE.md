# Commercial architecture: incremental migration plan

Status: **proposed foundation, not implemented monetization**. This document sets implementation boundaries; it does not claim that accounts, billing, gateway authentication, or production monitoring already exist.

## Objectives
- Continue Jarvis intelligence and voice development without breaking existing installed versions.
- Reuse validated assistant capabilities in Jarvis Keyboard and future products.
- Make commercial cloud usage secure, measurable, and financially controllable.
- Preserve the personal Jarvis and Beta behavior and existing API integrations until replacements are explicitly validated.

## Current baseline (verify again before implementation)
- Android Gradle project currently includes `:app` only; `cloud-backend/` is a separate Python service.
- `release` uses `com.assistant.core`; `beta` appends `.beta`, with separate updater configuration and bundled voices.
- GitHub Actions builds and publishes signed APKs. Release versioning, signing identity, and updater compatibility are migration invariants.
- The Beta build currently embeds `JARVIS_BETA_GEMINI_API_KEY` in BuildConfig. GitHub Secrets do **not** make that value secret in a distributed APK. This is a commercial security blocker, not an approved long-term pattern.
- UI emulator testing has been disabled on main; PR #78 proposes enabling it. Do not treat emulator verification as passing until a real run succeeds.

## Target boundaries
1. **Assistant core:** conversation state, intents, tool contracts, policy, memory and validated learning; no Android Activity dependencies.
2. **Android integration:** permissions, wake service, accessibility, notifications, overlays, device actions, speech hardware.
3. **Presentation:** themes, settings, onboarding, chat, diagnostics, user-visible state.
4. **Cloud gateway:** authenticated provider proxy, per-user quota and throttling, cost metering, abuse protection, error handling and observability. Provider keys remain on the server.
5. **Product/release policy:** app identity, channel, update endpoint, entitlement abstraction and feature configuration. Personal, Beta and commercial behavior must be explicit and testable.

These are **logical boundaries first**. Add Gradle modules only when a concrete extraction can be validated without changing app identity, user data, or release behavior.

## Ordered implementation
### A. Inventory and baseline
- Record existing package names, signing fingerprints, updater URLs, persisted storage schemas, API flows, and current APK sizes.
- Establish passing unit/backend/voice/theme/UI checks and real-device upgrade tests. Diagnose any failing emulator hierarchy capture; never suppress failures as a workaround.
- Capture baseline startup time, idle CPU/battery, memory, voice model size, and cloud cost per interaction.

### B. Extract seams without behavior changes
- Define interfaces for AI provider transport, conversation persistence, release channel, and entitlements.
- Wrap existing implementations behind interfaces; add contract tests and preserve legacy paths.
- Extract only proven reusable code, one bounded area at a time.

### C. Secure commercial gateway
- Keep credentials server-side. Add authenticated user/device sessions, token rotation, quotas, rate limiting, request-size limits, and provider-cost telemetry.
- Introduce a staged gateway client behind configuration. Test offline fallback and failure handling.
- Do not remove Beta's existing API path until replacement is verified and user-approved. Never publish a commercial build with extractable provider credentials.

### D. Distribution and entitlement readiness
- Define account and subscription states, restore-purchase behavior, trials, refunds, and offline grace behavior before selecting billing integration.
- Keep personal and Beta free of unintended paywalls; validate upgrades from old versions and channel-specific updates.
- Add privacy disclosures, deletion/export paths where appropriate, consent controls, and crash telemetry without collecting chat content by default.

### E. Paid pilot and reuse
- Ship a small, instrumented beta only after security, upgrade, and stability gates pass.
- Track acquisition, activation, 7/30-day retention, paid conversion, inference cost per user, refunds, and support burden.
- Reuse assistant core for future products after interfaces and regression tests have proved stable.

## Release gates
- All applicable CI checks pass, including emulator UI smoke checks.
- Real-device cold install and in-place upgrade succeed without data loss.
- Personal and Beta identities, signatures, voices, API behaviors and update paths remain correct.
- No provider credentials are extractable from a commercial APK.
- Auth, quotas, usage metering, failure recovery and support diagnostics are tested.
- Cloud unit economics and privacy/security review are documented.

## Coordination
- PR #75: intelligence/context work; avoid cross-branch overwrite.
- PR #76: broader Codex documentation; reconcile its AGENTS.md guidance before merge.
- PR #78: UI emulator CI re-enablement; merge and diagnose UI failures before asserting release readiness.
- This foundation branch is documentation and guardrails only; implementation work should follow as small reviewed PRs.
