# Jarvis Developer — Six-phase intelligence roadmap

Status: approved product plan; **not** an assertion of implemented functionality. Owner-approved direction: an AI developer that can improve Jarvis and build other applications while protecting a known-good release.


## Verified delivery status — 2026-10-15

**Recovery prerequisite: BLOCKED. Phase 1: partial source helper only; acceptance NOT MET.** Audit source: [`993c6666e7b8dd7773702c1f2a8d111949115bee`](https://github.com/muddigger782012/J.A.R.V.I.S.-/commit/993c6666e7b8dd7773702c1f2a8d111949115bee), branch `feature/jarvis-developer`. No Phase 1 implementation changes were made during this audit because the required backup/recovery checks failed. The evidence below distinguishes existing source, historical CI, and work still pending. Estimates remain relative to recovery verification.

## Prerequisite: recovery/release gate
Verify manual release permissions, signed release builds, `developer-latest` publication, `developer-previous` preservation and byte verification, immutable recovery source SHA, recovery rebuild, and user-approved installation. The recovery gate is not complete merely because a build compiled.

## Phase 1 — Codebase awareness (3–5 working days; week 1)
Deliver repository tree discovery, scoped file reading, symbol and dependency references, bounded context retrieval, change-impact summaries, and read-only task previews. Acceptance: correctly identify relevant files/dependencies for at least five realistic Developer tasks, with no write permission needed.

## Phase 2 — AI coding engine (5–10 working days; weeks 2–3 cumulative)
Expand the existing launcher-icon-only proof of concept into allowlisted multi-file changes; support Kotlin, Java, XML and Gradle; propose patch/diff previews; validate paths and output; use isolated branches, commit attribution, CI and user-approved writes. Acceptance: execute a representative two-or-more-file Developer feature change, show a reviewable diff, produce a passing CI build, and never silently release/install.

## Phase 3 — Autonomous task planning (1–2 weeks; weeks 3–5 cumulative)
Task decomposition, ordered dependencies, persistent checkpoints, resumable progress, user-editable task queue and an in-app progress dashboard. Acceptance: resume a multi-session task without losing scope or decisions.

## Phase 4 — Debugging and automated testing (2–3 weeks; weeks 5–8 cumulative)
Read build/test diagnostics, identify failures, propose targeted corrections, run unit/lint/instrumentation checks where available, retry with budgets and stop conditions, retain evidence. Acceptance: fix representative seeded compiler/test failures without uncontrolled edits or loops.

## Phase 5 — Controlled self-development (3–5 weeks; weeks 8–13 cumulative)
Self-proposed changes on isolated branches; baseline comparisons, automated safety checks, signed candidate evaluation, rollback and recovery, staged publication requiring approval. Acceptance: safely develop, test and propose an update to its own code while keeping previous release recoverable.

## Phase 6 — Complete application development (6–12 weeks; weeks 14–25 cumulative)
Android project generation, UI/backend scaffolding, testing and packaging, then Windows-specific toolchain support. Acceptance: build a small standalone Android app from a written spec and deliver a tested APK; Windows release capability is a separate exit criterion.

## Technical architecture
- Repository intelligence layer: file inventory, references, retrieval and read-only summaries.
- Planner: task graph, checkpoints, acceptance criteria and approvals.
- Coding layer: model adapter, patch generation, validation, diff review and isolated commits.
- CI feedback: signed builds, tests, diagnostics and bounded repair.
- Release controller: backup verification, candidate promotion and explicit installation approval.
- Progress UI: phases, active tasks, blockers, commit/build links, evidence and next steps.

## Timing and scope notes
Estimates assume steady focused work and working API/GitHub/CI access; unexpected Android integration or model/tool limitations can extend them. Phases may overlap after their safety gates pass. The initial Developer coding agent currently only edits `developer/src/main/res/drawable/developer_icon.xml`; multi-file coding and the roadmap UI still need implementation. Documentation is shared with Codex through `developer/AGENTS.md`; the installed Developer APK will not automatically display this file until a roadmap reader/UI is built.

## Immediate backlog
1. Complete recovery gate with a successful manual release and tested recovery behavior.
2. Implement read-only GitHub tree/file inventory and task-to-file mapping.
3. Add dependency/symbol context and source citation to Developer UI.
4. Build diff preview, multi-file allowlist and approval workflow.
5. Add multi-file commit/CI feedback and clear phase progress.

## Recovery verification evidence — 2026-10-15

- **Published baseline:** `developer-latest` release 413391078 records immutable source `c94c4af5d5066a016912cc2a1bb6649cc91eb1f2`, version 0.1.8. The commit exists and is an ancestor of the audited branch. The moving `developer-latest` tag instead resolves to `2e727a83925a1106bbd60c0d7fe4bc341cc01486`; recovery must use the recorded full source SHA, not that tag.
- **Signed build evidence:** [original publication run 38677089919](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/38677089919), [job 116233046684](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/38677089919/job/116233046684), passed compilation, `apksigner verify`, and package check `com.jarvis.developer`. These logs do not establish device behavior or compare signing certificate fingerprints.
- **Integrity metadata only:** GitHub reports APK asset 642551934 as 1,545,511 bytes with SHA-256 `e34535e65b77e7a3a7d3412b1e329ac09dde913a766d964f47ff0662de710557`. This audit did not download or independently hash the APK. A reported digest is not a verified backup or a byte comparison.
- **Previous release absent:** both the `developer-previous` release and tag return 404. There is no previous APK preserved in the expected release channel to compare against the published baseline.
- **403 remains unresolved:** manual runs [38681257799](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/38681257799/job/116246113915) and [38681731982](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/38681731982/job/116247581283) compiled and verified signed candidates, then failed `Preserve previous signed Developer release`: `HTTP 403: Resource not accessible by integration` on the repository releases endpoint. The failed step attempts to create `developer-previous` after downloading the old APK. Logs already show `Contents: write`; they do not establish that a read-only default caused the failure. Backup comparison and promotion were skipped.
- **Latest credential failure:** manual run [38712747264](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/38712747264/job/116347929514), source `6153c599f716b94ff583f7bb92bdcfec2712a0d8`, built a signed candidate but failed `Verify release write access` with `Bad credentials (HTTP 401)`. The workflow selects `JARVIS_DEVELOPER_RELEASE_TOKEN || github.token`; masked logs do not establish which credential was selected. A GET preflight also cannot prove release-write authorization.
- **Recovery rebuild not validated:** the three recorded Developer manual runs all have empty recovery SHA and `Recovery selected: false`. None verifies a selected historical source rebuild. No approved device installation or rollback exercise was performed in this audit.

To unblock, repair effective release authorization through owner-managed credential/permission review; preserve the exact published APK with its immutable source SHA; independently download, hash and compare the backup bytes; verify package and signing identity; and validate a higher-version signed recovery candidate from the pinned source. Use a build-only recovery path for validation. The current manual workflow couples recovery with publication, so it must not be dispatched merely to validate recovery under the current no-publish instruction. Publication and installation remain separate approval gates. Do not bypass backup failures or count a successful compile as recovery verification.

## Phase 1 implementation and validation evidence — 2026-10-15

At the audited source SHA:

- `developer/src/main/java/com/jarvis/developer/RepositoryIntelligence.kt` contains GET-only inventory, filename/path keyword ranking, and single-file retrieval. It pins a full commit SHA, rejects truncated trees, compares the returned blob SHA, and caps decoded source at 200 KB. This is existing source, not a passing runtime result.
- Inventory line 56 accepts only `developer/`. The inspected tree is complete (`truncated=false`): 408 blobs total, 12 under Developer, and no Developer unit or instrumentation test files. The helper cannot read the application sources required by four of the five tasks below.
- No symbol extraction, dependency tracing, bounded multi-file context selection, change-impact plans, or source-citation UI exists. `developer/src/main/java/com/jarvis/developer/MainActivity.kt` does not call the helper; its AI request sends the raw task without repository context. Its current plan reader reads `docs/development-plans/`, not this roadmap.
- **Existing CI is failing:** both [debug run 38714468621](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/38714468621) and [signed candidate run 38714468655](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/38714468655), at the audited SHA, fail at `RepositoryIntelligence.kt:73:70`: `sumOf` overload ambiguity between Int and Long. APK artifact upload was skipped. Fixing this is pending behind the recovery gate.

The following is a **human read-only source audit**, not successful execution of Jarvis Developer acceptance tests. All paths refer to the immutable audit SHA above. In the table, `core/` means `app/src/main/java/com/assistant/core/`; `services/` means `app/src/main/java/com/assistant/core/services/`.

| Representative task | Relevant files and dependencies | Sensible proposed change and likely impact |
| --- | --- | --- |
| Menu restructuring | `core/MainActivity.kt:2338` (`showToolsAndSettingsMenu`), `core/SettingsActivity.kt`, `core/SettingsMenuViews.kt`, `app/src/main/AndroidManifest.xml`, `app/src/androidTest/java/com/assistant/core/SettingsNavigationUiTest.kt`, `docs/SETTINGS_NAVIGATION.md` | Plan menu routing and category/row changes together; preserve themed rows, touch targets, Back navigation, editor return routes and microphone handoff. Navigation changes need regression coverage. |
| Appearance scrolling | `core/AppearanceSettingsActivity.kt:87` (`showTab`), `core/ThemeSettingsActivity.kt`, `core/FontPreferences.kt`, `core/ThemePreferences.kt`, `app/src/main/res/layout/activity_appearances.xml`, `app/src/main/res/layout/activity_themes.xml`, `docs/UI_THEMING.md` | Inspect the weighted tab host, fixed darkness panel and independently scrolling children; choose scroll ownership that keeps controls reachable with large text and narrow screens. Validate all themes and preference persistence. |
| AI connectivity diagnosis | `core/IntelligenceActivity.kt:260` (`testConnection`), `core/AiConnectionSettings.kt`, `services/IntelligenceOverview.kt`, `services/DirectOnlineClient.kt`, `services/FallbackAiClient.kt`, `services/ApiKeyStore.kt`, `services/GeminiFailureMemory.kt`, `app/src/test/java/com/assistant/core/services/IntelligenceOverviewTest.kt`, `app/src/test/java/com/assistant/core/services/GeminiFailureMemoryTest.kt` | Trace provider priority, settings validation, key retrieval, requests and diagnostic classification. Preserve credential configuration, the static diagnostic prompt, strict expected-response validation, and disabled learning/location for gateway checks. |
| Per-chat context | `core/MainActivity.kt:1488` (`loadOrCreateConversation`), `services/HybridAssistantService.kt:41` (`history`), `services/DirectOnlineClient.kt`, `services/FallbackAiClient.kt`, `services/LiveChatStore.kt`, `services/VoiceForegroundService.kt`, `services/ConversationMessages.kt` | New chat clears UI output while the service retains an unkeyed history deque. Plan conversation-scoped context and bind delayed replies to the originating conversation; cover saved-chat switching, persistence and background voice behavior. |
| Intelligence Plan progress | `developer/DEVELOPMENT_ROADMAP.md`, `developer/src/main/java/com/jarvis/developer/MainActivity.kt:151`, `developer/src/main/java/com/jarvis/developer/GithubPlans.kt`, `docs/JARVIS_DEVELOPER_PLAN.md`, `docs/INTELLIGENCE_DASHBOARD.md` | Add Developer-only roadmap status backed by commit/build/test evidence and explicit pending states. Existing Jarvis Intelligence reports learned-item counts; preserve that behavior and avoid inventing completion percentages. |

**Acceptance result: NOT MET.** No new executable acceptance tests, Android builds, or device/emulator checks were run by this audit. The historical build failures above are verified CI evidence. The source audit did not change the analyzed implementation. Only this shared roadmap is updated; normal Jarvis/Beta sources, credentials configuration, signing, update channels and release workflows remain unchanged. Nothing was published or installed.

After recovery passes, remaining Phase 1 work is: fix the existing compile error; separate a testable analysis engine from GET-only transport; add explicit read scopes for Developer, selected application sources and shared docs while denying secrets; extract symbols/imports/resource references; retrieve bounded relevant excerpts at one immutable SHA; generate file plans with reasons, dependencies, impact and uncertainty; and expose cited results in the Developer UI. Validate all five tasks through that API with write-rejecting transport and unchanged repository snapshots, plus malformed revisions, moved branches, denied paths, truncated trees and oversized responses. Record those results here before changing Phase 1 status.
