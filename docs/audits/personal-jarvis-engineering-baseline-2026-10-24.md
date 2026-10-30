# Personal J.A.R.V.I.S. — evidence-based engineering baseline (2026-10-24)

**Scope:** source-level architecture, CI workflow definitions, GitHub run results and user-provided installed-app screenshots. **Not performed:** physical-device execution, network-provider end-to-end probes, Android Accessibility grant, real microphone measurements, or independent APK installation.

## Executive finding

The project has substantial implementation, but the integration between user language, deterministic Android capabilities, provider answers, and verified device outcomes is the release-blocking weakness. Passing a compilation/build/release workflow is not proof of an assistant function. Do not publish a capability as *working* without an executable acceptance test and (where hardware dependent) real-device evidence.

## Evidence and findings

| ID | Severity | Evidence | Conclusion | Required proof |
|---|---|---|---|---|
| F-01 | P0 | User screenshots: Jarvis denies screen reading and diagnostics. `HybridAssistantService.kt` routes only recognized `ScreenSession`/ `DeviceReport.command`/ `WorkflowDiagnostics.command` phrases before general response handling. `DeviceReport.kt` originally recognizes narrow phrases such as `device capabilities`, not the user's natural questions. | Capability discoverability/routing failure. **Does not establish screen access itself is broken.** | Exact screenshot utterances return local permission/capability status; explicit `read my screen` is exercised on a consenting test device with Accessibility enabled and disabled. |
| F-02 | P0 | `AssistantCapabilityCatalog.kt` lists broad domains, while `DeviceReport.kt` explicitly says notification reading/replies are not implemented. | A declared capability catalog is not an executable feature inventory; listing broad domains risks misleading answers. | For each capability report implemented/not implemented, permission readiness, execution adapter, and verified last outcome. |
| F-03 | P0 | `VoiceAssistantService.kt`, `VoiceForegroundService.kt`, `LiveSpeechCapture.kt`, `VoiceSessionController.kt` each contain state/callback logic. User reports slow wake/mic, mid-utterance cutoff, premature chat termination. | Multiple interacting voice lifecycles; no physical-device latency evidence in this audit. Root cause **not established**. | 20 wake attempts; 20 push-to-talk starts; long utterances with 1–3s pauses; 10 consecutive live turns; record stage latencies and failures on target device. |
| F-04 | P0 | `DirectOnlineClient.kt`, `FallbackAiClient.kt`, `IntelligenceRouting.kt`, `HybridAssistantService.kt` exist; user reports unreliable Gemini access. | Provider integration exists in source; provider authentication, selection and response quality on installed app remain unverified. | Run a safe provider connectivity probe, report selected model, HTTP category, fallback decision, and time to response; redact keys. |
| F-05 | P1 | `ScreenAccessClient.kt` calls a private content provider, `ScreenControls.kt` implements review tokens and command parsing, and `JarvisAccessibilityService.kt` exists. | Screen interaction is implemented at source level, but enablement, bridge connection, visible-screen read, and tap verification are separate conditions. | On-device enablement and controlled read/scroll/tap review with success/failure readback. |
| F-06 | P1 | `.github/workflows/build-jarvis.yml` compiles and signs APK; `.github/workflows/local-knowledge-checks.yml` runs unit tests, lint and emulator suites. Build/release run succeeded for 3.6.520. | Packaging is functional. CI covers significant areas, but no evidence here of complete physical-device conversational acceptance. | Gate public feature claims on a recorded device test matrix in addition to CI. |
| F-07 | P1 | `HybridAssistantService.kt` is approximately 157k characters and coordinates local commands, cloud routing, screen access, routines, learning and diagnostics. | High-concentration orchestration increases regression risk and makes failures difficult to isolate; this is an architectural risk, not proof of a particular defect. | Extract a deterministic dispatch trace (redacted) and test each handler precedence and failure path before large refactors. |

## Immediate disposition

1. **Preserve:** build/signing/updater infrastructure, on-device report adapters, existing screen bridge, action approval and verification, and automated test suites. They are real assets, though their runtime correctness is not universally verified.
2. **Repair first:** deterministic capability/self-status routing; clear distinction between *supported*, *permission granted*, *connected*, *executed*, and *verified*. PR #218 is a partial phrase-routing repair, **not** a complete capability inventory.
3. **Isolate and test:** PR #217's voice lifecycle changes. Do not merge just because unrelated UI/diagnostic checks pass.
4. **Defer:** additional visual redesigns, broad feature announcements, unsupported self-learning claims, and new app modules until P0 acceptance is met.
5. **Stop reporting:** invented phase completion percentages or build success as proof of real-world capability.

## Minimal release gate (must record pass/fail/blocked with evidence)

- [ ] Installed version and release channel are read from Android and correspond to published artifact.
- [ ] All three user screenshot questions return truthful local capability/permission/diagnostic answers.
- [ ] With Accessibility disabled, Jarvis says it is disabled; with consent and enablement, `read my screen` returns visible content and no protected content.
- [ ] Wake phrase succeeds >=18/20 on the target phone in defined quiet conditions; record actual numerator.
- [ ] Push-to-talk starts listening within an agreed threshold on 20 trials; record p50/p95, not a guessed latency.
- [ ] 10 continuous conversation turns do not terminate without user request; log all failures.
- [ ] Gemini test records selected model, auth/connectivity result and explicit fallback without exposing API keys.
- [ ] One safe device action (volume or timer) records requested action, Android dispatch, outcome verification and truthful user feedback.
- [ ] Every advertised capability has a working execution adapter or is clearly labeled unavailable/planned.

**Audit limitations:** Source inspection establishes presence and control-flow structure, not end-to-end operational correctness. This document is a baseline for evidence gathering, not a certification.
