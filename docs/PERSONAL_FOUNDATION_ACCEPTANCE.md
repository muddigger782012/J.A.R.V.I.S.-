# Personal J.A.R.V.I.S. foundation acceptance

Tracks [the six-phase initiative](https://github.com/muddigger782012/J.A.R.V.I.S.-/issues/140)
and its phase and delivery issues. This increment improves working behavior across
phases without using Phase 1 perfection as a gate. The requested percentages remain
planning targets, **not verified completion measurements**.

## Delivered behavior and acceptance evidence

| Phase | This increment | Automated evidence | Still required |
| --- | --- | --- | --- |
| 1: core reliability | Preserves wake thresholds, speech profiles, contact matching, foreground services and themes. Reports rejected Accessibility actions as failures and Clock launches as requests. | Existing voice/contact tests; new Accessibility result tests; existing voice emulator job remains enabled. | Controlled wake/deep-voice/false-activation measurements, decode-stall and lifecycle recovery, physical-device contact and call checks. |
| 2: conversation | Explicit interpretation state replaces response-text matching. Questions mentioning commands stay conversational. Personal solution-first instructions apply in both persona modes. One configured gateway alternative can answer after a failed Gemini interpretation. | Intent, local query, fallback, personality and provider-routing tests; Android service acceptance cases. | Live provider behavior, naturalness and three-turn voice/text evaluation on the personal phone. Prompt contract tests do not prove model behavior. |
| 3: memory/offline | A bounded local dialogue window survives service/process reconstruction. Latest corrections follow older dialogue. Exact review/reset controls; confirmed device memory and validated command learning remain separate. Local arithmetic, date/time, commands and approved routines remain available offline. | Reconstruction, expiry, malformed data, deletion, write-failure and command-control tests; actual Android storage acceptance cases. | Physical restart and foreground/voice-process smoke tests. No offline generative model or arbitrary fact-learning is claimed. |
| 4: device integration | Selects implemented Standard fallbacks before an unavailable/unsupported privileged route is attempted. Maintains risk confirmations. Does not repeat uncertain adapter effects. Android rejecting an Accessibility action cannot become success. | Broker tests with injected adapters exercise actual selection, disabled fallback, denied policy, unsupported actions, failures and exceptions. | Standard, Accessibility, Shizuku and Dhizuku permission/device matrix. An advertised capability is not proof of permission or a working service. |
| 5: execution | Personal saved approved routines use a step/checkpoint engine. Supports review, bounded explicit resume, cancellation, deduplication and distinct requested/user-confirmed/device-verified results. | Engine tests for representative volume/timer/app/updater routines, interruption, cancellation, changed approval/targets, storage failure and duplicate effects. | Device execution of three representative workflows; durable service-level checkpoint adapter, cross-process task coordination and broader goal decomposition. |
| 6: improvement | Read-only proposals from recurring existing local result history: failure categories, bounded evidence, synthetic regression cases, recovery and recurrence. | Deterministic journal evaluation tests cover insufficient evidence, duplicates, conflicts, later verification and reopening. | Reviewed real-world feedback dataset, conversational failure collection with opt-in, developer integration for proposed patches/PRs and before/after behavioral evaluation. |

The engine and pure policy tests establish their bounded contracts. Fake/injected
adapters do not prove that Android or an AI provider performed an action. Android
instrumentation is a separate check from JVM compilation and a real phone is a
separate check from the emulator.

## Local controls and data boundaries

Personal-app-only controls:

- `show conversation memory` / `review conversation memory`
- `clear conversation memory` / `reset conversation memory`
- `routine execution status`
- `resume routine execution`
- `cancel routine execution`
- `the routine worked` / `the routine failed` for an explicitly checked current routine result; reports remain user evidence, not device verification or installation proof
- `improvement status`
- `review improvement proposals`

These controls cannot be taught as command aliases or saved routine names. They
run locally and do not authorize unrelated pending actions. Routine status and
control refer only to the current tracked execution in that assistant session.

Conversation memory retains at most 30 exchanges, 2,000 characters per side and
24,000 characters total, with seven-day expiry. It uses atomic writes and a file
lock across app processes, and lives outside Android backup. It does not import
the visible chat archive. Clearing context leaves existing preferences, validated
commands and visible chat history separate. Restored dialogue is untrusted context,
never an executable command, saved fact, contact selection or pending approval.
When a configured online provider is used, the supplied recent context is part of
that request, as it already was for in-memory conversation.

The service currently uses the workflow engine's **process-local checkpoint store**.
It does not claim task recovery or duplicate protection across independent service
instances, process death or restarts. The injectable durable-store recovery is
unit-tested; a production durable adapter remains work for Phase 5. No background
execution, generic autonomous planner or unattended consequential action is added.
An uncertain launch cannot be resumed automatically. Cancellation stops subsequent
steps and does not undo effects already requested.

Improvement review reads the existing bounded result journal only. There is no new
collector, telemetry, external submission, automatic code edit or deployment.
User reports remain distinct from device readback. Timestamp/target-based attempt
deduplication is conservative; independent retries with different times cannot
always be identified. Counts are recorded evidence, not measured reliability or a
proven root cause. Proposals exclude raw transcript/detail fields and use synthetic
regression scenarios. Existing result-history deletion remains the data reset.

## Product and upgrade preservation

Recent dialogue persistence, tracked routine dispatch, improvement controls,
solution-first provider instructions and alternative provider routing are enabled
for the personal package `com.assistant.core`. The existing shared safety fixes also
apply to shared command/result code. No Beta subscription rules, cost-routing
restrictions, provider keys, selected models, package identities, preference
namespaces, appearance resources or Developer sources are changed. The gateway's
new behavior-profile field defaults off for older/other clients.

No application version or production update pointer is changed in this increment.
The stable build workflow still publishes both products on main. Feature-branch
manual runs now produce only personal signed build artifacts: both publication
steps and Beta compilation/upload require the main branch. This permits a signed
personal phone-test candidate without replacing the updater or Beta release.
A debug APK is build evidence, not a same-signer personal update candidate.

## Required checks before merge and updater publication

1. Run the complete JVM suite, debug build and instrumentation compilation, gateway
   suite, APK archive checks and theme contract. Record actual results below.
2. Pass the existing CI unit/build, gateway, theme and optimized voice emulator jobs
   on the final source. The voice runner now requires the original five tests plus
   four isolated foundation service/storage acceptance tests. Broad visual-layout
   smoke remains disabled upstream; do not count it as a pass.
3. On a disposable emulator, verify exact commands, no unintended actions for
   command questions, storage reconstruction/deletion and unchanged local controls.
4. On the personal phone, test a three-turn goal conversation with a correction,
   restart continuity, offline arithmetic/commands, missing permissions, failed
   adapter alternatives and three approved multistep routines. Verify requested
   versus completed action language and cancellation. Record device/Android/build,
   steps, expected result and observed result for every case.
5. Measure wake detection on at least 20 controlled ordinary/deep-voice utterances
   per condition, aiming for at least 95% in that controlled sample. Separately
   record false activations and listening time under a reproducible negative-audio
   condition. Do not infer field reliability from the small controlled sample.
6. Produce a versioned production-signed personal APK through the existing signing
   path with production wake/voice assets. Verify package, version, supported ABI,
   APK integrity and exact installed signer compatibility.
7. Exercise an in-place update from the installed personal build. Confirm retained
   settings, keys, manual model selection, appearance, chat and learned data.
   Publish through the updater only after the release evidence is complete.

## Verification record for this increment

- JVM suite: 410 tests across 77 suites passed locally, with no failures, errors or skipped tests.
- Debug assemblies: all three supported ABI APKs and the Android test APK built successfully.
- APK archive Python checks: six passed locally.
- UI theme contract: passed locally; no visual source/resource changes.
- Gateway tests: 16 passed locally, including the personal-only behavior-profile contract.
- Final-source CI and emulator acceptance: recorded in the pull request's checks; do not infer success from compilation.
- Physical-device acceptance, controlled wake measurements, signed upgrade and updater delivery: pending.

No verified per-phase percentage or release readiness is claimed while these
acceptance gates remain open. Track the next smallest unmet contract in each phase
in parallel, starting with device evidence and durable task coordination.
