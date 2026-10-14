# Current APK update status

Inspection date: 2026-10-14. Development continues in existing PR [#75](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/75), based on main `da46b0a7b87afb045ad3afd36c8f2fd7a87e63a0`. This report distinguishes the published APK, candidate source and physical-device evidence.

All 75 pull requests and 77 branches were inspected: 72 PRs are merged into main, two are superseded, and #75 is the only open app PR. Retained merged branch heads match their merged PR revisions. The separate `jarvis-keyboard-updates` branch distributes keyboard APKs and is outside this assistant update. All four Actions workflows were reviewed: APK publishing, local assistant checks, theme checking and the legacy source bootstrap.

## Published installation artifacts

The last successful signed build is [run 38493540574](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/38493540574), from source `31d161493f15bea9ef7ef6eb1e89f5a0f2327383`.

| Variant | Identity | Version/code | Delivery |
| --- | --- | --- | --- |
| Original | `com.assistant.core` | `3.5.394` / 394 | `artifacts/app-debug.apk` (despite the historical filename, a signed release build) |
| Beta | `com.assistant.core.beta` | `3.5.394-beta` / 394 | [Beta.apk](https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/download/beta-latest/Beta.apk) |

The published updater artifacts target ARM64 and Android 8/API 26 or newer. CI also builds ARMv7 and x86_64 splits. Beta's bundled offline voice archive makes its published APK approximately 354.5 MiB; the inspected asset SHA-256 is `ca55861fd860fcaf216938ad2d160f18053af3145cf200a6fa09f1ff69e16400`. That mutable download channel can advance after a later release.

Original builds 391 and 394 have the same verified signing certificate SHA-256, `93a2f2031692f947440f4cfd874a166609cd2735eeda28e60904da64e8245790`. Beta uses the existing release signing configuration and a separate package. The candidate workflow explicitly verifies both certificates before publication. No package, database version, Keystore alias, stored user-key format or existing update channel is changed by this continuation.

## Feature status

| Area | Published APK 394 | Current PR |
| --- | --- | --- |
| Intelligence | Local routing, verified learning, bounded routines/trials, Gemini/search and local failure explanations | Seven-workstream offline Intelligence Plan, explicit public-report refresh and bounded local plan follow-ups |
| Conversation | Existing chat history and operational memory | Persisted bounded context per chat; immutable provider request history; shared selection epochs; stale reply/proposal rejection |
| Menus/navigation | PR #74's compact gear categories, Settings hub and direct AI configuration paths are merged | Focused Intelligence/Appearance categories, themed rows, Back paths and plan view; shared New/history transition cleanup |
| Voice | Existing controls, My Voice Profile, neural voices/cache/diagnostics; Beta voice preparation lifecycle included | Voice/live-chat context isolation and switch cleanup; controls, presets and Arc Reactor remain in place |
| Provider keys | Published Beta previously included a build-time Gemini fallback | Removed embedded-key configuration; user-supplied encrypted Gemini keys retained; onboarding opens existing configuration |
| Delivery | Permanent-key signing and working updater | Signer pin, unsigned variant validation, credential-pattern checks, source-freshness guard and matching packaged/public plan publication |

Private management reviews stay outside provider conversation history. Context files keep up to twelve completed exchanges within an actual 64 KiB serialized UTF-8 budget, with forty recent files retained. Shared selection epochs distinguish reopening the same chat from continuing its current request. Pending action approvals are scoped to that selection; old unscoped transient proposals require confirmation again. Saved learned commands, routines, operational memory and archived conversations retain their existing storage.

## Failures and integration problems

- Main builds 392/393 failed on duplicate `onResume()` definitions in `AssistantVoiceSettingsActivity`. Source `31d1614` fixed that integration; build 394 passed.
- PR #75's earlier revision failed on unresolved `materialButtonOutlinedStyle` references. Its original latest revision `5d63e2a` passed before this continuation.
- The continuation fixes context serialization exceeding its own read limit, missing management-review exclusions, live/history switch cleanup and stale permission delivery. Cross-process approval persistence and async history snapshots are covered by targeted regressions.
- The release workflow previously discarded its freshly stamped public plan during `git reset`. Publication now preserves the plan compiled into the APK and skips superseded source builds. The legacy v3.3 source-bootstrap workflow also skips an existing tracked Android project.
- General `ui_layout_smoke.py` screenshot/layout automation is restored with current public-menu selectors, offline math/plan checks, actual emulator taps, palette restart persistence and narrow-screen/enlarged-font captures. Optimized Android instrumentation covers all twelve themes, voice/profile behavior and chat switching. Emulator checks remain separate from physical-phone appearance, microphone quality and sound-at-speaker behavior.

## Validation and release gate

Before this continuation, PR #75's [run 38499153892](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/38499153892) passed debug JVM tests/build, 15 gateway tests and six optimized Android emulator tests. Its separate theme-contract check passed. Published build 394 passed release/Beta compilation, signature/identity and voice-archive checks.

The continuation passes local theme checking, workflow YAML/shell syntax, credential-source exclusion and 22 Python APK/publication/UI-helper regressions. Local gateway execution cannot import the missing `openai` dependency, and the workspace lacks an Android SDK/compiler/native AAR. JVM, debug, optimized release/Beta, gateway, seven-test instrumentation and restored screen-smoke validation therefore run in GitHub Actions; inspect PR checks for the final candidate revision. The initial continuation passed JVM/debug, optimized release/Beta compilation and nine ABI/variant integrity checks before the final timer-edit race and screen-smoke refinements.

The expanded update becomes an installable in-place update only after PR integration and a successful permanently signed publishing run. Unsigned release/Beta PR outputs establish compilation, not installation readiness. No merge or public APK replacement is performed as part of preparing this PR.

Phone acceptance remains: install over the existing same-channel app; confirm saved data/settings/keys; exercise all themes and large fonts; navigate Settings/Voice/Intelligence/Appearance Back paths; switch chats during delayed typed/live/wake replies and permissions; test offline context/plan and explicit refresh; audition speech and wake/listen/speak cancellation. Record real device results independently of CI.

Follow-on work is staged in [the development roadmap](DEVELOPMENT_ROADMAP.md), aligned with the existing seven workstreams without changing their estimates.
